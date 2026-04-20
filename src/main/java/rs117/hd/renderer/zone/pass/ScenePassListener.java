/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.renderer.zone.pass;

/**
 * Optional hook for code that needs to run before or after the entire scene pass loop.
 * Implement and register (e.g. via injector) to receive callbacks each frame.
 */
public interface ScenePassListener {

	default void beforeScenePasses(ScenePassContext ctx) {
	}

	default void afterScenePasses(ScenePassContext ctx) {
	}
}
