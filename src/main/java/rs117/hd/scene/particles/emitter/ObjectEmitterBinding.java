/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;
import rs117.hd.scene.lights.Alignment;

/**
 * Single config + runtime type for object emitters.
 * JSON: { "object": "FAI_FALADOR_HOUSE_TORCH", "offsetX": 0, "offsetY": 0, "offsetZ": 0, "alignment": "CUSTOM" }
 * particleId is set when loading from the parent entry (entry.particleId), since it's not repeated per object.
 */
@Getter
@Setter
public class ObjectEmitterBinding {
	@Nullable
	private String object;
	private int offsetX;
	private int offsetY;
	private int offsetZ;
	@Nullable
	private Alignment alignment;
	@Nullable
	private String particleId;

	public Alignment getAlignment() {
		return alignment != null ? alignment : Alignment.CUSTOM;
	}
}
