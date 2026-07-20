package rs117.hd.scene.tile_overrides;

import lombok.Getter;
import net.runelite.api.*;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.VariableSupplier;

public class TileOverrideVariables implements VariableSupplier {
	private final int[] hsl = new int[3];

	@Getter
	private Tile tile;
	private boolean requiresHslUpdate;

	public TileOverrideVariables setTile(Tile tile) {
		if (tile == this.tile)
			return this;
		this.tile = tile;
		requiresHslUpdate = true;
		return this;
	}

	@Override
	public Object get(String name) {
		return getInt(name);
	}

	@Override
	public int getInt(String name) {
		if(name.length() == 1) {
			final int idx;
			switch (name.charAt(0)) {
				case 'h':
					idx = 0;
					break;
				case 's':
					idx = 1;
					break;
				case 'l':
					idx = 2;
					break;
				default:
					idx = -1;
					break;
			}

			if(idx != -1) {
				if (requiresHslUpdate) {
					HDUtils.getSouthWesternMostTileColor(hsl, tile);
					requiresHslUpdate = false;
				}
				return hsl[idx];
			}
		}

		throw new IllegalArgumentException("Undefined variable '" + name + "'");
	}
}
