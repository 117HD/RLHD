package rs117.hd.utils;

import javax.annotation.Nullable;
import net.runelite.api.*;

import static rs117.hd.utils.HDUtils.clamp;

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
	// 117 HD custom types
	public static final int TYPE_PROJECTILE = 4;
	public static final int TYPE_GRAPHICS_OBJECT = 5;
	public static final int TYPE_UNKNOWN = 0xF;

	public static final int UNKNOWN_ID = 0xFFFFFF;

	public static final long SCENE_X_MASK = 0x7f;
	public static final long SCENE_Y_MASK = 0x7f << 7;
	public static final long TYPE_MASK = 3L << 14;
	public static final long ID_OR_INDEX_MASK = 0xffffffffL << 17;

	private static final String[] TYPE_NAMES = {
		"Player",
		"NPC",
		"Game Object",
		"Ground item",
		"Projectile",
		"Graphics object",
		"Unknown"
	};
	private static final String[] TYPE_NAMES_SHORT = {
		"PLR",
		"NPC",
		"OBJ",
		"ITM",
		"PRJ",
		"GFX",
		"N/A"
	};

	public static String getTypeName(int type) {
		return TYPE_NAMES[clamp(type, 0, TYPE_NAMES.length - 1)];
	}

	public static String getTypeNameShort(int type) {
		return TYPE_NAMES_SHORT[clamp(type, 0, TYPE_NAMES_SHORT.length - 1)];
	}

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
		return (int) ((hash >> TileObject.HASH_PLANE_SHIFT) & 3);
	}

	public static int getType(long hash) {
		return (int) ((hash & TYPE_MASK) >> 14);
	}

	public static int getIdOrIndex(long hash) {
		return (int) ((hash & ID_OR_INDEX_MASK) >>> 17);
	}

	/**
	 * Generate an identifier of a Renderable, consisting of the type and ID.
	 *
	 * @param client     RuneLite client instance
	 * @param hash       RuneLite draw call hash
	 * @param renderable the Renderable passed into the draw callback
	 * @return a combined identifier
	 */
	public static int generateUuid(Client client, long hash, @Nullable Renderable renderable) {
		int type = TYPE_UNKNOWN;
		int id = UNKNOWN_ID;

		if (hash == -1) {
			if (renderable instanceof Projectile) {
				type = TYPE_PROJECTILE;
				id = ((Projectile) renderable).getId();
			} else if (renderable instanceof GraphicsObject) {
				type = TYPE_GRAPHICS_OBJECT;
				id = ((GraphicsObject) renderable).getId();
			}
		} else {
			type = ModelHash.getType(hash);
			id = ModelHash.getIdOrIndex(hash);

			if (renderable instanceof DynamicObject) {
				var def = client.getObjectDefinition(id);
				if (def.getImpostorIds() != null) {
					var impostor = def.getImpostor();
					if (impostor != null)
						id = impostor.getId();
				}
			} else if (type == TYPE_NPC) {
				int index = id;
				id = UNKNOWN_ID;
				NPC[] npcs = client.getCachedNPCs();
				if (index >= 0 && index < npcs.length) {
					NPC npc = npcs[index];
					if (npc != null)
						id = npc.getId();
				}
			}
		}

		return packUuid(type, id);
	}

	/**
	 * Pack a type ID and object/NPC/projectile/other ID into an int for use with 117 HD functions.
	 *
	 * @param type ModelHash type ID
	 * @param id   object/NPC/projectile/other ID
	 * @return a combined identifier
	 */
	public static int packUuid(int type, int id) {
		return type << 24 | id;
	}

	public static int getUuidType(int uuid) {
		return uuid >> 24;
	}

	public static int getUuidId(int uuid) {
		return uuid & 0xFFFFFF;
	}
}
