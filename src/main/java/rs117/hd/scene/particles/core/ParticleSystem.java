/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;
import rs117.hd.scene.particles.core.buffer.ParticleBuffer;
import rs117.hd.scene.particles.core.buffer.ParticlePool;
import rs117.hd.scene.particles.emitter.ParticleEmitter;

/**
 * Holds emitters, pre-allocated pool, and render buffer.
 */
public final class ParticleSystem {

	@Getter
	private final List<ParticleEmitter> emitters = new ArrayList<>();

	@Getter
	private final Map<net.runelite.api.TileObject, List<ParticleEmitter>> emittersByTileObject = new LinkedHashMap<>();

	@Getter
	private final ParticleBuffer renderBuffer;

	@Getter
	private final ParticlePool pool;

	@Getter
	private final Set<ParticleEmitter> emittersCulledThisFrame = new HashSet<>();

	@Getter
	private final Map<net.runelite.api.TileObject, float[]> objectPositionCache = new HashMap<>();

	@Setter
	@Getter
	private int lastEmittersUpdating;
	@Setter
	@Getter
	private int lastEmittersCulled;

	public ParticleSystem(int maxParticles) {
		this.pool = new ParticlePool(maxParticles);
		this.renderBuffer = new ParticleBuffer(maxParticles);
	}

	public void addEmitter(@Nullable ParticleEmitter emitter) {
		if (emitter != null && !emitters.contains(emitter)) {
			emitters.add(emitter);
		}
	}

	public void removeEmitter(ParticleEmitter emitter) {
		emitters.remove(emitter);
	}

	public void removeEmitters(Collection<ParticleEmitter> toRemove) {
		emitters.removeAll(toRemove);
	}

	public void clear() {
		emittersByTileObject.clear();
		emitters.clear();
		renderBuffer.clear();
		objectPositionCache.clear();
	}
}
