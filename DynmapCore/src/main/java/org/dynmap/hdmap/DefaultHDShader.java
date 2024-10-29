package org.dynmap.hdmap;

import org.dynmap.*;
import org.dynmap.common.BiomeMap;
import org.dynmap.common.DynmapCommandSender;
import org.dynmap.exporter.OBJExport;
import org.dynmap.renderer.DynmapBlockState;
import org.dynmap.utils.BlockStep;
import org.dynmap.utils.DynLongHashMap;
import org.dynmap.utils.MapChunkCache;
import org.dynmap.utils.MapIterator;
import org.json.simple.JSONObject;

import java.io.IOException;

import static org.dynmap.JSONUtils.s;

public class DefaultHDShader implements HDShader {
    private String name;
    protected ColorScheme colorScheme;

    protected boolean transparency; /* Is transparency support active? */

    public enum BiomeColorOption {
        NONE, BIOME, TEMPERATURE, RAINFALL
    }

    protected BiomeColorOption biomecolored = BiomeColorOption.NONE; /* Use biome for coloring */

    public DefaultHDShader(DynmapCore core, ConfigurationNode configuration) {
        name = (String) configuration.get("name");
        colorScheme = ColorScheme.getScheme(core, configuration.getString("colorscheme", "default"));
        transparency = configuration.getBoolean("transparency", true);  /* Default on */
        String biomeopt = configuration.getString("biomecolored", "none");
        if (biomeopt.equals("biome")) {
            biomecolored = BiomeColorOption.BIOME;
        } else if (biomeopt.equals("temperature")) {
            biomecolored = BiomeColorOption.TEMPERATURE;
        } else if (biomeopt.equals("rainfall")) {
            biomecolored = BiomeColorOption.RAINFALL;
        } else {
            biomecolored = BiomeColorOption.NONE;
        }
    }

    @Override
    public boolean isBiomeDataNeeded() {
        return biomecolored == BiomeColorOption.BIOME;
    }

    @Override
    public boolean isRawBiomeDataNeeded() {
        return (biomecolored == BiomeColorOption.RAINFALL) || (biomecolored == BiomeColorOption.TEMPERATURE);
    }

    @Override
    public boolean isHightestBlockYDataNeeded() {
        return false;
    }

    @Override
    public boolean isBlockTypeDataNeeded() {
        return true;
    }

    @Override
    public boolean isSkyLightLevelNeeded() {
        return false;
    }

    @Override
    public boolean isEmittedLightLevelNeeded() {
        return false;
    }

    @Override
    public String getName() {
        return name;
    }

    private class OurShaderState implements HDShaderState {
        private Color color[];
        protected MapIterator mapiter;
        protected HDMap map;
        private Color tmpcolor[];
        private int pixelodd;
        private HDLighting lighting;
        final int[] lightingTable;

        private OurShaderState(MapIterator mapiter, HDMap map, MapChunkCache cache) {
            this.mapiter = mapiter;
            this.map = map;
            this.lighting = map.getLighting();
            if (lighting.isNightAndDayEnabled()) {
                color = new Color[]{new Color(), new Color()};
                tmpcolor = new Color[]{new Color(), new Color()};
            } else {
                color = new Color[]{new Color()};
                tmpcolor = new Color[]{new Color()};
            }
            if (MapManager.mapman.useBrightnessTable()) {
                lightingTable = cache.getWorld().getBrightnessTable();
            } else {
                lightingTable = null;
            }
        }

        /**
         * Get our shader
         */
        public HDShader getShader() {
            return DefaultHDShader.this;
        }

        /**
         * Get our map
         */
        public HDMap getMap() {
            return map;
        }

        /**
         * Get our lighting
         */
        public HDLighting getLighting() {
            return lighting;
        }

        /**
         * Reset renderer state for new ray
         */
        public void reset(HDPerspectiveState ps) {
            for (int i = 0; i < color.length; i++)
                color[i].setTransparent();
            pixelodd = (ps.getPixelX() & 0x3) + (ps.getPixelY() << 1);
        }

        protected Color[] getBlockColors(DynmapBlockState block) {
            int idx = block.globalStateIndex;
            if (colorScheme.colors.length > idx) {
                return colorScheme.colors[idx];
            }
            return null;
        }

