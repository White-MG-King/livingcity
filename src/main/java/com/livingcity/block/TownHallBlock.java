package com.livingcity.block;

import com.livingcity.command.CityCommand;
import com.livingcity.data.CityData;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 市政厅方块（城市篇 §3.4/§3.12）：建城仪式的玩家入口。
 * 首次右键在空闲地块登记城市；收编于下一周期执行。
 * 已建城后右键 = 只读账本摘要（与 /city info 同源）。
 * 方块被拆不毁城：账本是真，锚点只是仪式记录。
 */
public class TownHallBlock extends Block {

	public TownHallBlock(Settings settings) {
		super(settings);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState replacement, boolean moved) {
		super.onStateReplaced(state,world,pos,replacement,moved);
		if(world instanceof ServerWorld sw && sw==sw.getServer().getOverworld() && !replacement.isOf(this)) {
			var city=com.livingcity.data.CityRegistry.get(sw.getServer()).owner(pos);
			if(city!=null && pos.equals(city.townHallPos())) {city.land.hallMissing=true;city.markDirty();}
		}
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if (world.isClient) {
			return ActionResult.SUCCESS;
		}
		ServerWorld serverWorld=(ServerWorld)world;
		if (serverWorld != serverWorld.getServer().getOverworld()) return ActionResult.PASS;
		var registry=com.livingcity.data.CityRegistry.get(serverWorld.getServer());
		CityData data=registry.owner(pos);
		if (data==null || !data.isFounded()) CityCommand.foundAt(serverWorld,pos,t->player.sendMessage(t,false));
		else {
			player.sendMessage(Text.literal("城市 #"+data.id+" · 市政厅 "+data.townHallPos().toShortString()),false);
			for(String line:CityCommand.ledgerLines(data.harness().city)) player.sendMessage(Text.literal(line),false);
		}
		return ActionResult.CONSUME;
	}
}