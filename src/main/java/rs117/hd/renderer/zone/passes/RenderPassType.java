package rs117.hd.renderer.zone.passes;

import rs117.hd.overlays.Timer;

public enum RenderPassType {
	TILED_LIGHTING(TiledLightingPass.class, Timer.TILED_LIGHTING_PASS),
	DIRECTIONAL(DirectionalShadowPass.class, Timer.DIRECTIONAL_PASS),
	SCENE(ScenePass.class, Timer.SCENE_PASS),
	DEBUG_DRAW(DebugDrawPass.class, Timer.DEBUG_DRAW_PASS);

	public final Class<? extends RenderPass> clazz;
	public final String name;
	public final Timer timer;

	RenderPassType(Class<? extends RenderPass> clazz, Timer timer) {
		this.clazz = clazz;
		this.timer = timer;
		this.name = clazz.getSimpleName();
	}
}
