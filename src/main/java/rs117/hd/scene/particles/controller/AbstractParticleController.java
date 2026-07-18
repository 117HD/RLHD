/*
 * Copyright (c) 2026
 * All rights reserved.
 */
package rs117.hd.scene.particles.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.client.eventbus.EventBus;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.particles.ParticleManager;
import rs117.hd.scene.particles.effector.EffectorRef;
import rs117.hd.scene.particles.emitter.ParticleEmitter;

public abstract class AbstractParticleController {

	protected ParticleManager particleManager;
	protected ParticleControllerManager controllerManager;
	protected EventBus eventBus;

	public abstract String getName();

	public List<String> getViewAreas() {
		return List.of();
	}

	public List<String> getParticleIds() {
		return controllerManager != null
			? controllerManager.getBoundParticleIds(getName())
			: List.of();
	}

	void attach(ParticleManager particleManager, ParticleControllerManager controllerManager, EventBus eventBus) {
		this.particleManager = particleManager;
		this.controllerManager = controllerManager;
		this.eventBus = eventBus;
	}

	public boolean shouldProcess(@Nullable SceneContext ctx) {
		if (ctx == null || ctx.sceneBase == null) {
			return false;
		}
		List<String> areas = getViewAreas();
		if (areas.isEmpty()) {
			return true;
		}
		return controllerManager != null && controllerManager.isAnyAreaInView(ctx, areas);
	}

	public void onActivate() {
		if (eventBus != null) {
			eventBus.register(this);
		}
	}

	public void onDeactivate() {
		if (eventBus != null) {
			eventBus.unregister(this);
		}
	}

	public void onSceneLoaded(@Nullable SceneContext ctx) {}

	public void onUpdate(@Nullable SceneContext ctx, float dt) {}

	protected List<ParticleEmitter> getMatchingEmitters() {
		if (particleManager == null) {
			return List.of();
		}
		return particleManager.getEmittersForParticleIds(getParticleIds());
	}

	protected void applyModifierToMatching(ParticleModifier modifier) {
		for (ParticleEmitter emitter : getMatchingEmitters()) {
			modifier.apply(emitter);
		}
	}

	protected void setMatchingEmittersActive(boolean active) {
		for (ParticleEmitter emitter : getMatchingEmitters()) {
			emitter.active(active);
		}
	}

	protected void setMatchingGlobalEffectors(List<String> effectorIds) {
		List<String> upper = upperCaseList(effectorIds);
		for (ParticleEmitter emitter : getMatchingEmitters()) {
			emitter.setGlobalEffectors(upper);
		}
	}

	protected void setMatchingEmbeddedEffectors(List<String> effectorIds) {
		List<String> upper = upperCaseList(effectorIds);
		for (ParticleEmitter emitter : getMatchingEmitters()) {
			emitter.setEmbeddedEffectors(upper);
		}
	}

	protected void setMatchingLocalEffectorFilter(List<String> effectorIds) {
		List<String> upper = upperCaseList(effectorIds);
		for (ParticleEmitter emitter : getMatchingEmitters()) {
			emitter.setLocalEffectorFilter(upper);
		}
	}

	protected void addRuntimeEffector(int worldX, int worldY, int plane, String effectorId) {
		if (controllerManager != null) {
			controllerManager.addRuntimeEffector(getName(), worldX, worldY, plane, effectorId);
		}
	}

	protected void addRuntimeEffector(int worldX, int worldY, int plane, EffectorRef effector) {
		addRuntimeEffector(worldX, worldY, plane, effector.id());
	}

	protected void clearRuntimeEffectors() {
		if (controllerManager != null) {
			controllerManager.clearRuntimeEffectors(getName());
		}
	}

	protected static List<String> upperCaseList(@Nullable List<String> values) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		List<String> out = new ArrayList<>(values.size());
		for (String value : values) {
			if (value != null && !value.isEmpty()) {
				out.add(value.toUpperCase());
			}
		}
		return out.isEmpty() ? List.of() : Collections.unmodifiableList(out);
	}
}
