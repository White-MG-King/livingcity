package com.livingcity.ai;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.livingcity.block.LivingCityBlocks;
import com.livingcity.data.CityData;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.World;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;

/**
 * 三段式回家：走到本层电梯 -> 传送到床所在层 -> 走回床（交给原版 REST 任务）。
 * 每 20 tick 扫一次实体；白天零开销。超时/无路线一律兜底硬传送，保证不露宿。
 */
public final class HomeManager {
	private static final int SCAN_INTERVAL = 20;
	private static final int WALK_TIMEOUT = 20 * 60;
	private static final int BED_TIMEOUT = 20 * 30;
	private static final int CLOSE_HORIZONTAL_SQ = 144;
	private static final int CLOSE_VERTICAL = 2;
	private static final float WALK_SPEED = 0.5F;
	private static final int ASSIGN_RADIUS = 64;
	private static final int SHAFT_SEARCH_RADIUS = 48;

	private static final Map<UUID, Move> MOVES = new HashMap<>();
	private static int ticks;
	private static int scans;
	private static int lastVillagers;
	private static int elevatorRides;
	private static int emergencyReturns;

	private HomeManager() {
	}

	private static final class Move {
		int phase;
		final long shaftKey;
		final int boardY;
		final int exitY;
		long deadline;

		Move(int phase, long shaftKey, int boardY, int exitY, long deadline) {
			this.phase = phase;
			this.shaftKey = shaftKey;
			this.boardY = boardY;
			this.exitY = exitY;
			this.deadline = deadline;
		}
	}

	public static void onWorldTick(ServerWorld world) {
		if (world.getRegistryKey() != World.OVERWORLD) {
			return;
		}
		if (++ticks % SCAN_INTERVAL != 0) {
			return;
		}

		boolean night = world.isNight();
		long now = world.getTime();
		CityData data = CityData.get(world);
		Set<UUID> seen = new HashSet<>();
		scans++;

		for (Entity entity : world.iterateEntities()) {
			if (!(entity instanceof VillagerEntity villager) || villager.isBaby()) {
				continue;
			}
			UUID id = villager.getUuid();
			seen.add(id);

			if (!night || villager.isSleeping() || !villager.isAlive()) {
				MOVES.remove(id);
				continue;
			}

			BlockPos bedPos = resolveBed(world, data, villager);
			if (bedPos == null) {
				continue;
			}

			Move move = MOVES.get(id);
			if (move == null && closeEnough(villager, bedPos)) {
				continue;
			}

			if (move == null) {
				CityData.Route route = data.route(bedPos.asLong());
				if (route == null) {
					route = computeRoute(world, data, bedPos);
				}
				if (route == null) {
					emergencyReturn(world, villager, bedPos);
					continue;
				}
				int boardY = nearestStop(world, data, route.shaftKey, villager.getBlockY());
				if (boardY == Integer.MIN_VALUE) {
					emergencyReturn(world, villager, bedPos);
					continue;
				}
				move = new Move(0, route.shaftKey, boardY, route.exitY, now + WALK_TIMEOUT);
				MOVES.put(id, move);
			}

			if (move.phase == 0) {
				BlockPos elevatorTop = CityData.posOf(move.shaftKey, move.boardY).up();
				if (villager.getBlockPos().getSquaredDistance(elevatorTop) <= 4.0) {
					BlockPos exitTop = CityData.posOf(move.shaftKey, move.exitY).up();
					teleportTo(world, villager, exitTop);
					elevatorRides++;
					move.phase = 1;
					move.deadline = now + BED_TIMEOUT;
				} else if (now > move.deadline) {
					MOVES.remove(id);
					emergencyReturn(world, villager, bedPos);
				} else {
					// 持续占用 WALK_TARGET，挡住原版 REST 里“往够不着的家走”的任务
					villager.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(elevatorTop, WALK_SPEED, 1));
				}
			} else if (now > move.deadline) {
				MOVES.remove(id);
				emergencyReturn(world, villager, bedPos);
			}
		}

