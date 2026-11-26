package com.livingcity.data;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

/** Completed structures are stored outside the city ledger and debug map data. */
final class CityBuildingHistory extends PersistentState {
    private final NbtCompound cities;

    private CityBuildingHistory(NbtCompound cities) {
        this.cities = cities;
    }

    static CityBuildingHistory get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(
                tag -> new CityBuildingHistory(tag.getCompound("cities")),
                () -> new CityBuildingHistory(new NbtCompound()), "livingcity_building_history");
    }

    CityBlockChanges snapshot(int city, int building) {
        var buildings = cities.getCompound(Integer.toString(city));
        String key = Integer.toString(building);
        return buildings.contains(key, 10) ? CityBlockChanges.read(buildings.getCompound(key)) : null;
    }

    void save(int city, int building, CityBlockChanges snapshot) {
        String key = Integer.toString(city);
        var buildings = cities.getCompound(key);
        buildings.put(Integer.toString(building), snapshot.write());
        cities.put(key, buildings);
        markDirty();
    }

    void remove(int city, int building) {
        String key = Integer.toString(city);
        var buildings = cities.getCompound(key);
        buildings.remove(Integer.toString(building));
        if (buildings.isEmpty()) cities.remove(key);
        markDirty();
    }

    @Override public NbtCompound writeNbt(NbtCompound tag) {
        tag.put("cities", cities);
        return tag;
    }
}
