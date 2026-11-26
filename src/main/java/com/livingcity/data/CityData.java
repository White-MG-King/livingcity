package com.livingcity.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import citysim.CityConfig;
import citysim.SimHarness;
import com.livingcity.sensor.CityClock;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

/**
 * 城市级存档数据（每存档一份）：
 * shafts = 电梯井（XZ 相同的一摞电梯方块）索引；routes = 床 -> 电梯路线缓存；homeOwners = 床位分配。
 * 所有缓存必须可失效：拆电梯清路线，拆床/村民死亡释放床位。
 *
 * 表演层任务单 §0.2（空间数据归属）：账本零空间数据（BuildingEntry 无坐标），
 * 建筑锚点 / 市民住所 / 实体映射三张空间表全在本类，随存档持久化，不进账本 schema。
 */
public class CityData extends PersistentState {
	CityRegistry registry;
	public int id = 1;
	int nationId;
	int foundedFrom;
	public final CityLand land = new CityLand();
	public final java.util.Queue<com.livingcity.sensor.CitySensorBus.Input> pending = new java.util.ArrayDeque<>();

	public Collection<BlockPos> anchors() { return Collections.unmodifiableCollection(buildingAnchors.values()); }

	@Override public void markDirty() {
		super.markDirty();
		if (registry != null) registry.markDirty();
	}

	public static final String ID = "livingcity_city";

	public static final class Route {
		public final long shaftKey;
		public final int exitY;

		public Route(long shaftKey, int exitY) {
			this.shaftKey = shaftKey;
			this.exitY = exitY;
		}
	}

	private final Map<Long, TreeSet<Integer>> shafts = new HashMap<>();
	private final Map<Long, Route> routes = new HashMap<>();
	private final Map<Long, UUID> homeOwners = new HashMap<>();

	// ---- 表演层空间注册表（P1）----
	/** 建筑锚点：账本 buildingId -> 世界坐标（register 时记录）。 */
	private final Map<Integer, BlockPos> buildingAnchors = new HashMap<>();
	/** 市民住所：citizenId -> buildingId（首次物化时分配，确定性规则见 MaterializationManager）。 */
	private final Map<Integer, Integer> citizenHomes = new HashMap<>();
	/** 实体映射：citizenId -> 活实体 UUID（双向账，装卸区块经 ENTITY_LOAD/UNLOAD 自愈）。 */
	private final Map<Integer, UUID> citizenEntities = new HashMap<>();

	/** 无头模拟核心单例（S0：挂在 CityData 上，随存档持久化）。 */
	private transient SimHarness harness;
	private final CityClock clock = new CityClock();

	public CityClock clock() {
		return clock;
	}

	/** 市政厅锚点（建城仪式）：null = 城市尚未举行建城仪式。账本本身隐式存在，此字段只记仪式与空间锚。 */
	private BlockPos townHallPos;

	public boolean isFounded() {
		return this.townHallPos != null;
	}

	public int nationId() { return nationId; }
	public int foundedFrom() { return foundedFrom; }

	public void foundCity(BlockPos pos) {
		this.townHallPos = pos.toImmutable();
		harness().city.config.requireBuildingMaterials = true;
		harness().city.requireBuildingPlacement(buildingAnchors::containsKey);
		this.markDirty();
	}

	/** @return 市政厅锚点；null = 未建城。 */
	public BlockPos townHallPos() {
		return this.townHallPos;
	}

	public SimHarness harness() {
		if (this.harness == null) {
			this.harness = new SimHarness(id == 1 ? 0L : id, new CityConfig());
		}
		return this.harness;
	}

	public static CityData get(MinecraftServer server) {
		return CityRegistry.get(server).byId(1);
	}

	public static CityData get(ServerWorld world) {
		return get(world.getServer());
	}

	public static long xzKey(int x, int z) {
		return ((long) x << 32) | (z & 0xffffffffL);
	}

	public static long xzKey(BlockPos pos) {
		return xzKey(pos.getX(), pos.getZ());
	}

	public static int xOf(long key) {
		return (int) (key >> 32);
	}

	public static int zOf(long key) {
		return (int) key;
	}

	public static BlockPos posOf(long key, int y) {
		return new BlockPos(xOf(key), y, zOf(key));
	}

	public void addElevator(BlockPos pos) {
		TreeSet<Integer> stops = this.shafts.computeIfAbsent(xzKey(pos), key -> new TreeSet<>());
		if (stops.add(pos.getY())) {
			this.markDirty();
		}
	}