		MOVES.keySet().retainAll(seen);
		lastVillagers = seen.size();
	}

	public static int activeMoves() {
		return MOVES.size();
	}

	public static int scans() {
		return scans;
	}

	public static int lastVillagers() {
		return lastVillagers;
	}

	public static int elevatorRides() {
		return elevatorRides;
	}

	public static int emergencyReturns() {
		return emergencyReturns;
	}

	private static BlockPos resolveBed(ServerWorld world, CityData data, VillagerEntity villager) {
		Optional<GlobalPos> home = villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME);
		if (home.isPresent() && home.get().getDimension() == world.getRegistryKey()) {
			return home.get().getPos();
		}
		return assignHome(world, data, villager);
	}

	private static BlockPos assignHome(ServerWorld world, CityData data, VillagerEntity villager) {
		PointOfInterestStorage storage = world.getPointOfInterestStorage();
		Optional<BlockPos> bed = storage
				.getInSquare(entry -> entry.matchesKey(PointOfInterestTypes.HOME), villager.getBlockPos(), ASSIGN_RADIUS,
						PointOfInterestStorage.OccupationStatus.ANY)
				.map(PointOfInterest::getPos)
				.filter(pos -> world.getBlockState(pos).isIn(BlockTags.BEDS))
				.filter(pos -> data.isHomeFree(pos.asLong(), villager.getUuid()))
				.min((a, b) -> Double.compare(a.getSquaredDistance(villager.getBlockPos()), b.getSquaredDistance(villager.getBlockPos())));

		if (bed.isEmpty()) {
			return null;
		}
		BlockPos bedPos = bed.get();
		data.claimHome(bedPos.asLong(), villager.getUuid());
		villager.getBrain().remember(MemoryModuleType.HOME, GlobalPos.create(world.getRegistryKey(), bedPos));
		return bedPos;
	}

	private static CityData.Route computeRoute(ServerWorld world, CityData data, BlockPos bedPos) {
		long bestKey = Long.MIN_VALUE;
		double bestDistSq = (double) SHAFT_SEARCH_RADIUS * SHAFT_SEARCH_RADIUS;
		for (long key : data.shaftKeys()) {
			double dx = CityData.xOf(key) + 0.5 - bedPos.getX();
			double dz = CityData.zOf(key) + 0.5 - bedPos.getZ();
			double distSq = dx * dx + dz * dz;
			if (distSq <= bestDistSq) {
				bestDistSq = distSq;
				bestKey = key;
			}
		}
		if (bestKey == Long.MIN_VALUE) {
			return null;
		}
		int exitY = nearestStop(world, data, bestKey, bedPos.getY());
		if (exitY == Integer.MIN_VALUE) {
			return null;
		}
		CityData.Route route = new CityData.Route(bestKey, exitY);
		data.putRoute(bedPos.asLong(), route);
		return route;
	}

	private static int nearestStop(ServerWorld world, CityData data, long shaftKey, int targetY) {
		TreeSet<Integer> stops = data.stops(shaftKey);
		if (stops == null) {
			return Integer.MIN_VALUE;
		}
		int best = Integer.MIN_VALUE;
		int bestDiff = Integer.MAX_VALUE;
		for (int y : stops) {
			int diff = Math.abs(y - targetY);
			if (diff >= bestDiff) {
				continue;
			}
			BlockPos pos = CityData.posOf(shaftKey, y);
			if (isStandable(world, pos)) {
				best = y;
				bestDiff = diff;
			}
		}
		return best;
	}

	private static boolean isStandable(ServerWorld world, BlockPos pos) {
		if (!world.getBlockState(pos).isOf(LivingCityBlocks.ELEVATOR)) {
			return false;
		}
		BlockPos up1 = pos.up();
		BlockPos up2 = up1.up();
		BlockState state1 = world.getBlockState(up1);
		BlockState state2 = world.getBlockState(up2);
		return state1.getCollisionShape(world, up1).isEmpty() && state2.getCollisionShape(world, up2).isEmpty();
	}

	private static boolean closeEnough(VillagerEntity villager, BlockPos bedPos) {
		int dx = villager.getBlockX() - bedPos.getX();
		int dz = villager.getBlockZ() - bedPos.getZ();
		return Math.abs(villager.getBlockY() - bedPos.getY()) <= CLOSE_VERTICAL && dx * dx + dz * dz <= CLOSE_HORIZONTAL_SQ;
	}

	private static void teleportTo(ServerWorld world, VillagerEntity villager, BlockPos standPos) {
		villager.getNavigation().stop();
		villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
		villager.teleport(world, standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, Set.of(), villager.getYaw(), villager.getPitch());
		world.spawnParticles(ParticleTypes.PORTAL, standPos.getX() + 0.5, standPos.getY() + 1.0, standPos.getZ() + 0.5, 40, 0.3, 0.8, 0.3, 0.05);
		world.playSound(null, standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.NEUTRAL, 0.8F, 1.0F);
	}

	private static void emergencyReturn(ServerWorld world, VillagerEntity villager, BlockPos bedPos) {
		if (!world.getBlockState(bedPos).isIn(BlockTags.BEDS)) {
			return;
		}
		emergencyReturns++;
		villager.getBrain().remember(MemoryModuleType.HOME, GlobalPos.create(world.getRegistryKey(), bedPos));
		teleportTo(world, villager, bedPos.up());
		if (!villager.isSleeping()) {
			villager.sleep(bedPos);
		}
	}
}
