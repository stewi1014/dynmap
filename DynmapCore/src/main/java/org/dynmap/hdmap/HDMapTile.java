package org.dynmap.hdmap;

import org.dynmap.DynmapChunk;
import org.dynmap.DynmapWorld;
import org.dynmap.MapManager;
import org.dynmap.MapTile;
import org.dynmap.utils.MapChunkCache;

import java.util.List;

public class HDMapTile extends MapTile {
    public final HDPerspective perspective;
    public final int tx, ty;  /* Tile X and Tile Y are in tile coordinates (pixels/tile-size) */
    public final int boostzoom;
    public final int tilescale;

    public HDMapTile(DynmapWorld world, HDPerspective perspective, int tx, int ty, int boostzoom, int tilescale) {
        super(world);
        this.perspective = perspective;
        this.tx = tx;
        this.ty = ty;
        this.boostzoom = boostzoom;
        this.tilescale = tilescale;
    }

    // Used for restore of saved pending renders
    public HDMapTile(DynmapWorld world, String parm) throws Exception {
        super(world);

        String[] parms = parm.split(",");
        if (parms.length < 3) throw new Exception("wrong parameter count");
        this.tx = Integer.parseInt(parms[0]);
        this.ty = Integer.parseInt(parms[1]);
        this.perspective = MapManager.mapman.hdmapman.perspectives.get(parms[2]);
        if (this.perspective == null) throw new Exception("invalid perspective");
        if (parms.length > 3)
            this.boostzoom = Integer.parseInt(parms[3]);
        else
            this.boostzoom = 0;
        if (parms.length > 4) {
            this.tilescale = Integer.parseInt(parms[4]);
        } else {
            this.tilescale = 0;
        }
    }

    @Override
    protected String saveTileData() {
        return String.format("%d,%d,%s,%d,%d", tx, ty, perspective.getName(), boostzoom, tilescale);
    }

    @Override
    public int hashCode() {
        return tx ^ ty ^ perspective.hashCode() ^ world.hashCode() ^ boostzoom ^ tilescale;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof HDMapTile) {
            return equals((HDMapTile) obj);
        }
        return false;
    }

    public boolean equals(HDMapTile o) {
        return o.tx == tx && o.ty == ty && (perspective == o.perspective) && (o.world == world) && (o.boostzoom == boostzoom) && (o.tilescale == tilescale);
    }

    @Override
    public String toString() {
        return world.getName() + ":" + perspective.getName() + "," + tx + "," + ty + ":" + boostzoom + ":" + tilescale;
    }

    @Override
    public int getTileSize() {
        return 128 << tilescale;
    }

    @Override
    public boolean isBiomeDataNeeded() {
        return MapManager.mapman.hdmapman.isBiomeDataNeeded(this);
    }

    @Override
    public boolean isHightestBlockYDataNeeded() {
        return MapManager.mapman.hdmapman.isHightestBlockYDataNeeded(this);
    }

    @Override
    public boolean isRawBiomeDataNeeded() {
        return MapManager.mapman.hdmapman.isRawBiomeDataNeeded(this);
    }

    @Override
    public boolean isBlockTypeDataNeeded() {
        return MapManager.mapman.hdmapman.isBlockTypeDataNeeded(this);
    }

    public boolean render(MapChunkCache cache, String mapname) {
        return perspective.render(cache, this, mapname);
    }

    public List<DynmapChunk> getRequiredChunks() {
        return perspective.getRequiredChunks(this);
    }

    public MapTile[] getAdjecentTiles() {
        return perspective.getAdjecentTiles(this);
    }

    public int tileOrdinalX() {
        return tx;
    }

    public int tileOrdinalY() {
        return ty;
    }

}
