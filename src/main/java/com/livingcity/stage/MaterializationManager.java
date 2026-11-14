package com.livingcity.stage;

import citysim.BuildingEntry;
import citysim.BuildingKind;
import citysim.CityLedger;
import com.livingcity.data.CityData;
import com.livingcity.entity.CitizenEntity;
import com.livingcity.entity.LivingCityEntities;

import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 物化同步（表演层任务单 P2）：周期边界跑一遍，把账本物化池投影成世界里的市民实体。
 * 「账本是真，舞台是采样」——本类对账本只读，唯一写路径是 harness.materializeUpTo（核心指令口）。
 *
 * 红线：实体 ≤200；物化半径 96 格 / 去物化 128 格（滞回带防边界抖动）；
 * 批内只遍历池（≤200）、住所表与在线玩家，不做全实体扫描。
 *
 * 去物化 ≠ 死亡：超距实体 discard()，池 id 与住所保留，禁止上报 CITIZEN_DEATH。
 */
public final class MaterializationManager {

	public static final int ENTITY_CAP = 200;
	public static final int MATERIALIZE_RADIUS = 96;
	public static final int DEMATERIALIZE_RADIUS = 128;

	private static final Logger LOGGER = LoggerFactory.getLogger("livingcity-sim");

	private MaterializationManager() {
	}

	public static void sync(MinecraftServer server) {
		CityData data = CityData.get(server);
		CityLedger city = data.harness().city;
		ServerWorld world = server.getOverworld();

		// 1. 扩池：账本给市民分配实体 id（id 单调递增，死者 id 永不复用）
		data.harness().materializeUpTo(Math.min(ENTITY_CAP, city.population));

		// 2. 对账（先于生成跑，保证映射与在场实体一致，cap 计数不被幽灵映射占用）：
		//    映射指向的实体查无 → 清映射（下周期若仍在池内自动重生成）；
		//    住所的主人已不在池内（死亡清账）→ 顺带清住所
		int reconciled = reconcile(world, data, city);

		// 3. 生成：池中无活实体的 id → 住所 → 锚点 → 最近玩家 ≤96 格且未满 cap → 落位生成
		int spawned = 0;
		int skippedNoHome = 0;
		int alive = data.citizenEntityCount();
		for (int id : new TreeSet<>(city.materializationPool)) {
			if (alive >= ENTITY_CAP) {
				break;
			}
			if (data.citizenEntity(id) != null) {
				continue; // 已有活实体（对账保证映射真实）
			}
			int home = resolveHome(city, data, id);
			if (home < 0) {
				skippedNoHome++;
				continue;
			}
			BlockPos anchor = data.buildingAnchor(home);
			if (anchor == null || !anyPlayerWithin(server, world, anchor, MATERIALIZE_RADIUS)) {
				continue;
			}
			CitizenEntity citizen = new CitizenEntity(LivingCityEntities.CITIZEN, world);
			citizen.setCitizenId(id);
			// 确定性错位偏移（防同锚点叠罗汉）：id%3-1 ∈ {-1,0,1} × id/3%3-1 ∈ {-1,0,1}
			int offsetX = id % 3 - 1;
			int offsetZ = id / 3 % 3 - 1;
			BlockPos at = new BlockPos(anchor.getX() + offsetX, anchor.getY() + 1, anchor.getZ() + offsetZ);
			citizen.refreshPositionAndAngles(at.getX() + 0.5d, at.getY(), at.getZ() + 0.5d, 0.0f, 0.0f);
			world.spawnEntity(citizen);
			data.bindCitizenEntity(id, citizen.getUuid());
			alive++;
			spawned++;
		}

		// 4. 去物化：最近玩家 >128 格 → discard（池 id 与住所保留，不上报死亡）
		int discarded = 0;
		for (Map.Entry<Integer, UUID> entry : data.citizenEntitySnapshot()) {
			Entity entity = world.getEntity(entry.getValue());
			if (entity == null) {
				continue; // 已被对账清掉
			}
			if (!anyPlayerWithin(server, world, entity.getBlockPos(), DEMATERIALIZE_RADIUS)) {
				entity.discard();
				data.unbindCitizenEntity(entry.getKey());
				discarded++;
			}
		}

		if (spawned > 0 || discarded > 0 || reconciled > 0 || skippedNoHome > 0) {
			LOGGER.info("[LivingCity] materialization sync: pool={} alive={} spawned={} discarded={} reconciled={} skippedNoHome={}",
					city.materializationPool.size(), data.citizenEntityCount(), spawned, discarded, reconciled, skippedNoHome);
			if (skippedNoHome > 0) {
				LOGGER.info("[LivingCity] {} pool citizen(s) skipped: no anchored ACTIVE HOUSE with free beds (use /city register house <beds> [pos])",
						skippedNoHome);
			}
		}
	}

	/**
	 * 住所分配（确定性规则，任务单 P1）：已有住所保持不变（防抖动）；
	 * 新市民取「有锚点的 ACTIVE HOUSE」按 buildingId 升序、按 bedCount 容量顺次填。
	 *
	 * @return 分到的建筑 id；-1 = 无可住锚点房（跳过物化）
	 */
	private static int resolveHome(CityLedger city, CityData data, int citizenId) {
		int existing = data.citizenHome(citizenId);
		if (existing >= 0) {
			return data.buildingAnchor(existing) != null ? existing : -1;
		}
		List<BuildingEntry> houses = new ArrayList<>();
		for (BuildingEntry b : city.buildings) {
			if (b.kind == BuildingKind.HOUSE && b.state == BuildingEntry.State.ACTIVE
					&& b.bedCount > 0 && data.buildingAnchor(b.id) != null) {
				houses.add(b);
			}
		}
		if (houses.isEmpty()) {
			return -1;
		}
		houses.sort(Comparator.comparingInt(b -> b.id));
		Map<Integer, Integer> occupied = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : data.citizenHomeSnapshot()) {
			occupied.merge(entry.getValue(), 1, Integer::sum);
		}
		for (BuildingEntry b : houses) {
			if (occupied.getOrDefault(b.id, 0) < b.bedCount) {
				data.assignCitizenHome(citizenId, b.id);
				return b.id;
			}
		}
		return -1;
	}

	private static int reconcile(ServerWorld world, CityData data, CityLedger city) {
		int cleaned = 0;
		for (Map.Entry<Integer, UUID> entry : data.citizenEntitySnapshot()) {
			if (world.getEntity(entry.getValue()) == null) {
				data.unbindCitizenEntity(entry.getKey());
				cleaned++;
			}
		}
		for (Map.Entry<Integer, Integer> entry : data.citizenHomeSnapshot()) {
			if (!city.materializationPool.contains(entry.getKey())) {
				data.removeCitizenHome(entry.getKey());
				cleaned++;
			}
		}
		return cleaned;
	}

	/** pos 到任一主世界在线玩家的 3D 距离 ≤ radius。无在线玩家 → false（全部去物化，符合无人观看语义）。 */
	private static boolean anyPlayerWithin(MinecraftServer server, ServerWorld world, BlockPos pos, double radius) {
		double squared = radius * radius;
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (player.getWorld().getRegistryKey() != World.OVERWORLD) {
				continue;
			}
			if (player.squaredDistanceTo(pos.getX() + 0.5d, pos.getY(), pos.getZ() + 0.5d) <= squared) {
				return true;
			}
		}
		return false;
	}
}
