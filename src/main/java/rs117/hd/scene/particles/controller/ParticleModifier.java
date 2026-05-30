/*
 * Copyright (c) 2026
 * All rights reserved.
 */
package rs117.hd.scene.particles.controller;

import java.util.List;
import javax.annotation.Nullable;
import rs117.hd.scene.particles.effector.EffectorRef;
import rs117.hd.scene.particles.emitter.ParticleEmitter;

public final class ParticleModifier {

	@Nullable
	private Float speedMin;
	@Nullable
	private Float speedMax;
	@Nullable
	private Float weatherDensityScale;
	@Nullable
	private float[] colorMin;
	@Nullable
	private float[] colorMax;
	@Nullable
	private float[] targetColor;
	@Nullable
	private Float colorTransitionPct;
	@Nullable
	private Float alphaTransitionPct;
	@Nullable
	private List<String> localEffectorFilter;
	@Nullable
	private List<String> globalEffectors;

	private ParticleModifier() {}

	public static ParticleModifier create() {
		return new ParticleModifier();
	}

	public ParticleModifier speed(float min, float max) {
		speedMin = min;
		speedMax = max;
		return this;
	}

	public ParticleModifier weatherDensity(float scale) {
		weatherDensityScale = scale;
		return this;
	}

	public ParticleModifier colorRange(float[] minRgba, float[] maxRgba) {
		colorMin = minRgba;
		colorMax = maxRgba;
		return this;
	}

	public ParticleModifier targetColor(float[] targetRgba) {
		return targetColor(targetRgba, null, null);
	}

	public ParticleModifier targetColor(float[] targetRgba, @Nullable Float colorTransitionPct, @Nullable Float alphaTransitionPct) {
		targetColor = targetRgba;
		this.colorTransitionPct = colorTransitionPct;
		this.alphaTransitionPct = alphaTransitionPct;
		return this;
	}

	public ParticleModifier localEffectorFilter(String... effectorIds) {
		localEffectorFilter = new java.util.ArrayList<>(effectorIds.length);
		for (String id : effectorIds) {
			if (id != null && !id.isEmpty()) {
				localEffectorFilter.add(id.toUpperCase());
			}
		}
		return this;
	}

	public ParticleModifier localEffectorFilter(EffectorRef... effectors) {
		localEffectorFilter = new java.util.ArrayList<>(effectors.length);
		for (EffectorRef effector : effectors) {
			if (effector != null) {
				localEffectorFilter.add(effector.id());
			}
		}
		return this;
	}

	public ParticleModifier globalEffectors(String... effectorIds) {
		globalEffectors = new java.util.ArrayList<>(effectorIds.length);
		for (String id : effectorIds) {
			if (id != null && !id.isEmpty()) {
				globalEffectors.add(id.toUpperCase());
			}
		}
		return this;
	}

	public ParticleModifier globalEffectors(EffectorRef... effectors) {
		globalEffectors = new java.util.ArrayList<>(effectors.length);
		for (EffectorRef effector : effectors) {
			if (effector != null) {
				globalEffectors.add(effector.id());
			}
		}
		return this;
	}

	public void apply(ParticleEmitter emitter) {
		if (speedMin != null) {
			emitter.setSpeedMin(speedMin);
		}
		if (speedMax != null) {
			emitter.setSpeedMax(speedMax);
		}
		if (weatherDensityScale != null) {
			emitter.setWeatherDensityScale(weatherDensityScale);
		}
		if (colorMin != null && colorMax != null) {
			emitter.colorRange(colorMin, colorMax);
		}
		if (targetColor != null) {
			float colorPct = colorTransitionPct != null ? colorTransitionPct : emitter.getColorTransitionPct();
			float alphaPct = alphaTransitionPct != null ? alphaTransitionPct : emitter.getAlphaTransitionPct();
			emitter.targetColor(targetColor, colorPct, alphaPct);
		}
		if (localEffectorFilter != null) {
			emitter.setLocalEffectorFilter(localEffectorFilter);
		}
		if (globalEffectors != null) {
			emitter.setGlobalEffectors(globalEffectors);
		}
	}
}
