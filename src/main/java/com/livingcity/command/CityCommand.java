package com.livingcity.command;

import com.livingcity.ai.HomeManager;
import com.livingcity.block.LivingCityBlocks;
import com.livingcity.data.CityData;
import com.livingcity.data.CityRegistry;
import com.livingcity.sensor.CitySensorBus;
import com.livingcity.sensor.CitySimTicker;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import citysim.BuildingEntry;
import citysim.BuildingKind;
import citysim.CityLedger;
import citysim.SimEvent;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;

import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

public final class CityCommand {
	private CityCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("city")
					.requires(source -> source.hasPermissionLevel(2) && source.getWorld()==source.getServer().getOverworld())
					.then(CommandManager.literal("list").executes(ctx -> list(ctx.getSource())))
					.then(NationCommand.node())
					.then(CommandManager.literal("relocate").then(CommandManager.argument("cityId",IntegerArgumentType.integer(1))
						.then(CommandManager.argument("pos",BlockPosArgumentType.blockPos()).executes(ctx -> relocate(ctx.getSource(),
							IntegerArgumentType.getInteger(ctx,"cityId"),BlockPosArgumentType.getBlockPos(ctx,"pos"))))))
					.then(CommandManager.literal("info").executes(ctx -> info(ctx.getSource())))
					.then(CommandManager.literal("demolish")
							.then(CommandManager.argument("id", IntegerArgumentType.integer(1))
									.executes(ctx -> demolish(ctx.getSource(), IntegerArgumentType.getInteger(ctx,"id")))))
					.then(CommandManager.literal("speed")
							.executes(ctx -> speed(ctx.getSource(), null))
							.then(CommandManager.argument("value", IntegerArgumentType.integer(0, 1000))
									.executes(ctx -> speed(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "value")))))
					.then(CommandManager.literal("step").executes(ctx -> step(ctx.getSource())))
					.then(CommandManager.literal("status").executes(ctx -> status(ctx.getSource())))
					.then(CommandManager.literal("citizens").executes(ctx -> citizens(ctx.getSource())))
					.then(CommandManager.literal("raw").executes(ctx -> raw(ctx.getSource())))
					.then(CommandManager.literal("dusk").executes(ctx -> setTime(ctx.getSource(), 13000)))
					.then(CommandManager.literal("dawn").executes(ctx -> setTime(ctx.getSource(), 1000)))
					.then(CommandManager.literal("tower")
							.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
									.then(CommandManager.argument("floors", IntegerArgumentType.integer(1, 64))
											.executes(ctx -> tower(ctx.getSource(), BlockPosArgumentType.getBlockPos(ctx, "pos"),
													IntegerArgumentType.getInteger(ctx, "floors"))))))
					.then(CommandManager.literal("rescan")
							.executes(ctx -> rescan(ctx.getSource(), 16))
							.then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 32))
									.executes(ctx -> rescan(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
					.then(CommandManager.literal("beds")
							.executes(ctx -> beds(ctx.getSource(), 64))
							.then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 128))
									.executes(ctx -> beds(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
					.then(CommandManager.literal("register")
							.then(registerNode("house", BuildingKind.HOUSE))
							.then(registerNode("well", BuildingKind.WELL))
							.then(registerNode("plaza", BuildingKind.PLAZA)))
					.then(CommandManager.literal("found")
							.executes(ctx -> found(ctx.getSource(), null))
							.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
									.executes(ctx -> found(ctx.getSource(), BlockPosArgumentType.getBlockPos(ctx, "pos")))))
					.then(CommandManager.literal("takeover")
							.executes(ctx -> takeover(ctx.getSource(), null))
							.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
									.executes(ctx -> takeover(ctx.getSource(), BlockPosArgumentType.getBlockPos(ctx, "pos")))))
					.then(CommandManager.literal("winter")
							.then(CommandManager.argument("cycles", IntegerArgumentType.integer(1, 100000))
									.executes(ctx -> winter(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "cycles")))))
					.then(CommandManager.literal("fire")
							.then(CommandManager.argument("buildingId", IntegerArgumentType.integer(1, 1000000))
									.executes(ctx -> fire(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "buildingId")))))
					.then(CommandManager.literal("chronicle")
							.executes(ctx -> chronicle(ctx.getSource(), 10))
							.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 200))
									.executes(ctx -> chronicle(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))
					.then(CommandManager.literal("debug")
							.then(CommandManager.literal("materials")
									.then(CommandManager.argument("wood", IntegerArgumentType.integer(0, CityLedger.STOCK_LIMIT))
											.then(CommandManager.argument("stone", IntegerArgumentType.integer(0, CityLedger.STOCK_LIMIT))
													.executes(ctx -> debugMaterials(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "wood"),
															IntegerArgumentType.getInteger(ctx, "stone"))))))
							.then(CommandManager.literal("population")
									.then(CommandManager.argument("value", IntegerArgumentType.integer(0, 100000))
											.executes(ctx -> debugPopulation(ctx.getSource(),
													IntegerArgumentType.getInteger(ctx, "value")))))
							.then(CommandManager.literal("food")
									.then(CommandManager.argument("points", IntegerArgumentType.integer(0, 1000000))
											.executes(ctx -> debugFood(ctx.getSource(),
													IntegerArgumentType.getInteger(ctx, "points"))))));
			dispatcher.register(root);
		});
	}

	private static CityData local(ServerCommandSource source) {
		return CityRegistry.get(source.getServer()).at(BlockPos.ofFloored(source.getPosition()));
	}

	private static int list(ServerCommandSource source) {
		for (CityData city:CityRegistry.get(source.getServer()).cities()) if(city.isFounded())
			source.sendFeedback(()->Text.literal("#"+city.id+"  "+city.townHallPos().toShortString())
					.append(Text.translatable("message.livingcity.nation.list_tag", city.nationId()))
					.append(city.land.hallMissing ? "  市政厅缺失" : ""),false);
		return 1;
	}

	private static int relocate(ServerCommandSource source,int id,BlockPos p) {
		var registry=CityRegistry.get(source.getServer());
		var city=registry.byId(id);
		var world=source.getWorld();
		if(city==null || !city.isFounded() || !world.isChunkLoaded(p) || !world.getBlockState(p).isOf(LivingCityBlocks.TOWN_HALL)
				|| !registry.canPlaceHall(city,p)) {
			source.sendError(Text.literal("迁址失败：检查城市编号、新市政厅方块及其周围占地")); return 0;
		}
		if (!registry.moveHall(city,p)) { source.sendError(Text.literal("无法四向连接到原城领地")); return 0; }
		city.land.hallMissing=false;
		source.sendFeedback(()->Text.literal("城市 #"+id+" 已迁至 "+p.toShortString()),true);
		return 1;
	}

	private static int step(ServerCommandSource source) {
		if (!CityData.get(source.getServer()).clock().requestStep()) {
			source.sendError(Text.translatable("message.livingcity.step_requires_pause"));
			return 0;
		}
		source.sendFeedback(() -> Text.translatable("message.livingcity.step_queued"), false);
		return 1;
	}

	private static int info(ServerCommandSource source) {
		CityData data = local(source);
		source.sendFeedback(() -> Text.literal("[城市 #" + data.id + "] 市政厅="
				+ (data.isFounded() ? data.townHallPos().toShortString() + (data.land.hallMissing?"（缺失）":"") : "未建城（放市政厅方块右键，或 /city found）")), false);
		if (data.nationId() > 0) {
			source.sendFeedback(() -> Text.translatable("message.livingcity.nation.role", data.id, data.nationId()), false);
		}
		for (String line : ledgerLines(data.harness().city)) {
			source.sendFeedback(() -> Text.literal(line), false);
		}
		source.sendFeedback(() -> Text.literal(String.format("[LivingCity] 电梯井=%d 路线缓存=%d 床位分配=%d 回家状态机=%d 扫描=%d 上次村民=%d 电梯乘坐=%d 兜底传送=%d",
				data.shaftCount(), data.routeCount(), data.homeCount(), HomeManager.activeMoves(),
				HomeManager.scans(), HomeManager.lastVillagers(), HomeManager.elevatorRides(), HomeManager.emergencyReturns())), false);
		return 1;
	}

	private static int speed(ServerCommandSource source, Integer requested) {
		CityData data = CityData.get(source.getServer());
		if (requested != null && !CitySimTicker.setSpeed(data, requested)) {
			source.sendError(Text.translatable("message.livingcity.speed_values"));
			return 0;
		}
		source.sendFeedback(() -> CitySimTicker.speedStatus(data.clock()), requested != null);
		return 1;
	}

	private static int demolish(ServerCommandSource source, int id) {
		CityData data = local(source);
		if (!data.land.queueDemolition(source.getWorld(), data, id)) {
			source.sendError(Text.literal("城市 #" + data.id + " 的建筑 #" + id + " 没有可用的拆除快照"));
			return 0;
		}
		source.sendFeedback(() -> Text.literal("城市 #" + data.id + " 已安排拆除建筑 #" + id), false);
		return 1;
	}

	/** 账本全字段摘要（S1 重启对比用：满意度小数、工程进度、编年史、随机流相关计数字段都在）。市政厅右键复用。 */
	public static java.util.List<String> ledgerLines(CityLedger city) {
		java.util.List<String> lines = new java.util.ArrayList<>();
		lines.add(String.format("[我的世界盒子] 周期=%d 天=%d 人口=%d 存粮=%.3f 可用床=%d 待落地床=%d 建筑=%d 待落地=%d",
				city.cycleCount, city.dayCount, city.population, city.foodPoints, city.totalBeds(), city.pendingBeds(),
				city.buildings.size(), city.pendingBuildings()));

		StringBuilder sat = new StringBuilder("  满意度");
		StringBuilder bias = new StringBuilder("  bias  ");
		for (int layer = 0; layer < city.satisfaction.length; layer++) {
			sat.append(String.format(" %s=%.3f", citysim.LayerId.name(layer), city.satisfaction[layer]));
			bias.append(String.format(" %s=%.3f", citysim.LayerId.name(layer), city.bias[layer]));
		}
		lines.add(sat.toString());
		lines.add(bias.toString());

		StringBuilder bl = new StringBuilder("  建筑");
		if (city.buildings.isEmpty()) bl.append("=无");
		for (BuildingEntry b : city.buildings) {
			bl.append(String.format(" [#%d %s/%s 床=%d 完整性=%.3f(%d/%d) 停摆=%d 建于=%d]",
					b.id, b.kind.name(), b.state.name(), b.bedCount, b.integrity(),
					b.missingCount(), b.totalStructure, b.stoppedSince, b.builtAtCycle));
		}
		lines.add(bl.toString());

		CityLedger.Project p = city.activeProject;
		lines.add(p == null ? "  工程=无" : String.format(
				"  工程=%s ×%d paid=%.3f/%.3f delivered=%d paused=%b checkpoint=%b starved=%d",
				p.kind.name(), p.n, p.paidHours, p.requiredHours, p.delivered, p.paused, p.atCheckpoint, p.starvedCycles));
		CityLedger.Project waiting = city.deferredProject;
		if (waiting != null) {
			lines.add(String.format("  暂缓工程=%s ×%d 已付工时=%.3f/%.3f 已交付=%d",
					waiting.kind.displayName, waiting.n, waiting.paidHours, waiting.requiredHours, waiting.delivered));
		}

		lines.add("  解锁=" + city.unlocked + " 硬事件=" + (city.hardEvent == null ? "无"
				: citysim.LayerId.name(city.hardEvent.layer) + "(days=" + city.hardEvent.daysActive + ")"));

		java.util.List<citysim.Chronicle.Entry> entries = city.chronicle.entries();
		lines.add("  编年史=" + entries.size() + " 末条="
				+ (entries.isEmpty() ? "-" : entries.get(entries.size() - 1).toString()));

		lines.add("  物化池=" + new java.util.TreeSet<>(city.materializationPool)
				+ " nextCitizenId=" + city.nextCitizenId + " nextBuildingId=" + city.nextBuildingId);

		lines.add(String.format("  工时账 总=%.3f 农=%.3f 运=%.3f 建=%.3f 闲=%.3f",
				city.totalLaborProduced, city.totalFarmHours, city.totalOpsHours,
				city.totalBuildHours, city.totalIdleHours));

		lines.add(String.format("  覆盖 井=%d 广场=%d 随机源=%s",
				city.coveredBy(BuildingKind.WELL), city.coveredBy(BuildingKind.PLAZA),
				com.livingcity.data.CityLedgerCodec.randomFingerprint(city.random)));

		StringBuilder events = new StringBuilder("  事件");
		if (city.activeEvents.isEmpty()) events.append("=无");
		for (SimEvent e : city.activeEvents) {
			events.append(String.format(" [%s rem=%d eff=%.2f target=%d]",
					e.type.name(), e.remainingCycles, e.farmEff, e.targetBuildingId));
		}
		lines.add(events.toString());
		return lines;
	}

	/** 验收用只读口：打印最近 count 条编年史（不做任何业务判定）。 */
	private static int chronicle(ServerCommandSource source, int count) {
		java.util.List<citysim.Chronicle.Entry> entries = local(source).harness().city.chronicle.entries();
		int from = Math.max(0, entries.size() - count);
		for (int i = from; i < entries.size(); i++) {
			String line = "  [" + (i + 1) + "/" + entries.size() + "] " + entries.get(i);
			source.sendFeedback(() -> Text.literal(line), false);
		}
		return entries.size() - from;
	}

	/** 市民调试视图（表演层任务单 P4）：池 / 活实体 / 住所 / 锚点 + 逐条明细。只读。 */
	private static int citizens(ServerCommandSource source) {
		CityData data = local(source);
		CityLedger city = data.harness().city;
		ServerWorld world = source.getWorld();
		TreeSet<Integer> pool = new TreeSet<>(city.materializationPool);
		int alive = 0;
		java.util.List<String> detail = new java.util.ArrayList<>();
		for (int id : pool) {
			UUID uuid = data.citizenEntity(id);
			Entity entity = uuid == null ? null : world.getEntity(uuid);
			if (entity != null) {
				alive++;
			}
			if (detail.size() < 20) {
				int home = data.citizenHome(id);
				detail.add(String.format("  市民#%d 住所=%s 实体=%s %s",
						id,
						home >= 0 ? "建筑#" + home : "无",
						uuid == null ? "-" : uuid.toString().substring(0, 8),
						entity == null ? "(离场)" : String.format("(在场 %d,%d,%d)",
								entity.getBlockX(), entity.getBlockY(), entity.getBlockZ())));
			}
		}
		final int fAlive = alive;
		source.sendFeedback(() -> Text.literal(String.format(
				"[我的世界盒子] 市民：人口=%d 池=%d 活实体=%d 住所分配=%d 锚点=%d",
				city.population, pool.size(), fAlive, data.citizenHomeCount(), data.anchorCount())), false);
		for (String line : detail) {
			source.sendFeedback(() -> Text.literal(line), false);
		}
		if (pool.size() > detail.size()) {
			source.sendFeedback(() -> Text.literal("  …（共 " + pool.size() + " 条，仅显示前 " + detail.size() + " 条）"), false);
		}
		return pool.size();
	}

	/**
	 * 机器可读单行摘要（验收脚本解析用）。纯 ASCII 单行：既绕开 RCON 单包 4096 字节截断，
	 * 也绕开 Windows PowerShell 5.1 解析中文标签的编码坑。
	 */
	private static int raw(ServerCommandSource source) {
		CityData data = local(source);
		CityLedger city = data.harness().city;
		ServerWorld world = source.getWorld();
		TreeSet<Integer> pool = new TreeSet<>(city.materializationPool);
		int alive = 0;
		StringBuilder ids = new StringBuilder();
		StringBuilder aliveIds = new StringBuilder();
		for (int id : pool) {
			UUID uuid = data.citizenEntity(id);
			Entity entity = uuid == null ? null : world.getEntity(uuid);
			if (entity != null) {
				alive++;
				if (aliveIds.length() > 0) {
					aliveIds.append(',');
				}
				aliveIds.append(id);
			}
			if (ids.length() > 0) {
				ids.append(',');
			}
			ids.append(id);
		}
		final int fAlive = alive;
		final String idsStr = ids.toString();
		final String aliveStr = aliveIds.toString();
		source.sendFeedback(() -> Text.literal(String.format(
				"RAW cycle=%d day=%d population=%d pool=%d alive=%d homes=%d anchors=%d buildings=%d chronicle=%d ids=%s aliveIds=%s",
				city.cycleCount, city.dayCount, city.population, pool.size(), fAlive,
				data.citizenHomeCount(), data.anchorCount(), city.buildings.size(),
				city.chronicle.entries().size(), idsStr, aliveStr)), false);
		return 1;
	}

	private static int takeover(ServerCommandSource source, BlockPos pos) {
		BlockPos center = pos != null ? pos : BlockPos.ofFloored(source.getPosition());
		CitySensorBus.offer(CityRegistry.get(source.getServer()).at(center),new CitySensorBus.Input(
				CitySensorBus.Input.Type.TAKEOVER_VILLAGE, 0, 0, null, center.getX(), center.getY(), center.getZ()));
		source.sendFeedback(() -> Text.literal(String.format(
				"[我的世界盒子] 已入队：村庄收编（中心 %d,%d,%d，半径 48 格），下个周期边界生效", center.getX(), center.getY(), center.getZ())), false);
		return 1;
	}

	private static int debugMaterials(ServerCommandSource source, int wood, int stone) {
		CityData data = local(source);
		var ledger = data.harness().city;
		ledger.timber = wood;
		ledger.stone = stone;
		data.markDirty();
		source.sendFeedback(() -> Text.translatable("message.livingcity.nation.debug_materials", wood, stone), false);
		return 1;
	}

	private static int found(ServerCommandSource source, BlockPos pos) {
		BlockPos anchor = pos != null ? pos : BlockPos.ofFloored(source.getPosition());
		return foundAt(source.getWorld(),anchor,t -> source.sendFeedback(()->t,false));
	}

	public static int foundAt(ServerWorld world,BlockPos anchor,java.util.function.Consumer<Text> feedback) {
		var registry=CityRegistry.get(world.getServer());
		CityData city=registry.found(anchor);
		if(city==null) {
			var owner=registry.owner(anchor);
			feedback.accept(Text.literal(owner==null?"市政厅入口与已有领地冲突":"此处属于城市 #"+owner.id)); return 0;
		}
		city.land.hallMissing=!world.isChunkLoaded(anchor) || !world.getBlockState(anchor).isOf(LivingCityBlocks.TOWN_HALL);
		CitySensorBus.offer(city,new CitySensorBus.Input(CitySensorBus.Input.Type.TAKEOVER_VILLAGE,0,0,null,anchor.getX(),anchor.getY(),anchor.getZ()));
		city.harness().city.chronicle.log(city.harness().city.dayCount,"城市注册");
		feedback.accept(Text.literal("城市 #"+city.id+" 已成立，村庄收编已入队"));
		return 1;
	}

	private static LiteralArgumentBuilder<ServerCommandSource> registerNode(String literal, BuildingKind kind) {
		return CommandManager.literal(literal)
				.executes(ctx -> register(ctx.getSource(), kind, -1, null))
				.then(CommandManager.argument("beds", IntegerArgumentType.integer(0, 64))
						.executes(ctx -> register(ctx.getSource(), kind, IntegerArgumentType.getInteger(ctx, "beds"), null))
						.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
								.executes(ctx -> register(ctx.getSource(), kind, IntegerArgumentType.getInteger(ctx, "beds"),
										BlockPosArgumentType.getBlockPos(ctx, "pos")))));
	}

	private static int register(ServerCommandSource source, BuildingKind kind, int beds, BlockPos pos) {

		// 锚点缺省 = 执行人站位（表演层任务单 P1/P4）
		BlockPos anchor = pos != null ? pos : BlockPos.ofFloored(source.getPosition());
		CityData destination=CityRegistry.get(source.getServer()).at(anchor);
		int resolved=beds>=0 ? beds : (kind==BuildingKind.HOUSE ? destination.harness().city.config.houseBeds : 0);
		CitySensorBus.offer(destination,new CitySensorBus.Input(
				CitySensorBus.Input.Type.REGISTER_BUILDING, 0, resolved, kind.name(),
				anchor.getX(), anchor.getY(), anchor.getZ()));
		source.sendFeedback(() -> Text.literal(String.format(
				"[我的世界盒子] 已入队：登记%s（%d 床，锚点 %d,%d,%d），下个周期边界生效",
				kind.displayName, resolved, anchor.getX(), anchor.getY(), anchor.getZ())), false);
		return 1;
	}

	private static int winter(ServerCommandSource source, int cycles) {
		CitySensorBus.offer(local(source),new CitySensorBus.Input(
				CitySensorBus.Input.Type.WINTER_INJECT, cycles, 0, ""));
		source.sendFeedback(() -> Text.literal(
				"[我的世界盒子] 已入队：严冬 " + cycles + " 周期，下个周期边界生效"), false);
		return 1;
	}

	private static int fire(ServerCommandSource source, int buildingId) {
		CitySensorBus.offer(local(source),new CitySensorBus.Input(
				CitySensorBus.Input.Type.FIRE_INJECT, buildingId, 0, ""));
		source.sendFeedback(() -> Text.literal(
				"[我的世界盒子] 已入队：火灾 建筑#" + buildingId + "，下个周期边界生效"), false);
		return 1;
	}

	/** 验收夹具：账本人口没有 MC 侧写入口（核心只允许出生/移民自行增长），仅用于 S3 进游戏验收。 */
	private static int debugPopulation(ServerCommandSource source, int value) {
		CityData data = local(source);
		data.harness().city.population = value;
		data.markDirty();
		source.sendFeedback(() -> Text.literal("[我的世界盒子][调试] 人口=" + value), false);
		return value;
	}

	/** 验收夹具：S1 需要「工程进行中」的存档；只用于进游戏验收，不是玩法入口。 */
	private static int debugFood(ServerCommandSource source, int points) {
		CityData data = local(source);
		data.harness().city.foodPoints = points;
		data.markDirty();
		source.sendFeedback(() -> Text.literal("[我的世界盒子][调试] 存粮=" + points + " 点"), false);
		return points;
	}

	private static int beds(ServerCommandSource source, int radius) {
		ServerWorld world = source.getWorld();
		BlockPos center = BlockPos.ofFloored(source.getPosition());
		java.util.List<BlockPos> found = world.getPointOfInterestStorage()
				.getInSquare(entry -> entry.matchesKey(net.minecraft.world.poi.PointOfInterestTypes.HOME), center, radius,
						net.minecraft.world.poi.PointOfInterestStorage.OccupationStatus.ANY)
				.map(net.minecraft.world.poi.PointOfInterest::getPos)
				.limit(2000)
				.collect(java.util.stream.Collectors.toList());
		String sample = found.stream().limit(3).map(pos -> pos.toShortString()).collect(java.util.stream.Collectors.joining(" "));
		source.sendFeedback(() -> Text.literal(String.format("[LivingCity] 中心=%s 半径=%d 床POI=%d %s",
				center.toShortString(), radius, found.size(), sample)), false);
		return found.size();
	}

	private static int status(ServerCommandSource source) {
		ServerWorld world = source.getWorld();
		int total = 0;
		int sleeping = 0;
		int withHome = 0;
		int farFromHome = 0;
		int noHome = 0;
		StringBuilder problems = new StringBuilder();
		for (Entity entity : world.iterateEntities()) {
			if (!(entity instanceof VillagerEntity villager) || villager.isBaby()) {
				continue;
			}
			total++;
			if (villager.isSleeping()) {
				sleeping++;
			}
			Optional<GlobalPos> home = villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME);
			if (home.isEmpty() || home.get().getDimension() != world.getRegistryKey()) {
				noHome++;
				continue;
			}
			withHome++;
			BlockPos bed = home.get().getPos();
			int dx = villager.getBlockX() - bed.getX();
			int dz = villager.getBlockZ() - bed.getZ();
			boolean far = Math.abs(villager.getBlockY() - bed.getY()) > 2 || dx * dx + dz * dz > 144;
			if (far) {
				farFromHome++;
			}
			if ((far || noHome > 0) && problems.length() < 320) {
				problems.append(String.format(" %s@%d,%d,%d->床%d,%d,%d%s",
						villager.getUuid().toString().substring(0, 8),
						villager.getBlockX(), villager.getBlockY(), villager.getBlockZ(),
						bed.getX(), bed.getY(), bed.getZ(),
						villager.isSleeping() ? "(睡)" : (far ? "(远)" : "")));
			}
		}
		final int fTotal = total;
		final int fSleeping = sleeping;
		final int fWithHome = withHome;
		final int fNoHome = noHome;
		final int fFarFromHome = farFromHome;
		final String detail = problems.toString();
		source.sendFeedback(() -> Text.literal(String.format(
				"[LivingCity] 村民=%d 已睡=%d 有床=%d 无床=%d 离家远=%d 状态机=%d%s",
				fTotal, fSleeping, fWithHome, fNoHome, fFarFromHome, HomeManager.activeMoves(), detail)), false);
		return total;
	}

	private static int setTime(ServerCommandSource source, long timeOfDay) {
		source.getWorld().setTimeOfDay(timeOfDay);
		source.sendFeedback(() -> Text.literal("[LivingCity] timeOfDay=" + timeOfDay), false);
		return 1;
	}

	private static int tower(ServerCommandSource source, BlockPos base, int floors) {
		ServerWorld world = source.getWorld();
		CityData data = CityData.get(world);
		for (var city : CityRegistry.get(world.getServer()).cities()) {
			BlockPos h=city.townHallPos();
			if(h!=null && Math.abs(base.getX()-h.getX())<=5 && Math.abs(base.getZ()-h.getZ())<=5) {
				source.sendError(Text.literal("测试塔占地与市政厅保留区重叠")); return 0;
			}
		}
		BlockState stone = Blocks.SMOOTH_STONE.getDefaultState();
		int spawned = 0;

		// 0 层是街道层（无床），1..floors 每层一张床，保证每只村民都必须坐电梯
		for (int floor = 0; floor <= floors; floor++) {
			int y = base.getY() + floor * 4;
			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
					world.setBlockState(base.add(dx, y - base.getY(), dz), stone, Block.NOTIFY_ALL);
				}
			}
			BlockPos elevatorPos = base.add(0, y - base.getY(), 0);
			world.setBlockState(elevatorPos, LivingCityBlocks.ELEVATOR.getDefaultState(), Block.NOTIFY_ALL);
			data.addElevator(elevatorPos);

			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
					if (Math.abs(dx) != 4 && Math.abs(dz) != 4) {
						continue;
					}
					world.setBlockState(base.add(dx, y - base.getY() + 1, dz), stone, Block.NOTIFY_ALL);
					world.setBlockState(base.add(dx, y - base.getY() + 2, dz), stone, Block.NOTIFY_ALL);
				}
			}

			if (floor > 0) {
				BlockPos footPos = base.add(2, y - base.getY() + 1, 0);
				BlockState foot = Blocks.RED_BED.getDefaultState()
						.with(BedBlock.FACING, Direction.EAST)
						.with(BedBlock.PART, BedPart.FOOT)
						.with(BedBlock.OCCUPIED, false);
				world.setBlockState(footPos, foot, Block.NOTIFY_ALL);
				world.setBlockState(footPos.offset(Direction.EAST), foot.with(BedBlock.PART, BedPart.HEAD), Block.NOTIFY_ALL);
			}
		}

		for (int i = 0; i < floors; i++) {
			int dx = (i % 7) - 3;
			int dz = ((i / 7) % 7) - 3;
			if (dx == 0 && dz == 0) {
				dx = 1;
			}
			BlockPos spawnPos = base.add(dx, 1, dz);
			VillagerEntity villager = EntityType.VILLAGER.spawn(world, spawnPos, SpawnReason.COMMAND);
			if (villager != null) {
				spawned++;
			}
		}

		world.setTimeOfDay(13000);
		final int count = spawned;
		source.sendFeedback(() -> Text.literal(String.format("[LivingCity] 测试塔已生成：%d 层，村民 %d 只，时间已设为夜晚", floors, count)), true);
		return spawned;
	}

	private static int rescan(ServerCommandSource source, int radius) {
		ServerWorld world = source.getWorld();
		CityData data = CityData.get(world);
		BlockPos center = BlockPos.ofFloored(source.getPosition());
		int found = 0;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int y = world.getBottomY(); y < world.getTopY(); y++) {
					BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
					if (world.getBlockState(pos).isOf(LivingCityBlocks.ELEVATOR)) {
						data.addElevator(pos);
						found++;
					}
				}
			}
		}
		final int count = found;
		source.sendFeedback(() -> Text.literal("[LivingCity] 重新索引电梯方块 " + count + " 个，井 " + data.shaftCount() + " 条"), true);
		return found;
	}
}
