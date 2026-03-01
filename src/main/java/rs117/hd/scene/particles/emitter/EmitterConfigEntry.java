/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import java.util.List;
import javax.annotation.Nullable;
import lombok.Data;
import lombok.Getter;
import rs117.hd.scene.lights.Alignment;

@Data
public class EmitterConfigEntry {
	@Nullable
	public int[][] placements;
	@Nullable
	public List<ObjectBinding> objectEmitters;
	@Nullable
	public String description;
	public String particleId;

	/** Multiple particle IDs (overrides particleId when set). */
	@Nullable
	public List<String> particleIds;

	/** Weather area names from areas.json. Resolved to AABBs for each particleId. */
	@Nullable
	public List<String> weatherAreas;

	/** Tiles from edge (inside) to fade particles to 0 alpha. 0 = no inside fade. */
	public int edgeFadeInside;

	/** Tiles from edge (outside) for margin fade. 0 = no outside margin. Default 2. */
	public int edgeFadeOutside = 2;

	/** Emitter every N tiles. E.g. 10 = 1 emitter per 10 tiles. 0 = no cap (use full placement). */
	public int weatherEveryNTiles;

	/** Grid spacing for weather placements (tiles between emitters). Default 3. */
	public int weatherSpacing = 3;

	/** Object-specific emitter config. JSON: { "object": "...", "offsetX": 0, "offsetY": 0, "offsetZ": 0, "alignment": "CUSTOM" } */
	@Data
	public static class ObjectBinding {
		@Nullable
		public String object;
		public int offsetX;
		public int offsetY;
		public int offsetZ;
		@Nullable
		@Getter(lombok.AccessLevel.NONE)
		public Alignment alignment;
		/** Set when loading from parent entry (entry.particleId). */
		@Nullable
		public String particleId;

		public Alignment getAlignment() {
			return alignment != null ? alignment : Alignment.CUSTOM;
		}
	}
}
