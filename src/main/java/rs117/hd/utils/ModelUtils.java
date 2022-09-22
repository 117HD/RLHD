package rs117.hd.utils;

import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;

public class ModelUtils {
    public static final int TYPE_PLAYER = 0;
    public static final int TYPE_NPC = 1;
    public static final int TYPE_OBJECT = 2;
    public static final int TYPE_GROUND_ITEM = 3;

    public static WorldPoint getWorldLocation(Client client, int x, int z) {
        int lx = x + client.getCameraX2();
        int lz = z + client.getCameraZ2();
        return WorldPoint.fromScene(client, lx / 128, lz / 128, 0);
    }

    /**
     * Objects it's the obj id
     * Npc's it's the npc index
     * Players it's the player index
     */
    public static int getIdOrIndex(long hash) {
        return (int) (hash >>> 17 & 0xffffffffL);
    }

    public static int getSceneX(long hash) {
        return (int) (hash & 0x7FL);
    }

    public static int getSceneY(long hash) {
        return (int) ((hash >> 7) & 0x7FL);
    }

    public static int getModelType(long hash) {
        return (int) ((hash >> 14) & 3);
    }
}
