package com.livingcity.data;

import citysim.CityFrontier;
import com.livingcity.sensor.CitySensorBus;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

final class CityRegistryTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static void run() {
        CityData legacy = new CityData();
        BlockPos hall = new BlockPos(-16, 70, 0);
        legacy.foundCity(hall);
        legacy.harness().city.population = 19;
        legacy.setBuildingAnchor(3, new BlockPos(-51, 68, 20));
        CityRegistry registry = CityRegistry.read(legacy.writeNbt(new NbtCompound()));
        check(registry.rules().map().border() == 8 && registry.rules().map().guardMs() == 45, "legacy map settings lost their defaults");
        CityData first = registry.byId(1);
        check(registry.at(hall) == first && first.harness().city.population == 19, "lost legacy city");
        check(registry.owner(-4, 1) == first && registry.owner(2, 4) == null, "invented or lost legacy claim");
        check(registry.found(hall.east()) == null, "duplicate city on entrance");
        CityData second = registry.found(new BlockPos(80, 64, 80));
        check(second != null && second.id == 2, "second city not created");
        check(registry.claim(first, 1, 1) && !registry.claim(second, 1, 1), "two cities claimed same chunk");
        check(!registry.canPlaceHall(second, hall), "relocation crossed city boundary");
        check(first.land.hallReserved(hall.west(), first) && !first.land.hallReserved(hall.west(3), first),
                "hall footprint must not occupy its entire chunk");
        CitySensorBus.offer(second, new CitySensorBus.Input(CitySensorBus.Input.Type.WINTER_INJECT,4,0,"winter"));
        second.land.hallMissing = true;
        CityRegistry read = CityRegistry.read(registry.writeNbt(new NbtCompound()));
        var settings = new CityRules.MapSettings(12, 2, 1, 1, 3, 42);
        check(read.rules().survey().equals(CityRules.SurveySettings.defaults()), "legacy survey defaults missing");
        var surveySettings = new CityRules.SurveySettings(false, 4, 3);
        read.rules().setSurvey(surveySettings);
        check(CityRegistry.read(read.writeNbt(new NbtCompound())).rules().survey().equals(surveySettings),
                "survey enable switch, slots or workers changed on reload");
        read.rules().setMap(settings);
        check(CityRegistry.read(read.writeNbt(new NbtCompound())).rules().map().equals(settings), "map performance settings did not survive reload");
        check(read.byId(1).pending.isEmpty() && read.byId(2).pending.element().idA()==4, "event routed to wrong city");
        check(read.owner(1, 1).id == 1 && read.byId(2).land.hallMissing, "lost claim or missing hall");
        for(CityData city:read.cities()) {
            var connected=new java.util.HashSet<Long>();
            var queue=new java.util.ArrayDeque<Long>();
            var hallPos=city.townHallPos();
            queue.add(CityData.xzKey(hallPos.getX()>>4,hallPos.getZ()>>4));
            while(!queue.isEmpty()) {
                long k=queue.remove();
                if(!city.land.chunks.contains(k) || !connected.add(k)) continue;
                int x=CityData.xOf(k),z=CityData.zOf(k);
                queue.add(CityData.xzKey(x+1,z)); queue.add(CityData.xzKey(x-1,z));
                queue.add(CityData.xzKey(x,z+1)); queue.add(CityData.xzKey(x,z-1));
            }
            check(connected.equals(city.land.chunks),"disconnected territory after migration/claim");
        }
        NbtCompound versionOne = registry.writeNbt(new NbtCompound());
        versionOne.putInt("registryVersion", 1);
        NbtCompound oldFirst = versionOne.getCompound("cities").getCompound("1");
        oldFirst.putLongArray("chunks", new long[]{CityData.xzKey(-1, 0), CityData.xzKey(-3, 0), CityData.xzKey(100, 100)});
        CityRegistry migrated = CityRegistry.read(versionOne);
        check(migrated.owner(-2, 0) == migrated.byId(1) && migrated.owner(-3, 0) == migrated.byId(1),
                "a reachable legacy enclave was not joined");
        check(migrated.owner(100, 100) == null && migrated.owner(5, 5) == migrated.byId(2),
                "legacy repair kept an unreachable enclave or removed a competitor's territory");
        check(migrated.byId(1).buildingAnchor(3).equals(first.buildingAnchor(3)),
                "legacy repair removed a building anchor");
        var economy = first.harness().city;
        check(economy.runIndustry(5)==0 && economy.timber==0,"timber without forest");
        economy.forestAvailable=true;
        economy.runIndustry(6);economy.runIndustry(6);
        check(economy.timber==12,"wrong timber yield");
        economy.quarryLocated=true;economy.runIndustry(6);
        check(!economy.quarryActive() && economy.stone==0,"quarry produced before completion");
        economy.runIndustry(2);
        check(economy.quarryActive() && economy.timber==0,"quarry did not pay exact wood price");
        economy.runIndustry(4);
        check(economy.stone==2,"quarry output missing");
        var limits = new CityRegistry();
        limits.rules().toggleTimedExpansion();
        var town = limits.found(new BlockPos(8, 64, 8));
        check(town != null && !limits.claimForBuilding(town, citysim.CityFrontier.key(2, 0), null),
                "automatic growth jumped an intervening chunk");
        for (int x = 1; x < limits.rules().territory(); x++) {
            check(limits.claimForBuilding(town, citysim.CityFrontier.key(x, 0), null), "adjacent cell refused");
        }
        long next = citysim.CityFrontier.key(limits.rules().territory(), 0);
        check(!limits.claimForBuilding(town, next, null), "finite cap was ignored");
        limits.rules().toggleUnlimited();
        limits.rules().nextEntities();
        limits.rules().nextConstruction();
        limits = CityRegistry.read(limits.writeNbt(new NbtCompound()));
        check(limits.rules().unlimited() && limits.rules().entities() == 0 && limits.rules().construction() == 288
                && limits.claimForBuilding(limits.byId(1), next, null), "world rule values did not survive reload");