	public void removeElevator(BlockPos pos) {
		long key = xzKey(pos);
		TreeSet<Integer> stops = this.shafts.get(key);
		if (stops == null || !stops.remove(pos.getY())) {
			return;
		}
		// 井里任何一层被拆，整条井的路线缓存全部作废（下次使用时重算）
		this.routes.entrySet().removeIf(entry -> entry.getValue().shaftKey == key);
		if (stops.isEmpty()) {
			this.shafts.remove(key);
		}
		this.markDirty();
	}

	public Collection<Long> shaftKeys() {
		return Collections.unmodifiableSet(this.shafts.keySet());
	}

	public TreeSet<Integer> stops(long shaftKey) {
		TreeSet<Integer> stops = this.shafts.get(shaftKey);
		return stops == null ? null : stops;
	}

	public Route route(long bedKey) {
		return this.routes.get(bedKey);
	}

	public void putRoute(long bedKey, Route route) {
		this.routes.put(bedKey, route);
		this.markDirty();
	}

	public UUID homeOwner(long bedKey) {
		return this.homeOwners.get(bedKey);
	}

	public void claimHome(long bedKey, UUID owner) {
		this.homeOwners.put(bedKey, owner);
		this.markDirty();
	}

	public void releaseHome(UUID owner) {
		if (this.homeOwners.values().removeIf(owner::equals)) {
			this.markDirty();
		}
	}

	public boolean isHomeFree(long bedKey, UUID self) {
		UUID owner = this.homeOwners.get(bedKey);
		return owner == null || owner.equals(self);
	}

	public int shaftCount() {
		return this.shafts.size();
	}

	public int routeCount() {
		return this.routes.size();
	}

	public int homeCount() {
		return this.homeOwners.size();
	}

	// ---- 表演层空间注册表访问器（P1）----

	public void setBuildingAnchor(int buildingId, BlockPos pos) {
		this.buildingAnchors.put(buildingId, pos.toImmutable());
		this.markDirty();
	}

	/** @return 锚点；null = 该建筑无锚点（老存档登记 / 显式无锚点，物化时跳过）。 */
	public BlockPos buildingAnchor(int buildingId) {
		return this.buildingAnchors.get(buildingId);
	}

	void removeBuildingAnchor(int buildingId) {
		this.buildingAnchors.remove(buildingId);
		this.citizenHomes.values().removeIf(home -> home == buildingId);
		this.markDirty();
	}

	public int anchorCount() {
		return this.buildingAnchors.size();
	}

	public void assignCitizenHome(int citizenId, int buildingId) {
		this.citizenHomes.put(citizenId, buildingId);
		this.markDirty();
	}

	/** @return 住所建筑 id；-1 = 未分配。 */
	public int citizenHome(int citizenId) {
		return this.citizenHomes.getOrDefault(citizenId, -1);
	}

	public void removeCitizenHome(int citizenId) {
		if (this.citizenHomes.remove(citizenId) != null) {
			this.markDirty();
		}
	}

	public int citizenHomeCount() {
		return this.citizenHomes.size();
	}

	/** 快照遍历用（防同步循环里增删导致 CME）。 */
	public List<Map.Entry<Integer, Integer>> citizenHomeSnapshot() {
		return new ArrayList<>(this.citizenHomes.entrySet());
	}

	public void bindCitizenEntity(int citizenId, UUID uuid) {
		this.citizenEntities.put(citizenId, uuid);
		this.markDirty();
	}

	/** @return 活实体 UUID；null = 当前无实体（未生成或已去物化）。 */
	public UUID citizenEntity(int citizenId) {
		return this.citizenEntities.get(citizenId);
	}

	public void unbindCitizenEntity(int citizenId) {
		if (this.citizenEntities.remove(citizenId) != null) {
			this.markDirty();
		}
	}

	public int citizenEntityCount() {
		return this.citizenEntities.size();
	}

