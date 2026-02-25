package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import rs117.hd.HdPlugin;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.utils.FrameTimingsRecorder;
import rs117.hd.utils.NpcDisplacementCache;
import rs117.hd.utils.jobs.JobSystem;

import static rs117.hd.renderer.zone.SceneManager.MAX_WORLDVIEWS;
import static rs117.hd.utils.MathUtils.*;

@Singleton
public class StatsOverlay extends OverlayPanel {
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private JobSystem jobSystem;

	@Inject
	private FrameTimingsRecorder frameTimingsRecorder;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	private final Map<String, LineComponent> componentMap = new HashMap<>();
	private final StringBuilder sb = new StringBuilder();

	@Inject
	public StatsOverlay(HdPlugin plugin) {
		super(plugin);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.TOP_RIGHT);
		panelComponent.setPreferredSize(new Dimension(215, 200));
	}

	public void setActive(boolean activate) {
		if (activate) {
			overlayManager.add(this);
		} else {
			overlayManager.remove(this);
		}
	}

	@Override
	public Dimension render(Graphics2D g) {
		var boldFont = FontManager.getRunescapeBoldFont();
		var children = panelComponent.getChildren();

		children.add(TitleComponent.builder()
			.text("Stats (non-timers)")
			.build());

		children.add(LineComponent.builder()
			.left("Garbage collection count:")
			.right(String.valueOf(plugin.getGarbageCollectionCount()))
			.build());

		children.add(LineComponent.builder()
			.left("Power saving mode:")
			.right(plugin.isPowerSaving ? "ON" : "OFF")
			.build());

		children.add(LineComponent.builder()
			.leftFont(boldFont)
			.left("Scene stats:")
			.build());

		if (plugin.getSceneContext() != null) {
			var sceneContext = plugin.getSceneContext();
			children.add(LineComponent.builder()
				.left("Lights:")
				.right(String.format("%d/%d", sceneContext.numVisibleLights, sceneContext.lights.size()))
				.build());
		}

		if (plugin.renderer instanceof ZoneRenderer) {
			children.add(LineComponent.builder()
				.left("Dynamic renderables:")
				.right(String.valueOf(plugin.getDrawnDynamicRenderableCount()))
				.build());

			children.add(LineComponent.builder()
				.left("Temp renderables:")
				.right(String.valueOf(plugin.getDrawnTempRenderableCount()))
				.build());
		} else {
			children.add(LineComponent.builder()
				.left("Tiles:")
				.right(String.valueOf(plugin.getDrawnTileCount()))
				.build());

			children.add(LineComponent.builder()
				.left("Static renderables:")
				.right(String.valueOf(plugin.getDrawnStaticRenderableCount()))
				.build());

			children.add(LineComponent.builder()
				.left("Dynamic renderables:")
				.right(String.valueOf(plugin.getDrawnDynamicRenderableCount()))
				.build());

			children.add(LineComponent.builder()
				.left("NPC displacement cache size:")
				.right(String.valueOf(npcDisplacementCache.size()))
				.build());
		}

		children.add(LineComponent.builder()
			.leftFont(boldFont)
			.left("Streaming Stats:")
			.build());

		WorldViewContext root = sceneManager.getRoot();
		addTiming("Root Scene Load", root.loadTime, false);
		addTiming("Root Scene Upload", root.uploadTime, false);
		addTiming("Root Scene Swap", root.sceneSwapTime, false);

		int subSceneCount = 0;
		long subSceneLoadTime = 0;
		long subSceneUploadTime = 0;
		long subSceneSwapTime = 0;

		for (int worldViewId = 0; worldViewId < MAX_WORLDVIEWS; worldViewId++) {
			WorldViewContext subscene = sceneManager.getContext(worldViewId);
			if (subscene != null) {
				subSceneCount++;
				subSceneLoadTime += subscene.loadTime;
				subSceneUploadTime += subscene.uploadTime;
				subSceneSwapTime += subscene.sceneSwapTime;
			}
		}

		if (subSceneCount > 0) {
			addTiming("Avg SubScene Load", subSceneLoadTime / subSceneCount, false);
			addTiming("Avg SubScene Upload", subSceneUploadTime / subSceneCount, false);
			addTiming("Avg SubScene Swap", subSceneSwapTime / subSceneCount, false);
		}

		children.add(LineComponent.builder()
			.left("Sub Scene Count:")
			.right(String.valueOf(subSceneCount))
			.build());

		children.add(LineComponent.builder()
			.left("Streaming Zones:")
			.right(String.valueOf(jobSystem.getWorkQueueSize()))
			.build());

		if (frameTimingsRecorder.isCapturingSnapshot()) {
			children.add(LineComponent.builder()
				.leftFont(boldFont)
				.left("Capturing Snapshot...")
				.rightFont(boldFont)
				.right(String.format("%d%%", frameTimingsRecorder.getProgressPercentage()))
				.build());
		}

		return super.render(g);
	}

	private void addTiming(String name, long nanos, boolean bold) {
		if (nanos == 0)
			return;

		String result = "~0 ms";
		if (abs(nanos) > 1e3) {
			result = sb.append(round(nanos / 1e3) / 1e3).append(" ms").toString();
			sb.setLength(0);
		}

		LineComponent component = componentMap.get(name);
		if (component == null) {
			var font = bold ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont();
			component = LineComponent.builder()
				.left(name + ":")
				.leftFont(font)
				.right(result)
				.rightFont(font)
				.build();
			componentMap.put(name, component);
		} else {
			component.setRight(result);
		}

		panelComponent.getChildren().add(component);
	}
}
