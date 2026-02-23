/*
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import java.util.List;
import javax.annotation.Nullable;

public class EmitterConfigEntry {
	@Nullable
	public int[][] placements;
	@Nullable
	public List<String> objectEmitters;
	@Nullable
	public String description;
	public String particleId;
}
