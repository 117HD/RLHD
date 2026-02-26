/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.renderer.zone.pass;

import java.io.IOException;
import java.util.Comparator;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;

/**
 * A single pass run during the scene render (e.g. particles, zoom overlay).
 * Lifecycle: initialize → initializeShaders → [beforeDraw → draw → afterDraw] each frame → destroyShaders → destroy.
 * <p>
 * Use {@link #order()} to control draw order (lower runs first). Override {@link #shouldDraw(ScenePassContext)}
 * to skip the pass conditionally. Override {@link #beforeDraw(ScenePassContext)} / {@link #afterDraw(ScenePassContext)}
 * for setup/teardown around draw.
 */
public interface ScenePass {

	default int order() {
		return 0;
	}

	default String passName() {
		return getClass().getSimpleName();
	}

	default boolean shouldDraw(ScenePassContext ctx) {
		return true;
	}

	default void beforeDraw(ScenePassContext ctx) {
	}

	default void afterDraw(ScenePassContext ctx) {
	}

	void initialize();

	void destroy();

	void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException;

	void destroyShaders();

	void draw(ScenePassContext ctx);

	Comparator<ScenePass> ORDER_COMPARATOR = Comparator.comparingInt(ScenePass::order);
}
