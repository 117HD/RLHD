/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.core.buffer;

import rs117.hd.scene.particles.core.Particle;

import javax.annotation.Nullable;

/**
 * Pre-allocated particle pool with index-based free list. No runtime allocation.
 */
public final class ParticlePool {

	private final Particle[] particles;
	private final int[] freeIndices;
	private int freeCount;
	private final int capacity;

	public ParticlePool(int capacity) {
		this.capacity = capacity;
		this.particles = new Particle[capacity];
		this.freeIndices = new int[capacity];
		for (int i = 0; i < capacity; i++) {
			particles[i] = new Particle();
			particles[i].poolIndex = i;
			freeIndices[i] = i;
		}
		this.freeCount = capacity;
	}

	@Nullable
	public Particle obtain() {
		if (freeCount <= 0) return null;
		int idx = freeIndices[--freeCount];
		Particle p = particles[idx];
		p.resetForPool();
		return p;
	}

	public void release(Particle p) {
		if (p != null) {
			p.resetForPool();
			freeIndices[freeCount++] = p.poolIndex;
		}
	}

	public int getCapacity() {
		return capacity;
	}

	public int getAvailable() {
		return freeCount;
	}
}
