package rs117.hd.data.materials;

import lombok.NonNull;
import net.runelite.api.*;
import rs117.hd.HdPlugin;

@FunctionalInterface
public interface TileOverrideResolver<T> {
	@NonNull
	T resolve(HdPlugin plugin, Scene scene, Tile tile, T override);
}
