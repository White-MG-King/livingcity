package com.livingcity.sensor;

import citysim.BuildingEntry;
import citysim.BuildingKind;
import citysim.CityLedger;
import citysim.SimEvent;
import citysim.SimHarness;
import com.livingcity.data.CityData;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 传感总线（MC → 账本的唯一合法路径，见《传感层任务单》§0.2）。
 * 回调里只做一件事：把输入结构体 offer 进队列；业务翻译只在周期边界统一执行一次。
 */
public final class CitySensorBus {

	/**
	 * 输入结构体。锚点三元组 (ax,ay,az) 是表演层任务单 P1 的契约扩展：
	 * REGISTER_BUILDING 登记时把执行人站位（或显式 pos 参数）记进 CityData.buildingAnchors；
	 * ax=ay=az=0 约定为「无锚点」（老存档兼容路径，物化时会跳过该建筑并提示重新 register）。
	 */
	public record Input(Type type, int idA, int idB, String text, int ax, int ay, int az) {
		/** 旧四参便捷重载：无锚点。既有 offer 调用点（winter/fire 等）零改动。 */
		public Input(Type type, int idA, int idB, String text) {
			this(type, idA, idB, text, 0, 0, 0);
		}

		public net.minecraft.nbt.NbtCompound save() {
			var n = new net.minecraft.nbt.NbtCompound();
			n.putString("type",type.name()); n.putInt("a",idA); n.putInt("b",idB);
			if (text!=null) n.putString("text",text);
			n.putInt("x",ax); n.putInt("y",ay); n.putInt("z",az); return n;
		}
		public static Input read(net.minecraft.nbt.NbtCompound n) {
			return new Input(Type.valueOf(n.getString("type")),n.getInt("a"),n.getInt("b"),n.getString("text"),n.getInt("x"),n.getInt("y"),n.getInt("z"));
		}

		public enum Type {
			REGISTER_BUILDING,  // idA 无（返回新 id）, text=档位名, idB=床数, (ax,ay,az)=锚点
			BED_COUNT_CHANGED,  // idA=建筑条目 id, idB=新床数
			CITIZEN_DEATH,      // idA=物化市民 id, text=死因
			BLOCK_DAMAGED,      // idA=建筑条目 id, idB=结构位 index（M3 启用）
			BLOCK_REPAIRED,     // 同上（M3 启用）
			WINTER_INJECT,      // idA=持续周期数
			FIRE_INJECT,        // idA=建筑条目 id
			TAKEOVER_VILLAGE    // (ax,ay,az)=收编中心；半径内床分栋入籍+原版村民转市民（§3.13）
		}
	}


	private CitySensorBus() {
	}

	public static void offer(CityData city, Input in) {
		city.pending.offer(in);
		city.markDirty();
	}

	/**
	 * 周期边界消费队列。除下方翻译表外禁止加任何逻辑（《扩展纪律》§2）。
	 *
	 * @param data MC 侧空间注册表（建筑锚点写这里——账本零空间数据，见表演层任务单 §0.2）
	 * @param world 主世界（村庄收编的一次性区块床扫描用；床账本「注册时全扫一次」时机，城市篇 §3.4）
	 * @return 需要回显给玩家的结果行（如登记后回显账本 id）
	 */
	public static List<String> drainAndApply(SimHarness harness, CityData data, net.minecraft.server.world.ServerWorld world) {
		List<String> echo = new ArrayList<>();
		CityLedger city = harness.city;
		Input in;
		while ((in = data.pending.poll()) != null) {
			switch (in.type()) {
				case REGISTER_BUILDING -> {
					BuildingKind kind;
					try {
						kind = BuildingKind.valueOf(in.text());
					} catch (IllegalArgumentException e) {
						continue;
					}
					BlockPos site = new BlockPos(in.ax(), in.ay(), in.az());
					var registry = com.livingcity.data.CityRegistry.get(world.getServer());
					if (data.land.hallReserved(site,data) || data.land.occupied(site,data) || !registry.claim(data,site.getX()>>4,site.getZ()>>4)) {
						echo.add("登记失败：位置已占用"); continue;
					}
					BuildingEntry entry = city.placeExisting(kind, in.idB());
					data.setBuildingAnchor(entry.id,site);
					echo.add("登记" + kind.displayName + " #" + entry.id + "（" + entry.bedCount + " 床"
							+ ("，锚点 " + site.toShortString()) + "）");
				}
				case BED_COUNT_CHANGED -> {
					BuildingEntry entry = find(city, in.idA());
					if (entry != null && entry.kind == BuildingKind.HOUSE) {
						entry.bedCount = Math.max(0, in.idB());
					}
				}
				case CITIZEN_DEATH -> harness.killCitizen(in.idA(), in.text());
				case BLOCK_DAMAGED -> {
					BuildingEntry entry = find(city, in.idA());
					if (entry != null) entry.markMissing(in.idB());
				}
				case BLOCK_REPAIRED -> {
					BuildingEntry entry = find(city, in.idA());
					if (entry != null) entry.clearMissing(in.idB());
				}
				case WINTER_INJECT -> harness.injectEvent(
						SimEvent.harshWinter(in.idA(), city.config.harshWinterFarmEff));
				case FIRE_INJECT -> harness.injectEvent(SimEvent.fire(in.idA()));
				case TAKEOVER_VILLAGE -> echo.add(takeover(city, data, world, new BlockPos(in.ax(), in.ay(), in.az())));
			}
		}
		return echo;
	}

