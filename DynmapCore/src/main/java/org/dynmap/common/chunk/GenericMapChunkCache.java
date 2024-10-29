package org.dynmap.common.chunk;

import org.dynmap.DynmapChunk;
import org.dynmap.DynmapCore;
import org.dynmap.DynmapWorld;
import org.dynmap.common.BiomeMap;
import org.dynmap.common.chunk.GenericChunkCache.ChunkCacheRec;
import org.dynmap.hdmap.HDBlockModels;
import org.dynmap.renderer.DynmapBlockState;
import org.dynmap.renderer.RenderPatchFactory;
import org.dynmap.utils.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Abstract container for handling map cache and map iterator, using DynmapChunks
 */
public abstract class GenericMapChunkCache extends MapChunkCache {
    protected DynmapWorld dw;
    private int nsect;
    private int sectoff;    // Offset for sake of negative section indexes
    private List<DynmapChunk> chunks;
    private ListIterator<DynmapChunk> iterator;
    private int x_min, x_max, z_min, z_max;
    private int x_dim;
    private HiddenChunkStyle hidestyle = HiddenChunkStyle.FILL_AIR;
    private List<VisibilityLimit> visible_limits = null;
    private List<VisibilityLimit> hidden_limits = null;
    private boolean isempty = true;
    private int snapcnt;
    private GenericChunk[] snaparray; /* Index = (x-x_min) + ((z-z_min)*x_dim) */
    private boolean[][] isSectionNotEmpty; /* Indexed by snapshot index, then by section index */
    private AtomicInteger loadingChunks = new AtomicInteger(0); //the amount of threads loading chunks at this moment, used by async loading

    private static final BlockStep unstep[] = {BlockStep.X_MINUS, BlockStep.Y_MINUS, BlockStep.Z_MINUS,
            BlockStep.X_PLUS, BlockStep.Y_PLUS, BlockStep.Z_PLUS};

    /**
     * Iterator for traversing map chunk cache (base is for non-snapshot)
     */
    public class OurMapIterator implements MapIterator {
        private int x, y, z, chunkindex, bx, bz;
        private GenericChunk snap;
        private BlockStep laststep;
        private DynmapBlockState blk;
        private final int worldheight;
        private final int ymin;
        private final int sealevel;

        OurMapIterator(int x0, int y0, int z0) {
            initialize(x0, y0, z0);
            worldheight = dw.worldheight;
            ymin = dw.minY;
            sealevel = dw.sealevel;
        }

        @Override
        public final void initialize(int x0, int y0, int z0) {
            this.x = x0;
            this.y = y0;
            this.z = z0;
            this.chunkindex = ((x >> 4) - x_min) + (((z >> 4) - z_min) * x_dim);
            this.bx = x & 0xF;
            this.bz = z & 0xF;

            if ((chunkindex >= snapcnt) || (chunkindex < 0)) {
                snap = getEmpty();
            } else {
                snap = snaparray[chunkindex];
            }

            laststep = BlockStep.Y_MINUS;

            if ((y >= ymin) && (y < worldheight)) {
                blk = null;
            } else {
                blk = DynmapBlockState.AIR;
            }
        }

        @Override
        public int getBlockSkyLight() {
            try {
                return snap.getBlockSkyLight(bx, y, bz);
            } catch (ArrayIndexOutOfBoundsException aioobx) {
                return 15;
            }
        }

        @Override
        public final int getBlockEmittedLight() {
            try {
                return snap.getBlockEmittedLight(bx, y, bz);
            } catch (ArrayIndexOutOfBoundsException aioobx) {
                return 0;
            }
        }

        @Override
        /**
         * Get block sky and emitted light, relative to current coordinate
         * @return (emitted light * 256) + sky light
         */
        public final int getBlockLight(BlockStep step) {
            int emit = 0, sky = 15;
            GenericChunkSection sect;
            if (step.yoff != 0) {    // Y coord - snap is valid already
                int ny = y + step.yoff;
                sect = snap.getSection(ny);
                emit = sect.emitted.getLight(x, ny, z);
                sky = sect.sky.getLight(x, ny, z);
            } else {
                int nx = x + step.xoff;
                int nz = z + step.zoff;
                int nchunkindex = ((nx >> 4) - x_min) + (((nz >> 4) - z_min) * x_dim);
                if ((nchunkindex < snapcnt) && (nchunkindex >= 0)) {
                    sect = snaparray[nchunkindex].getSection(y);
                    emit = sect.emitted.getLight(nx, y, nz);
                    sky = sect.sky.getLight(nx, y, nz);
                }
            }
            return (emit << 8) + sky;
        }

        @Override
        /**
         * Get block sky and emitted light, relative to current coordinate
         * @return (emitted light * 256) + sky light
         */
        public final int getBlockLight(int xoff, int yoff, int zoff) {
            int emit = 0, sky = 15;
            int nx = x + xoff;
            int ny = y + yoff;
            int nz = z + zoff;
            GenericChunkSection sect;
            int nchunkindex = ((nx >> 4) - x_min) + (((nz >> 4) - z_min) * x_dim);
            if ((nchunkindex < snapcnt) && (nchunkindex >= 0)) {
                sect = snaparray[nchunkindex].getSection(ny);
                emit = sect.emitted.getLight(nx, ny, nz);
                sky = sect.sky.getLight(nx, ny, nz);
            }
            return (emit << 8) + sky;
        }

        @Override
        public final BiomeMap getBiome() {
            try {
                return snap.getBiome(bx, y, bz);
            } catch (Exception ex) {
                return BiomeMap.NULL;
            }
        }

