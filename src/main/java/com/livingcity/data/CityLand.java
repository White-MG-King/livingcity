package com.livingcity.data;

import java.util.*;
import citysim.BuildingEntry;
import citysim.BuildingKind;
import citysim.CityLedger;
import citysim.CityFrontier;
import com.livingcity.stage.CityBlueprint;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/** Physical road samples and planned sites. Unknown building footprints stay anchors. */
public final class CityLand {
    final Set<Long> chunks = new LinkedHashSet<>();
    final Set<Long> perimeter = new HashSet<>();
    private final Map<Long,List<BlockPos>> roads = new TreeMap<>();
    private final Map<Integer,BlockPos> entrances = new TreeMap<>();
    private Set<BlockPos> connectedRoads;
    private BlockPos connectedHall;
    private List<BlockPos> accessRoad = List.of();
    private int roadCursor;
    private BlockRotation rotation = BlockRotation.NONE;
    private final Map<Integer,CityPlot> parcels = new TreeMap<>();
    private final Map<Long,List<Integer>> parcelIndex = new HashMap<>();
    private final Map<Integer,CityDemolition> demolitions = new LinkedHashMap<>();
    private CityTerrain.Plan terrainPlan;
    private boolean snapshotReady;
    private final Map<Long,Integer> bedGroups = new HashMap<>();
    public boolean hallMissing;
    private int forestIndex;
    private long forestChunk;
    private BlockPos quarry;
    private int buildId=-1, cursor, foundationColumn;
    private BlockPos origin;
    private String buildBlueprint="house_4bed_combo";
    private String reason = "等待建设需求";

