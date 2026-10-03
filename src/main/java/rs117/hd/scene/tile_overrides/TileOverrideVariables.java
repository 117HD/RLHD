package rs117.hd.scene.tile_overrides;

import lombok.Getter;
import net.runelite.api.*;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.VariableSupplier;

public class TileOverrideVariables implements VariableSupplier {
	public enum Var {h, s, l}

	private final int[] hsl = new int[Var.values().length];

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
	public Object get(String name) { return getInt(Var.valueOf(name)); }

	@Override
	public int getInt(Enum<?> key) {
		if(Props.DEVELOPMENT && !(key instanceof Var))
			throw new IllegalArgumentException("Undefined variable '" + key + "'");

		if (requiresHslUpdate) {
			HDUtils.getSouthWesternMostTileColor(hsl, tile);
			requiresHslUpdate = false;
		}
		return hsl[key.ordinal()];
	}
}
