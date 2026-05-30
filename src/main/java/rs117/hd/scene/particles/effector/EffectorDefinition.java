/*
 * Copyright (c) 2026
 * All rights reserved.
 */
package rs117.hd.scene.particles.effector;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;

public class EffectorDefinition {
	@Nullable
	public String id;

	@SerializedName(value = "placements", alternate = { "placments" })
	@Nullable
	public int[][] placements;

	public float radiusTiles;

	public int heightOffset;

	public float debugRadiusTiles = 10f;

	public int scope;

	public List<EffectorEffectSpec> effects = Collections.emptyList();

	public transient long maxRangeSq;
	public transient float radiusLocal;

	static final int[] cosineTable = new int[16384];

	static {
		final double d = 3.834951969714103E-4D;
		for (int i = 0; i < 16384; i++) {
			cosineTable[i] = (int) (16384.0D * Math.cos(i * d));
		}
	}

	public void postDecode() {
		if (effects == null) {
			effects = Collections.emptyList();
		}
		for (EffectorEffectSpec spec : effects) {
			spec.postDecode();
		}

		if (radiusTiles > 0f) {
			radiusLocal = radiusTiles * LOCAL_TILE_SIZE;
			long range = (long) radiusLocal;
			maxRangeSq = range * range;
		} else {
			radiusLocal = 0f;
			maxRangeSq = Long.MAX_VALUE;
		}
	}

	public float getDebugRadiusLocal() {
		if (radiusTiles > 0f) {
			return radiusTiles * LOCAL_TILE_SIZE;
		}
		return Math.max(1f, debugRadiusTiles) * LOCAL_TILE_SIZE;
	}

	public boolean isUnlimitedRange() {
		return maxRangeSq >= Long.MAX_VALUE / 2L;
	}
}
