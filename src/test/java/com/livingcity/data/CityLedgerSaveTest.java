package com.livingcity.data;

import citysim.BuildingEntry;
import citysim.BuildingKind;
import citysim.CityConfig;
import citysim.SimHarness;
import com.livingcity.sensor.CityClock;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class CityLedgerSaveTest {
    public static void main(String[] args) throws IOException {
        SimHarness live = new SimHarness(42, new CityConfig());
        live.city.population = 8;
        live.city.foodPoints = 2000;
        BuildingEntry home = live.city.placeExisting(BuildingKind.HOUSE, 8);
        live.run(1);
        require(live.city.activeProject != null && live.city.activeProject.kind == BuildingKind.WELL,
                "Expected a well under construction before losing a bed");

        NbtCompound legacy = save(live);
        legacy.getCompound("ledger").putInt("schemaVersion", 1);
        SimHarness migrated = load(legacy);
        require(save(live).equals(save(migrated)), "Schema 1 migration changed the ledger");

        home.bedCount = 7;
        live.run(1);
        require(live.city.deferredProject != null && live.city.activeProject.kind == BuildingKind.HOUSE,
                "Expected housing to interrupt the well");
        compareContinuation(live);
        home.bedCount = 8;
        live.run(1);
        require(live.city.deferredProject != null && live.city.deferredProject.kind == BuildingKind.HOUSE,
                "Expected the partially built house to be saved after bed repairs");
        compareContinuation(live);
        System.out.println("NBT PASS: schema 1 migration and both interrupted project states");
        testClock();
        CityRegistryTest.run();
        com.livingcity.sensor.CityWorkRotationTest.run();
        testBlueprintFloor();
        com.livingcity.web.TerrainAtlasTest.run();
        var tradeLedger = new citysim.CityLedger(9, new citysim.CityConfig());
        tradeLedger.portLocated = true;
        tradeLedger.portWork = 7;
        tradeLedger.incomingStone = 8;
        var tradeTag = new NbtCompound();
        CityLedgerCodec.write(tradeLedger, tradeTag);
        var restoredTradeLedger = new citysim.CityLedger(9, new citysim.CityConfig());
        CityLedgerCodec.read(restoredTradeLedger, tradeTag);
        require(restoredTradeLedger.portLocated && restoredTradeLedger.portWork == 7 && restoredTradeLedger.incomingStone == 8,
                "Port work or reserved shipment was lost on reload");
    }

    private static void testBlueprintFloor() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
        var floor = new net.minecraft.util.math.BlockPos(-18, 65, 40);
        for (var turn : net.minecraft.util.BlockRotation.values()) {
            var design = com.livingcity.stage.CityBlueprint.design("test_raised_floor", turn);
            var origin = design.originAtFloor(floor);
            require(design.floorY() == 3 && origin.getY() == 62, "Fourth layer did not align to the graded surface");
            require(origin.add(design.entrance()).getY() == 65, "Rotation or floor offset changed road connection height");
            require(origin.up(design.top()).getY() == 66, "Placement did not keep the relative top height");
            require(design.cells().stream().anyMatch(part -> origin.add(part.offset()).getY() == 62),
                    "Foundation block did not retain its position below the floor");
        }
        for (String name : com.livingcity.stage.CityBlueprint.TYPES.values()) {
            if (name.equals("port")) continue;
            var legacy = com.livingcity.stage.CityBlueprint.design(name, net.minecraft.util.BlockRotation.NONE);
            require(legacy.floorY() == 0 && legacy.originAtFloor(floor).equals(floor), "Legacy blueprint floor moved: " + name);
        }
        var port = com.livingcity.stage.CityBlueprint.design("port", net.minecraft.util.BlockRotation.NONE);
        require(port.floorY() == 3 && port.width() == 20 && port.depth() == 16 && port.cells().size() == 516,
                "Real port asset or its fourth-layer floor was lost");
        System.out.println("BLUEPRINT PASS: raised floor alignment, foundations, rotation and legacy floor defaults");
    }

    private static void compareContinuation(SimHarness live) throws IOException {
        NbtCompound snapshot = save(live);
        SimHarness restored = load(snapshot);
        require(snapshot.equals(save(restored)), "NBT changed on round trip");
        // Keep the caller's live city intact while comparing a run with one interrupted at cycle 17.
        SimHarness baseline = load(snapshot);
        baseline.run(250);
        restored.run(17);
        restored = load(save(restored));
        restored.run(233);
        require(save(baseline).equals(save(restored)), "Reload changed the ledger after 250 cycles");
    }

    private static NbtCompound save(SimHarness sim) {
        NbtCompound nbt = new NbtCompound();
        CityLedgerCodec.write(sim.city, nbt);
        return nbt;
    }

    private static SimHarness load(NbtCompound tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bytes);
        SimHarness result = new SimHarness(0, new CityConfig());
        CityLedgerCodec.read(result.city, NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray())));
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void testClock() {
        NbtCompound baseline = null;
        for (int speed : new int[]{1, 5, 10, 1000}) {
            CityData data = new CityData();
            data.harness().city.population = 8;
            data.harness().city.foodPoints = 2000;
            data.harness().city.placeExisting(BuildingKind.HOUSE, 7);
            data.clock().setSpeed(speed);
            int ticks = 120 * CityClock.TICKS_PER_CYCLE / speed;
            for (int i = 0; i < ticks; i++) {
                if (data.clock().tick()) data.harness().run(1);
                if (i == 12) data = CityData.fromNbt(data.writeNbt(new NbtCompound()));
            }
            require(data.clock().speed() == speed && data.clock().remainder() == 0
                    && data.harness().city.cycleCount == 120, "Wrong number of cycles at " + speed);
            NbtCompound current = save(data.harness());
            if (baseline == null) baseline = current;
            require(baseline.equals(current), "Speed changed the final ledger at " + speed);
        }
        CityClock clock = CityData.fromNbt(new NbtCompound()).clock();
        require(clock.speed() == 1 && clock.remainder() == 0, "Legacy clock must start at normal speed");
        clock.setSpeed(1000);
        clock.tick();
        clock.recordCycleCost(21_000_000L);
        for (int i = 0; i < 10; i++) require(!clock.tick(), "Over-budget cycle caught up in a burst");
        require(clock.remainder() == 1010, "Cost guard added a backlog");
        clock.setSpeed(1);
        for (int i = 1; i < 190; i++) require(!clock.tick(), "Incomplete cycle after slowing down");
        require(clock.tick() && clock.remainder() == 0, "Slowing down lost partial progress");
        require(!clock.setSpeed(3) && clock.speed() == 1, "Accepted an unsupported speed");
        CityData paused = new CityData();
        paused.clock().tick();
        paused.clock().setSpeed(0);
        paused = CityData.fromNbt(paused.writeNbt(new NbtCompound()));
        require(paused.clock().speed() == 0 && !paused.clock().tick(), "Pause must survive a reload");
        require(paused.clock().requestStep() && !paused.clock().requestStep(), "Queued more than one step");
        require(paused.clock().tick() && !paused.clock().tick() && paused.clock().remainder() == 1,
                "Single-step changed the fractional clock or advanced twice");
        System.out.println("CLOCK PASS: four speeds, budget, persisted pause and single-step");
    }
}
