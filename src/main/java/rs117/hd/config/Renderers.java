package rs117.hd.config;

import lombok.RequiredArgsConstructor;
import rs117.hd.renderer.Renderer;
import rs117.hd.renderer.legacy.LegacyRenderer;
import rs117.hd.renderer.zone.ZoneRenderer;

@RequiredArgsConstructor
public enum Renderers {
	LEGACY(LegacyRenderer.class),
	ZONE(ZoneRenderer.class);

	public final Class<? extends Renderer> rendererClass;
}