        private final BiomeMap getBiomeRel(int dx, int dz) {
            int nx = x + dx;
            int nz = z + dz;
            int nchunkindex = ((nx >> 4) - x_min) + (((nz >> 4) - z_min) * x_dim);
            if ((nchunkindex >= snapcnt) || (nchunkindex < 0)) {
                return BiomeMap.NULL;
            } else {
                return snaparray[nchunkindex].getBiome(nx, y, nz);
            }
        }

        @Override
        public final int getSmoothGrassColorMultiplier(int[] colormap) {
            int mult = 0xFFFFFF;

            try {
                int raccum = 0;
                int gaccum = 0;
                int baccum = 0;
                int cnt = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BiomeMap bm = getBiomeRel(dx, dz);
                        if (bm == BiomeMap.NULL) continue;
                        int rmult = getGrassColor(bm, colormap, getX() + dx, getZ() + dz);
                        raccum += (rmult >> 16) & 0xFF;
                        gaccum += (rmult >> 8) & 0xFF;
                        baccum += rmult & 0xFF;
                        cnt++;
                    }
                }
                cnt = (cnt > 0) ? cnt : 1;
                mult = ((raccum / cnt) << 16) | ((gaccum / cnt) << 8) | (baccum / cnt);
            } catch (Exception x) {
                //Log.info("getSmoothGrassColorMultiplier() error: " + x);
                mult = 0xFFFFFF;
            }

