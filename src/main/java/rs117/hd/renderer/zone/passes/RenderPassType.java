package rs117.hd.renderer.zone.passes;

import rs117.hd.overlays.Timer;

public enum RenderPassType {
	CLEAR_SCENE(ClearScenePass.class, Timer.CLEAR_SCENE_PASS, Timer.CLEAR_SCENE),
	DEPTH_PASS(DepthPass.class, Timer.DEPTH_PASS, Timer.RENDER_DEPTH),
	TILED_LIGHTING(TiledLightingPass.class, Timer.TILED_LIGHTING_PASS, Timer.RENDER_TILED_LIGHTING),
	DIRECTIONAL(DirectionalShadowPass.class, Timer.DIRECTIONAL_PASS, Timer.RENDER_SHADOWS),
	SCENE(ScenePass.class, Timer.SCENE_PASS, Timer.RENDER_SCENE),
	DEBUG_DRAW(DebugDrawPass.class, Timer.DEBUG_DRAW_PASS, Timer.RENDER_DEBUG_DRAW),
	BLIT_SCENE(BlitScenePass.class, Timer.BLIT_SCENE_PASS);

	public final Class<? extends RenderPass> clazz;
	public final String name;
	public final Timer timer;
	public final Timer gpuTimer;

	RenderPassType(Class<? extends RenderPass> clazz, Timer timer) {
		this.clazz = clazz;
		this.timer = timer;
		this.gpuTimer = null;
		this.name = clazz.getSimpleName();
	}

	RenderPassType(Class<? extends RenderPass> clazz, Timer timer, Timer gpuTimer) {
		this.clazz = clazz;
		this.timer = timer;
		this.gpuTimer = gpuTimer;
		this.name = clazz.getSimpleName();
	}
}
