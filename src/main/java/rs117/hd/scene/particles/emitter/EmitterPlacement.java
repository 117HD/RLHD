/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import javax.annotation.Nullable;

/** A single world placement for a particle emitter (worldX, worldY, plane + particleId). */
public final class EmitterPlacement {
	public final int worldX;
	public final int worldY;
	public final int plane;
	@Nullable
	public final String particleId;

	public EmitterPlacement(int worldX, int worldY, int plane, @Nullable String particleId) {
		this.worldX = worldX;
		this.worldY = worldY;
		this.plane = plane;
		this.particleId = particleId;
	}
}
