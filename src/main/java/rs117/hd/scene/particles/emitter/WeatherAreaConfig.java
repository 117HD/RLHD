/*
 * Copyright (c) 2025
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import java.util.List;
import lombok.Value;
import rs117.hd.scene.areas.AABB;

/** Resolved weather area config: AABBs from area names + particle IDs. Used for debug overlay. */
@Value
public class WeatherAreaConfig {
	List<AABB> aabbs;
	List<String> particleIds;
	/** Tiles from edge (inside) to fade to 0. 0 = no fade. */
	int edgeFadeInside;
	/** Tiles from edge (outside) for margin. 0 = no outside. */
	int edgeFadeOutside;
}
