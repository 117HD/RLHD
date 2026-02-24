/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import javax.annotation.Nullable;
import lombok.Data;

/**
 * Per-object emitter entry with optional offset from the object's position (like lights).
 * Offsets are in local/scene units (same as tile coordinates).
 * JSON can use "offset": [ x, height, z ] (same order as lights) or separate offsetX, offsetY, offsetZ.
 */
@Data
public class ObjectEmitterProperty {
	/** Object gameval name (e.g. "FAI_FALADOR_HOUSE_TORCH"). */
	@Nullable
	public String object;
	/** Offset from object position in local X (same units as scene). */
	public int offsetX;
	/** Offset from object position in local Z (same units as scene). */
	public int offsetY;
	/** Offset from object position in height (local Y / vertical). */
	public int offsetZ;

	/** Set all offsets from lights-style array: [ x, height, z ]. */
	public void setOffset(int[] offset) {
		if (offset != null && offset.length >= 3) {
			this.offsetX = offset[0];
			this.offsetZ = offset[1];
			this.offsetY = offset[2];
		}
	}
}
