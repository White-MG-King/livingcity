package com.livingcity.data;

import citysim.CityFrontier;
import citysim.WarSimulation;
import com.livingcity.sensor.CityAdmissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.function.LongPredicate;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.biome.Biome;

/** All overworld cities and their exclusive chunk ownership are serialized together. */
public final class CityRegistry extends PersistentState {
    public static final int SAVE_VERSION = 6;
    private final TreeMap<Integer, CityData> cities = new TreeMap<>();
    private final Map<Long, CityData> owners = new HashMap<>();
    private final Map<Long, RegistryEntry<Biome>> perimeterBiomes = new HashMap<>();
    private final CityRules rules = new CityRules();
    private final CityAdmissions admissions = new CityAdmissions();
    private final CityNations nations = new CityNations();
    private final CityCampaignWorld battlefield = new CityCampaignWorld(this);
    private WarSimulation war = new WarSimulation();
    private long worldCycle;

    public CityRules rules() { return rules; }
    public CityAdmissions admissions() { return admissions; }
    public CityNations nations() { return nations; }
    public long worldCycle() { return worldCycle; }
    public WarSimulation.View warView() { return war.view(); }

    public void advanceWorldCycle() {
        worldCycle++;
        markDirty();
    }

    public void stepCampaign(ServerWorld world) {
        battlefield.begin(world);
        war.step(battlefield, worldCycle);
    }

    public WarSimulation.Action warAction(boolean declare, int a, int b) {
        var outcome = declare ? war.declare(battlefield, a, b, worldCycle) : war.truce(battlefield, a, b, worldCycle);
        if (WarSave.accepted(outcome)) markDirty();
        return outcome;
    }

    public void updateCampaignTerrain(int x, int z) { battlefield.invalidate(x, z); }

    public CityRegistry() { attach(1, new CityData()); }

