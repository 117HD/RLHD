/*
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
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
