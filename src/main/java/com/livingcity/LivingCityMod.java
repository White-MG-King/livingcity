package com.livingcity;

import com.livingcity.ai.HomeManager;
import com.livingcity.block.LivingCityBlocks;
import com.livingcity.command.CityCommand;
import com.livingcity.data.CityData;
import com.livingcity.data.CityRegistry;
import com.livingcity.entity.CitizenEntity;
import com.livingcity.entity.LivingCityEntities;
import com.livingcity.item.HourglassItem;
import com.livingcity.sensor.CitySensorBus;
import com.livingcity.sensor.CitySimTicker;
import com.livingcity.web.CityMapTerrain;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class LivingCityMod implements ModInitializer {
	public static final String MOD_ID = "livingcity";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static void requestSurfaceSurvey(net.minecraft.world.World world,net.minecraft.util.math.BlockPos pos) {
		if (!(world instanceof net.minecraft.server.world.ServerWorld sw) || sw!=sw.getServer().getOverworld()) return;
		CityData data=com.livingcity.data.CityRegistry.get(sw.getServer()).owner(pos);
		if(data!=null) data.land.requestSurvey(pos.getX()>>4,pos.getZ()>>4);
	}

	@Override
	public void onInitialize() {
		LivingCityBlocks.register();
		HourglassItem.register();
		com.livingcity.item.CityHandbook.register();
		com.livingcity.item.CityInspector.register();
		com.livingcity.item.WorldMapItem.register();
		LivingCityEntities.register();
		CityCommand.register();
		CitySimTicker.register();
		CityMapTerrain.register();
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world,player,pos,state,blockEntity)->{
			if (state.getBlock() instanceof net.minecraft.block.BedBlock || state.isOf(net.minecraft.block.Blocks.DIRT_PATH)) {
				requestSurfaceSurvey(world,pos);
				if (state.getBlock() instanceof net.minecraft.block.BedBlock) {
					requestSurfaceSurvey(world,pos.offset(state.get(net.minecraft.block.BedBlock.FACING).getOpposite()));
				}
			}
		});
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player,world,hand,hit)->{
			var stack=player.getStackInHand(hand);
			if(stack.getItem() instanceof net.minecraft.item.BedItem || stack.getItem() instanceof net.minecraft.item.ShovelItem) {
				requestSurfaceSurvey(world,hit.getBlockPos());
				requestSurfaceSurvey(world,hit.getBlockPos().offset(hit.getSide()));
			}
			return net.minecraft.util.ActionResult.PASS;
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register((world,chunk)->{
			if(world!=world.getServer().getOverworld()) return;
			var p=chunk.getPos();
			var registry=com.livingcity.data.CityRegistry.get(world.getServer());
			registry.updateCampaignTerrain(p.x,p.z);
			var city=registry.owner(p.x,p.z);
			if(city!=null) city.land.requestSurvey(p.x,p.z);
		});
		ServerTickEvents.END_WORLD_TICK.register(HomeManager::onWorldTick);
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof VillagerEntity villager) {
				CityData.get(villager.getServer()).releaseHome(villager.getUuid());
			} else if (entity instanceof CitizenEntity citizen) {
				// 真死亡才走这里（去物化用 discard()，不触发 AFTER_DEATH——双轨制判别点，任务单 P3）。
				// 上报账本：下周期边界 killCitizen（移池 + 人口 -1 + 编年史）；映射立即清。
				if (citizen.getServer() != null && citizen.getCitizenId() >= 0) {
					CityData city=com.livingcity.data.CityRegistry.get(citizen.getServer()).byId(citizen.getCityId());
					if(city==null) return;
					city.unbindCitizenEntity(citizen.getCitizenId());
					CitySensorBus.offer(city,new CitySensorBus.Input(
							CitySensorBus.Input.Type.CITIZEN_DEATH, citizen.getCitizenId(), 0,
							damageSource.getName()));
				}
			}
		});
		// 实体映射自愈（任务单 P2 步骤 4 的事件化兜底）：区块装载重新绑定、卸载清映射。
		// 同 id 异 UUID 的旧实体（理论不可达的重复态）直接丢弃，映射在册的活体为准。
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof CitizenEntity citizen && citizen.getCitizenId() >= 0) {
				CityData data = com.livingcity.data.CityRegistry.get(world.getServer()).byId(citizen.getCityId());
				if(data==null) return;
				if (!data.harness().city.materializationPool.contains(citizen.getCitizenId())) {
					citizen.discard();
					return;
				}
				UUID mapped = data.citizenEntity(citizen.getCitizenId());
				if (mapped != null && !mapped.equals(citizen.getUuid())) {
					citizen.discard();
					return;
				}
				data.bindCitizenEntity(citizen.getCitizenId(), citizen.getUuid());
			} else if (entity instanceof VillagerEntity villager && entity.getType() == EntityType.VILLAGER
					&& world == world.getServer().getOverworld()) {
				CityRegistry.get(world.getServer()).admissions().load(villager);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof CitizenEntity citizen && citizen.getCitizenId() >= 0) {
				CityData data = com.livingcity.data.CityRegistry.get(world.getServer()).byId(citizen.getCityId());
				if(data==null) return;
				UUID mapped = data.citizenEntity(citizen.getCitizenId());
				if (mapped != null && mapped.equals(citizen.getUuid())) {
					data.unbindCitizenEntity(citizen.getCitizenId());
				}
			} else if (entity instanceof VillagerEntity villager && entity.getType() == EntityType.VILLAGER
					&& world == world.getServer().getOverworld()) {
				CityRegistry.get(world.getServer()).admissions().unload(villager);
			}
		});
		// 日志用英文，避免 Windows 控制台 GBK 编码把中文显示成乱码
		LOGGER.info("[LivingCity] main entrypoint initialized (common side)");
	}
}