        var land = java.util.Set.of(citysim.CityFrontier.key(0, 0), citysim.CityFrontier.key(1, 0), citysim.CityFrontier.key(0, 1));
        var roadTown = new CityRegistry();
        roadTown.rules().toggleTimedExpansion();
        var village = roadTown.found(new BlockPos(8, 64, 8));
        check(!roadTown.claimForBuilding(village, citysim.CityFrontier.key(3, 0), citysim.CityFrontier.key(1, 0)),
                "road extension skipped a chunk");
        check(roadTown.claimForBuilding(village, citysim.CityFrontier.key(2, 0), citysim.CityFrontier.key(1, 0))
                && roadTown.owner(1, 0) == village, "one surveyed approach should connect the lot");
        var compact = citysim.CityFrontier.candidates(land, citysim.CityFrontier.key(0, 0), land::contains, true);
        check(compact.get(0) == citysim.CityFrontier.key(1, 1), "compact corner should beat a spur");
        var road = new BlockPos(9, 63, 8);
        var step = new BlockPos(10, 64, 8);
        var broken = new BlockPos(11, 66, 8);
        var reachable = CityRoads.connected(java.util.List.of(java.util.List.of(road, step, broken)), new BlockPos(8, 64, 8));
        check(reachable.equals(java.util.Set.of(road, step)), "road connectivity ignored a height discontinuity");
        CityPlot crossChunk = new CityPlot(-3, 13, 7, 9);
        check(crossChunk.chunkKeys().size() == 4 && crossChunk.contains(-1, 16) && !crossChunk.contains(4, 16),
                "a rectangular footprint must index every intersecting chunk");
        check(CityPlot.read(crossChunk.write()).equals(crossChunk), "rectangular footprint changed on save/reload");
        int[] heights = CityTerrain.constrain(
                new int[]{63, 63, 63, 63}, new int[]{65, 61, 61, 63},
                new int[]{65, 65, 65, 63}, new int[]{65, 65, 64, 63}, 4);
        check(java.util.Arrays.equals(heights, new int[]{65, 65, 64, 63}), "the slope must ease into unchanged ground");
        check(CityTerrain.constrain(new int[]{65, 65}, new int[]{67, 65}, new int[]{67, 65}, new int[]{67, 65}, 2) == null,
                "a protected neighbor must never move to fit a new foundation");
        timedExpansion();
        nationsAndColonization();
        var recruitment = new CityRegistry();
        check(!recruitment.rules().automaticAdmissions(), "automatic admissions must be opt-in");
        recruitment.rules().toggleAutomaticAdmissions();
        check(CityRegistry.read(recruitment.writeNbt(new NbtCompound())).rules().automaticAdmissions(),
                "automatic admissions must survive reload");
        System.out.println("REGISTRY PASS: migration, timed expansion, compact frontier, persisted rules and road connectivity");
    }

    private static void nationsAndColonization() {
        var fresh = new CityRegistry();
        check(fresh.rules().automaticColonization(), "fresh worlds should support autonomous settlement");
        fresh.found(new BlockPos(8, 64, 8));
        fresh.found(new BlockPos(168, 64, 168));
        var versionThree = fresh.writeNbt(new NbtCompound());
        versionThree.putInt("registryVersion", 3);
        versionThree.remove("nations");
        versionThree.getCompound("cityRules").remove("automaticColonization");
        for (String key : versionThree.getCompound("cities").getKeys()) {
            versionThree.getCompound("cities").getCompound(key).remove("nationId");
        }
        var registry = CityRegistry.read(versionThree);
        var source = registry.byId(1);
        var rival = registry.byId(2);
        check(source.nationId() == 1 && rival.nationId() == 2 && !registry.rules().automaticColonization(),
                "old independent cities must become distinct nations with an explicit migration rule");
        check(registry.nations().rename(registry, source, "Delta"), "name change refused");
        registry.rules().setAutomaticColonization(true);
        var ledger = source.harness().city;
        ledger.cycleCount = 80;
        while (registry.worldCycle() < 80) registry.advanceWorldCycle();
        ledger.population = 32;
        ledger.foodPoints = 2400;
        ledger.timber = 64;
        ledger.stone = 32;
        check(registry.nations().dispatch(registry, source, k -> false, id -> {})
                == CityNations.Dispatch.NO_LAND && ledger.population == 32, "failed search charged the city");
        check(registry.nations().dispatch(registry, source, k -> true, id -> {})
                == CityNations.Dispatch.DEPARTED, "eligible city did not dispatch");
        var party = registry.nations().expedition(1);
        var target = new BlockPos(CityData.xOf(party.target()) * 16 + 8, 64, CityData.zOf(party.target()) * 16 + 8);
        check(registry.found(target) == null && !registry.claim(rival, target.getX() >> 4, target.getZ() >> 4),
                "a reserved destination could be taken by another city");
        check(ledger.population == 26 && ledger.foodPoints == 2040 && ledger.timber == 32 && ledger.stone == 16,
                "mother city's stocks were not transferred into the outbound party");
        var snapshot = registry.writeNbt(new NbtCompound());
        snapshot.getCompound("nations").getList("expeditions", 10).getCompound(0).putLong("hall", target.asLong());
        var restored = CityRegistry.read(snapshot);
        var restoredParty = restored.nations().expedition(1);
        var origin = restored.byId(1).harness().city;
        check(restoredParty != null && restoredParty.hall().equals(target) && restoredParty.cargo().equals(party.cargo())
                        && restored.rules().automaticColonization() && restored.nations().name(1).equals("Delta"),
                "lost caravan, nation name or world rule after restore");
        check(restored.nations().complete(restored, restoredParty, target) == null, "colony can be committed before arrival");
        origin.cycleCount = restoredParty.arrival();
        while (restored.worldCycle() < restoredParty.arrival()) restored.advanceWorldCycle();
        var child = restored.nations().complete(restored, restoredParty, target);
        check(child != null && restored.nations().complete(restored, restoredParty, target) == null,
                "colony did not settle once");
        check(child.nationId() == 1 && child.foundedFrom() == 1 && restored.owner(target) == child
                        && child.harness().city.population == 6 && child.harness().city.foodPoints == 360
                        && child.harness().city.timber == 32 && child.harness().city.stone == 16 && origin.population == 26,
                "colony affiliation, charter land or booked assets were lost");
        var finished = CityRegistry.read(restored.writeNbt(new NbtCompound()));
        check(finished.nations().expedition(1) == null && finished.byId(child.id).foundedFrom() == 1
                        && finished.nations().summaries(finished).get(0).cities() == 2,
                "settled party or national roster changed on reload");

        var returning = CityRegistry.read(registry.writeNbt(new NbtCompound()));
        check(returning.nations().cancel(returning, 1) && !returning.nations().cancel(returning, 1)
                        && returning.byId(1).harness().city.population == 32
                        && returning.byId(1).harness().city.foodPoints == 2400
                        && returning.byId(1).harness().city.timber == 64 && returning.byId(1).harness().city.stone == 32
                        && returning.found(target) != null, "cancellation failed to refund once and free the site");
        System.out.println("NATIONS PASS: legacy migration, in-transit restore, exclusive reservation, single settlement and refunds");
        campaignPersistence();
    }

    private static void campaignPersistence() {
        var fresh = new CityRegistry();
        fresh.found(new BlockPos(8, 64, 8)).harness().city.cycleCount = 49;
        fresh.found(new BlockPos(168, 64, 8));
        var old = fresh.writeNbt(new NbtCompound());
        old.putInt("registryVersion", 4);
        old.remove("campaign");
        old.remove("worldCycle");
        var registry = CityRegistry.read(old);
        check(registry.worldCycle() == 49 && registry.warView().relations().isEmpty(),
                "v4 must inherit the clock and start in peace");
        check(registry.warAction(true, 1, 2) == citysim.WarSimulation.Action.STARTED, "war declaration failed");
        registry.advanceWorldCycle();
        var saved = registry.writeNbt(new NbtCompound());
        var restored = CityRegistry.read(saved);
        check(restored.worldCycle() == 50 && restored.warView().equals(registry.warView()),
                "world clock or campaign changed on NBT restore");
        check(restored.warAction(false, 1, 2) == citysim.WarSimulation.Action.ENDED
                        && CityRegistry.read(restored.writeNbt(new NbtCompound())).warView().relations().get(0).until() == 130,
                "truce expiry was not saved with the world");
        System.out.println("CAMPAIGN PASS: v4 peace migration, independent world cycle, war and truce restore");
        conquestPersistence();
    }

    private static void conquestPersistence() {
        var registry = new CityRegistry();
        registry.found(new BlockPos(8, 64, 8));
        registry.found(new BlockPos(168, 64, 8));
        registry.found(new BlockPos(328, 64, 8));
        var source = registry.writeNbt(new NbtCompound());
        source.putInt("registryVersion", 5);
        source.getCompound("cities").getCompound("3").putInt("nationId", 2);
        var old = new citysim.WarSimulation.State(
                java.util.List.of(new citysim.WarSimulation.Relation(1, 2, 0, 0)), java.util.List.of(),
                java.util.List.of(new citysim.WarSimulation.Control(2, 1, 4)), java.util.List.of(), java.util.List.of());
        source.putByteArray("campaign", new com.google.gson.Gson().toJson(old).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var loaded = CityRegistry.read(source);
        check(loaded.nations().capital(2) == 2, "legacy capital was not loaded");
        var king = loaded.warView().officers().stream().filter(p -> p.nation() == 2 && p.role() == citysim.OfficerBook.Role.KING)
                .findFirst().orElseThrow();
        var before = loaded.byId(2).townHallPos();
        loaded.advanceWorldCycle();
        loaded.stepCampaign(null);
        check(loaded.byId(2).nationId() == 1 && loaded.nations().capital(2) == 3 && loaded.owner(before).id == 2,
                "conquest did not transfer the whole city and select a new capital");
        var saved = CityRegistry.read(loaded.writeNbt(new NbtCompound()));
        check(saved.byId(2).nationId() == 1 && saved.nations().capital(2) == 3 && saved.warView().officers().stream()
                        .anyMatch(p -> p.id() == king.id() && p.city() == 3 && p.nation() == 2),
                "city ownership, successor capital or king identity was lost upon reloading");
        System.out.println("CONQUEST PASS: v5 occupation transfer, new capital, intact land and officer identity");
    }

    private static void timedExpansion() {
        var registry = new CityRegistry();
        var town = registry.found(new BlockPos(8, 64, 8));
        var rival = registry.found(new BlockPos(24, 64, 8));
        var ledger = town.harness().city;
        ledger.population = 12;
        ledger.cycleCount = 19;
        check(registry.rules().timedExpansion(), "scheduled growth must be on in a new world");
        check(registry.tryTimedExpansion(town, key -> true).isEmpty(), "claim before the scheduled cycle");
        check(registry.claimForBuilding(town, CityFrontier.key(0, 0), null)
                        && !registry.claimForBuilding(town, CityFrontier.key(0, -1), null),
                "timed growth must allow construction in town without extra claims");

        long water = CityFrontier.key(-1, 0);
        ledger.cycleCount = 20;
        check(registry.tryTimedExpansion(town, key -> key != water).isPresent() && town.land.chunkCount() == 2
                        && registry.owner(-1, 0) == null && registry.owner(1, 0) == rival,
                "scheduled growth must add one dry neighbor without taking a rival's chunk");
        var stored = registry.writeNbt(new NbtCompound());
        stored.getCompound("cityRules").remove("timedExpansion");
        var migrated = CityRegistry.read(stored);
        check(migrated.rules().timedExpansion(), "pre-rule saves must adopt the default");
        var resumed = migrated.byId(1);
        resumed.harness().city.cycleCount = 40;
        check(migrated.tryTimedExpansion(resumed, key -> true).isPresent() && resumed.land.chunkCount() == 3,
                "scheduled growth lost its frontier on reload");
        migrated.rules().toggleTimedExpansion();
        var disabled = CityRegistry.read(migrated.writeNbt(new NbtCompound()));
        check(!disabled.rules().timedExpansion()
                        && disabled.tryTimedExpansion(disabled.byId(1), key -> true).isEmpty(),
                "explicitly disabled growth must stay off across reloads");
    }
}
