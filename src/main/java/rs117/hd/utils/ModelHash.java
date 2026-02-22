package rs117.hd.utils;

import java.util.Objects;
import javax.annotation.Nullable;
import net.runelite.api.*;

import static rs117.hd.utils.MathUtils.*;

public class ModelHash {
	// Model hashes are composed as follows:
	// | 12-bit worldView | 32-bit id or index | 1-bit wall | 3-bit type | 2-bit plane | 7-bit sceneY | 7-bit sceneX |
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

	// 117 HD UUID types
	public static final int TYPE_PROJECTILE = 4;
	public static final int TYPE_GRAPHICS_OBJECT = 5;
	public static final int TYPE_UNKNOWN = 0xF;

	public static final int CATEGORY_PLAYER        = 0;
	public static final int CATEGORY_NPC_FRIENDLY  = 1;
	public static final int CATEGORY_HOSTILE = 2;
	public static final int CATEGORY_OBJECT        = 3;
	public static final int CATEGORY_PROJECTILE    = 4;
	public static final int CATEGORY_GRAPHICS      = 5;
	public static final int CATEGORY_PLAYER_OTHER  = 6;
	public static final int CATEGORY_GROUND_ITEM   = 7;
	public static final int CATEGORY_UNKNOWN       = 15;

	// 117 HD UUID sub object types
	public static final int TYPE_WALL_OBJECT = 1 << 4 | TYPE_OBJECT;
	public static final int TYPE_GROUND_OBJECT = 2 << 4 | TYPE_OBJECT;
	public static final int TYPE_DECORATIVE_OBJECT = 3 << 4 | TYPE_OBJECT;
	public static final int TYPE_GAME_OBJECT = 4 << 4 | TYPE_OBJECT;

	public static final int UNKNOWN_ID = 0xFFFFFF;

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

	public static int getSceneX(long hash) {
		return (int) (hash & 0x7f);
	}

	public static int getSceneY(long hash) {
		return (int) (hash >> 7 & 0x7f);
	}

	public static int getPlane(long hash) {
		return (int) (hash >> TileObject.HASH_PLANE_SHIFT & 3);
	}

	public static int getType(long hash) {
		return (int) (hash >> 16 & 7);
	}

	public static int getIdOrIndex(long hash) {
		return (int) (hash >> 20);
	}

	public static boolean isTemporaryObject(long hash) {
		return getIdOrIndex(hash) == 0xFFFFFFFF;
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
				var def = ((DynamicObject) renderable).getRecordedObjectComposition();
				if (def.getImpostorIds() != null) {
					var impostor = def.getImpostor();
					if (impostor != null)
						id = impostor.getId();
				}
			} else if (type == TYPE_NPC) {
				int index = id;
				id = UNKNOWN_ID;
				if (renderable instanceof NPC) {
					id = ((NPC) renderable).getId();
				} else if (client.isClientThread()) {
					var npcs = client.getTopLevelWorldView().npcs();
					if (index >= 0 && index < 65536) {
						NPC npc = npcs.byIndex(index);
						if (npc != null)
							id = npc.getId();
					}
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
		return uuid >> 24 & 0xF;
	}

	public static int getUuidSubType(int uuid) {
		return uuid >> 24;
	}

	public static int getUuidWithoutSubType(int uuid) {
		return uuid & ~0xF0000000;
	}

	public static int getUuidId(int uuid) {
		return uuid & 0xFFFFFF;
	}

	/**
	 * Returns a category id (0-15) derived from the UUID type for coloring.
	 */
	public static int getCategoryForUuid(Client client,Renderable renderable, long hash, boolean rs3HighContrast) {
		return getCategoryForUuid(client, renderable, hash, false, rs3HighContrast);
	}

	/**
	 * Returns a small category id (0-15) derived from the UUID type for coloring.
	 */
	public static int getCategoryForUuid(Client client, Renderable renderable ,long hash, boolean hasActions, boolean rs3HighContrast) {
		if (!rs3HighContrast) {
			return CATEGORY_UNKNOWN;
		}

		if (hash == -1) {
			if (renderable instanceof Projectile) {
				Projectile projectile = (Projectile) renderable;
				return projectile.getTargetActor() == client.getLocalPlayer() ? CATEGORY_HOSTILE : CATEGORY_PROJECTILE;
			} else if (renderable instanceof GraphicsObject) {
				return CATEGORY_GRAPHICS;
			}
		}

		int category = CATEGORY_UNKNOWN;
		int type;
		int id;

		if (hash == -1) {
			return hasActions ? CATEGORY_OBJECT : CATEGORY_UNKNOWN;
		}

		type = getType(hash);

		if (type == TYPE_OBJECT) {
			category = hasActions ? CATEGORY_OBJECT : CATEGORY_UNKNOWN;
		} else if (type == TYPE_NPC) {
			id = ModelHash.getIdOrIndex(hash);
			var npcs = client.getTopLevelWorldView().npcs();
			if (id >= 0 && id < 65536) {
				NPC npc = npcs.byIndex(id);
				if (npc != null) {
					category = npc.getCombatLevel() != 0 ? CATEGORY_HOSTILE : CATEGORY_NPC_FRIENDLY;
				}
			}

		} else if (type == TYPE_PLAYER) {
			id = ModelHash.getIdOrIndex(hash);
			var players = client.getTopLevelWorldView().players();
			if (id >= 0 && id < 65536) {
				Player player = players.byIndex(id);
				if (player != null) {
					category = Objects.equals(player.getName(), client.getLocalPlayer().getName()) ? CATEGORY_PLAYER : CATEGORY_PLAYER_OTHER;
				}
			}
		} else if (type == TYPE_GROUND_ITEM) {
			category = CATEGORY_GROUND_ITEM;
		}

		return category;

	}
}
