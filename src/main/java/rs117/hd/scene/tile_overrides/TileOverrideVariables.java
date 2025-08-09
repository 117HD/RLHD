package rs117.hd.scene.tile_overrides;

import net.runelite.api.*;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.VariableSupplier;

public class TileOverrideVariables implements VariableSupplier {
	private final String[] HSL_VARS = { "h", "s", "l" };
	private final int[] hsl = new int[3];

	private Tile tile;
	private boolean requiresHslUpdate;

	public void setTile(Tile tile) {
		if (tile == this.tile)
			return;
		this.tile = tile;
		requiresHslUpdate = true;
	}

	@Override
	public Object get(String name) {
		for (int i = 0; i < HSL_VARS.length; i++) {
			if (HSL_VARS[i].equals(name)) {
				if (requiresHslUpdate) {
					HDUtils.getSouthWesternMostTileColor(hsl, tile);
					requiresHslUpdate = false;
				}
				return hsl[i];
			}
		}

		throw new IllegalArgumentException("Undefined variable '" + name + "'");
	}
}
