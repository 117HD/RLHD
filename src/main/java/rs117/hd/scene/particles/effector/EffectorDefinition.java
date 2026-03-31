/*
 * Copyright (c) 2026
 * All rights reserved.
 */
package rs117.hd.scene.particles.effector;

import com.google.gson.annotations.SerializedName;
import javax.annotation.Nullable;

import static rs117.hd.utils.MathUtils.*;

public class EffectorDefinition {
	@Nullable
	public String id;
	public EffectorEffect effect = EffectorEffect.WIND;
	@SerializedName(value = "placements", alternate = { "placments" })
	@Nullable
	public int[][] placements;

	public int spreadAngle;
	public int forceDirectionX;
	public int forceDirectionY;
	public int forceDirectionZ;
	public int heightOffset;
	public int falloffType;
	public int falloffRate;
	public int scope;
	public boolean invertDirection;

	/**
	 * Derived values populated in postDecode().
	 */
	public transient int coneAngleCosine;
	public transient int forceMagnitude;
	public transient long maxRange;

	/**
	 * Internal-only fields, derived from effect in postDecode().
	 */
	public transient int directPositionMode;
	public transient int radialForceMode;

	public void postDecode() {
		EffectorEffect resolved = effect != null ? effect : EffectorEffect.WIND;
		radialForceMode = resolved.radialForceMode;
		directPositionMode = resolved.directPositionMode;

		coneAngleCosine = COSINE[(spreadAngle << 3) & 0x3fff];

		long fx = forceDirectionX;
		long fy = forceDirectionY;
		long fz = forceDirectionZ;
		forceMagnitude = (int) Math.sqrt(fx * fx + fy * fy + fz * fz);

		if (falloffRate == 0) {
			falloffRate = 1;
		}

		if (falloffType == 0) {
			maxRange = Long.MAX_VALUE;
		} else if (falloffType == 1) {
			long range = (long) forceMagnitude * 8 / falloffRate;
			maxRange = range * range;
		} else if (falloffType == 2) {
			maxRange = (long) forceMagnitude * 8 / falloffRate;
		}

		if (invertDirection) {
			forceMagnitude *= -1;
		}
	}

	private static final int[] SINE = new int[16384];
	private static final int[] COSINE = new int[16384];

	static {
		final double d = 3.834951969714103E-4D;
		for (int i = 0; i < 16384; i++) {
			SINE[i] = (int) (16384.0D * Math.sin(i * d));
			COSINE[i] = (int) (16384.0D * Math.cos(i * d));
		}
	}

	public enum EffectorEffect {
		@SerializedName("wind")
		WIND(0, 0),
		@SerializedName("gravity")
		GRAVITY(0, 1),
		@SerializedName("conveyor")
		CONVEYOR(1, 0),
		@SerializedName("magnet")
		MAGNET(1, 1);

		public final int radialForceMode;
		public final int directPositionMode;

		EffectorEffect(int radialForceMode, int directPositionMode) {
			this.radialForceMode = radialForceMode;
			this.directPositionMode = directPositionMode;
		}
	}
}