            //Log.info(String.format("getSmoothGrassColorMultiplier() at %d, %d = %X", x, z, mult));
            return mult;
        }

        @Override
        public final int getSmoothFoliageColorMultiplier(int[] colormap) {
            int mult = 0xFFFFFF;

            try {
                int raccum = 0;
                int gaccum = 0;
                int baccum = 0;
                int cnt = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BiomeMap bm = getBiomeRel(dx, dz);
                        if (bm == BiomeMap.NULL) continue;
                        int rmult = getFoliageColor(bm, colormap, getX() + dx, getZ() + dz);
                        raccum += (rmult >> 16) & 0xFF;
                        gaccum += (rmult >> 8) & 0xFF;
                        baccum += rmult & 0xFF;
                        cnt++;
                    }
                }
                cnt = (cnt > 0) ? cnt : 1;
                mult = ((raccum / cnt) << 16) | ((gaccum / cnt) << 8) | (baccum / cnt);
            } catch (Exception x) {
                //Log.info("getSmoothFoliageColorMultiplier() error: " + x);
            }
            //Log.info(String.format("getSmoothFoliageColorMultiplier() at %d, %d = %X", x, z, mult));

            return mult;
        }

        @Override
        public final int getSmoothColorMultiplier(int[] colormap, int[] swampmap) {
            int mult = 0xFFFFFF;

            try {
                int raccum = 0;
                int gaccum = 0;
                int baccum = 0;
                int cnt = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BiomeMap bm = getBiomeRel(dx, dz);
                        if (bm == BiomeMap.NULL) continue;
                        int rmult;
                        if (bm == BiomeMap.SWAMPLAND) {
                            rmult = swampmap[bm.biomeLookup()];
                        } else {
                            rmult = colormap[bm.biomeLookup()];
                        }
                        raccum += (rmult >> 16) & 0xFF;
                        gaccum += (rmult >> 8) & 0xFF;
                        baccum += rmult & 0xFF;
                        cnt++;
                    }
                }
                cnt = (cnt > 0) ? cnt : 1;
                mult = ((raccum / cnt) << 16) | ((gaccum / cnt) << 8) | (baccum / cnt);
            } catch (Exception x) {
                //Log.info("getSmoothColorMultiplier() error: " + x);
            }
            //Log.info(String.format("getSmoothColorMultiplier() at %d, %d = %X", x, z, mult));

            return mult;
        }

        @Override
        public final int getSmoothWaterColorMultiplier() {
            int multv = 0xFFFFFF;
            try {
                int raccum = 0;
                int gaccum = 0;
                int baccum = 0;
                int cnt = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BiomeMap bm = getBiomeRel(dx, dz);
                        if (bm == BiomeMap.NULL) continue;
                        int rmult = bm.getWaterColorMult();
                        raccum += (rmult >> 16) & 0xFF;
                        gaccum += (rmult >> 8) & 0xFF;
                        baccum += rmult & 0xFF;
                        cnt++;
                    }
                }
                cnt = (cnt > 0) ? cnt : 1;
                multv = ((raccum / cnt) << 16) | ((gaccum / cnt) << 8) | (baccum / cnt);
            } catch (Exception x) {
                //Log.info("getSmoothWaterColorMultiplier(nomap) error: " + x);
            }
            //Log.info(String.format("getSmoothWaterColorMultiplier(nomap) at %d, %d = %X", x, z, multv));

            return multv;
        }

        @Override
        public final int getSmoothWaterColorMultiplier(int[] colormap) {
            int mult = 0xFFFFFF;

            try {
                int raccum = 0;
                int gaccum = 0;
                int baccum = 0;
                int cnt = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BiomeMap bm = getBiomeRel(dx, dz);
                        if (bm == BiomeMap.NULL) continue;
                        int rmult = colormap[bm.biomeLookup()];
                        raccum += (rmult >> 16) & 0xFF;
                        gaccum += (rmult >> 8) & 0xFF;
                        baccum += rmult & 0xFF;
                        cnt++;
                    }
                }
                cnt = (cnt > 0) ? cnt : 1;
                mult = ((raccum / cnt) << 16) | ((gaccum / cnt) << 8) | (baccum / cnt);
            } catch (Exception x) {
                //Log.info("getSmoothWaterColorMultiplier() error: " + x);
            }
            //Log.info(String.format("getSmoothWaterColorMultiplier() at %d, %d = %X", x, z, mult));

            return mult;
        }

        /**
         * Step current position in given direction
         */
        @Override
        public final void stepPosition(BlockStep step) {
            blk = null;

            switch (step.ordinal()) {
                case 0:
                    x++;
                    bx++;

                    if (bx == 16) /* Next chunk? */ {
                        bx = 0;
                        chunkindex++;
                        if ((chunkindex >= snapcnt) || (chunkindex < 0)) {
                            snap = getEmpty();
                        } else {
                            snap = snaparray[chunkindex];
                        }
                    }

                    break;

                case 1:
                    y++;

                    if (y >= worldheight) {
                        blk = DynmapBlockState.AIR;
                    }

                    break;

                case 2:
                    z++;
                    bz++;

                    if (bz == 16) /* Next chunk? */ {
                        bz = 0;
                        chunkindex += x_dim;
                        if ((chunkindex >= snapcnt) || (chunkindex < 0)) {
                            snap = getEmpty();
                        } else {
                            snap = snaparray[chunkindex];
                        }
                    }
                    break;

                case 3:
                    x--;
                    bx--;

                    if (bx == -1) /* Next chunk? */ {
                        bx = 15;
                        chunkindex--;
                        if ((chunkindex >= snapcnt) || (chunkindex < 0)) {
                            snap = getEmpty();
                        } else {
                            snap = snaparray[chunkindex];
                        }
                    }

                    break;

                case 4:
                    y--;

                    if (y < ymin) {
                        blk = DynmapBlockState.AIR;
                    }

                    break;

                case 5:
                    z--;
                    bz--;

                    if (bz == -1) /* Next chunk? */ {
                        bz = 15;
                        chunkindex -= x_dim;
                        if ((chunkindex >= snapcnt) || (chunkindex < 0)) {
                            snap = getEmpty();
                        } else {
                            snap = snaparray[chunkindex];
                        }
                    }
                    break;
            }

            laststep = step;
        }

        /**
         * Unstep current position to previous position
         */
        @Override
        public final BlockStep unstepPosition() {
            BlockStep ls = laststep;
            stepPosition(unstep[ls.ordinal()]);
            return ls;
        }

        /**
         * Unstep current position in oppisite director of given step
         */
        @Override
        public final void unstepPosition(BlockStep s) {
            stepPosition(unstep[s.ordinal()]);
        }

        @Override
        public final void setY(int y) {
            if (y > this.y) {
                laststep = BlockStep.Y_PLUS;
            } else {
                laststep = BlockStep.Y_MINUS;
            }

            this.y = y;

            if ((y < ymin) || (y >= worldheight)) {
                blk = DynmapBlockState.AIR;
            } else {
                blk = null;
            }
        }

        @Override
        public final int getX() {
            return x;
        }

        @Override
        public final int getY() {
            return y;
        }

        @Override
        public final int getZ() {
            return z;
        }

        @Override
        public final DynmapBlockState getBlockTypeAt(BlockStep s) {
            return getBlockTypeAt(s.xoff, s.yoff, s.zoff);
        }

        @Override
        public final BlockStep getLastStep() {
            return laststep;
        }

        @Override
        public final int getWorldHeight() {
            return worldheight;
        }

        @Override
        public final int getWorldYMin() {
            return ymin;
        }

        /**
         * Get world sealevel
         */
        public final int getWorldSeaLevel() {
            return sealevel;
        }

        @Override
        public final long getBlockKey() {
            return (((chunkindex * (worldheight - ymin)) + (y - ymin)) << 8) | (bx << 4) | bz;
        }

        @Override
        public final RenderPatchFactory getPatchFactory() {
            return HDBlockModels.getPatchDefinitionFactory();
        }

        @Override
        public final Object getBlockTileEntityField(String fieldId) {
            // TODO: handle tile entities here
            return null;
        }

        @Override
        public final DynmapBlockState getBlockTypeAt(int xoff, int yoff, int zoff) {
            int nx = x + xoff;
            int ny = y + yoff;
            int nz = z + zoff;
            int nchunkindex = ((nx >> 4) - x_min) + (((nz >> 4) - z_min) * x_dim);
            if ((nchunkindex >= snapcnt) || (nchunkindex < 0)) {
                return DynmapBlockState.AIR;
            } else {
                return snaparray[nchunkindex].getBlockType(nx & 0xF, ny, nz & 0xF);
            }
        }

        @Override
        public final Object getBlockTileEntityFieldAt(String fieldId, int xoff, int yoff, int zoff) {
            return null;
        }

        @Override
        public final long getInhabitedTicks() {
            try {
                return snap.getInhabitedTicks();
            } catch (Exception x) {
                return 0;
            }
        }

        @Override
        public final DynmapBlockState getBlockType() {
            if (blk == null) {
                blk = snap.getBlockType(bx, y, bz);
            }
            return blk;
        }

        @Override
        public int getDataVersion() {
            return (snap != null) ? snap.dataVersion : 0;
        }

        @Override
        public String getChunkStatus() {
            return (snap != null) ? snap.chunkStatus : null;
        }
    }

    public int getGrassColor(BiomeMap bm, int[] colormap, int x, int z) {
        return bm.getModifiedGrassMultiplier(colormap[bm.biomeLookup()]);
    }

    public int getFoliageColor(BiomeMap bm, int[] colormap, int x, int z) {
        return bm.getModifiedFoliageMultiplier(colormap[bm.biomeLookup()]);
    }

    private class OurEndMapIterator extends OurMapIterator {
        OurEndMapIterator(int x0, int y0, int z0) {
            super(x0, y0, z0);
        }

        @Override
        public final int getBlockSkyLight() {
            return 15;
        }
    }

    private static final GenericChunkSection STONESECTION = (new GenericChunkSection.Builder()).singleBiome(BiomeMap.PLAINS).singleBlockState(DynmapBlockState.getBaseStateByName(DynmapBlockState.STONE_BLOCK)).build();
    private static final GenericChunkSection WATERSECTION = (new GenericChunkSection.Builder()).singleBiome(BiomeMap.OCEAN).singleBlockState(DynmapBlockState.getBaseStateByName(DynmapBlockState.WATER_BLOCK)).build();

    private GenericChunkCache cache;

    // Lazy generic chunks (tailored to height of world)
    private GenericChunk empty_chunk;
    private GenericChunk stone_chunk;
    private GenericChunk ocean_chunk;

    private final GenericChunk getEmpty() {
        if (empty_chunk == null) {
            empty_chunk = (new GenericChunk.Builder(dw.minY, dw.worldheight)).build();
        }
        return empty_chunk;
    }

    private final GenericChunk getStone() {
        if (stone_chunk == null) {
            GenericChunk.Builder bld = new GenericChunk.Builder(dw.minY, dw.worldheight);
            for (int sy = -sectoff; sy < 4; sy++) {
                bld.addSection(sy, STONESECTION);
            }
            stone_chunk = bld.build();
        }
        return stone_chunk;
    }

    private final GenericChunk getOcean() {
        if (ocean_chunk == null) {
            GenericChunk.Builder bld = new GenericChunk.Builder(dw.minY, dw.worldheight);
            for (int sy = -sectoff; sy < 3; sy++) {
                bld.addSection(sy, STONESECTION);
            }
            bld.addSection(3, WATERSECTION);    // Put stone with ocean on top - less expensive render
            ocean_chunk = bld.build();
        }
        return ocean_chunk;
    }

    /**
     * Construct empty cache
     */
    public GenericMapChunkCache(GenericChunkCache c) {
        cache = c;    // Save reference to cache
    }

    public void setChunks(DynmapWorld dw, List<DynmapChunk> chunks) {
        this.dw = dw;
        nsect = (dw.worldheight - dw.minY) >> 4;
        sectoff = (-dw.minY) >> 4;
        this.chunks = chunks;

        /* Compute range */
        if (chunks.size() == 0) {
            this.x_min = 0;
            this.x_max = 0;
            this.z_min = 0;
            this.z_max = 0;
            x_dim = 1;
        } else {
            x_min = x_max = chunks.get(0).x;
            z_min = z_max = chunks.get(0).z;

            for (DynmapChunk c : chunks) {
                if (c.x > x_max) {
                    x_max = c.x;
                }

                if (c.x < x_min) {
                    x_min = c.x;
                }

                if (c.z > z_max) {
                    z_max = c.z;
                }

                if (c.z < z_min) {
                    z_min = c.z;
                }
            }

            x_dim = x_max - x_min + 1;
        }

        snapcnt = x_dim * (z_max - z_min + 1);
        snaparray = new GenericChunk[snapcnt];
        isSectionNotEmpty = new boolean[snapcnt][];

    }

    private boolean isChunkVisible(DynmapChunk chunk) {
        boolean vis = true;
        if (visible_limits != null) {
            vis = false;
            for (VisibilityLimit limit : visible_limits) {
                if (limit.doIntersectChunk(chunk.x, chunk.z)) {
                    vis = true;
                    break;
                }
            }
        }
        if (vis && (hidden_limits != null)) {
            for (VisibilityLimit limit : hidden_limits) {
                if (limit.doIntersectChunk(chunk.x, chunk.z)) {
                    vis = false;
                    break;
                }
            }
        }
        return vis;
    }

    private boolean tryChunkCache(DynmapChunk chunk, boolean vis) {
        /* Check if cached chunk snapshot found */
        GenericChunk ss = null;
        ChunkCacheRec ssr = cache.getSnapshot(dw.getName(), chunk.x, chunk.z);
        if (ssr != null) {
            ss = ssr.ss;
            if (!vis) {
                if (hidestyle == HiddenChunkStyle.FILL_STONE_PLAIN) {
                    ss = getStone();
                } else if (hidestyle == HiddenChunkStyle.FILL_OCEAN) {
                    ss = getOcean();
                } else {
                    ss = getEmpty();
                    ;
                }
            }
            int idx = (chunk.x - x_min) + (chunk.z - z_min) * x_dim;
            snaparray[idx] = ss;
        }
        return (ssr != null);
    }

    // Prep snapshot and add to cache
    private void prepChunkSnapshot(DynmapChunk chunk, GenericChunk ss) {
        DynIntHashMap tileData = new DynIntHashMap();

        ChunkCacheRec ssr = new ChunkCacheRec();
        ssr.ss = ss;
        ssr.tileData = tileData;

        cache.putSnapshot(dw.getName(), chunk.x, chunk.z, ssr);
    }

    // Load generic chunk from existing and already loaded chunk
    protected abstract GenericChunk getLoadedChunk(DynmapChunk ch);

    // Load generic chunk from unloaded chunk
    protected abstract GenericChunk loadChunk(DynmapChunk ch);

    // Load generic chunk from existing and already loaded chunk async
    protected Supplier<GenericChunk> getLoadedChunkAsync(DynmapChunk ch) {
        throw new IllegalStateException("Not implemeted");
    }

    // Load generic chunks from unloaded chunk async
    protected Supplier<GenericChunk> loadChunkAsync(DynmapChunk ch) {
        throw new IllegalStateException("Not implemeted");
    }

    /**
     * Read NBT data from loaded chunks - needs to be called from server/world
     * thread to be safe
     *
     * @returns number loaded
     */
    public int getLoadedChunks() {
        int cnt = 0;
        if (!dw.isLoaded()) {
            isempty = true;
            unloadChunks();
            return 0;
        }
        ListIterator<DynmapChunk> iter = chunks.listIterator();
        while (iter.hasNext()) {
            long startTime = System.nanoTime();
            DynmapChunk chunk = iter.next();
            int chunkindex = (chunk.x - x_min) + (chunk.z - z_min) * x_dim;
            if (snaparray[chunkindex] != null)
                continue; // Skip if already processed

            boolean vis = isChunkVisible(chunk);

            /* Check if cached chunk snapshot found */
            if (tryChunkCache(chunk, vis)) {
                endChunkLoad(startTime, ChunkStats.CACHED_SNAPSHOT_HIT);
                cnt++;
            }
            // If chunk is loaded and not being unloaded, we're grabbing its NBT data
            else {
                // Get generic chunk from already loaded chunk, if we can
                GenericChunk ss = getLoadedChunk(chunk);
                if (ss != null) {
                    if (vis) { // If visible
                        prepChunkSnapshot(chunk, ss);
                    } else {
                        if (hidestyle == HiddenChunkStyle.FILL_STONE_PLAIN) {
                            ss = getStone();
                        } else if (hidestyle == HiddenChunkStyle.FILL_OCEAN) {
                            ss = getOcean();
                        } else {
                            ss = getEmpty();
                        }
                    }
                    snaparray[chunkindex] = ss;
                    endChunkLoad(startTime, ChunkStats.LOADED_CHUNKS);
                    cnt++;
                }
            }
        }
        return cnt;
    }

    /**
     * Read NBT data from loaded chunks - do not needs to be called from server/world <p>
     * Will throw {@link IllegalStateException} if not supporting
     */
    public void getLoadedChunksAsync() {
        class SimplePair { //simple pair of the supplier that finishes read async, and a consumer that also finish his work async
            final Supplier<GenericChunk> supplier;
            final BiConsumer<GenericChunk, Long> consumer;

            SimplePair(Supplier<GenericChunk> supplier, BiConsumer<GenericChunk, Long> consumer) {
                this.supplier = supplier;
                this.consumer = consumer;
            }
        }
        if (!dw.isLoaded()) {
            isempty = true;
            unloadChunks();
            return;
        }
        List<SimplePair> lastApply = new ArrayList<>();
        for (DynmapChunk dynmapChunk : chunks) {
            long startTime = System.nanoTime();
            int chunkIndex = (dynmapChunk.x - x_min) + (dynmapChunk.z - z_min) * x_dim;
            if (snaparray[chunkIndex] != null)
                continue; // Skip if already processed

            boolean vis = isChunkVisible(dynmapChunk);

            /* Check if cached chunk snapshot found */
            if (tryChunkCache(dynmapChunk, vis)) {
                endChunkLoad(startTime, ChunkStats.CACHED_SNAPSHOT_HIT);
            }
            // If chunk is loaded and not being unloaded, we're grabbing its NBT data
            else {
                // Get generic chunk from already loaded chunk, if we can
                Supplier<GenericChunk> supplier = getLoadedChunkAsync(dynmapChunk);
                long startPause = System.nanoTime();
                BiConsumer<GenericChunk, Long> consumer = (ss, reloadTime) -> {
                    if (ss == null) return;
                    long pause = reloadTime - startPause;
                    if (vis) { // If visible
                        prepChunkSnapshot(dynmapChunk, ss);
                    } else {
                        if (hidestyle == HiddenChunkStyle.FILL_STONE_PLAIN) {
                            ss = getStone();
                        } else if (hidestyle == HiddenChunkStyle.FILL_OCEAN) {
                            ss = getOcean();
                        } else {
                            ss = getEmpty();
                        }
                    }
                    snaparray[chunkIndex] = ss;
                    endChunkLoad(startTime - pause, ChunkStats.LOADED_CHUNKS);

                };
                lastApply.add(new SimplePair(supplier, consumer));
            }
        }
        //impact on the main thread should be minimal, so we plan and finish the work after main thread finished it's part
        lastApply.forEach(simplePair -> {
            long reloadWork = System.nanoTime();
            simplePair.consumer.accept(simplePair.supplier.get(), reloadWork);
        });
    }

    @Override
    public int loadChunks(int max_to_load) {
        return getLoadedChunks() + readChunks(max_to_load);
    }

    /**
     * Loads all chunks in the world asynchronously.
     * <p>
     * If it is not supported, it will throw {@link IllegalStateException}
     */
    public void loadChunksAsync() {
        getLoadedChunksAsync();
        readChunksAsync();
    }

    public int readChunks(int max_to_load) {
        if (!dw.isLoaded()) {
            isempty = true;
            unloadChunks();
            return 0;
        }

        int cnt = 0;

        if (iterator == null) {
            iterator = chunks.listIterator();
        }

        DynmapCore.setIgnoreChunkLoads(true);

        // Load the required chunks.
        while ((cnt < max_to_load) && iterator.hasNext()) {
            long startTime = System.nanoTime();

            DynmapChunk chunk = iterator.next();

            int chunkindex = (chunk.x - x_min) + (chunk.z - z_min) * x_dim;

            if (snaparray[chunkindex] != null)
                continue; // Skip if already processed

            boolean vis = isChunkVisible(chunk);

            /* Check if cached chunk snapshot found */
            if (tryChunkCache(chunk, vis)) {
                endChunkLoad(startTime, ChunkStats.CACHED_SNAPSHOT_HIT);
            } else {
                GenericChunk ss = loadChunk(chunk);
                // If read was good
                if (ss != null) {
                    // If hidden
                    if (!vis) {
                        if (hidestyle == HiddenChunkStyle.FILL_STONE_PLAIN) {
                            ss = getStone();
                        } else if (hidestyle == HiddenChunkStyle.FILL_OCEAN) {
                            ss = getOcean();
                        } else {
                            ss = getEmpty();
                        }
                    } else {
                        // Prep snapshot
                        prepChunkSnapshot(chunk, ss);
                    }
                    snaparray[chunkindex] = ss;
                    endChunkLoad(startTime, ChunkStats.UNLOADED_CHUNKS);
                } else {
                    endChunkLoad(startTime, ChunkStats.UNGENERATED_CHUNKS);
                }
            }
            cnt++;
        }

        DynmapCore.setIgnoreChunkLoads(false);

        if (iterator.hasNext() == false) { /* If we're done */
            isempty = true;

            /* Fill missing chunks with empty dummy chunk */
            for (int i = 0; i < snaparray.length; i++) {
                if (snaparray[i] == null) {
                    snaparray[i] = getEmpty();
                } else if (!snaparray[i].isEmpty) {
                    isempty = false;
                }
            }
        }
        return cnt;
    }

    /**
     * It loads chunks from the cache or from the world, and if the chunk is not visible, it fills it with stone, ocean or
     * empty chunk
     * <p>
     * if it's not supported, will throw {@link IllegalStateException}
     */
    public void readChunksAsync() {
        class SimplePair { //pair of the chunk and the data which is readed async
            private final Supplier<GenericChunk> supplier;
            private final DynmapChunk chunk;

            SimplePair(DynmapChunk chunk) {
                this.chunk = chunk;
                this.supplier = loadChunkAsync(chunk);
            }
        }
        if (!dw.isLoaded()) {
            isempty = true;
            unloadChunks();
            return;
        }

        List<DynmapChunk> chunks;
        if (iterator == null) {
            iterator = Collections.emptyListIterator();
            chunks = new ArrayList<>(this.chunks);
        } else {
            chunks = new ArrayList<>();
            iterator.forEachRemaining(chunks::add);
        }
        //if before increent was 0, means that we are the first, so we need to set this
        if (loadingChunks.getAndIncrement() == 0) {
            DynmapCore.setIgnoreChunkLoads(true);
        }

        try {
            List<DynmapChunk> cached = new ArrayList<>();
            List<SimplePair> notCached = new ArrayList<>();

            iterator.forEachRemaining(chunks::add);
            chunks.stream()
                    .filter(chunk -> snaparray[(chunk.x - x_min) + (chunk.z - z_min) * x_dim] == null)
                    .forEach(chunk -> {
                        if (cache.getSnapshot(dw.getName(), chunk.x, chunk.z) == null) {
                            notCached.add(new SimplePair(chunk));
                        } else {
                            cached.add(chunk);
                        }
                    });

            cached.forEach(chunk -> {
                long startTime = System.nanoTime();
                tryChunkCache(chunk, isChunkVisible(chunk));
                endChunkLoad(startTime, ChunkStats.CACHED_SNAPSHOT_HIT);
            });
            notCached.forEach(chunkSupplier -> {
                long startTime = System.nanoTime();
                GenericChunk chunk = chunkSupplier.supplier.get();
                DynmapChunk dynmapChunk = chunkSupplier.chunk;
                if (chunk != null) {
                    // If hidden
                    if (isChunkVisible(dynmapChunk)) {
                        // Prep snapshot
                        prepChunkSnapshot(dynmapChunk, chunk);
                    } else {
                        if (hidestyle == HiddenChunkStyle.FILL_STONE_PLAIN) {
                            chunk = getStone();
                        } else if (hidestyle == HiddenChunkStyle.FILL_OCEAN) {
                            chunk = getOcean();
                        } else {
                            chunk = getEmpty();
                        }
                    }
                    snaparray[(dynmapChunk.x - x_min) + (dynmapChunk.z - z_min) * x_dim] = chunk;
                    endChunkLoad(startTime, ChunkStats.UNLOADED_CHUNKS);
                } else {
                    endChunkLoad(startTime, ChunkStats.UNGENERATED_CHUNKS);
                }
            });

            isempty = true;
            /* Fill missing chunks with empty dummy chunk */
            for (int i = 0; i < snaparray.length; i++) {
                if (snaparray[i] == null) {
                    snaparray[i] = getEmpty();
                } else if (!snaparray[i].isEmpty) {
                    isempty = false;
                }
            }
        } finally {
            if (loadingChunks.decrementAndGet() == 0) {
                DynmapCore.setIgnoreChunkLoads(false);
            }
        }
    }

    /**
     * Test if done loading
     */
    public boolean isDoneLoading() {
        if (!dw.isLoaded()) {
            return true;
        }
        if (iterator != null) {
            return !iterator.hasNext();
        }

        return false;
    }

    /**
     * Test if all empty blocks
     */
    public boolean isEmpty() {
        return isempty;
    }

    /**
     * Unload chunks
     */
    public void unloadChunks() {
        if (snaparray != null) {
            for (int i = 0; i < snaparray.length; i++) {
                snaparray[i] = null;
            }

            snaparray = null;
        }
    }

    private void initSectionData(int idx) {
        isSectionNotEmpty[idx] = new boolean[nsect + 1];

        if (!snaparray[idx].isEmpty) {
            for (int i = 0; i < nsect; i++) {
                if (snaparray[idx].isSectionEmpty(i - sectoff) == false) {
                    isSectionNotEmpty[idx][i] = true;
                }
            }
        }
    }

    public boolean isEmptySection(int sx, int sy, int sz) {
        int idx = (sx - x_min) + (sz - z_min) * x_dim;
        boolean[] flags = isSectionNotEmpty[idx];
        if (flags == null) {
            initSectionData(idx);
            flags = isSectionNotEmpty[idx];
        }
        return !flags[sy + sectoff];
    }

    /**
     * Get cache iterator
     */
    public MapIterator getIterator(int x, int y, int z) {
        if (dw.getEnvironment().equals("the_end")) {
            return new OurEndMapIterator(x, y, z);
        }
        return new OurMapIterator(x, y, z);
    }

    /**
     * Set hidden chunk style (default is FILL_AIR)
     */
    public void setHiddenFillStyle(HiddenChunkStyle style) {
        this.hidestyle = style;
    }

    /**
     * Add visible area limit - can be called more than once Needs to be set before
     * chunks are loaded Coordinates are block coordinates
     */
    public void setVisibleRange(VisibilityLimit lim) {
        if (visible_limits == null)
            visible_limits = new ArrayList<VisibilityLimit>();
        visible_limits.add(lim);
    }

    /**
     * Add hidden area limit - can be called more than once Needs to be set before
     * chunks are loaded Coordinates are block coordinates
     */
    public void setHiddenRange(VisibilityLimit lim) {
        if (hidden_limits == null)
            hidden_limits = new ArrayList<VisibilityLimit>();
        hidden_limits.add(lim);
    }

    @Override
    public DynmapWorld getWorld() {
        return dw;
    }

    @Override
    public boolean setChunkDataTypes(boolean blockdata, boolean biome, boolean highestblocky, boolean rawbiome) {
        return true;
    }

    private static final String litStates[] = {"light", "spawn", "heightmaps", "full"};

    public GenericChunk parseChunkFromNBT(GenericNBTCompound orignbt) {
        GenericNBTCompound nbt = orignbt;
        if ((nbt != null) && nbt.contains("Level", GenericNBTCompound.TAG_COMPOUND)) {
            nbt = nbt.getCompound("Level");
        }
        if (nbt == null) return null;
        String status = nbt.getString("Status");
        int version = orignbt.getInt("DataVersion");
        boolean lit = nbt.getBoolean("isLightOn");
        boolean hasLitState = false;
        if (status != null) {
            for (int i = 0; i < litStates.length; i++) {
                if (status.equals(litStates[i])) {
                    hasLitState = true;
                }
            }
        }
        boolean hasLight = false; // pessimistic: only has light if we see it, due to WB and other flawed chunk generation hasLitState;	// Assume good light in a lit state

        // Start generic chunk builder
        GenericChunk.Builder bld = new GenericChunk.Builder(dw.minY, dw.worldheight);
        int x = nbt.getInt("xPos");
        int z = nbt.getInt("zPos");

        // Set chunk info
        bld.coords(x, z).chunkStatus(status).dataVersion(version);

        if (nbt.contains("InhabitedTime")) {
            bld.inhabitedTicks(nbt.getLong("InhabitedTime"));
        }
        // Check for 2D or old 3D biome data from chunk level: need these when we build old sections
        List<BiomeMap[]> old3d = null;    // By section, then YZX list
        BiomeMap[] old2d = null;
        if (nbt.contains("Biomes")) {
            int[] bb = nbt.getIntArray("Biomes");
            if (bb != null) {
                // If v1.15+ format
                if (bb.length > 256) {
                    old3d = new ArrayList<BiomeMap[]>();
                    // Get 4 x 4 x 4 list for each section
                    for (int sect = 0; sect < (bb.length / 64); sect++) {
                        BiomeMap smap[] = new BiomeMap[64];
                        for (int i = 0; i < 64; i++) {
                            smap[i] = BiomeMap.byBiomeID(bb[sect * 64 + i]);
                        }
                        old3d.add(smap);
                    }
                } else { // Else, older chunks
                    old2d = new BiomeMap[256];
                    for (int i = 0; i < bb.length; i++) {
                        old2d[i] = BiomeMap.byBiomeID(bb[i]);
                    }
                }
            }
        }
        // Start section builder
        GenericChunkSection.Builder sbld = new GenericChunkSection.Builder();
        /* Get sections */
        GenericNBTList sect = nbt.contains("sections") ? nbt.getList("sections", 10) : nbt.getList("Sections", 10);
        // And process sections
        for (int i = 0; i < sect.size(); i++) {
            GenericNBTCompound sec = sect.getCompound(i);
            int secnum = sec.getByte("Y");

            DynmapBlockState[] palette = null;
            // If we've got palette and block states list, process non-empty section
            if (sec.contains("Palette", 9) && sec.contains("BlockStates", 12)) {
                GenericNBTList plist = sec.getList("Palette", 10);
                long[] statelist = sec.getLongArray("BlockStates");
                palette = new DynmapBlockState[plist.size()];
                for (int pi = 0; pi < plist.size(); pi++) {
                    GenericNBTCompound tc = plist.getCompound(pi);
                    String pname = tc.getString("Name");
                    if (tc.contains("Properties")) {
                        StringBuilder statestr = new StringBuilder();
                        GenericNBTCompound prop = tc.getCompound("Properties");
                        for (String pid : prop.getAllKeys()) {
                            if (statestr.length() > 0) statestr.append(',');
                            statestr.append(pid).append('=').append(prop.getAsString(pid));
                        }
                        palette[pi] = DynmapBlockState.getStateByNameAndState(pname, statestr.toString());
                    }
                    if (palette[pi] == null) {
                        palette[pi] = DynmapBlockState.getBaseStateByName(pname);
                    }
                    if (palette[pi] == null) {
                        palette[pi] = DynmapBlockState.AIR;
                    }
                }
                int recsperblock = (4096 + statelist.length - 1) / statelist.length;
                int bitsperblock = 64 / recsperblock;
                GenericBitStorage db = null;
                DataBitsPacked dbp = null;
                try {
                    db = nbt.makeBitStorage(bitsperblock, 4096, statelist);
                } catch (Exception ex) {    // Handle legacy encoded
                    bitsperblock = (statelist.length * 64) / 4096;
                    dbp = new DataBitsPacked(bitsperblock, 4096, statelist);
                }
                if (bitsperblock > 8) {    // Not palette
                    for (int j = 0; j < 4096; j++) {
                        int v = (dbp != null) ? dbp.getAt(j) : db.get(j);
                        sbld.xyzBlockState(j & 0xF, (j & 0xF00) >> 8, (j & 0xF0) >> 4, DynmapBlockState.getStateByGlobalIndex(v));
                    }
                } else {
                    sbld.xyzBlockStatePalette(palette);    // Set palette
                    for (int j = 0; j < 4096; j++) {
                        int v = db != null ? db.get(j) : dbp.getAt(j);
                        sbld.xyzBlockStateInPalette(j & 0xF, (j & 0xF00) >> 8, (j & 0xF0) >> 4, (short) v);
                    }
                }
            } else if (sec.contains("block_states", GenericNBTCompound.TAG_COMPOUND)) {    // 1.18
                GenericNBTCompound block_states = sec.getCompound("block_states");
                // If we've got palette, process non-empty section
                if (block_states.contains("palette", GenericNBTCompound.TAG_LIST)) {
                    long[] statelist = block_states.contains("data", GenericNBTCompound.TAG_LONG_ARRAY) ? block_states.getLongArray("data") : new long[4096 / 64]; // Handle zero bit palette (all same)
                    GenericNBTList plist = block_states.getList("palette", GenericNBTCompound.TAG_COMPOUND);
                    palette = new DynmapBlockState[plist.size()];
                    for (int pi = 0; pi < plist.size(); pi++) {
                        GenericNBTCompound tc = plist.getCompound(pi);
                        String pname = tc.getString("Name");
                        if (tc.contains("Properties")) {
                            StringBuilder statestr = new StringBuilder();
                            GenericNBTCompound prop = tc.getCompound("Properties");
                            for (String pid : prop.getAllKeys()) {
                                if (statestr.length() > 0) statestr.append(',');
                                statestr.append(pid).append('=').append(prop.getAsString(pid));
                            }
                            palette[pi] = DynmapBlockState.getStateByNameAndState(pname, statestr.toString());
                        }
                        if (palette[pi] == null) {
                            palette[pi] = DynmapBlockState.getBaseStateByName(pname);
                        }
                        if (palette[pi] == null) {
                            palette[pi] = DynmapBlockState.AIR;
                        }
                    }
                    GenericBitStorage db = null;
                    DataBitsPacked dbp = null;

                    int bitsperblock = (statelist.length * 64) / 4096;
                    int expectedStatelistLength = (4096 + (64 / bitsperblock) - 1) / (64 / bitsperblock);
                    if (statelist.length == expectedStatelistLength) {
                        db = nbt.makeBitStorage(bitsperblock, 4096, statelist);
                    } else {
                        bitsperblock = (statelist.length * 64) / 4096;
                        dbp = new DataBitsPacked(bitsperblock, 4096, statelist);
                    }
                    //if (bitsperblock > 8) {    // Not palette
                    //	for (int j = 0; j < 4096; j++) {
                    //		int v = db != null ? db.get(j) : dbp.getAt(j);
                    //    	sbld.xyzBlockState(j & 0xF, (j & 0xF00) >> 8, (j & 0xF0) >> 4, DynmapBlockState.getStateByGlobalIndex(v));
                    //	}
                    //}
                    //else {
                    sbld.xyzBlockStatePalette(palette);    // Set palette
                    for (int j = 0; j < 4096; j++) {
                        int v = db != null ? db.get(j) : dbp.getAt(j);
                        sbld.xyzBlockStateInPalette(j & 0xF, (j & 0xF00) >> 8, (j & 0xF0) >> 4, (short) v);
                    }
                    //}
                }
            }
            if (sec.contains("BlockLight")) {
                sbld.emittedLight(sec.getByteArray("BlockLight"));
            }
            if (sec.contains("SkyLight")) {
                sbld.skyLight(sec.getByteArray("SkyLight"));
                hasLight = true;
            }
            // If section biome palette
            if (sec.contains("biomes")) {
                GenericNBTCompound nbtbiomes = sec.getCompound("biomes");
                long[] bdataPacked = nbtbiomes.getLongArray("data");
                GenericNBTList bpalette = nbtbiomes.getList("palette", 8);
                GenericBitStorage bdata = null;
                if (bdataPacked.length > 0) {
                    int valsPerLong = (64 / bdataPacked.length);
                    bdata = nbt.makeBitStorage((64 + valsPerLong - 1) / valsPerLong, 64, bdataPacked);
                }
                for (int j = 0; j < 64; j++) {
                    int b = bdata != null ? bdata.get(j) : 0;
                    sbld.xyzBiome(j & 0x3, (j & 0x30) >> 4, (j & 0xC) >> 2, BiomeMap.byBiomeResourceLocation(bpalette.getString(b)));
                }
            } else {    // Else, apply legacy biomes
                if (old3d != null) {
                    BiomeMap m[] = old3d.get((secnum > 0) ? ((secnum < old3d.size()) ? secnum : old3d.size() - 1) : 0);
                    if (m != null) {
                        for (int j = 0; j < 64; j++) {
                            sbld.xyzBiome(j & 0x3, (j & 0x30) >> 4, (j & 0xC) >> 2, m[j]);
                        }
                    }
                } else if (old2d != null) {
                    for (int j = 0; j < 256; j++) {
                        sbld.xzBiome(j & 0xF, (j & 0xF0) >> 4, old2d[j]);
                    }
                }
            }
            // Finish and add section
            bld.addSection(secnum, sbld.build());
            sbld.reset();
        }
        // Assume skylight is only trustworthy in a lit state
        if ((!hasLitState) || (!lit)) {
            hasLight = false;
        }
        // If no light, do simple generate
        if (!hasLight) {
            //Log.info(String.format("generateSky(%d,%d)", x, z));
            bld.generateSky();
        }
        return bld.build();
    }

}
