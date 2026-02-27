/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.core;

import javax.annotation.Nullable;
import lombok.NoArgsConstructor;
import rs117.hd.scene.particles.emitter.ParticleEmitter;

@NoArgsConstructor
public class Particle {
	public final float[] position = new float[3];
	public final float[] velocity = new float[3];
	public float life;
	public float maxLife;
	public float size;
	public float targetScale;
	public float scaleTransition;
	public float targetSpeed;
	public float speedTransition;
	public final float[] color = new float[4];
	public final float[] initialColor = new float[4];
	public float[] targetColor;
	public float colorTransitionPct = 100f;
	public float alphaTransitionPct = 100f;
	public int plane;

	@Nullable
	public float[] colourIncrementPerSecond;
	public float scaleIncrementPerSecond;
	public float speedIncrementPerSecond;
	public float colourTransitionEndLife = -1f;
	public float scaleTransitionEndLife = -1f;
	public float speedTransitionEndLife = -1f;
	public float emitterOriginX, emitterOriginY, emitterOriginZ;
	public int distanceFalloffType;
	public int distanceFalloffStrength;
	public boolean clipToTerrain;
	public boolean hasLevelBounds;
	public int upperBoundLevel = -2;
	public int lowerBoundLevel = -2;

	@Nullable
	public ParticleEmitter emitter;

	public int flipbookRandomFrame = -1;

	/** Pool slot index; used by ParticlePool for bookkeeping. */
	public int poolIndex;

	public void resetForPool() {
		emitter = null;
		flipbookRandomFrame = -1;
		colourIncrementPerSecond = null;
		scaleIncrementPerSecond = 0f;
		speedIncrementPerSecond = 0f;
		colourTransitionEndLife = -1f;
		scaleTransitionEndLife = -1f;
		speedTransitionEndLife = -1f;
		distanceFalloffType = 0;
		distanceFalloffStrength = 0;
		clipToTerrain = false;
		hasLevelBounds = false;
		upperBoundLevel = -2;
		lowerBoundLevel = -2;
		targetColor = null;
	}

	public void setPosition(float x, float y, float z) {
		position[0] = x;
		position[1] = y;
		position[2] = z;
	}

	public void setVelocity(float vx, float vy, float vz) {
		velocity[0] = vx;
		velocity[1] = vy;
		velocity[2] = vz;
	}

	public void setColor(float r, float g, float b, float a) {
		color[0] = r;
		color[1] = g;
		color[2] = b;
		color[3] = a;
	}

	public float getElapsedFraction() {
		if (maxLife <= 0) return 1f;
		return 1f - life / maxLife;
	}

	public static float transitionBlend(float elapsedFraction, float transitionPct) {
		if (transitionPct <= 0) return 0f;
		return Math.min(1f, elapsedFraction / (transitionPct / 100f));
	}

	public float getAlpha() {
		if (maxLife <= 0) return 1f;
		if (targetColor == null) return Math.max(0f, Math.min(1f, life / maxLife));
		float blend = transitionBlend(getElapsedFraction(), alphaTransitionPct);
		return initialColor[3] + (targetColor[3] - initialColor[3]) * blend;
	}

	public void getCurrentColor(float[] out) {
		if (colourIncrementPerSecond != null) {
			out[0] = color[0];
			out[1] = color[1];
			out[2] = color[2];
			out[3] = color[3];
			return;
		}
		float t = getElapsedFraction();
		if (targetColor == null || colorTransitionPct <= 0) {
			out[0] = color[0];
			out[1] = color[1];
			out[2] = color[2];
			out[3] = getAlpha();
			return;
		}
		float blend = transitionBlend(t, colorTransitionPct);
		float aBlend = transitionBlend(t, alphaTransitionPct);
		out[0] = initialColor[0] + (targetColor[0] - initialColor[0]) * blend;
		out[1] = initialColor[1] + (targetColor[1] - initialColor[1]) * blend;
		out[2] = initialColor[2] + (targetColor[2] - initialColor[2]) * blend;
		out[3] = initialColor[3] + (targetColor[3] - initialColor[3]) * aBlend;
	}
}
