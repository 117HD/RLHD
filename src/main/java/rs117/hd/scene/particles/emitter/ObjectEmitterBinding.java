/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Binding of particleId to an object type with optional position offset (local units). */
@Getter
@AllArgsConstructor
public class ObjectEmitterBinding {
	private final String particleId;
	private final int offsetX;
	private final int offsetY;
	private final int offsetZ;
}
