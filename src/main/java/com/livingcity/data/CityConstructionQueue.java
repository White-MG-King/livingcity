package com.livingcity.data;

import com.livingcity.web.CityMapTerrain;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.*;
import java.util.concurrent.*;

/** Bounded, temporary FULL-level tickets. Workers inspect map records; only the
 * server thread owns tickets, live chunk reads, land plans and world writes. */
public final class CityConstructionQueue {
    private static final ChunkTicketType<ChunkPos> TICKET = ChunkTicketType.create(
            "livingcity_construction", Comparator.comparingLong(ChunkPos::toLong), 200);
    private static final int LOAD_TIMEOUT = 600, SURVEY_LEASE = 300, BUILD_LEASE = 1800;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(1, 4, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), task -> {
                var thread = new Thread(task, "LivingCity construction preflight");
                thread.setDaemon(true); thread.setPriority(Thread.NORM_PRIORITY - 1); return thread;
            });
    private final Map<Integer, Job> jobs = new LinkedHashMap<>();
    private final Map<Long, Integer> tickets = new HashMap<>();
    private final Map<Integer, Integer> retry = new HashMap<>();
    private int cityCursor, readyCursor, waiting, blockedSince = -1;
    private long completed, expired;
    private boolean paused, closed;

    private static final class Job {
        final CityData city;
        final CityLand.RemoteWork request;
        final int started;
        Future<List<Long>> forecast;
        List<Long> candidates = List.of();
        Set<Long> chunks = Set.of();
        int next, loadedAt = -1, waitingSince, finishingSince = -1;
        boolean building;
        String phase = "地图初筛";
        Job(CityData city, CityLand.RemoteWork request, int tick) {
            this.city = city; this.request = request; this.started = tick;
            building = request.purpose().equals("建筑施工") || request.purpose().equals("建筑拆除");
        }
    }

    public record Activity(int city, String purpose, String phase, int ready, int requested, int seconds) {}
    public record Status(CityRules.SurveySettings settings, boolean paused, int waiting, int active, int computing,
                         int requested, int ready, int chunkLimit, long completed, long expired, List<Activity> jobs) {
        public NbtCompound write() {
            var tag = settings.write();
            tag.putBoolean("paused", paused); tag.putInt("waiting", waiting); tag.putInt("active", active);
            tag.putInt("computing", computing); tag.putInt("requested", requested); tag.putInt("ready", ready);
            tag.putInt("chunkLimit", chunkLimit); tag.putLong("completed", completed); tag.putLong("expired", expired);
            if (!jobs.isEmpty()) tag.putString("job", "城市 #" + jobs.get(0).city() + " · " + jobs.get(0).purpose() + " · " + jobs.get(0).phase());
            return tag;
        }
    }

    public CityConstructionQueue() { workers.allowCoreThreadTimeOut(true); }

    public void tick(ServerWorld world, CityRegistry registry, boolean running, boolean budget) {
        if (closed) return;
        var settings = registry.rules().survey();
        workers.setCorePoolSize(settings.workers());
        int tick = world.getServer().getTicks();
        paused = !running || !budget || !settings.enabled();
        if (!running || !settings.enabled()) {
            releaseAll(world); blockedSince = -1;
        } else if (!budget) {
            if (blockedSince < 0) blockedSince = tick;
            if (tick - blockedSince >= 100) releaseAll(world);
        } else {
            blockedSince = -1;
            for (var iterator = jobs.values().iterator(); iterator.hasNext();) {
                var job = iterator.next();
                if (jobs.size() > settings.slots() || !job.city.isFounded()
                        || !job.building && !job.city.land.hasRemoteDemand(job.city) || tick - job.started > BUILD_LEASE) {
                    release(world, job); iterator.remove(); expired++; continue;
                }
                if (job.forecast != null && job.forecast.isDone()) {
                    try { job.candidates = job.forecast.get(); }
                    catch (Exception failure) { job.candidates = job.request.candidates(); }
                    job.forecast = null;
                    nextSite(world, job);
                }
                if (job.chunks.isEmpty() && job.forecast == null) {
                    release(world, job); iterator.remove(); retry.put(job.city.id, tick + 40); continue;
                }
                if (tick % 40 == 0) for (long key : job.chunks) addTicket(world, key);
                boolean loaded = !job.chunks.isEmpty() && job.chunks.stream().allMatch(key -> loaded(world, key));
                if (loaded && job.loadedAt < 0) {
                    job.loadedAt = tick; job.phase = "现场检测";
                    // Keep the candidate area and its loaded road approaches available to the planner.
                    job.city.land.remoteFocus(job.chunks);
                }
                if (!loaded && tick - job.waitingSince > LOAD_TIMEOUT) {
                    release(world, job); iterator.remove(); retry.put(job.city.id, tick + 100); expired++; continue;
                }
                if (!loaded) continue;
                if (job.city.land.hasActiveConstruction()) { job.building = true; job.phase = "施工放置"; }
                if (job.building && !job.city.land.hasActiveConstruction() && !job.city.land.hasDemolition()) {
                    if (job.finishingSince < 0) {
                        job.finishingSince = tick; job.phase = "回写地图";
                        CityMapTerrain.observeConstruction(world, List.copyOf(job.chunks));
                    }
                    if (CityMapTerrain.constructionCaptured(world, job.chunks) || tick - job.finishingSince >= 20) {
                        release(world, job); iterator.remove(); retry.put(job.city.id, tick + 20); completed++;
                    }
                    continue;
                }
                if (!job.building && tick - job.loadedAt >= SURVEY_LEASE) {
                    // Try another map-ranked location. No perpetual city forceload.
                    releaseChunks(world, job); job.city.land.remoteFocus(Set.of()); job.loadedAt = -1;
                    nextSite(world, job);
                    if (job.chunks.isEmpty()) { iterator.remove(); retry.put(job.city.id, tick + 40); expired++; }
                }
            }
        }
        var cities = List.copyOf(registry.cities());
        if (tick % 20 == 0) waiting = (int) cities.stream().filter(c -> !jobs.containsKey(c.id) && c.land.needsRemoteChunks(world, c)).count();
        if (paused || jobs.size() >= settings.slots() || cities.isEmpty()) return;
        // One new city per tick; simultaneous slots do not mean simultaneous world writes.
        for (int n = 0; n < cities.size(); n++) {
            var city = cities.get(Math.floorMod(cityCursor++, cities.size()));
            if (jobs.containsKey(city.id) || retry.getOrDefault(city.id, 0) > tick) continue;
            var request = city.land.remoteWork(city);
            if (request == null) continue;
            if (!request.fixed().isEmpty() && request.fixed().stream().allMatch(key -> loaded(world, key))) continue;
            if (request.fixed().isEmpty() && request.candidates().stream()
                    .flatMap(key -> CityLand.neighborhood(key, request.shore() ? 2 : 1).stream())
                    .allMatch(key -> loaded(world, key))) continue;
            var job = new Job(city, request, tick);
            jobs.put(city.id, job);
            if (request.fixed().isEmpty()) job.forecast = workers.submit(CityMapTerrain.constructionPreflight(world, request.candidates(), request.shore()));
            else acquire(world, job, new LinkedHashSet<>(request.fixed()));
            break;
        }
    }

    public CityData planningCity() {
        var ready = jobs.values().stream().filter(job -> job.loadedAt >= 0 && !job.city.land.hasActiveConstruction()).toList();
        return ready.isEmpty() ? null : ready.get(Math.floorMod(readyCursor++, ready.size())).city;
    }

    public void retainBuild(ServerWorld world, CityData city) {
        var job = jobs.get(city.id);
        if (job == null || !city.land.hasActiveConstruction()) return;
        var request = city.land.remoteWork(city);
        var required = new LinkedHashSet<>(job.chunks); required.addAll(request.fixed());
        if (!required.equals(job.chunks)) acquire(world, job, required);
        job.building = true; job.phase = "施工放置";
    }

    private void nextSite(ServerWorld world, Job job) {
        while (job.next < job.candidates.size()) {
            long key = job.candidates.get(job.next++);
            var chunks = new LinkedHashSet<>(CityLand.neighborhood(key, job.request.shore() ? 2 : 1));
            if (acquire(world, job, chunks)) return;
        }
    }

    private boolean acquire(ServerWorld world, Job job, Set<Long> chunks) {
        if (chunks.size() > 64 || chunks.stream().anyMatch(key -> !world.getWorldBorder().contains(new ChunkPos(CityData.xOf(key), CityData.zOf(key))))) return false;
        long added = chunks.stream().filter(key -> !tickets.containsKey(key)).count();
        if (tickets.size() + added > CityRules.SurveySettings.CHUNK_LIMIT) { job.phase = "等待加载名额"; return false; }
        for (long key : chunks) if (!job.chunks.contains(key)) {
            tickets.merge(key, 1, Integer::sum); addTicket(world, key);
        }
        for (long key : job.chunks) if (!chunks.contains(key)) removeTicket(world, key);
        job.chunks = Set.copyOf(chunks); job.phase = "等待区块加载";
        job.waitingSince = world.getServer().getTicks();
        return true;
    }

    private void addTicket(ServerWorld world, long key) {
        var pos = new ChunkPos(CityData.xOf(key), CityData.zOf(key));
        world.getChunkManager().addTicket(TICKET, pos, 0, pos);
    }
    private void removeTicket(ServerWorld world, long key) {
        int count = tickets.getOrDefault(key, 1);
        if (count > 1) { tickets.put(key, count - 1); return; }
        tickets.remove(key);
        var pos = new ChunkPos(CityData.xOf(key), CityData.zOf(key));
        world.getChunkManager().removeTicket(TICKET, pos, 0, pos);
    }
    private static boolean loaded(ServerWorld world, long key) {
        return world.getChunkManager().getWorldChunk(CityData.xOf(key), CityData.zOf(key)) != null;
    }
    private void releaseChunks(ServerWorld world, Job job) {
        for (long key : job.chunks) removeTicket(world, key);
        job.chunks = Set.of();
    }
    private void release(ServerWorld world, Job job) {
        if (job.forecast != null) job.forecast.cancel(true);
        releaseChunks(world, job); job.city.land.remoteFocus(Set.of());
    }
    private void releaseAll(ServerWorld world) {
        for (var job : jobs.values()) release(world, job);
        jobs.clear();
    }
    public String cityStatus(int id) {
        var job = jobs.get(id); return job == null ? "" : job.request.purpose() + " · " + (paused ? "守卫或时钟暂停" : job.phase);
    }
    public Status status(ServerWorld world, CityRegistry registry) {
        int tick = world.getServer().getTicks();
        var activity = jobs.values().stream().map(job -> new Activity(job.city.id, job.request.purpose(), job.phase,
                (int) job.chunks.stream().filter(key -> loaded(world, key)).count(), job.chunks.size(), (tick - job.started) / 20)).toList();
        return new Status(registry.rules().survey(), paused, waiting, jobs.size(), workers.getActiveCount(),
                tickets.size(), (int) tickets.keySet().stream().filter(key -> loaded(world, key)).count(), CityRules.SurveySettings.CHUNK_LIMIT,
                completed, expired, activity);
    }
    public void close(ServerWorld world) { releaseAll(world); closed = true; workers.shutdownNow(); }
}
