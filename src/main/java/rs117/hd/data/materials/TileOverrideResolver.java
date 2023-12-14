package rs117.hd.data.materials;

import javax.annotation.Nullable;
import net.runelite.api.*;
import rs117.hd.HdPlugin;

@FunctionalInterface
public interface TileOverrideResolver<T> {
	@Nullable
	T resolve(HdPlugin plugin, Scene scene, Tile tile, T override);
}
