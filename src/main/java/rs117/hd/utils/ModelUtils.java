package rs117.hd.utils;

import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;

enum ModelType {
    PLAYER,
    NPC,
    OBJECT,
    GROUND_ITEM
}

public class ModelUtils {
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
    public static int getID(long hash) {
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

    public static ModelType getType(long hash) {
        return ModelType.values()[getModelType(hash)];
    }
}
