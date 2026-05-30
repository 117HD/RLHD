/*
 * Copyright (c) 2026
 * All rights reserved.
 */
package rs117.hd.scene.particles.effector;

import java.util.ArrayList;
import java.util.List;

public final class EffectorBuilder {

	private final EffectorDefinition def = new EffectorDefinition();
	private final List<EffectorEffectSpec> effects = new ArrayList<>();

	private EffectorBuilder(String id) {
		def.id = id.toUpperCase();
	}

	public static EffectorBuilder create(String id) {
		return new EffectorBuilder(id);
	}

	public EffectorBuilder heightOffset(int heightOffset) {
		def.heightOffset = heightOffset;
		return this;
	}

	public EffectorBuilder radiusTiles(float radiusTiles) {
		def.radiusTiles = radiusTiles;
		return this;
	}

	public EffectorBuilder debugRadiusTiles(float debugRadiusTiles) {
		def.debugRadiusTiles = debugRadiusTiles;
		return this;
	}

	public EffectorBuilder wind(float dirX, float dirY, float dirZ, float speed, float intensity) {
		return wind(dirX, dirY, dirZ, speed, intensity, 0f, 0f);
	}

	public EffectorBuilder wind(
		float dirX,
		float dirY,
		float dirZ,
		float speed,
		float intensity,
		float directionVariance,
		float turbulence
	) {
		EffectorEffectSpec spec = new EffectorEffectSpec();
		spec.type = EffectorEffectSpec.EffectType.WIND;
		spec.direction = new float[] { dirX, dirY, dirZ };
		spec.speed = speed;
		spec.intensity = intensity;
		spec.directionVariance = directionVariance;
		spec.turbulence = turbulence;
		effects.add(spec);
		return this;
	}

	public EffectorBuilder attract(int strength) {
		return radial(EffectorEffectSpec.EffectType.ATTRACT, strength);
	}

	public EffectorBuilder repel(int strength) {
		return radial(EffectorEffectSpec.EffectType.REPEL, strength);
	}

	public EffectorBuilder whirlpool(int strength, int sink) {
		EffectorEffectSpec spec = new EffectorEffectSpec();
		spec.type = EffectorEffectSpec.EffectType.WHIRLPOOL;
		spec.strength = strength;
		spec.sink = sink;
		effects.add(spec);
		return this;
	}

	public EffectorBuilder effect(EffectorEffectSpec spec) {
		effects.add(spec);
		return this;
	}

	public EffectorBuilder edgeFalloff(boolean edgeFalloff) {
		if (!effects.isEmpty()) {
			effects.get(effects.size() - 1).edgeFalloff = edgeFalloff;
		}
		return this;
	}

	public EffectorBuilder falloffPower(float falloffPower) {
		if (!effects.isEmpty()) {
			effects.get(effects.size() - 1).falloffPower = falloffPower;
		}
		return this;
	}

	public EffectorBuilder effectPercent(float effectPercent) {
		if (!effects.isEmpty()) {
			effects.get(effects.size() - 1).effectPercent = effectPercent;
		}
		return this;
	}

	public EffectorBuilder immunePercent(float immunePercent) {
		if (!effects.isEmpty()) {
			effects.get(effects.size() - 1).immunePercent = immunePercent;
		}
		return this;
	}

	public EffectorBuilder inverted(boolean inverted) {
		if (!effects.isEmpty()) {
			effects.get(effects.size() - 1).inverted = inverted;
		}
		return this;
	}

	public EffectorBuilder clockwise(boolean clockwise) {
		if (!effects.isEmpty()) {
			effects.get(effects.size() - 1).clockwise = clockwise;
		}
		return this;
	}

	public EffectorBuilder pathVariation(float pathVariation) {
		if (!effects.isEmpty()) {
			effects.get(effects.size() - 1).pathVariation = pathVariation;
		}
		return this;
	}

	public EffectorBuilder targetColor(String hex) {
		if (!effects.isEmpty()) {
			effects.get(effects.size() - 1).targetColor = hex;
		}
		return this;
	}

	public EffectorBuilder colorBlend(EffectorEffectSpec.ColorBlendMode mode) {
		if (!effects.isEmpty()) {
			effects.get(effects.size() - 1).colorBlend = mode;
		}
		return this;
	}

	public EffectorDefinition build() {
		def.effects = List.copyOf(effects);
		def.postDecode();
		return def;
	}

	public EffectorRef buildRef() {
		return EffectorRef.builtIn(build());
	}

	private EffectorBuilder radial(EffectorEffectSpec.EffectType type, int strength) {
		EffectorEffectSpec spec = new EffectorEffectSpec();
		spec.type = type;
		spec.strength = strength;
		spec.applyTo = EffectorEffectSpec.ApplicationMode.VELOCITY;
		effects.add(spec);
		return this;
	}
}