        /**
         * Process next ray step - called for each block on route
         *
         * @return true if ray is done, false if ray needs to continue
         */
        public boolean processBlock(HDPerspectiveState ps) {
            int i;
            DynmapBlockState blocktype = ps.getBlockState();
            if (blocktype.isAir())
                return false;
            Color[] colors = getBlockColors(blocktype);

            if (colors != null) {
                int seq;
                int subalpha = ps.getSubmodelAlpha();
                /* Figure out which color to use */
                switch (ps.getLastBlockStep()) {
                    case X_PLUS:
                    case X_MINUS:
                        seq = 2;
                        break;
                    case Z_PLUS:
                    case Z_MINUS:
                        seq = 0;
                        break;
                    default:
                        //if(subalpha >= 0)	/* We hit a block in a model */
                        //	seq = 4;	/* Use smooth top */
                        //else
                        if (((pixelodd + mapiter.getY()) & 0x03) == 0)
                            seq = 3;
                        else
                            seq = 1;
                        break;
                }
                Color c = colors[seq];
                if (c.getAlpha() > 0) {
                    /* Handle light level, if needed */
                    lighting.applyLighting(ps, this, c, tmpcolor);
                    /* If we got alpha from subblock model, use it instead */
                    if (subalpha >= 0) {
                        for (int j = 0; j < tmpcolor.length; j++)
                            tmpcolor[j].setAlpha(Math.max(subalpha, tmpcolor[j].getAlpha()));
                    }
                    /* Blend color with accumulated color (weighted by alpha) */
                    if (!transparency) {  /* No transparency support */
                        for (i = 0; i < color.length; i++)
                            color[i].setARGB(tmpcolor[i].getARGB() | 0xFF000000);
                        return true;    /* We're done */
                    }
                    /* If no previous color contribution, use new color */
                    else if (color[0].isTransparent()) {
                        for (i = 0; i < color.length; i++)
                            color[i].setColor(tmpcolor[i]);
                        return (color[0].getAlpha() == 255);
                    }
                    /* Else, blend and generate new alpha */
                    else {
                        int alpha = color[0].getAlpha();
                        int alpha2 = tmpcolor[0].getAlpha() * (255 - alpha) / 255;
                        int talpha = alpha + alpha2;
                        for (i = 0; i < color.length; i++)
                            color[i].setRGBA((tmpcolor[i].getRed() * alpha2 + color[i].getRed() * alpha) / talpha,
                                    (tmpcolor[i].getGreen() * alpha2 + color[i].getGreen() * alpha) / talpha,
                                    (tmpcolor[i].getBlue() * alpha2 + color[i].getBlue() * alpha) / talpha, talpha);
                        return (talpha >= 254);   /* If only one short, no meaningful contribution left */
                    }
                }
            }
            return false;
        }

        /**
         * Ray ended - used to report that ray has exited map (called if renderer has not reported complete)
         */
        public void rayFinished(HDPerspectiveState ps) {
        }

        /**
         * Get result color - get resulting color for ray
         *
         * @param c     - object to store color value in
         * @param index - index of color to request (renderer specific - 0=default, 1=day for night/day renderer
         */
        public void getRayColor(Color c, int index) {
            c.setColor(color[index]);
        }

        /**
         * Clean up state object - called after last ray completed
         */
        public void cleanup() {
        }

        @Override
        public DynLongHashMap getCTMTextureCache() {
            return null;
        }

        @Override
        public int[] getLightingTable() {
            return lightingTable;
        }

        @Override
        public void setLastBlockState(DynmapBlockState new_lastbs) {
        }
    }

    private class OurBiomeShaderState extends OurShaderState {
        private OurBiomeShaderState(MapIterator mapiter, HDMap map, MapChunkCache cache) {
            super(mapiter, map, cache);
        }

        @Override
        protected Color[] getBlockColors(DynmapBlockState blk) {
            BiomeMap bio = mapiter.getBiome();
            if (bio != null)
                return colorScheme.biomecolors[bio.ordinal()];
            return null;
        }
    }

    private class OurBiomeRainfallShaderState extends OurShaderState {
        private OurBiomeRainfallShaderState(MapIterator mapiter, HDMap map, MapChunkCache cache) {
            super(mapiter, map, cache);
        }

        @Override
        protected Color[] getBlockColors(DynmapBlockState blk) {
            return colorScheme.getRainColor(mapiter.getBiome().getRainfall());
        }
    }

    private class OurBiomeTempShaderState extends OurShaderState {
        private OurBiomeTempShaderState(MapIterator mapiter, HDMap map, MapChunkCache cache) {
            super(mapiter, map, cache);
        }

        @Override
        protected Color[] getBlockColors(DynmapBlockState blk) {
            return colorScheme.getTempColor(mapiter.getBiome().getTemperature());
        }
    }

    /**
     * Get renderer state object for use rendering a tile
     *
     * @param map     - map being rendered
     * @param cache   - chunk cache containing data for tile to be rendered
     * @param mapiter - iterator used when traversing rays in tile
     * @param scale   - scale of the perspecitve
     * @return state object to use for all rays in tile
     */
    @Override
    public HDShaderState getStateInstance(HDMap map, MapChunkCache cache, MapIterator mapiter, int scale) {
        switch (biomecolored) {
            case NONE:
                return new OurShaderState(mapiter, map, cache);
            case BIOME:
                return new OurBiomeShaderState(mapiter, map, cache);
            case RAINFALL:
                return new OurBiomeRainfallShaderState(mapiter, map, cache);
            case TEMPERATURE:
                return new OurBiomeTempShaderState(mapiter, map, cache);
        }
        return null;
    }

    /* Add shader's contributions to JSON for map object */
    public void addClientConfiguration(JSONObject mapObject) {
        s(mapObject, "shader", name);
    }

    @Override
    public void exportAsMaterialLibrary(DynmapCommandSender sender, OBJExport out) throws IOException {
        throw new IOException("Export unsupported");
    }

    private static final String[] nulllist = new String[0];

    @Override
    public String[] getCurrentBlockMaterials(DynmapBlockState blk, MapIterator mapiter, int[] txtidx,
                                             BlockStep[] steps) {
        return nulllist;
    }
}
