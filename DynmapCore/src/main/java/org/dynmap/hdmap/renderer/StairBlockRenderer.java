package org.dynmap.hdmap.renderer;

import org.dynmap.renderer.*;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

public class StairBlockRenderer extends CustomRenderer {
    private static final int TEXTURE_SIDES = 0;
    private static final int TEXTURE_TOP = 1;
    private static final int TEXTURE_BOTTOM = 2;
    private static BitSet stair_ids = new BitSet();

    // Array of meshes for normal steps - index = (data value & 7)
    private RenderPatch[][] stepmeshes = new RenderPatch[8][];
    // Array of meshes for 3/4 steps - index = (data value & 7), with extra one clockwise from normal step
    private RenderPatch[][] step_3_4_meshes = new RenderPatch[8][];
    // Array of meshes for 1/4 steps - index = (data value & 7), with clockwise quarter clopped from normal step
    private RenderPatch[][] step_1_4_meshes = new RenderPatch[8][];

    private int textsetcnt = 0;
    private String textindex = null;
    private String[] tilefields = null;
    private String[] texturemap;

    private void setID(String bname) {
        DynmapBlockState bbs = DynmapBlockState.getBaseStateByName(bname);
        if (bbs.isNotAir()) {
            for (int i = 0; i < bbs.getStateCount(); i++) {
                stair_ids.set(bbs.getState(i).globalStateIndex);
            }
        }
    }

    @Override
    public boolean initializeRenderer(RenderPatchFactory rpf, String blkname, BitSet blockdatamask, Map<String, String> custparm) {
        if (!super.initializeRenderer(rpf, blkname, blockdatamask, custparm))
            return false;
        setID(blkname);   /* Mark block as a stair */
        /* Build step meshes */
        for (int i = 0; i < 8; i++) {
            stepmeshes[i] = buildStepMeshes(rpf, i);
            step_1_4_meshes[i] = buildCornerStepMeshes(rpf, i);
            step_3_4_meshes[i] = buildIntCornerStepMeshes(rpf, i);
        }
        textindex = custparm.get("textureindex");
        if (textindex != null) {
            String cnt = custparm.get("texturecnt");
            if (cnt != null)
                textsetcnt = Integer.parseInt(cnt);
            else
                textsetcnt = 16;
            tilefields = new String[]{textindex};
            texturemap = new String[textsetcnt];
            for (int i = 0; i < textsetcnt; i++) {
                texturemap[i] = custparm.get("textmap" + i);
                if (texturemap[i] == null) {
                    texturemap[i] = Integer.toString(i);
                }
            }
        }
        return true;
    }

    @Override
    public int getMaximumTextureCount() {
        if (textsetcnt == 0)
            return 3;
        else
            return textsetcnt;
    }

    @Override
    public String[] getTileEntityFieldsNeeded() {
        return tilefields;
    }

    private static final int[] patchlist = {TEXTURE_BOTTOM, TEXTURE_TOP, TEXTURE_SIDES, TEXTURE_SIDES, TEXTURE_SIDES, TEXTURE_SIDES};

    private void addBox(RenderPatchFactory rpf, List<RenderPatch> list, double xmin, double xmax, double ymin, double ymax, double zmin, double zmax) {
        addBox(rpf, list, xmin, xmax, ymin, ymax, zmin, zmax, patchlist);
    }

    private RenderPatch[] buildStepMeshes(RenderPatchFactory rpf, int dat) {
        ArrayList<RenderPatch> list = new ArrayList<RenderPatch>();
        /* If inverted, add half top */
        if ((dat & 0x4) != 0) {
            addBox(rpf, list, 0, 1, 0.5, 1, 0, 1);
        } else {  // Else, add half bottom
            addBox(rpf, list, 0, 1, 0.0, 0.5, 0, 1);
        }
        switch (dat & 0x3) {
            case 0:
                addBox(rpf, list, 0.5, 1, 0, 1, 0, 1);
                break;
            case 1:
                addBox(rpf, list, 0, 0.5, 0, 1, 0, 1);
                break;
            case 2:
                addBox(rpf, list, 0, 1, 0, 1, 0.5, 1);
                break;
            case 3:
                addBox(rpf, list, 0, 1, 0, 1, 0, 0.5);
                break;
        }
        return list.toArray(new RenderPatch[list.size()]);
    }

    private RenderPatch[] buildCornerStepMeshes(RenderPatchFactory rpf, int dat) {
        ArrayList<RenderPatch> list = new ArrayList<RenderPatch>();
        /* If inverted, add half top */
        if ((dat & 0x4) != 0) {
            addBox(rpf, list, 0, 1, 0.5, 1, 0, 1);
        } else {  // Else, add half bottom
            addBox(rpf, list, 0, 1, 0.0, 0.5, 0, 1);
        }
        switch (dat & 0x3) {
            case 0:
                addBox(rpf, list, 0.5, 1, 0, 1, 0, 0.5);
                break;
            case 1:
                addBox(rpf, list, 0, 0.5, 0, 1, 0, 0.5);
                break;
            case 2:
                addBox(rpf, list, 0, 0.5, 0, 1, 0.5, 1);
                break;
            case 3:
                addBox(rpf, list, 0.5, 1, 0, 1, 0.5, 1);
                break;
        }
        return list.toArray(new RenderPatch[list.size()]);
    }

