/*
 * Copyright (c) 2026
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import java.util.List;
import lombok.Value;
import rs117.hd.scene.areas.AABB;

/**
 * Procedural weather: single virtual emitter per AABB spawning in a cylinder footprint.
 */
@Value
public class WeatherCylinderConfig {
	AABB aabb;
	List<String> particleIds;
	float particlesPerTile;
}

