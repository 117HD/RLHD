/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.renderer.zone.pass;

import javax.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.RenderState;

/**
 * Immutable context provided to scene passes each frame. Contains shared render state
 */
@Getter
@RequiredArgsConstructor
public class ScenePassContext {

	private final RenderState renderState;
	private final FrameTimer frameTimer;

	/** Current scene context for the root world view, or null if not available. */
	@Nullable
	private final SceneContext sceneContext;

	public void beginTimer(Timer timer) {
		frameTimer.begin(timer);
	}

	public void endTimer(Timer timer) {
		frameTimer.end(timer);
	}
}