    public int chunkCount() { return chunks.size(); }
    List<BlockPos> mapRoads() { return roads.values().stream().flatMap(List::stream).toList(); }
    Map<Integer, CityPlot> mapPlots() { return Collections.unmodifiableMap(parcels); }
    BlockPos mapQuarry() { return quarry; }
    long mapForest() { return forestChunk; }
    boolean forestScanComplete() { return forestIndex >= FOREST_OFFSETS.size(); }
    public String siteStatus() { return reason; }
    public int buildCursor() {
        return terrainPlan != null && terrainPlan.structure() != null
                ? Math.max(0, cursor - terrainPlan.terrain().edits().size()) : cursor;
    }
    public int roadCursor() { return roadCursor; }
    public int roadLength() { return accessRoad.size(); }
    private static final int MAX_SITE_GRADE = 6;
    private static final List<int[]> FOREST_OFFSETS=offsets(16);
    private static List<int[]> offsets(int radius) {
        List<int[]> result=new ArrayList<>();
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++)
            if(x*x+z*z<=radius*radius) result.add(new int[]{x,z});
        result.sort(Comparator.comparingInt(d->d[0]*d[0]+d[1]*d[1]));
        return result;
    }

    private void produceSites(ServerWorld world, CityData city) {
        var ledger=city.harness().city;
        var hall=city.townHallPos();
        for(int n=0;n<16 && !ledger.forestAvailable && forestIndex<FOREST_OFFSETS.size();n++) {
            int[] off=FOREST_OFFSETS.get(forestIndex++);
            int cx=(hall.getX()>>4)+off[0],cz=(hall.getZ()>>4)+off[1];
            var biome=world.getGeneratorStoredBiome(cx*4+2,Math.floorDiv(hall.getY(),4),cz*4+2);
            if(biome.isIn(net.minecraft.registry.tag.BiomeTags.IS_FOREST) ||
                    biome.isIn(net.minecraft.registry.tag.BiomeTags.IS_TAIGA) ||
                    biome.isIn(net.minecraft.registry.tag.BiomeTags.IS_JUNGLE)) {
                ledger.forestAvailable=true; forestChunk=CityData.xzKey(cx,cz);
            }
        }
        if(quarry==null && ledger.timber >= CityLedger.QUARRY_TIMBER) {
            int x=(hall.getX()>>4)*16,z=(hall.getZ()>>4)*16;
            for(int i=0;i<256;i++) {
                BlockPos p=new BlockPos(x+i%16,hall.getY(),z+i/16);
                if(hallReserved(p,city) || occupied(p,city)) continue;
                if (!CityBlueprint.cells("quarry").isEmpty()) throw new IllegalStateException("Expected an empty quarry blueprint");
                quarry=p; ledger.quarryLocated=true; break;
            }
        }
    }

    private void build(ServerWorld world, CityData city, int budget) {
        if (!world.isChunkLoaded(origin)) {
            reason = "施工暂停：等待区块加载";
            return;
        }
        if (terrainPlan != null) {
            for (long key : terrainPlan.chunks()) {
                if (!world.getChunkManager().isChunkLoaded(CityData.xOf(key), CityData.zOf(key))) {
                    reason = "施工暂停：等待地基和过渡带区块加载";
                    return;
                }
            }
        }
        if (city.registry.owner(origin) != city) {
            reason = "施工暂停：等待领地连通";
            return;
        }
        if (terrainPlan != null && cursor == 0 && roadCursor == 0
                && !canApply(world, city, terrainPlan.terrain().edits(), 0, terrainPlan.terrain().edits().size(), true)) return;
        if (!accessRoad.isEmpty()) {
            BlockPos trunk = accessRoad.get(0);
            if (!CityRoads.hallEntrance(trunk, city.townHallPos()) && !reachableRoads(city).contains(trunk)) {
                reason = "施工暂停 · 干路已断开";
                return;
            }
            for (int i = 0; i < accessRoad.size(); i++) {
                BlockPos floor = accessRoad.get(i);
                if (CityRoads.fillDepth(world, city, floor) < 0) {
                    reason = "施工暂停 · 道路未加载或被阻挡";
                    return;
                }
                // Repair an interrupted stretch when its ground is still safe to pave.
                if (i < roadCursor && !world.getBlockState(floor).isOf(Blocks.DIRT_PATH)) roadCursor = i;
            }
            reason = "铺设入口道路";
            while (roadCursor < accessRoad.size() && budget > 0) {
                BlockPos floor = accessRoad.get(roadCursor);
                if (!world.getBlockState(floor).isOf(Blocks.DIRT_PATH)) {
                    int depth = CityRoads.fillDepth(world, city, floor);
                    if (depth < 0 || budget < depth + 1) return;
                    for (int y = depth - 1; y > 0; y--) {
                        if (!world.setBlockState(floor.down(y), Blocks.DIRT.getDefaultState(), net.minecraft.block.Block.NOTIFY_LISTENERS)) return;
                        budget--;
                    }
                    if (!world.setBlockState(floor, Blocks.DIRT_PATH.getDefaultState(), net.minecraft.block.Block.NOTIFY_ALL)) return;
                    budget--;
                }
                rememberRoad(floor);
                roadCursor++;
            }
            if (roadCursor < accessRoad.size() || budget == 0) return;
        }
        var blueprint = CityBlueprint.design(buildBlueprint, rotation).cells();
        reason = "施工 · " + buildBlueprint;
        if (terrainPlan != null) {
            if (!buildFromPlan(world, city, budget)) return;
        } else {
            // Fill only the gap above the existing ground surface.
            var chunk = world.getChunkManager().getWorldChunk(origin.getX() >> 4, origin.getZ() >> 4);
            if (chunk == null) return;
            while (foundationColumn < 256 && budget >= MAX_SITE_GRADE) {
                int x = foundationColumn % 16, z = foundationColumn / 16;
                int groundY = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                int start = Math.max(-MAX_SITE_GRADE, groundY + 1 - origin.getY());
                for (int y = start; y < 0; y++) {
                    BlockPos p=origin.add(x,y,z);
                    if(CityBlueprint.replaceable(world.getBlockState(p))) {
                        if(!world.setBlockState(p,Blocks.DIRT.getDefaultState(),net.minecraft.block.Block.NOTIFY_LISTENERS)) return;
                        budget--;
                    }
                }
                foundationColumn++;
            }
            for(;foundationColumn == 256 && budget>0 && cursor<blueprint.size();budget--) {
                var part=blueprint.get(cursor);
                var target=origin.add(part.offset());
                var existing=world.getBlockState(target);
                if(!existing.equals(part.state())) {
                    if(!CityBlueprint.replaceable(existing)) {reason="施工暂停：现场有改建"; return;}
                    if(!world.setBlockState(target,part.state(),net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE)) return;
                }
                cursor++;
            }
            if (cursor < blueprint.size()) {
                city.markDirty();
                return;
            }
        }
        {
            if (snapshotReady) CityBuildingHistory.get(world).save(city.id, buildId, terrainPlan.structure());
            var foot=blueprint.stream().filter(c->c.state().getBlock() instanceof net.minecraft.block.BedBlock &&
                        c.state().get(net.minecraft.block.BedBlock.PART)==net.minecraft.block.enums.BedPart.FOOT).findFirst();
            city.setBuildingAnchor(buildId,foot.isPresent()?origin.add(foot.get().offset()):origin);
            var ledger = city.harness().city;
            for (var entry : ledger.buildings) {
                if (entry.id != buildId) continue;
                ledger.finishConstruction(entry);
                break;
            }
            CityPlot parcel = parcels.get(buildId);
            if (foot.isPresent() && parcel != null) pendingSurveys.addAll(parcel.chunkKeys());
            reason = "建筑已落成";
            origin = null;
            buildId = -1;
            cursor = roadCursor = 0;
            accessRoad = List.of();
            terrainPlan = null;
            snapshotReady = false;
            rotation = BlockRotation.NONE;
        }
        city.markDirty();
    }

    private boolean buildFromPlan(ServerWorld world, CityData city, int budget) {
        var terrain = terrainPlan.terrain().edits();
        var structure = terrainPlan.structure();
        if (cursor < terrain.size()) {
            if (structure != null) reason = "整理地基与过渡带";
            if (!applySlice(world, city, terrain, 0, budget)) return false;
            // The following slice opens the structure stage after all grading writes are finished.
            return structure == null;
        }
        if (structure == null) return true;
        if (!snapshotReady) {
            // Reserve the verified post-grading states before writing any blueprint blocks.
            if (!canApply(world, city, structure.edits(), 0, structure.edits().size(), false)) return false;
            snapshotReady = true;
            city.markDirty();
        }
        return applySlice(world, city, structure.edits(), terrain.size(), budget);
    }

    private boolean applySlice(ServerWorld world, CityData city, List<CityBlockChanges.Change> edits, int offset, int budget) {
        int end = Math.min(edits.size(), cursor - offset + budget);
        if (!canApply(world, city, edits, cursor - offset, end, true)) return false;
        for (; cursor - offset < end; cursor++) {
            var edit = edits.get(cursor - offset);
            if (world.getBlockState(edit.pos()).equals(edit.after())) continue;
            if (!world.setBlockState(edit.pos(), edit.after(),
                    net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE)) return false;
        }
        city.markDirty();
        return cursor - offset == edits.size();
    }

    private boolean canApply(ServerWorld world, CityData city, List<CityBlockChanges.Change> edits,
                             int start, int end, boolean allowAlreadyApplied) {
        for (int i = start; i < end; i++) {
            var edit = edits.get(i);
            var current = world.getBlockState(edit.pos());
            boolean expected = current.equals(edit.before()) || (allowAlreadyApplied && current.equals(edit.after()));
            if (city.registry.owner(edit.pos()) != city || !expected) {
                reason = "施工暂停：地形或方块与勘测时不同";
                return false;
            }
        }
        return true;
    }

    public boolean queueDemolition(ServerWorld world, CityData city, int buildingId) {
        if (demolitions.containsKey(buildingId)) return true;
        if (city.buildingAnchor(buildingId) == null || !parcels.containsKey(buildingId)) return false;
        var snapshot = CityBuildingHistory.get(world).snapshot(city.id, buildingId);
        if (snapshot == null) return false;
        demolitions.put(buildingId, new CityDemolition(snapshot));
        city.markDirty();
        return true;
    }

    public boolean tickDemolition(ServerWorld world, CityData city, int budget) {
        var jobs = demolitions.entrySet().iterator();
        while (jobs.hasNext()) {
            var entry = jobs.next();
            int id = entry.getKey();
            CityPlot plot = parcels.get(id);
            if (plot == null || city.buildingAnchor(id) == null) {
                jobs.remove();
                city.markDirty();
                continue;
            }
            if (plot.chunkKeys().stream().anyMatch(key -> !world.getChunkManager()
                    .isChunkLoaded(CityData.xOf(key), CityData.zOf(key)))) continue;
            var job = entry.getValue();
            reason = "拆除建筑 #" + id;
            if (job.tick(world, city, plot, budget)) {
                jobs.remove();
                finishDemolition(world, city, id, plot, job.preserved());
            }
            return true;
        }
        if (!demolitions.isEmpty()) reason = "拆除暂停 · 等待建筑区块加载";
        return false;
    }

    private void finishDemolition(ServerWorld world, CityData city, int id, CityPlot plot, int preserved) {
        var ledger = city.harness().city;
        ledger.buildings.removeIf(building -> building.id == id);
        city.removeBuildingAnchor(id);
        parcels.remove(id);
        for (long key : plot.chunkKeys()) {
            parcelIndex.computeIfPresent(key, (ignored, ids) -> {
                ids.remove(Integer.valueOf(id));
                return ids.isEmpty() ? null : ids;
            });
            pendingSurveys.add(key);
        }
        entrances.remove(id);
        plannedBuilding = -1;
        CityBuildingHistory.get(world).remove(city.id, id);
        reason = "已拆除建筑 #" + id + (preserved == 0 ? "" : " · 跳过 " + preserved + " 处改动");
        ledger.chronicle.log(ledger.dayCount, reason);
        city.markDirty();
    }

    private Iterator<Long> survey = Collections.emptyIterator();
    private int surveyedSize;
    private int surveyTurns;
    private final Set<Long> pendingSurveys = new LinkedHashSet<>();
    private record Plot(long lot, Long roadChunk) {}
    private Iterator<Plot> sites = Collections.emptyIterator();
    private Plot probe;
    private int probeIndex;
    private final Set<CityPlot> rejectedGround = new HashSet<>();
    private static final int[][] SITE_OFFSETS = {
            {1,1}, {8,1}, {1,8}, {8,8}, {4,4}, {0,0}, {8,0}, {0,8}, {4,0}, {0,4}, {12,4}, {4,12}, {12,12}
    };
    private int plannedBuilding = -1;
    private int plannedSize = -1;
    private boolean plannedGrowth;
    private long retrySitesAt;
    private final Set<Integer> legacyBedCandidates=new HashSet<>();

    void noteLegacyHousing(CityData city) {
        for(var entry:city.harness().city.buildings)
            if(entry.kind==citysim.BuildingKind.HOUSE && city.buildingAnchor(entry.id)!=null)
                legacyBedCandidates.add(entry.id);
    }

    public void requestSurvey(int x,int z) {
        long key=CityData.xzKey(x,z);
        if(chunks.contains(key)) pendingSurveys.add(key);
    }
    public boolean surveyPending() { return !pendingSurveys.isEmpty(); }

    public boolean occupied(BlockPos p, CityData city) {
        if (city.anchors().stream().anyMatch(a -> a.getX()==p.getX() && a.getZ()==p.getZ())) return true;
        return reservedGround(p);
    }

    private boolean reservedGround(BlockPos p) {
        if (quarry != null && p.getX() == quarry.getX() && p.getZ() == quarry.getZ()) return true;
        return parcelIndex.getOrDefault(CityData.xzKey(p.getX() >> 4, p.getZ() >> 4), List.of())
                .stream().anyMatch(id -> parcels.get(id).contains(p));
    }

    public boolean hallReserved(BlockPos p, CityData city) {
        BlockPos hall=city.townHallPos();
        return hall!=null && Math.abs(hall.getX()-p.getX())<=1 && Math.abs(hall.getZ()-p.getZ())<=1;
    }

    public void trackBeds(long chunk,int id) { bedGroups.put(chunk,id); }

    public boolean hasPlotInChunk(int cx, int cz) {
        return parcelIndex.containsKey(CityData.xzKey(cx, cz));
    }

    private void addParcel(int id, CityPlot parcel) {
        parcels.put(id, parcel);
        for (long key : parcel.chunkKeys()) parcelIndex.computeIfAbsent(key, ignored -> new ArrayList<>()).add(id);
    }

    private boolean overlapsParcel(CityPlot footprint, List<Long> plotChunks) {
        for (long key : plotChunks) {
            for (int id : parcelIndex.getOrDefault(key, List.of())) {
                if (footprint.intersects(parcels.get(id))) return true;
            }
        }
        return false;
    }

    private void surveyHousingPlot(ServerWorld world, BuildingEntry home, CityPlot plot) {
        int beds = 0;
        for (long key : plot.chunkKeys()) {
            int cx = CityData.xOf(key), cz = CityData.zOf(key);
            var chunk = world.getChunkManager().getWorldChunk(cx, cz);
            if (chunk == null) return; // Do not replace the saved count with a partial survey.
            for (int x = Math.max(plot.x(), cx << 4); x <= Math.min(plot.maxX(), (cx << 4) + 15); x++) {
                for (int z = Math.max(plot.z(), cz << 4); z <= Math.min(plot.maxZ(), (cz << 4) + 15); z++) {
                    int y = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
                    for (int h = y; h >= Math.max(world.getBottomY(), y - 12); h--) {
                        var state = chunk.getBlockState(new BlockPos(x, h, z));
                        if (state.getBlock() instanceof net.minecraft.block.BedBlock
                                && state.get(net.minecraft.block.BedBlock.PART) == net.minecraft.block.enums.BedPart.FOOT) beds++;
                    }
                }
            }
        }
        home.bedCount = beds;
    }

    public void surveyNext(ServerWorld world, CityData city) {
        if (chunks.isEmpty()) return;
        long key;
        if (!pendingSurveys.isEmpty()) {
            var pending=pendingSurveys.iterator(); key=pending.next(); pending.remove();
        } else {
            if (++surveyTurns % 10 != 0) return;
            if (!survey.hasNext() || surveyedSize!=chunks.size()) {
                survey = chunks.iterator();
                surveyedSize = chunks.size();
            }
            key=survey.next();
        }
        int cx=CityData.xOf(key), cz=CityData.zOf(key);
        if (!world.getChunkManager().isChunkLoaded(cx,cz)) return;
        var chunk=world.getChunk(cx,cz);
        List<BlockPos> paths=new ArrayList<>();
        Integer housing=bedGroups.get(key);
        if (parcels.containsKey(housing == null ? -1 : housing)) housing = null;
        // Legacy takeover stored the first bed as anchor. Preserve manual bed counts unless
        // both the anchor and the sampled capacity corroborate a legacy housing group.
        var candidates=legacyBedCandidates.stream().filter(id->{
            BlockPos p=city.buildingAnchor(id);
            return p!=null && (p.getX()>>4)==cx && (p.getZ()>>4)==cz;
        }).toList();
        int beds=0;
        for (int x=0;x<16;x++) for(int z=0;z<16;z++) {
            int y=chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,x,z);
            BlockPos p=new BlockPos(cx*16+x,y,cz*16+z);
            if (chunk.getBlockState(p).isOf(Blocks.DIRT_PATH)) paths.add(p);
            if(housing!=null || !candidates.isEmpty()) for(int h=y;h>=Math.max(world.getBottomY(),y-12);h--) {
                var s=chunk.getBlockState(p.withY(h));
                if(s.getBlock() instanceof net.minecraft.block.BedBlock &&
                        s.get(net.minecraft.block.BedBlock.PART)==net.minecraft.block.enums.BedPart.FOOT) beds++;
            }
        }
        final int verifiedBeds=beds;
        for(int id:candidates) {
            var anchor=city.buildingAnchor(id);
            if(candidates.size()==1 && chunk.getBlockState(anchor).getBlock() instanceof net.minecraft.block.BedBlock
                    && city.harness().city.buildings.stream().anyMatch(b->b.id==id && b.bedCount==verifiedBeds)) {
                trackBeds(key,id);
            }
            legacyBedCandidates.remove(id);
        }
        List<BlockPos> previous = paths.isEmpty() ? roads.remove(key) : roads.put(key, paths);
        if (!Objects.equals(previous, paths.isEmpty() ? null : paths)) connectedRoads = null;
        if(housing!=null) for(var b:city.harness().city.buildings) if(b.id==housing) { b.bedCount=beds; break; }
        var homes = parcelIndex.get(key);
        if (homes != null) {
            for (var home : city.harness().city.buildings) {
                if (home.kind == BuildingKind.HOUSE && homes.contains(home.id) && city.buildingAnchor(home.id) != null) {
                    surveyHousingPlot(world, home, parcels.get(home.id));
                }
            }
        }
        city.markDirty();
    }

    public boolean hasRoads() {return !roads.isEmpty();}
    public List<BlockPos> visibleRoads(ServerWorld world) {
        List<BlockPos> result = new ArrayList<>();
        for(var entry:roads.entrySet()) {
            int x=CityData.xOf(entry.getKey()), z=CityData.zOf(entry.getKey());
            if (!world.getChunkManager().isChunkLoaded(x,z) || world.getPlayers().stream().noneMatch(player ->
                    Math.abs(player.getX()-(x*16.0+8))<=105 && Math.abs(player.getZ()-(z*16.0+8))<=105)) continue;
            for(BlockPos ground:entry.getValue()) {
                BlockPos feet=ground.up();
                if(world.getClosestPlayer(feet.getX()+0.5,feet.getY(),feet.getZ()+0.5,96,false)!=null
                        && world.getBlockState(ground).isOf(Blocks.DIRT_PATH) && safe(world,feet)) result.add(feet);
            }
        }
        return result;
    }

    public static boolean safe(ServerWorld world,BlockPos feet) {
        return world.isChunkLoaded(feet) && world.getBlockState(feet).isAir() && world.getBlockState(feet.up()).isAir()
                && !world.getBlockState(feet.down()).getCollisionShape(world,feet.down()).isEmpty()
                && world.getFluidState(feet.down()).isEmpty();
    }

    private Set<BlockPos> reachableRoads(CityData city) {
        if (connectedRoads == null || !city.townHallPos().equals(connectedHall)) {
            connectedHall = city.townHallPos();
            connectedRoads = CityRoads.connected(roads.values(), connectedHall);
        }
        return connectedRoads;
    }

    private void rememberRoad(BlockPos block) {
        long key = CityData.xzKey(block.getX() >> 4, block.getZ() >> 4);
        List<BlockPos> roadChunk = roads.getOrDefault(key, List.of());
        if (roadChunk.contains(block)) return;
        List<BlockPos> updated = new ArrayList<>(roadChunk);
        updated.add(block);
        roads.put(key, updated);
        connectedRoads = null;
    }

    /** Reuse known land, then score only the four-connected perimeter. Roads are a prerequisite. */
    public void tickSites(ServerWorld world, CityData city, int budget) {
        if (!city.isFounded()) return;
        produceSites(world, city);
        if (buildId >= 0) {
            build(world, city, budget);
            return;
        }
        BuildingEntry waiting = null;
        for (BuildingEntry entry : city.harness().city.buildings) {
            if (entry.state != BuildingEntry.State.UNDER_CONSTRUCTION || !CityBlueprint.TYPES.containsKey(entry.kind)
                    || city.buildingAnchor(entry.id) != null) continue;
            if (waiting == null || entry.kind == BuildingKind.HOUSE) waiting = entry;
            if (entry.kind == BuildingKind.HOUSE) break;
        }
        if (waiting == null) {
            if (!reason.equals("建筑已落成")) reason = "没有待选址工程";
            return;
        }
        var hall = city.townHallPos();
        var hallPlot = new CityPlot(hall.getX() - 1, hall.getZ() - 1, 3, 3);
        var rules = city.registry.rules();
        boolean canExpand = rules.mayExpand(chunks.size());
        boolean timed = rules.timedExpansion();
        boolean expand = canExpand && !timed;
        boolean exhausted = probe == null && !sites.hasNext();
        // Let an in-progress survey finish when a scheduled claim adds a chunk.
        boolean rescanSize = plannedSize != chunks.size() && (!timed || exhausted);
        if (plannedBuilding != waiting.id || plannedGrowth != expand || rescanSize
                || (exhausted && world.getTime() >= retrySitesAt)) {
            java.util.function.LongPredicate blocked = key -> !world.getWorldBorder().contains(new ChunkPos(CityData.xOf(key), CityData.zOf(key)))
                    || (city.registry.owner(CityData.xOf(key), CityData.zOf(key)) != null && !chunks.contains(key));
            List<Long> direct = CityFrontier.candidates(chunks, CityData.xzKey(hall.getX() >> 4, hall.getZ() >> 4), blocked, expand);
            List<Plot> options = new ArrayList<>();
            for (long key : direct) options.add(new Plot(key, null));
            // A single neighboring road chunk can reach a surveyed plot across rough terrain.
            // Reserve it only after both the lot and the real road route pass validation.
            if (expand && city.registry.rules().mayExpand(chunks.size() + 1)) {
                Set<Long> directKeys = new HashSet<>(direct);
                for (long access : direct) {
                    if (chunks.contains(access)) continue;
                    for (long key : CityFrontier.neighbors(access)) {
                        if (!chunks.contains(key) && !directKeys.contains(key) && !blocked.test(key)) {
                            options.add(new Plot(key, access));
                        }
                    }
                }
            }
            sites = options.iterator();
            probe = null;
            plannedBuilding = waiting.id;
            plannedSize = chunks.size();
            plannedGrowth = expand;
            retrySitesAt = world.getTime() + 100;
            reason = !canExpand ? "领地已达上限 · 搜索城内空地"
                    : timed ? "定时扩张 · 搜索城内空地" : "寻找已加载的相邻工地";
        }
        String name = CityBlueprint.TYPES.get(waiting.kind);
        Set<Long> legacyOccupied = new HashSet<>();
        for (var building : city.harness().city.buildings) {
            BlockPos anchor = city.buildingAnchor(building.id);
            if (anchor != null && !parcels.containsKey(building.id)) {
                legacyOccupied.add(CityData.xzKey(anchor.getX() >> 4, anchor.getZ() >> 4));
            }
        }
        int surveys = 0, roadPlans = 0;
        for (int checked = 0; checked < 16 && surveys < 4 && roadPlans < 2; checked++) {
            if (probe == null) {
                if (!sites.hasNext()) return;
                probe = sites.next();
                probeIndex = 0;
                rejectedGround.clear();
            }
            Plot candidate = probe;
            long key = candidate.lot();
            int cx = CityData.xOf(key), cz = CityData.zOf(key);
            if (legacyOccupied.contains(key) || world.getChunkManager().getWorldChunk(cx, cz) == null) {
                probe = null;
                continue;
            }
            int[] offset = SITE_OFFSETS[probeIndex / BlockRotation.values().length];
            BlockRotation turn = BlockRotation.values()[probeIndex++ % BlockRotation.values().length];
            if (probeIndex == SITE_OFFSETS.length * BlockRotation.values().length) probe = null;
            var design = CityBlueprint.design(name, turn);
            CityPlot footprint = new CityPlot((cx << 4) + offset[0], (cz << 4) + offset[1], design.width(), design.depth());
            if (rejectedGround.contains(footprint) || footprint.intersects(hallPlot)
                    || (quarry != null && footprint.contains(quarry))) continue;
            var plotChunks = footprint.chunkKeys();
            if (plotChunks.stream().anyMatch(part -> (part != key && !chunks.contains(part)) || legacyOccupied.contains(part))
                    || overlapsParcel(footprint, plotChunks)) continue;
            surveys++;
            var terrain = CityTerrain.survey(world, footprint, pos -> {
                long part = CityData.xzKey(pos.getX() >> 4, pos.getZ() >> 4);
                return (part == key || chunks.contains(part)) && !legacyOccupied.contains(part)
                        && world.getWorldBorder().contains(pos) && !hallReserved(pos, city) && !reservedGround(pos);
            });
            if (terrain == null) {
                rejectedGround.add(footprint);
                reason = "寻找符合挖填范围的矩形工地";
                continue;
            }
            roadPlans++;
            BlockPos base = terrain.origin();
            CityRoads.Access access = CityRoads.plan(world, city, base, design, footprint, reachableRoads(city), candidate.roadChunk(), key);
            if (access == null) {
                reason = "找不到通向市政厅的入口道路";
                continue;
            }
            CityTerrain.Plan plan = CityTerrain.plan(world, terrain, design, access.path());
            if (plan == null) {
                reason = "地基、道路或过渡带不能同时落地";
                continue;
            }
            if (!city.registry.claimForBuilding(city, key, candidate.roadChunk())) continue;
            buildId = waiting.id;
            buildBlueprint = name;
            origin = base;
            rotation = turn;
            accessRoad = access.path();
            terrainPlan = plan;
            snapshotReady = false;
            cursor = roadCursor = foundationColumn = 0;
            addParcel(buildId, footprint);
            entrances.put(buildId, access.entrance());
            build(world, city, budget);
            city.markDirty();
            return;
        }
    }

    void write(NbtCompound n) {
        n.putLongArray("chunks",chunks.stream().mapToLong(Long::longValue).toArray());
        n.putInt("forestIndex", forestIndex);
        n.putLong("forestChunk", forestChunk);
        n.putBoolean("forestScanComplete", forestIndex >= FOREST_OFFSETS.size());
        if(quarry!=null) n.putLong("quarry",quarry.asLong());
        if (origin != null) {
            n.putLong("buildOrigin", origin.asLong());
            n.putString("blueprint", buildBlueprint);
            n.putInt("buildId", buildId);
            n.putInt("buildCursor", cursor);
            n.putInt("foundationColumn", foundationColumn);
            n.putInt("rotation", rotation.ordinal());
            n.putLongArray("accessRoad", accessRoad.stream().mapToLong(BlockPos::asLong).toArray());
            n.putInt("roadCursor", roadCursor);
            if (terrainPlan != null) n.put("terrainPlan", terrainPlan.write());
            if (snapshotReady) n.putBoolean("snapshotReady", true);
        }
        if (!demolitions.isEmpty()) {
            NbtCompound queued = new NbtCompound();
            demolitions.forEach((id, job) -> queued.put(id.toString(), job.snapshot.write()));
            n.put("demolitions", queued);
        }
        NbtCompound entries = new NbtCompound();
        entrances.forEach((id, pos) -> entries.putLong(id.toString(), pos.asLong()));
        n.put("entrances", entries);
        n.putString("siteStatus",reason);
        n.putBoolean("hallMissing",hallMissing);
        n.putIntArray("legacyBedCandidates",legacyBedCandidates.stream().mapToInt(Integer::intValue).toArray());
        NbtCompound r=new NbtCompound(), p=new NbtCompound(), b=new NbtCompound();
        roads.forEach((k,v)->r.putLongArray(k.toString(),v.stream().mapToLong(BlockPos::asLong).toArray()));
        parcels.forEach((k,v)->p.put(k.toString(),v.write()));
        bedGroups.forEach((k,v)->b.putInt(k.toString(),v));
        n.put("roads",r); n.put("buildingPlots",p); n.put("surveyedHousing",b);
    }

    void read(NbtCompound n, CityData city) {
        for(long k:n.getLongArray("chunks")) chunks.add(k);
        for(int id:n.getIntArray("legacyBedCandidates")) legacyBedCandidates.add(id);
        forestIndex=n.getInt("forestIndex"); forestChunk=n.getLong("forestChunk");
        if(n.contains("quarry")) quarry=BlockPos.fromLong(n.getLong("quarry"));
        if (n.contains("buildOrigin")) {
            if (n.contains("blueprint")) buildBlueprint = n.getString("blueprint");
            origin = BlockPos.fromLong(n.getLong("buildOrigin"));
            buildId = n.getInt("buildId");
            cursor = n.getInt("buildCursor");
            foundationColumn = n.getInt("foundationColumn");
            rotation = BlockRotation.values()[Math.floorMod(n.getInt("rotation"), BlockRotation.values().length)];
            accessRoad = Arrays.stream(n.getLongArray("accessRoad")).mapToObj(BlockPos::fromLong).toList();
            roadCursor = Math.max(0, Math.min(n.getInt("roadCursor"), accessRoad.size()));
            if (n.contains("terrainPlan", 10)) terrainPlan = CityTerrain.Plan.read(n.getCompound("terrainPlan"));
            snapshotReady = terrainPlan != null && terrainPlan.structure() != null && n.getBoolean("snapshotReady");
        }
        var queued = n.getCompound("demolitions");
        // Recheck each position on reload. Edits already restored are harmless to visit again.
        for (String id : queued.getKeys()) {
            demolitions.put(Integer.parseInt(id), new CityDemolition(CityBlockChanges.read(queued.getCompound(id))));
        }
        var entries = n.getCompound("entrances");
        for (String id : entries.getKeys()) entrances.put(Integer.parseInt(id), BlockPos.fromLong(entries.getLong(id)));
        if (n.contains("siteStatus")) reason = n.getString("siteStatus");
        hallMissing = n.getBoolean("hallMissing");
        NbtCompound r=n.getCompound("roads"),p=n.getCompound("housingParcels"),b=n.getCompound("surveyedHousing");
        for(String k:r.getKeys()) roads.put(Long.parseLong(k),Arrays.stream(r.getLongArray(k)).mapToObj(BlockPos::fromLong).toList());
        var savedPlots = n.getCompound("buildingPlots");
        for (String k : savedPlots.getKeys()) addParcel(Integer.parseInt(k), CityPlot.read(savedPlots.getCompound(k)));
        for (String k : p.getKeys()) {
            int id = Integer.parseInt(k);
            // Dev.2 reserved some parcels with no started construction. Survey those again.
            if (!parcels.containsKey(id) && (id == buildId || city.buildingAnchor(id) != null)) {
                addParcel(id, CityPlot.wholeChunk(p.getLong(k)));
            }
        }
        for(String k:b.getKeys()) bedGroups.put(Long.parseLong(k),b.getInt(k));
    }
}