	/** 村庄收编半径（格）。MC 侧调参常量，将来进调参表；覆盖典型村庄尺度。 */
	private static final int FOUNDING_RADIUS = 48;

	/**
	 * 村庄收编（城市篇 §3.13）：半径内床按区块分栋入籍 + 原版村民计数进账本并 discard。
	 * 一次性扫描：只扫已加载区块；每列用 heightmap 限高（顶面下 12 格）控成本；
	 * 已有 HOUSE 锚点的区块跳过（防重复登记）。村民 discard 不算死亡（无 CITIZEN_DEATH、无编年史死亡条）。
	 */
	private static String takeover(CityLedger city, CityData data, net.minecraft.server.world.ServerWorld world, BlockPos center) {
		var registry = com.livingcity.data.CityRegistry.get(world.getServer());
		int r = FOUNDING_RADIUS;
		java.util.Set<Long> claimedChunks = new java.util.HashSet<>();
		for (BuildingEntry b : city.buildings) {
			if (b.kind == BuildingKind.HOUSE) {
				BlockPos a = data.buildingAnchor(b.id);
				if (a != null) claimedChunks.add(CityData.xzKey(a.getX() >> 4, a.getZ() >> 4));
			}
		}

		int houses = 0;
		int beds = 0;
		int cx0 = (center.getX() - r) >> 4, cx1 = (center.getX() + r) >> 4;
		int cz0 = (center.getZ() - r) >> 4, cz1 = (center.getZ() + r) >> 4;
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				if ((registry.owner(cx,cz)!=null && registry.owner(cx,cz)!=data) || claimedChunks.contains(CityData.xzKey(cx, cz)) || data.land.hasPlotInChunk(cx, cz)
                        || !world.getChunkManager().isChunkLoaded(cx, cz)) {
					continue;
				}
				net.minecraft.world.chunk.WorldChunk chunk = world.getChunk(cx, cz);
				net.minecraft.world.Heightmap hm = chunk.getHeightmap(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES);
				int chunkBeds = 0;
				BlockPos firstBed = null;
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						int wx = (cx << 4) + x, wz = (cz << 4) + z;
						int top = hm.get(x, z);
						for (int y = Math.max(world.getBottomY(), top - 12); y <= top; y++) {
							cursor.set(wx, y, wz);
							net.minecraft.block.BlockState state = world.getBlockState(cursor);
							if (state.isIn(net.minecraft.registry.tag.BlockTags.BEDS)
									&& state.get(net.minecraft.block.BedBlock.PART) == net.minecraft.block.enums.BedPart.FOOT) {
								chunkBeds++;
								if (firstBed == null) firstBed = cursor.toImmutable();
							}
						}
					}
				}
				if (chunkBeds > 0 && registry.claim(data,cx,cz)) {
					BuildingEntry entry = city.placeExisting(BuildingKind.HOUSE, chunkBeds);
					data.setBuildingAnchor(entry.id, firstBed);
					data.land.trackBeds(CityData.xzKey(cx,cz),entry.id);
					houses++;
					beds += chunkBeds;
				}
			}
		}

		net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(
				center.getX() - r, center.getY() - r, center.getZ() - r,
				center.getX() + r + 1, center.getY() + r + 1, center.getZ() + r + 1);
		java.util.List<net.minecraft.entity.passive.VillagerEntity> villagers =
				world.getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class, box, v -> registry.at(v.getBlockPos()) == data);
		for (net.minecraft.entity.passive.VillagerEntity v : villagers) {
			v.discard();
		}
		city.population += villagers.size();
		if (houses > 0 || !villagers.isEmpty()) {
			city.chronicle.log(city.dayCount, "收编村民 " + villagers.size() + " 名，住宅 " + houses + " 栋（" + beds + " 床）");
		}
		return "收编：村民 " + villagers.size() + " 名入籍（人口=" + city.population + "），住宅 " + houses
				+ " 栋共 " + beds + " 床（半径 " + r + " 格，未加载区块跳过）";
	}

	private static BuildingEntry find(CityLedger city, int id) {
		for (BuildingEntry b : city.buildings) {
			if (b.id == id) return b;
		}
		return null;
	}
}