    private RenderPatch[] buildIntCornerStepMeshes(RenderPatchFactory rpf, int dat) {
        ArrayList<RenderPatch> list = new ArrayList<RenderPatch>();
        /* If inverted, add half top */
        if ((dat & 0x4) != 0) {
            addBox(rpf, list, 0, 1, 0.5, 1, 0, 1);
        } else {  // Else, add half bottom
            addBox(rpf, list, 0, 1, 0.0, 0.5, 0, 1);
        }
        switch (dat & 0x3) {
            case 0:
                addBox(rpf, list, 0.5, 1, 0, 1, 0, 1);
                addBox(rpf, list, 0, 0.5, 0, 1, 0, 0.5);
                break;
            case 1:
                addBox(rpf, list, 0.5, 1, 0, 1, 0, 1);
                addBox(rpf, list, 0, 0.5, 0, 1, 0.5, 1);
                break;
            case 2:
                addBox(rpf, list, 0, 0.5, 0, 1, 0, 1);
                addBox(rpf, list, 0.5, 1, 0, 1, 0, 0.5);
                break;
            case 3:
                addBox(rpf, list, 0, 0.5, 0, 1, 0, 1);
                addBox(rpf, list, 0.5, 1, 0, 1, 0.5, 1);
                break;
        }
        return list.toArray(new RenderPatch[list.size()]);
    }

    //  Steps
    // 0 = up to east
    // 1 = up to west
    // 2 = up to south
    // 3 = up to north
    //  Corners
    // 0 = NE
    // 1 = NW
    // 2 = SW
    // 3 = SE
    //  Interior Corners
    // 0 = open to SW
    // 1 = open to NW
    // 2 = open to SE
    // 3 = open to NE
    private static final int off_x[] = {1, -1, 0, 0, 1, -1, 0, 0};
    private static final int off_z[] = {0, 0, 1, -1, 0, 0, 1, -1};
    private static final int match1[] = {2, 3, 0, 1, 6, 7, 4, 5};
    private static final int corner1[] = {3, 1, 3, 1, 7, 5, 7, 5};
    private static final int icorner1[] = {1, 2, 1, 2, 5, 6, 5, 6};
    private static final int match2[] = {3, 2, 1, 0, 7, 6, 5, 4};
    private static final int corner2[] = {0, 2, 2, 0, 4, 6, 6, 4};
    private static final int icorner2[] = {0, 3, 3, 0, 4, 7, 7, 4};

    @Override
    public RenderPatch[] getRenderPatchList(MapDataContext ctx) {
        RenderPatch[] rp = getBaseRenderPatchList(ctx);
        if (textindex != null) {
            int idx = 0;
            Object o = ctx.getBlockTileEntityField(textindex);
            if (o instanceof Number) {
                idx = ((Number) o).intValue();
            } else if (o instanceof String) {
                String os = (String) o;
                for (int i = 0; i < texturemap.length; i++) {
                    if (os.equals(texturemap[i])) {
                        idx = i;
                        break;
                    }
                }
            }
            if ((idx < 0) || (idx >= textsetcnt)) {
                idx = 0;
            }
            RenderPatch[] rp2 = new RenderPatch[rp.length];
            for (int i = 0; i < rp.length; i++) {
                rp2[i] = ctx.getPatchFactory().getRotatedPatch(rp[i], 0, 0, 0, idx);
            }
            return rp2;
        } else {
            return rp;
        }
    }

    private RenderPatch[] getBaseRenderPatchList(MapDataContext ctx) {
        int data = ctx.getBlockType().stateIndex & 0x07;   /* Get block data */
        /* Check block behind stair */
        DynmapBlockState corner = ctx.getBlockTypeAt(off_x[data], 0, off_z[data]);
        if (stair_ids.get(corner.globalStateIndex)) {   /* If it is a stair */
            int cornerdat = corner.stateIndex & 0x07;
            if (cornerdat == match1[data]) {    /* If right orientation */
                /* Make sure we don't have matching stair to side */
                DynmapBlockState side = ctx.getBlockTypeAt(-off_x[cornerdat], 0, -off_z[cornerdat]);
                if ((!stair_ids.get(side.globalStateIndex)) || ((side.stateIndex & 0x07) != data)) {
                    return step_1_4_meshes[corner1[data]];
                }
            } else if (cornerdat == match2[data]) {   /* If other orientation */
                /* Make sure we don't have matching stair to side */
                DynmapBlockState side = ctx.getBlockTypeAt(-off_x[cornerdat], 0, -off_z[cornerdat]);
                if ((!stair_ids.get(side.globalStateIndex)) || ((side.stateIndex & 0x07) != data)) {
                    return step_1_4_meshes[corner2[data]];
                }
            }
        }
        /* Check block in front of stair */
        corner = ctx.getBlockTypeAt(-off_x[data], 0, -off_z[data]);
        if (stair_ids.get(corner.globalStateIndex)) {   /* If it is a stair */
            int cornerdat = corner.stateIndex & 0x07;
            if (cornerdat == match1[data]) {    /* If right orientation */
                /* Make sure we don't have matching stair to side */
                DynmapBlockState side = ctx.getBlockTypeAt(off_x[cornerdat], 0, off_z[cornerdat]);
                if ((!stair_ids.get(side.globalStateIndex)) || ((side.stateIndex & 0x07) != data)) {
                    return step_3_4_meshes[icorner1[data]];
                }
            } else if (cornerdat == match2[data]) {   /* If other orientation */
                /* Make sure we don't have matching stair to side */
                DynmapBlockState side = ctx.getBlockTypeAt(off_x[cornerdat], 0, off_z[cornerdat]);
                if ((!stair_ids.get(side.globalStateIndex)) || ((side.stateIndex & 0x07) != data)) {
                    return step_3_4_meshes[icorner2[data]];
                }
            }
        }

        return stepmeshes[data];
    }
}
