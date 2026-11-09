package rs117.hd.overlays;

import javax.annotation.Nullable;

/**
 * What RuneLite's API doesn't expose about how an item's icon is drawn, read from the item's definition in the cache.
 * An opcode it doesn't know leaves the item without a native icon, until it's added here.
 */
public class ItemIconDefinition {
	public int model = -1;
	public int zoom2d = 2000;
	public int xOffset2d;
	public int yOffset2d;
	public int resizeX = 128;
	public int resizeY = 128;
	public int resizeZ = 128;
	@Nullable
	public int[] countObj;
	@Nullable
	public int[] countCo;
	public int boughtTemplateId = -1;

	private final byte[] data;
	private int position;

	private ItemIconDefinition(byte[] data) {
		this.data = data;
	}

	@Nullable
	public static ItemIconDefinition decode(@Nullable byte[] data) {
		if (data == null)
			return null;
		var definition = new ItemIconDefinition(data);
		try {
			return definition.decode() ? definition : null;
		} catch (ArrayIndexOutOfBoundsException ex) {
			return null;
		}
	}

	// The item whose model a stack of this many shows, like the game picks it
	public int stackItem(int itemId, int quantity) {
		int stackItemId = itemId;
		if (quantity > 1 && countObj != null)
			for (int i = 0; i < countObj.length; i++)
				if (countCo[i] != 0 && quantity >= countCo[i])
					stackItemId = countObj[i];
		return stackItemId;
	}

	private boolean decode() {
		while (true) {
			int opcode = u8();
			if (opcode == 0)
				return position == data.length;
			if (opcode >= 30 && opcode < 40) {
				string();
			} else if (opcode >= 100 && opcode < 110) {
				if (countObj == null) {
					countObj = new int[10];
					countCo = new int[10];
				}
				countObj[opcode - 100] = u16();
				countCo[opcode - 100] = u16();
			} else {
				switch (opcode) {
					case 1:
						model = u16();
						break;
					case 4:
						zoom2d = u16();
						break;
					case 7:
						xOffset2d = (short) u16();
						break;
					case 8:
						yOffset2d = (short) u16();
						break;
					case 44:
						model = s32();
						break;
					case 110:
						resizeX = u16();
						break;
					case 111:
						resizeY = u16();
						break;
					case 112:
						resizeZ = u16();
						break;
					case 140:
						boughtTemplateId = u16();
						break;
					case 11: case 15: case 16: case 65: case 160: case 251:
						break;
					case 13: case 14: case 27: case 42: case 113: case 114: case 115:
						position += 1;
						break;
					case 5: case 6: case 24: case 26: case 75: case 78: case 79: case 90: case 91: case 92: case 93: case 94: case 95:
					case 97: case 98: case 99: case 139: case 148: case 149:
						position += 2;
						break;
					case 23: case 25:
						position += 3;
						break;
					case 12: case 46: case 47: case 49: case 50: case 51: case 52: case 53: case 54:
						position += 4;
						break;
					case 45: case 48:
						position += 5;
						break;
					case 2: case 3: case 9:
						string();
						break;
					case 40: case 41:
						skip(4 * u8());
						break;
					case 43:
						u8();
						while (u8() != 0)
							string();
						break;
					case 161:
						skip(2 * u16());
						break;
					case 200:
						position += 2;
						string();
						break;
					case 201:
						position += 13;
						string();
						break;
					case 202:
						position += 15;
						string();
						break;
					case 249:
						for (int i = u8(); i > 0; i--) {
							int type = u8();
							position += 3;
							if (type == 1)
								string();
							else
								position += type == 2 ? 8 : 4;
						}
						break;
					default:
						return false;
				}
			}
			if (position > data.length)
				return false;
		}
	}

	private int u8() {
		return data[position++] & 0xFF;
	}

	private int u16() {
		return u8() << 8 | u8();
	}

	private int s32() {
		return u16() << 16 | u16();
	}

	// Not position += ..., which would read the position before the count moved it on
	private void skip(int bytes) {
		position += bytes;
	}

	private void string() {
		while (data[position++] != 0) ;
	}
}
