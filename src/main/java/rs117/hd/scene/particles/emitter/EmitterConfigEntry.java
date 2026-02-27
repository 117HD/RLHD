/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import java.util.List;
import javax.annotation.Nullable;
import rs117.hd.scene.lights.Alignment;

public class EmitterConfigEntry {
	@Nullable
	public int[][] placements;
	@Nullable
	public List<ObjectBinding> objectEmitters;
	@Nullable
	public String description;
	public String particleId;

	/** Object-specific emitter config. JSON: { "object": "...", "offsetX": 0, "offsetY": 0, "offsetZ": 0, "alignment": "CUSTOM" } */
	public static class ObjectBinding {
		@Nullable
		public String object;
		public int offsetX;
		public int offsetY;
		public int offsetZ;
		@Nullable
		public Alignment alignment;
		/** Set when loading from parent entry (entry.particleId). */
		@Nullable
		public String particleId;

		public Alignment getAlignment() {
			return alignment != null ? alignment : Alignment.CUSTOM;
		}
	}
}