	/** 快照遍历用（防同步循环里增删导致 CME）。 */
	public List<Map.Entry<Integer, UUID>> citizenEntitySnapshot() {
		return new ArrayList<>(this.citizenEntities.entrySet());
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtCompound shaftsNbt = new NbtCompound();
		this.shafts.forEach((key, stops) -> shaftsNbt.putIntArray(Long.toString(key), stops.stream().mapToInt(Integer::intValue).toArray()));
		nbt.put("shafts", shaftsNbt);

		NbtCompound routesNbt = new NbtCompound();
		this.routes.forEach((bedKey, route) -> {
			NbtCompound routeNbt = new NbtCompound();
			routeNbt.putLong("shaft", route.shaftKey);
			routeNbt.putInt("exitY", route.exitY);
			routesNbt.put(Long.toString(bedKey), routeNbt);
		});
		nbt.put("routes", routesNbt);

		NbtCompound homesNbt = new NbtCompound();
		this.homeOwners.forEach((bedKey, owner) -> homesNbt.putString(Long.toString(bedKey), owner.toString()));
		nbt.put("homes", homesNbt);

		// 表演层空间注册表（MC 侧数据，不进账本 schema；CityLedgerCodec 不动）
		NbtCompound anchorsNbt = new NbtCompound();
		this.buildingAnchors.forEach((buildingId, pos) -> anchorsNbt.putLong(Integer.toString(buildingId), pos.asLong()));
		nbt.put("anchors", anchorsNbt);

		NbtCompound citizenHomesNbt = new NbtCompound();
		this.citizenHomes.forEach((citizenId, buildingId) -> citizenHomesNbt.putInt(Integer.toString(citizenId), buildingId));
		nbt.put("citizenHomes", citizenHomesNbt);

		NbtCompound citizenEntitiesNbt = new NbtCompound();
		this.citizenEntities.forEach((citizenId, uuid) -> citizenEntitiesNbt.putString(Integer.toString(citizenId), uuid.toString()));
		nbt.put("citizenEntities", citizenEntitiesNbt);

		if (this.townHallPos != null) {
			nbt.putLong("townHall", this.townHallPos.asLong());
			nbt.putInt("nationId", nationId);
			if (foundedFrom != 0) nbt.putInt("foundedFrom", foundedFrom);
		}

		CityLedgerCodec.write(this.harness().city, nbt);
		NbtCompound clockNbt = new NbtCompound();
		clockNbt.putInt("speed", clock.speed());
		clockNbt.putInt("remainder", clock.remainder());
		nbt.put("simulationClock", clockNbt);
		land.write(nbt);
		net.minecraft.nbt.NbtList queue = new net.minecraft.nbt.NbtList();
		for (var input : pending) queue.add(input.save());
		nbt.put("pending",queue);
		return nbt;
	}

	public static CityData fromNbt(NbtCompound nbt) {
		CityData data = new CityData();
		NbtCompound clockNbt = nbt.getCompound("simulationClock");
		data.clock.restore(clockNbt.contains("speed") ? clockNbt.getInt("speed") : 1, clockNbt.getInt("remainder"));

		NbtCompound shaftsNbt = nbt.getCompound("shafts");
		for (String key : shaftsNbt.getKeys()) {
			int[] ys = shaftsNbt.getIntArray(key);
			if (ys.length == 0) {
				continue;
			}
			TreeSet<Integer> stops = new TreeSet<>();
			for (int y : ys) {
				stops.add(y);
			}
			data.shafts.put(Long.parseLong(key), stops);
		}

		NbtCompound routesNbt = nbt.getCompound("routes");
		for (String key : routesNbt.getKeys()) {
			NbtCompound routeNbt = routesNbt.getCompound(key);
			data.routes.put(Long.parseLong(key), new Route(routeNbt.getLong("shaft"), routeNbt.getInt("exitY")));
		}

		NbtCompound homesNbt = nbt.getCompound("homes");
		for (String key : homesNbt.getKeys()) {
			try {
				data.homeOwners.put(Long.parseLong(key), UUID.fromString(homesNbt.getString(key)));
			} catch (IllegalArgumentException ignored) {
				// 损坏的 UUID 直接丢弃，留给下次重新分配
			}
		}

		// 表演层空间注册表：读档缺键容错为空表（老存档升级路径）
		NbtCompound anchorsNbt = nbt.getCompound("anchors");
		for (String key : anchorsNbt.getKeys()) {
			try {
				data.buildingAnchors.put(Integer.parseInt(key), BlockPos.fromLong(anchorsNbt.getLong(key)));
			} catch (NumberFormatException ignored) {
			}
		}

		NbtCompound citizenHomesNbt = nbt.getCompound("citizenHomes");
		for (String key : citizenHomesNbt.getKeys()) {
			try {
				data.citizenHomes.put(Integer.parseInt(key), citizenHomesNbt.getInt(key));
			} catch (NumberFormatException ignored) {
			}
		}

		NbtCompound citizenEntitiesNbt = nbt.getCompound("citizenEntities");
		for (String key : citizenEntitiesNbt.getKeys()) {
			try {
				data.citizenEntities.put(Integer.parseInt(key), UUID.fromString(citizenEntitiesNbt.getString(key)));
			} catch (IllegalArgumentException ignored) {
			}
		}

		CityLedgerCodec.read(data.harness().city, nbt);
		if (nbt.contains("townHall")) {
			data.foundCity(BlockPos.fromLong(nbt.getLong("townHall")));
			data.nationId = nbt.getInt("nationId");
			data.foundedFrom = nbt.getInt("foundedFrom");
		}
		data.land.read(nbt, data);
		for (var tag : nbt.getList("pending",10)) data.pending.add(com.livingcity.sensor.CitySensorBus.Input.read((NbtCompound)tag));
		return data;
	}
}
