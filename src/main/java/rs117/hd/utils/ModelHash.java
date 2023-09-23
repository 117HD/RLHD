package rs117.hd.utils;

import net.runelite.api.*;

public class ModelHash {
	// Model hashes are composed as follows:
	// | 1111 1111 1111 1 |    11 | 1  1111 1111 1111 1111 1111 1111 1111 111 |               1 |   11 |    11 1111 1 |     111 1111 |
	// |   13 unused bits | plane |                        32-bit id or index | right-clickable | type | 7-bit sceneY | 7-bit sceneX |
	//
	// type:
	// - 0 = player
	// - 1 = NPC
	// - 2 = object
	// - 3 = ground item
	//
	// id_or_index for different types:
	// - player = index
	// - NPC = index
	// - object = id
	// - ground item = always zero

	public static final int TYPE_PLAYER = 0;
	public static final int TYPE_NPC = 1;
	public static final int TYPE_OBJECT = 2;
	public static final int TYPE_GROUND_ITEM = 3;

	private static final long SCENE_X_MASK = 0x7f;
	private static final long SCENE_Y_MASK = 0x7f << 7;
	private static final long TYPE_MASK = 3L << 14;
	private static final long ID_OR_INDEX_MASK = 0xffffffffL << 17;
	private static final long PLANE_MASK = 3L << 49;

	public static long pack(int idOrIndex, boolean rightClickable, int type, int sceneY, int sceneX) {
		return
			(idOrIndex & 0xffffffffL) << 17
			| (rightClickable ? 1L : 0L) << 16
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

	public static int getPlane(long hash) {
		return (int) ((hash & PLANE_MASK) >> 49);
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
}
