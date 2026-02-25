/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import java.util.List;
import javax.annotation.Nullable;

public class EmitterConfigEntry {
	@Nullable
	public int[][] placements;
	@Nullable
	public List<ObjectEmitterBinding> objectEmitters;
	@Nullable
	public String description;
	public String particleId;
}