    public static CityRegistry get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager()
                .getOrCreate(CityRegistry::read, CityRegistry::new, CityData.ID);
    }

    private CityData attach(int id, CityData data) {
        data.registry = this;
        data.id = id;
        cities.put(id, data);
        for (long chunk : data.land.chunks) {
            if (owners.putIfAbsent(chunk, data) != null)
                throw new IllegalStateException("Conflicting ownership for chunk " + chunk);
            indexPerimeter(data.land, chunk);
        }
        return data;
    }

    public Collection<CityData> cities() { return Collections.unmodifiableCollection(cities.values()); }
    public CityData byId(int id) { return cities.get(id); }
    CityData nextCity(int after) {
        var next = cities.higherEntry(after);
        return next == null ? cities.firstEntry().getValue() : next.getValue();
    }
    public CityData owner(int x, int z) { return owners.get(CityData.xzKey(x, z)); }
    public CityData owner(BlockPos p) { return owner(p.getX() >> 4, p.getZ() >> 4); }

    public CityData at(BlockPos p) {
        CityData owned = owner(p);
        if (owned != null) return owned;
        CityData nearest = byId(1);
        double best = Double.POSITIVE_INFINITY;
        for (CityData city : cities.values()) {
            if (!city.isFounded()) continue;
            double distance = Math.hypot((double)city.townHallPos().getX()-p.getX(), (double)city.townHallPos().getZ()-p.getZ());
            if (distance < best) { best = distance; nearest = city; }
        }
        return nearest;
    }

    public boolean claim(CityData city, int x, int z) {
        long key = CityData.xzKey(x,z);
        if (city.land.chunks.contains(key)) return true;
        if (nations.reserves(key)) return false;
        if (owner(x,z)!=null && owner(x,z)!=city) return false;
        if (city.land.chunks.isEmpty()) {
            addCell(city,key);
            return true;
        }
        // Search outward from the requested plot. Publish the entire route only on success.
        ArrayDeque<Long> queue=new ArrayDeque<>();
        Map<Long,Long> previous=new HashMap<>();
        queue.add(key);
        previous.put(key,key);
        while (!queue.isEmpty()) {
            long here=queue.remove();
            if (city.land.chunks.contains(here)) {
                while (here!=key) {
                    here=previous.get(here);
                    addCell(city,here);
                }
                return true;
            }
            for (long next:neighbors(here)) {
                int nx=CityData.xOf(next), nz=CityData.zOf(next);
                if (Math.abs(nx-x)+Math.abs(nz-z)>16 || previous.containsKey(next)) continue;
                CityData owner=owners.get(next);
                if ((owner!=null && owner!=city) || nations.reserves(next)) continue;
                previous.put(next,here);
                queue.add(next);
            }
        }
        return false;
    }


    public void tickExpansion(ServerWorld world, CityData city) {
        var claimed = tryTimedExpansion(city, key -> {
            int x = CityData.xOf(key), z = CityData.zOf(key);
            if (!world.getWorldBorder().contains(new ChunkPos(x, z))) return false;
            // Sample the generator's biome at the chunk center, without acquiring a chunk ticket.
            var biome = perimeterBiomes.computeIfAbsent(key, ignored ->
                    world.getGeneratorStoredBiome(x * 4 + 2, Math.floorDiv(world.getSeaLevel(), 4), z * 4 + 2));
            return !biome.isIn(BiomeTags.IS_OCEAN) && !biome.isIn(BiomeTags.IS_RIVER);
        });
        claimed.ifPresent(key -> {
            int x = CityData.xOf(key), z = CityData.zOf(key);
            // Unloaded claims enter the physical survey queue when their chunk loads.
            if (world.getChunkManager().isChunkLoaded(x, z)) city.land.requestSurvey(x, z);
        });
    }

    OptionalLong tryTimedExpansion(CityData city, LongPredicate landEligible) {
        var ledger = city.harness().city;
        if (!rules.timedExpansion() || !city.isFounded() || ledger.population <= 0
                || !CityRules.isExpansionCycle(ledger.cycleCount) || !rules.mayExpand(city.land.chunkCount())) return OptionalLong.empty();
        BlockPos hall = city.townHallPos();
        long center = CityData.xzKey(hall.getX() >> 4, hall.getZ() >> 4);
        var next = CityFrontier.bestClaim(city.land.perimeter, city.land.chunks, center,
                key -> !owners.containsKey(key) && !nations.reserves(key) && landEligible.test(key));
        next.ifPresent(key -> addCell(city, key, false));
        return next;
    }

    boolean claimForBuilding(CityData city, long key, Long approach) {
        CityData current = owners.get(key);
        if (current == city) return true;
        if (current != null || nations.reserves(key) || rules.timedExpansion()
                || !rules.mayExpand(city.land.chunks.size())) return false;
        if (approach == null) {
            if (CityFrontier.adjoining(city.land.chunks, key) == 0) return false;
        } else {
            if (owners.containsKey(approach) || nations.reserves(approach) || !rules.mayExpand(city.land.chunks.size() + 1)
                    || CityFrontier.adjoining(city.land.chunks, approach) == 0
                    || Math.abs(CityFrontier.x(approach) - CityFrontier.x(key))
                       + Math.abs(CityFrontier.z(approach) - CityFrontier.z(key)) != 1) return false;
            addCell(city, approach);
        }
        addCell(city, key);
        return true;
    }

    private static long[] neighbors(long k) {
        int x=CityData.xOf(k),z=CityData.zOf(k);
        return new long[]{CityData.xzKey(x+1,z),CityData.xzKey(x-1,z),CityData.xzKey(x,z+1),CityData.xzKey(x,z-1)};
    }

    private static void indexPerimeter(CityLand land, long key) {
        land.perimeter.remove(key);
        for (long adjacent : CityFrontier.neighbors(key)) {
            if (!land.chunks.contains(adjacent)) land.perimeter.add(adjacent);
        }
    }

    private void addCell(CityData city,long k) {
        addCell(city, k, true);
    }

    private void addCell(CityData city, long k, boolean survey) {
        owners.put(k,city);
        if(city.land.chunks.add(k)) {
            indexPerimeter(city.land, k);
            perimeterBiomes.remove(k);
            if (survey) city.land.requestSurvey(CityData.xOf(k), CityData.zOf(k));
            markDirty();
        }
    }

    private void repairLegacyBorders() {
        for(CityData city:cities.values()) {
            if(!city.isFounded()) continue;
            Set<Long> remaining=new LinkedHashSet<>(city.land.chunks);
            city.land.chunks.clear();
            city.land.perimeter.clear();
            long hall=CityData.xzKey(city.townHallPos().getX()>>4,city.townHallPos().getZ()>>4);
            addCell(city,hall);
            // Attach existing contiguous holdings before seeking a short bridge to an island.
            ArrayDeque<Long> frontier=new ArrayDeque<>();
            frontier.add(hall);
            remaining.remove(hall);
            while(!frontier.isEmpty()) {
                long k=frontier.remove();
                for(long n:neighbors(k)) if(remaining.remove(n)) {addCell(city,n);frontier.add(n);}
            }
            for(long k:remaining) if(!claim(city,CityData.xOf(k),CityData.zOf(k))) owners.remove(k,city);
        }
    }

    /** The hall and its entrance occupy a 3x3 footprint, including across chunk edges. */
    public boolean canPlaceHall(CityData city, BlockPos pos) {
        for (int x=-1;x<=1;x++) for (int z=-1;z<=1;z++) {
            BlockPos cell = pos.add(x,0,z);
            if (nations.reserves(CityData.xzKey(cell.getX() >> 4, cell.getZ() >> 4))) return false;
            CityData owner = owner(cell);
            if (owner != null && owner != city) return false;
            if (city != null && city.land.occupied(cell, city)) return false;
        }
        return true;
    }

    public boolean moveHall(CityData city, BlockPos pos) {
        if (!claim(city,pos.getX()>>4,pos.getZ()>>4)) return false;
        for (int x=-1;x<=1;x++) for (int z=-1;z<=1;z++)
            claim(city,(pos.getX()+x)>>4,(pos.getZ()+z)>>4);
        city.foundCity(pos);
        if (city.nationId > 0) war.reconcile(battlefield, worldCycle);
        return true;
    }

    public CityData found(BlockPos pos) {
        CityData city = byId(1);
        CityData existing = owner(pos);
        if ((existing != null && existing.isFounded()) || !canPlaceHall(city.isFounded() ? null : city,pos)) return null;
        if (city.isFounded()) city = attach(cities.lastKey()+1,new CityData());
        if (!moveHall(city,pos)) return null;
        nations.registerIndependent(city);
        war.reconcile(battlefield, worldCycle);
        return city;
    }

    CityData foundColony(BlockPos pos, int nation, int parent) {
        int x = pos.getX() & 15, z = pos.getZ() & 15;
        long key = CityData.xzKey(pos.getX() >> 4, pos.getZ() >> 4);
        if (x == 0 || x == 15 || z == 0 || z == 15 || owners.containsKey(key) || !nations.reserves(key)) return null;
        CityData city = attach(cities.lastKey() + 1, new CityData());
        city.nationId = nation;
        city.foundedFrom = parent;
        city.foundCity(pos);
        addCell(city, key);
        war.reconcile(battlefield, worldCycle);
        return city;
    }

    @Override public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putInt("registryVersion", SAVE_VERSION);
        nbt.putLong("worldCycle", worldCycle);
        nbt.put("cityRules", rules.write());
        nbt.put("nations", nations.write());
        WarSave.write(nbt, war);
        NbtCompound records = new NbtCompound();
        cities.forEach((id,city)->records.put(id.toString(),city.writeNbt(new NbtCompound())));
        nbt.put("cities",records);
        return nbt;
    }

    public static CityRegistry read(NbtCompound nbt) {
        CityRegistry registry = new CityRegistry();
        registry.rules.read(nbt.getCompound("cityRules"));
        if (!nbt.contains("registryVersion")) {
            CityData old = registry.attach(1,CityData.fromNbt(nbt));
            old.land.noteLegacyHousing(old);
            if (old.isFounded()) registry.moveHall(old,old.townHallPos());
            for (BlockPos anchor : old.anchors()) registry.claim(old,anchor.getX()>>4,anchor.getZ()>>4);
            registry.markDirty();
        } else {
            if (nbt.getInt("registryVersion") > SAVE_VERSION) throw new IllegalArgumentException("Unknown city registry version");
            NbtCompound records = nbt.getCompound("cities");
            for (String key : records.getKeys()) registry.attach(Integer.parseInt(key),CityData.fromNbt(records.getCompound(key)));
        }
        if (nbt.getInt("registryVersion") == 1) {
            registry.repairLegacyBorders();
            registry.markDirty();
        }
        registry.worldCycle = nbt.getInt("registryVersion") >= 5
                ? nbt.getLong("worldCycle") : registry.byId(1).harness().city.cycleCount;
        if (registry.worldCycle < 0 || registry.worldCycle >= Long.MAX_VALUE / 2) {
            throw new IllegalArgumentException("Invalid world cycle");
        }
        registry.nations.read(registry, nbt.getCompound("nations"));
        registry.war = WarSave.read(nbt, registry.battlefield, registry.worldCycle);
        return registry;
    }
}
