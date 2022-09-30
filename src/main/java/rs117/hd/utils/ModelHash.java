package rs117.hd.utils;

import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;

public class ModelHash {
    /**
     * Model hashes are constructed as follows:
     * | 1111 1111 1111 111 | 1  1111 1111 1111 1111 1111 1111 1111 111 |   1 |   11 |    11 1111 1 |     111 1111 |
     * |     15 unused bits |                        32-bit id or index | ??? | type | 7-bit sceneY | 7-bit sceneX |
     *
     * type:
     * - 0 = player
     * - 1 = NPC
     * - 2 = object
     * - 3 = ground item
     *
     * id_or_index for different types:
     * - player = index
     * - NPC = index
     * - object = id
     * - ground item = ???
     */

    public static final int TYPE_PLAYER = 0;
    public static final int TYPE_NPC = 1;
    public static final int TYPE_OBJECT = 2;
    public static final int TYPE_GROUND_ITEM = 3;

    private static final long SCENE_X_MASK = 0x7f;
    private static final long SCENE_Y_MASK = 0x7f << 7;
    private static final long TYPE_MASK = 3L << 14;
    private static final long ID_OR_INDEX_MASK = 0xffffffffL << 17;

    public static long pack(int id_or_index, boolean bool, int type, int sceneY, int sceneX) {
        return (id_or_index & 0xffffffffL) << 17
            | (bool ? 1L : 0L) << 16
            | ((long) type & 0x3) << 14
            | (sceneY & 0x7f) << 7
            | (sceneX & 0x7f);
    }

    public static int getSceneX(long hash) {
        return (int) (hash & SCENE_X_MASK);
    }

    public static int getSceneY(long hash) {
        return (int) ((hash & SCENE_Y_MASK) >> 7);
    }

    public static int getType(long hash) {
        return (int) ((hash & TYPE_MASK) >> 14);
    }

    public static int getIdOrIndex(long hash) {
        return (int) ((hash & ID_OR_INDEX_MASK) >>> 17);
    }

    public static long getUuid(Client client, long hash) {
        int type = getType(hash);
        int idOrIndex = getIdOrIndex(hash);
        int id = idOrIndex;
        if (type == TYPE_NPC) {
            id = -1;
            NPC[] npcs = client.getCachedNPCs();
            if (idOrIndex >= 0 && idOrIndex < npcs.length) {
                NPC npc = npcs[idOrIndex];
                if (npc != null) {
                    id = npcs[idOrIndex].getId();
                }
            }
        }
        return packUuid(id, type);
    }

    public static long packUuid(int id, int type) {
        return pack(id, false, type, 0, 0);
    }

    public static WorldPoint getWorldLocation(Client client, int x, int z) {
        return WorldPoint.fromScene(client,
            (x + client.getCameraX2()) / 128,
            (z + client.getCameraZ2()) / 128,
            0);
    }
}
