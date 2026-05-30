/*
 * Copyright (c) 2026
 * All rights reserved.
 */
package rs117.hd.scene.particles.effector;

import com.google.gson.annotations.SerializedName;
import javax.annotation.Nullable;
import rs117.hd.scene.particles.definition.ParticleDefinition;

public class EffectorEffectSpec {
	public EffectType type = EffectType.WIND;

	@Nullable
	public float[] direction;

	public int spreadAngle;
	public float speed = 1f;
	public float intensity = 1f;

	@SerializedName(value = "directionVariance", alternate = { "directionVariation" })
	public float directionVariance = 0f;

	public float turbulence = 0f;
	public int strength;
	public int sink;
	public boolean clockwise;
	public boolean inverted;
	public float pathVariation = 0f;
	public boolean edgeFalloff = true;
	public float falloffPower = 2f;
	public float effectPercent = 100f;
	public float immunePercent = 0f;

	@SerializedName("applyTo")
	public ApplicationMode applyTo = ApplicationMode.VELOCITY;

	@Nullable
	public String targetColor;

	@Nullable
	public ColorBlendMode colorBlend;

	public transient int forceDirectionX;
	public transient int forceDirectionY;
	public transient int forceDirectionZ;
	public transient int signedMagnitude;
	public transient int coneAngleCosine;
	public transient boolean radial;
	public transient boolean whirlpool;
	public transient boolean positionMode;
	public transient int sinkStrength;
	public transient float pathVariationScale;
	public transient float windDirX;
	public transient float windDirY;
	public transient float windDirZ;
	public transient boolean hasWindDirection;
	public transient float directionVarianceScale;
	public transient float turbulenceScale;
	public transient boolean hasTargetColor;
	public transient int targetRed16;
	public transient int targetGreen16;
	public transient int targetBlue16;
	public transient int targetAlpha16;
	public transient ColorBlendMode resolvedColorBlend;

	public void postDecode() {
		EffectType resolved = type != null ? type : EffectType.WIND;
		radial = resolved.radial;
		whirlpool = resolved == EffectType.WHIRLPOOL;
		positionMode = resolved == EffectType.PUSH || applyTo == ApplicationMode.POSITION;

		if (resolved == EffectType.WIND) {
			decodeWindDirection();
			edgeFalloff = true;
		} else if (resolved == EffectType.PUSH) {
			int angleIndex = (spreadAngle << 3) & 0x3fff;
			if (spreadAngle >= 1024) {
				angleIndex = 8192;
			}
			coneAngleCosine = EffectorDefinition.cosineTable[angleIndex];
			forceDirectionX = direction != null && direction.length > 0 ? (int) direction[0] : 0;
			forceDirectionY = direction != null && direction.length > 1 ? (int) direction[1] : 0;
			forceDirectionZ = direction != null && direction.length > 2 ? (int) direction[2] : 0;
			long fx = forceDirectionX;
			long fy = forceDirectionY;
			long fz = forceDirectionZ;
			signedMagnitude = (int) Math.sqrt(fx * fx + fy * fy + fz * fz);
		} else if (resolved == EffectType.ATTRACT) {
			signedMagnitude = -Math.abs(strength);
		} else if (resolved == EffectType.WHIRLPOOL) {
			signedMagnitude = Math.abs(strength > 0 ? strength : 2000);
			sinkStrength = sink > 0 ? sink : Math.max(1, signedMagnitude / 2);
			pathVariationScale = Math.max(0f, Math.min(1f, pathVariation / 100f));
		} else {
			signedMagnitude = Math.abs(strength);
		}

		speed = speed > 0f ? speed : 1f;
		intensity = intensity > 0f ? intensity : 1f;
		effectPercent = Math.max(0f, Math.min(100f, effectPercent));
		immunePercent = Math.max(0f, Math.min(100f, immunePercent));
		directionVarianceScale = Math.max(0f, Math.min(1f, directionVariance / 100f));
		turbulenceScale = Math.max(0f, Math.min(1f, turbulence / 100f));
		resolvedColorBlend = colorBlend != null ? colorBlend : (whirlpool ? ColorBlendMode.PATH : ColorBlendMode.ZONE);
		decodeTargetColor();
	}

	private void decodeTargetColor() {
		hasTargetColor = false;
		if (targetColor == null || targetColor.isEmpty()) {
			return;
		}
		int argb = ParticleDefinition.hexToArgb(targetColor);
		if (argb == 0) {
			return;
		}
		hasTargetColor = true;
		targetRed16 = ((argb >> 16) & 0xff) << 8;
		targetGreen16 = ((argb >> 8) & 0xff) << 8;
		targetBlue16 = (argb & 0xff) << 8;
		targetAlpha16 = ((argb >> 24) & 0xff) << 8;
	}

	private void decodeWindDirection() {
		float dx = direction != null && direction.length > 0 ? direction[0] : 0f;
		float dy = direction != null && direction.length > 1 ? direction[1] : 0f;
		float dz = direction != null && direction.length > 2 ? direction[2] : 0f;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len > 1e-6f) {
			windDirX = dx / len;
			windDirY = dy / len;
			windDirZ = dz / len;
			hasWindDirection = true;
			forceDirectionX = Math.round(windDirX * 4096f);
			forceDirectionY = Math.round(windDirY * 4096f);
			forceDirectionZ = Math.round(windDirZ * 4096f);
			signedMagnitude = 4096;
		} else {
			windDirX = 0f;
			windDirY = 1f;
			windDirZ = 0f;
			hasWindDirection = false;
			forceDirectionX = 0;
			forceDirectionY = 4096;
			forceDirectionZ = 0;
			signedMagnitude = 0;
		}
	}

	public enum EffectType {
		@SerializedName("wind")
		WIND(false),
		@SerializedName("push")
		PUSH(false),
		@SerializedName("attract")
		ATTRACT(true),
		@SerializedName("repel")
		REPEL(true),
		@SerializedName("whirlpool")
		WHIRLPOOL(false);

		public final boolean radial;

		EffectType(boolean radial) {
			this.radial = radial;
		}
	}

	public enum ApplicationMode {
		@SerializedName("velocity")
		VELOCITY,
		@SerializedName("position")
		POSITION
	}

	public enum ColorBlendMode {
		@SerializedName("path")
		PATH,
		@SerializedName("zone")
		ZONE
	}
}
