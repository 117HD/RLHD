/*
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.scene.particles.Particle;

import static rs117.hd.utils.MathUtils.*;

@Data
@NoArgsConstructor
@AllArgsConstructor(access = lombok.AccessLevel.PACKAGE)
@Builder
public class ParticleEmitter {
	private static final float[] TMP_DIR = new float[3];

	@Nullable
	private WorldPoint worldPoint;

	@Nullable
	private String particleId;

	@Builder.Default
	private float heightOffset = 50f;
	@Builder.Default
	private float directionYaw = 0f;
	@Builder.Default
	private float directionPitch = 0f;
	private float spreadYawMin;
	private float spreadYawMax;
	private float spreadPitchMin;
	private float spreadPitchMax;
	@Builder.Default
	private float speedMin = 20f;
	@Builder.Default
	private float speedMax = 60f;
	@Builder.Default
	private float targetSpeed = -1f;
	@Builder.Default
	private float speedTransition = 2f;
	private float emissionAccum;
	@Builder.Default
	private float particleLifeMin = 0.5f;
	@Builder.Default
	private float particleLifeMax = 1.5f;
	@Builder.Default
	private float sizeMin = 2f;
	@Builder.Default
	private float sizeMax = 6f;
	private float targetScale;
	@Builder.Default
	private float scaleTransition = 1f;
	@Builder.Default
	private float[] colorMin = new float[] { 1f, 0.9f, 0.5f, 0.9f };
	@Builder.Default
	private float[] colorMax = new float[] { 1f, 0.9f, 0.5f, 0.9f };
	private float[] targetColor;
	@Builder.Default
	private float colorTransitionPct = 100f;
	@Builder.Default
	private float alphaTransitionPct = 100f;
	private boolean uniformColorVariation;
	@Builder.Default
	private float emissionSpawnMin = 1f;
	@Builder.Default
	private float emissionSpawnMax = 1f;
	private int initialSpawn;
	private boolean initialSpawnDone;
	@Builder.Default
	private boolean active = true;
	@Nullable
	private ParticleEmitterDefinition definition;

	private long cycleStartCycle;
	@Builder.Default
	private int emissionCycleDurationTicks = -1;
	@Builder.Default
	private int emissionTimeThresholdTicks = -1;
	@Builder.Default
	private boolean emitOnlyBeforeTime = true;
	@Builder.Default
	private boolean loopEmission = true;

	public ParticleEmitter at(WorldPoint worldPoint) {
		this.worldPoint = worldPoint;
		return this;
	}

	public ParticleEmitter particleId(String id) {
		this.particleId = id;
		return this;
	}

	public ParticleEmitter heightOffset(float aboveGround) {
		this.heightOffset = aboveGround;
		return this;
	}

	public ParticleEmitter direction(float yawRad, float pitchRad) {
		this.directionYaw = yawRad;
		this.directionPitch = pitchRad;
		return this;
	}

	public ParticleEmitter spreadYaw(float minRad, float maxRad) {
		this.spreadYawMin = minRad;
		this.spreadYawMax = maxRad;
		return this;
	}

	public ParticleEmitter spreadPitch(float minRad, float maxRad) {
		this.spreadPitchMin = minRad;
		this.spreadPitchMax = maxRad;
		return this;
	}

	public ParticleEmitter speed(float min, float max) {
		this.speedMin = min;
		this.speedMax = max;
		return this;
	}

	public ParticleEmitter targetSpeed(float speed) {
		return targetSpeed(speed, 2f);
	}

	public ParticleEmitter targetSpeed(float speed, float transitionPerSecond) {
		this.targetSpeed = speed;
		this.speedTransition = transitionPerSecond;
		return this;
	}

	public ParticleEmitter particleLifetime(float minSeconds, float maxSeconds) {
		this.particleLifeMin = minSeconds;
		this.particleLifeMax = maxSeconds;
		return this;
	}

	public ParticleEmitter size(float minPixels, float maxPixels) {
		this.sizeMin = minPixels;
		this.sizeMax = maxPixels;
		return this;
	}

	public ParticleEmitter targetScale(float target, float transitionPerSecond) {
		this.targetScale = target;
		this.scaleTransition = transitionPerSecond;
		return this;
	}

	public ParticleEmitter color(float r, float g, float b, float a) {
		this.colorMin[0] = this.colorMax[0] = r;
		this.colorMin[1] = this.colorMax[1] = g;
		this.colorMin[2] = this.colorMax[2] = b;
		this.colorMin[3] = this.colorMax[3] = a;
		return this;
	}

	public ParticleEmitter colorRange(float[] min, float[] max) {
		if (min != null && min.length >= 4) System.arraycopy(min, 0, colorMin, 0, 4);
		if (max != null && max.length >= 4) System.arraycopy(max, 0, colorMax, 0, 4);
		return this;
	}

	public ParticleEmitter targetColor(float[] target, float colorTransitionPct, float alphaTransitionPct) {
		this.targetColor = target != null && target.length >= 4 ? target : null;
		this.colorTransitionPct = colorTransitionPct;
		this.alphaTransitionPct = alphaTransitionPct;
		return this;
	}

	public ParticleEmitter uniformColorVariation(boolean use) {
		this.uniformColorVariation = use;
		return this;
	}

	public ParticleEmitter emissionBurst(int minSpawnCount, int maxSpawnCount, int initialSpawnCount) {
		this.emissionSpawnMin = minSpawnCount;
		this.emissionSpawnMax = Math.max(minSpawnCount, maxSpawnCount);
		this.initialSpawn = initialSpawnCount;
		return this;
	}

	public void setDefinition(@Nullable ParticleEmitterDefinition def) {
		this.definition = def;
	}

	public void setEmissionTime(long cycleStartCycle, int cycleDurationTicks, int thresholdTicks, boolean emitOnlyBeforeTime, boolean loopEmission) {
		this.cycleStartCycle = cycleStartCycle;
		this.emissionCycleDurationTicks = cycleDurationTicks;
		this.emissionTimeThresholdTicks = thresholdTicks;
		this.emitOnlyBeforeTime = emitOnlyBeforeTime;
		this.loopEmission = loopEmission;
	}

	public boolean isEmissionAllowedAtCycle(long currentGameCycle) {
		if (emissionCycleDurationTicks < 0) return true;
		long elapsed = Math.max(0, currentGameCycle - cycleStartCycle);
		int elapsedTicks = (int) Math.min(elapsed, Integer.MAX_VALUE);
		if (loopEmission || elapsedTicks < emissionCycleDurationTicks) {
			elapsedTicks = elapsedTicks % Math.max(1, emissionCycleDurationTicks);
		} else {
			return false;
		}
		if (emissionTimeThresholdTicks < 0) return true;
		if (emitOnlyBeforeTime && elapsedTicks >= emissionTimeThresholdTicks) return false;
		if (!emitOnlyBeforeTime && elapsedTicks < emissionTimeThresholdTicks) return false;
		return true;
	}

	public ParticleEmitter active(boolean active) {
		this.active = active;
		return this;
	}

	public boolean isActive() {
		return active && worldPoint != null;
	}

	private void randomDirectionFromRanges(float[] out, ThreadLocalRandom rng) {
		float yaw = directionYaw + (spreadYawMin == spreadYawMax ? spreadYawMin : spreadYawMin + (spreadYawMax - spreadYawMin) * rng.nextFloat());
		float pitch = directionPitch + (spreadPitchMin == spreadPitchMax ? spreadPitchMin : spreadPitchMin + (spreadPitchMax - spreadPitchMin) * rng.nextFloat());
		float cp = cos(pitch);
		out[0] = sin(yaw) * cp;
		out[1] = cos(yaw) * cp;
		out[2] = -sin(pitch);
	}

	public boolean spawn(Particle into, float originLocalX, float originLocalY, float originLocalZ, int plane) {
		if (!isActive()) return false;

		ThreadLocalRandom rng = ThreadLocalRandom.current();
		randomDirectionFromRanges(TMP_DIR, rng);
		float speed = speedMin + (speedMax - speedMin) * rng.nextFloat();
		float speedWorld = speed / 16384f;
		float vx = TMP_DIR[0] * speedWorld;
		float vy = TMP_DIR[1] * speedWorld;
		float vz = TMP_DIR[2] * speedWorld;

		float life = particleLifeMin + (particleLifeMax - particleLifeMin) * rng.nextFloat();
		float size = sizeMin + (sizeMax - sizeMin) * rng.nextFloat();

		into.setPosition(originLocalX, originLocalY, originLocalZ);
		into.setVelocity(vx, vy, vz);
		into.life = life;
		into.maxLife = life;
		into.size = size;
		into.targetScale = targetScale;
		into.scaleTransition = scaleTransition;
		into.targetSpeed = targetSpeed;
		into.speedTransition = speedTransition;
		into.plane = plane;
		if (uniformColorVariation) {
			float u = rng.nextFloat();
			for (int i = 0; i < 4; i++)
				into.initialColor[i] = colorMin[i] + (colorMax[i] - colorMin[i]) * u;
		} else {
			for (int i = 0; i < 4; i++)
				into.initialColor[i] = colorMin[i] + (colorMax[i] - colorMin[i]) * rng.nextFloat();
		}
		into.color[0] = into.initialColor[0];
		into.color[1] = into.initialColor[1];
		into.color[2] = into.initialColor[2];
		into.color[3] = into.initialColor[3];
		into.targetColor = targetColor;
		into.colorTransitionPct = colorTransitionPct;
		into.alphaTransitionPct = alphaTransitionPct;
		return true;
	}

	public int advanceEmission(float dt) {
		if (!active) return 0;
		if (!initialSpawnDone && initialSpawn > 0) {
			initialSpawnDone = true;
			return initialSpawn;
		}
		float rate = emissionSpawnMin + (emissionSpawnMax - emissionSpawnMin) * ThreadLocalRandom.current().nextFloat();
		emissionAccum += rate * dt;
		if (emissionAccum < 1f) return 0;
		int n = (int) emissionAccum;
		emissionAccum -= n;
		return n;
	}
}
