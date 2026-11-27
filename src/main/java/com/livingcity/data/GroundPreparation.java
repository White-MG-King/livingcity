package com.livingcity.data;

import net.minecraft.world.chunk.WorldChunk;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server-thread cache of the expensive part of foundation surveys. */
public final class GroundPreparation {
    private static final int CACHE_LIMIT = 256;
    private final Map<Long, Prepared> ready = new LinkedHashMap<>(32, .75f, true);
    private Prepared active;

    private static final class Prepared {
        final WorldChunk chunk;
        final CitySurface.Column[] ground = new CitySurface.Column[256];
        int cursor;

        Prepared(WorldChunk chunk) { this.chunk = chunk; }
    }

    public boolean advance(WorldChunk chunk, long deadline) {
        long key = chunk.getPos().toLong();
        var done = ready.get(key);
        if (done != null && done.chunk == chunk) return true;
        if (active == null || active.chunk != chunk) active = new Prepared(chunk);
        int startX = chunk.getPos().getStartX(), startZ = chunk.getPos().getStartZ();
        while (active.cursor < 256 && System.nanoTime() < deadline) {
            int i = active.cursor++;
            active.ground[i] = CitySurface.constructionColumn(chunk, startX + (i & 15), startZ + (i >> 4));
        }
        if (active.cursor < 256) return false;
        ready.put(key, active);
        if (ready.size() > CACHE_LIMIT) ready.remove(ready.keySet().iterator().next());
        active = null;
        return true;
    }

    public void invalidate(long key) {
        ready.remove(key);
        if (active != null && active.chunk.getPos().toLong() == key) active = null;
    }

    CitySurface.Column column(WorldChunk chunk, int x, int z) {
        Prepared entry = ready.get(chunk.getPos().toLong());
        return entry != null && entry.chunk == chunk ? entry.ground[(z & 15) * 16 + (x & 15)]
                : CitySurface.constructionColumn(chunk, x, z);
    }
}
