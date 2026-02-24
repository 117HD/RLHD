/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import javax.annotation.Nullable;
import lombok.Data;

@Data
public class EmitterPlacement {
	public int worldX;
	public int worldY;
	public int plane;
	@Nullable
	public String description;
	public String particleId;
}
