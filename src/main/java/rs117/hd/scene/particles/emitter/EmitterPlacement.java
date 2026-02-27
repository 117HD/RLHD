/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import javax.annotation.Nullable;
import lombok.Value;

/** A single world placement for a particle emitter (worldX, worldY, plane + particleId). */
@Value
public class EmitterPlacement {
	int worldX;
	int worldY;
	int plane;
	@Nullable
	String particleId;
}
