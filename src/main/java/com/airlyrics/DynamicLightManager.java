package com.airlyrics;

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic light emitter that projects real block lighting into the client world around
 * active floating world and sky lyrics without mutating saved world chunks.
 */
public final class DynamicLightManager {
    public static final class LightPoint {
        public final double x;
        public final double y;
        public final double z;
        public final int level;

        public LightPoint(double x, double y, double z, int level) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.level = level;
        }
    }

    private static final Map<Long, Integer> LIGHT_MAP = new ConcurrentHashMap<Long, Integer>();
    private static volatile boolean active = false;
    private static long lastUpdateMs = 0L;
    private static final long UPDATE_INTERVAL_MS = 95L;

    private DynamicLightManager() {
    }

    public static boolean isActive() {
        return active && !LIGHT_MAP.isEmpty();
    }

    public static int getLightAt(BlockPos pos) {
        if (!active || pos == null || LIGHT_MAP.isEmpty()) {
            return 0;
        }
        Integer val = LIGHT_MAP.get(pos.toLong());
        return val != null ? val : 0;
    }

    public static void clear(Minecraft client) {
        boolean hadLights = !LIGHT_MAP.isEmpty();
        if (!hadLights && !active) {
            return;
        }
        List<Long> oldKeys = new ArrayList<Long>(LIGHT_MAP.keySet());
        LIGHT_MAP.clear();
        active = false;
        if (hadLights && client != null && client.worldRenderer != null) {
            notifyDirtyCells(client, oldKeys);
        }
    }

    /**
     * Updates the spatial light field around active lyric anchor positions.
     * Uses throttled deltas and exact region notifications so shaders and terrain stay smooth.
     */
    public static void update(Minecraft client, List<LightPoint> emitters) {
        if (client == null || client.world == null || client.player == null
                || !LyricsConfig.enabled || !LyricsConfig.lyricsEmitLight
                || emitters == null || emitters.isEmpty()) {
            clear(client);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastUpdateMs < UPDATE_INTERVAL_MS) {
            return;
        }
        lastUpdateMs = now;

        Map<Long, Integer> desired = new HashMap<Long, Integer>();
        World world = client.world;

        for (int i = 0; i < emitters.size(); i++) {
            LightPoint lp = emitters.get(i);
            int strength = Math.max(1, Math.min(15, lp.level));
            if (strength <= 1) {
                continue;
            }
            int cx = floorInt(lp.x);
            int cy = Math.max(0, Math.min(254, floorInt(lp.y)));
            int cz = floorInt(lp.z);

            // Emit a compact 3-block falloff kernel around each lyric light source plus nearby ground bounce.
            int radius = strength >= 11 ? 3 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    int ny = cy + dy;
                    if (ny < 0 || ny > 255) continue;
                    for (int dz = -radius; dz <= radius; dz++) {
                        int dist = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        int lvl = strength - dist * 3;
                        if (lvl <= 1) continue;
                        long key = new BlockPos(cx + dx, ny, cz + dz).toLong();
                        Integer cur = desired.get(key);
                        if (cur == null || lvl > cur) {
                            desired.put(key, lvl);
                        }
                    }
                }
            }

            // If floating/sky text hovers slightly above terrain, gently cast soft ambient glow down onto the floor.
            int groundY = findSurfaceY(world, cx, cy, cz, 14);
            if (groundY >= 0 && cy - groundY >= 3) {
                int bounceLvl = Math.max(4, strength - 3);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        int d = Math.abs(dx) + Math.abs(dz);
                        int lvl = bounceLvl - d * 2;
                        if (lvl <= 1) continue;
                        long key = new BlockPos(cx + dx, groundY + 1, cz + dz).toLong();
                        Integer cur = desired.get(key);
                        if (cur == null || lvl > cur) {
                            desired.put(key, lvl);
                        }
                    }
                }
            }
        }

        if (desired.isEmpty()) {
            clear(client);
            return;
        }

        Set<Long> dirtyKeys = new HashSet<Long>();
        for (Map.Entry<Long, Integer> e : LIGHT_MAP.entrySet()) {
            Long k = e.getKey();
            Integer next = desired.get(k);
            if (next == null || Math.abs(next - e.getValue()) >= 2) {
                dirtyKeys.add(k);
            }
        }
        for (Map.Entry<Long, Integer> e : desired.entrySet()) {
            Long k = e.getKey();
            Integer prev = LIGHT_MAP.get(k);
            if (prev == null || Math.abs(e.getValue() - prev) >= 2) {
                dirtyKeys.add(k);
            }
        }

        LIGHT_MAP.clear();
        LIGHT_MAP.putAll(desired);
        active = true;

        if (!dirtyKeys.isEmpty() && client.worldRenderer != null) {
            notifyDirtyCells(client, new ArrayList<Long>(dirtyKeys));
        }
    }

    private static int findSurfaceY(World world, int x, int startY, int z, int maxDrop) {
        int minY = Math.max(1, startY - maxDrop);
        for (int y = Math.min(254, startY); y >= minY; y--) {
            BlockPos below = new BlockPos(x, y - 1, z);
            if (!world.isAir(below)) {
                return y - 1;
            }
        }
        return -1;
    }

    private static void notifyDirtyCells(Minecraft client, List<Long> keys) {
        if (keys.isEmpty() || client.worldRenderer == null) {
            return;
        }
        // Group into coarse 4x4x4 cells so WorldRenderer only re-bakes affected sub-regions.
        Set<Long> coarseSeen = new HashSet<Long>();
        for (int i = 0; i < keys.size(); i++) {
            BlockPos p = BlockPos.fromLong(keys.get(i));
            int cx = p.getX() >> 2;
            int cy = p.getY() >> 2;
            int cz = p.getZ() >> 2;
            long coarseKey = (((long) cx & 0x3FFFFFL) << 42)
                | (((long) cz & 0x3FFFFFL) << 20)
                | ((long) cy & 0xFFFFFL);
            if (coarseSeen.add(coarseKey)) {
                int bx = (cx << 2);
                int by = (cy << 2);
                int bz = (cz << 2);
                client.worldRenderer.notifyRegionChanged(
                    bx - 1, Math.max(0, by - 1), bz - 1,
                    bx + 4, Math.min(255, by + 4), bz + 4
                );
            }
        }
    }

    private static int floorInt(double v) {
        int i = (int) v;
        return v < (double) i ? i - 1 : i;
    }
}
