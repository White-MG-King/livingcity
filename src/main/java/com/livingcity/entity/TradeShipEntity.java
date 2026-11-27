package com.livingcity.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/** A temporary projection of a persisted voyage. The market owns movement and cargo. */
public final class TradeShipEntity extends BoatEntity {
    public TradeShipEntity(EntityType<? extends BoatEntity> type, World world) {
        super(type, world);
        setNoGravity(true);
        setInvulnerable(true);
        setSilent(true);
    }

    @Override public boolean shouldSave() { return false; }
    @Override public void tick() {
        if (getWorld().isClient) super.tick();
        else baseTick();
    }
    @Override public ActionResult interact(PlayerEntity player, Hand hand) { return ActionResult.PASS; }
}
