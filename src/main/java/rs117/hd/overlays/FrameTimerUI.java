package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import rs117.hd.HdPluginConfig;
import rs117.hd.overlays.components.GraphComponent;

@Slf4j
@Singleton
public class FrameTimerUI {
	@RequiredArgsConstructor
	@Getter
	public enum Tab {
		ALL("All"),
		CPU("CPU"),
		ASYNC("Async"),
		GPU("GPU"),
		STATS("Stats");

		private final String label;

		public boolean isDetachable() {
			return this != ALL;
		}
	}

	@RequiredArgsConstructor
	@Getter
	public enum Graph {
		CPU_GPU("CPU/GPU"),
		CPU("CPU"),
		ASYNC("Async"),
		GPU("GPU"),
		ALLOCATIONS("Allocations"),
		HEAP("Heap Memory"),
		SYSTEM_MEMORY("System Memory");

		private final String label;
	}

	@Inject
	private ConfigManager configManager;

	@Inject
	private HdPluginConfig config;

	@Inject
	private FrameTimerGraphOverlay frameTimerGraphOverlay;

	@Getter
	@Setter
	private Tab selectedTab = Tab.ALL;

	@Getter
	@Setter
	private boolean settingsOpen;

	@Getter
	private boolean settingsDetached;

	@Getter
	@Setter
	private int lineWidth = 260;

	@Getter
	private int graphPlotWidth = GraphComponent.DEFAULT_GRAPH_WIDTH;

	@Getter
	private int graphPlotHeight = GraphComponent.DEFAULT_GRAPH_HEIGHT;

	@Getter
	private int memoryGraphPlotHeight = GraphComponent.DEFAULT_MEMORY_GRAPH_HEIGHT;

	@Getter
	private boolean graphEnabledPreference;

	@Getter
	private final Set<Tab> hiddenTabs = EnumSet.noneOf(Tab.class);

	@Getter
	private final Set<Graph> hiddenGraphs = EnumSet.noneOf(Graph.class);

	private final Set<Tab> detachedTabs = EnumSet.noneOf(Tab.class);

	private boolean loading;

	@Nullable
	private Consumer<FrameTimerUI> changeListener;

	public boolean loadOverlayEnabled() {
		String value = configManager.getConfiguration(
			HdPluginConfig.CONFIG_GROUP,
			HdPluginConfig.KEY_FRAME_TIMER_OVERLAY_ENABLED
		);
		if (value == null)
			return config.frameTimerOverlayEnabled();
		return Boolean.parseBoolean(value);
	}

	public void saveOverlayEnabled(boolean enabled) {
		configManager.setConfiguration(
			HdPluginConfig.CONFIG_GROUP,
			HdPluginConfig.KEY_FRAME_TIMER_OVERLAY_ENABLED,
			String.valueOf(enabled)
		);
	}

	public void load() {
		loading = true;
		try {
			selectedTab = parseTab(
				configManager.getConfiguration(
					HdPluginConfig.CONFIG_GROUP,
					HdPluginConfig.KEY_FRAME_TIMER_SELECTED_TAB
				),
				config.frameTimerSelectedTab()
			);

			loadTabSet(
				configManager.getConfiguration(
					HdPluginConfig.CONFIG_GROUP,
					HdPluginConfig.KEY_FRAME_TIMER_HIDDEN_TABS
				),
				config.frameTimerHiddenTabs(),
				hiddenTabs
			);

			loadTabSet(
				configManager.getConfiguration(
					HdPluginConfig.CONFIG_GROUP,
					HdPluginConfig.KEY_FRAME_TIMER_DETACHED_TABS
				),
				config.frameTimerDetachedTabs(),
				detachedTabs
			);

			graphEnabledPreference = parseBoolean(
				configManager.getConfiguration(
					HdPluginConfig.CONFIG_GROUP,
					HdPluginConfig.KEY_FRAME_TIMER_GRAPH_ENABLED
				),
				config.frameTimerGraphEnabled()
			);

			loadGraphSet(
				configManager.getConfiguration(
					HdPluginConfig.CONFIG_GROUP,
					HdPluginConfig.KEY_FRAME_TIMER_HIDDEN_GRAPHS
				),
				config.frameTimerHiddenGraphs(),
				hiddenGraphs
			);

			settingsDetached = parseBoolean(
				configManager.getConfiguration(
					HdPluginConfig.CONFIG_GROUP,
					HdPluginConfig.KEY_FRAME_TIMER_SETTINGS_DETACHED
				),
				config.frameTimerSettingsDetached()
			);

			setGraphPlotWidthInternal(parseInt(
				configManager.getConfiguration(
					HdPluginConfig.CONFIG_GROUP,
					HdPluginConfig.KEY_FRAME_TIMER_GRAPH_WIDTH
				),
				config.frameTimerGraphWidth()
			));

			setGraphPlotHeightInternal(parseInt(
				configManager.getConfiguration(
					HdPluginConfig.CONFIG_GROUP,
					HdPluginConfig.KEY_FRAME_TIMER_GRAPH_HEIGHT
				),
				config.frameTimerGraphHeight()
			));

			setMemoryGraphPlotHeightInternal(parseInt(
				configManager.getConfiguration(
					HdPluginConfig.CONFIG_GROUP,
					HdPluginConfig.KEY_FRAME_TIMER_MEMORY_GRAPH_HEIGHT
				),
				config.frameTimerMemoryGraphHeight()
			));
		} finally {
			loading = false;
		}
	}

	public void setChangeListener(@Nullable Consumer<FrameTimerUI> listener) {
		changeListener = listener;
	}

	public void setGraphPlotWidth(int width) {
		setGraphPlotWidthInternal(width);
		persist();
	}

	public void setGraphPlotHeight(int height) {
		setGraphPlotHeightInternal(height);
		persist();
	}

	public void setMemoryGraphPlotHeight(int height) {
		setMemoryGraphPlotHeightInternal(height);
		persist();
	}

	void setGraphPlotWidthInternal(int width) {
		graphPlotWidth = clamp(width, GraphComponent.MIN_GRAPH_WIDTH, GraphComponent.MAX_GRAPH_WIDTH);
	}

	void setGraphPlotHeightInternal(int height) {
		graphPlotHeight = clamp(height, GraphComponent.MIN_GRAPH_HEIGHT, GraphComponent.MAX_GRAPH_HEIGHT);
	}

	void setMemoryGraphPlotHeightInternal(int height) {
		memoryGraphPlotHeight = clamp(
			height,
			GraphComponent.MIN_MEMORY_GRAPH_HEIGHT,
			GraphComponent.MAX_MEMORY_GRAPH_HEIGHT
		);
	}

	public boolean isGraphEnabled() {
		return graphEnabledPreference;
	}

	public boolean isGraphOverlayActive() {
		return frameTimerGraphOverlay.isActive();
	}

	public void applyGraphOverlayState() {
		frameTimerGraphOverlay.setActive(graphEnabledPreference);
	}

	public void setGraphOverlayActive(boolean active) {
		frameTimerGraphOverlay.setActive(active);
	}

	public void setGraphEnabled(boolean enabled) {
		if (enabled == graphEnabledPreference && enabled == frameTimerGraphOverlay.isActive())
			return;

		graphEnabledPreference = enabled;
		frameTimerGraphOverlay.setActive(enabled);
		notifyChanged();
	}

	public void toggleGraph() {
		setGraphEnabled(!graphEnabledPreference);
	}

	public boolean isGraphVisible(Graph graph) {
		return !hiddenGraphs.contains(graph);
	}

	public void setGraphHidden(Graph graph, boolean hidden) {
		if (hidden)
			hiddenGraphs.add(graph);
		else
			hiddenGraphs.remove(graph);
		notifyChanged();
	}

	public void toggleGraphHidden(Graph graph) {
		setGraphHidden(graph, isGraphVisible(graph));
	}

	public boolean isHidden(Tab tab) {
		return hiddenTabs.contains(tab);
	}

	public void setHidden(Tab tab, boolean hidden) {
		if (tab == Tab.ALL)
			return;

		if (hidden)
			hiddenTabs.add(tab);
		else
			hiddenTabs.remove(tab);

		if (hidden && selectedTab == tab)
			selectedTab = Tab.ALL;

		notifyChanged();
	}

	public void toggleHidden(Tab tab) {
		if (isDetached(tab)) {
			setDetached(tab, false);
			return;
		}
		setHidden(tab, !isHidden(tab));
	}

	public boolean isDetached(Tab tab) {
		return detachedTabs.contains(tab);
	}

	public void setDetached(Tab tab, boolean detached) {
		if (!tab.isDetachable())
			return;

		if (detached)
			detachedTabs.add(tab);
		else
			detachedTabs.remove(tab);

		if (detached && selectedTab == tab)
			selectedTab = Tab.ALL;

		notifyChanged();
	}

	public List<Tab> getTabBarTabs() {
		List<Tab> tabs = new ArrayList<>();
		tabs.add(Tab.ALL);
		for (Tab tab : Tab.values()) {
			if (tab == Tab.ALL)
				continue;
			if (hiddenTabs.contains(tab) || detachedTabs.contains(tab))
				continue;
			tabs.add(tab);
		}
		return tabs;
	}

	public List<Tab> getDetachedTabs() {
		List<Tab> tabs = new ArrayList<>();
		for (Tab tab : Tab.values()) {
			if (detachedTabs.contains(tab))
				tabs.add(tab);
		}
		return tabs;
	}

	public void persist() {
		if (loading)
			return;

		configManager.setConfiguration(
			HdPluginConfig.CONFIG_GROUP,
			HdPluginConfig.KEY_FRAME_TIMER_SELECTED_TAB,
			selectedTab.name()
		);

		configManager.setConfiguration(
			HdPluginConfig.CONFIG_GROUP,
			HdPluginConfig.KEY_FRAME_TIMER_HIDDEN_TABS,
			saveTabSet(hiddenTabs)
		);

		configManager.setConfiguration(
			HdPluginConfig.CONFIG_GROUP,
			HdPluginConfig.KEY_FRAME_TIMER_DETACHED_TABS,
			saveTabSet(detachedTabs)
		);

		configManager.setConfiguration(
			HdPluginConfig.CONFIG_GROUP,
			HdPluginConfig.KEY_FRAME_TIMER_SETTINGS_DETACHED,
			String.valueOf(settingsDetached)
		);

		configManager.setConfiguration(
			HdPluginConfig.CONFIG_GROUP,
			HdPluginConfig.KEY_FRAME_TIMER_GRAPH_ENABLED,
			String.valueOf(graphEnabledPreference)
		);

		configManager.setConfiguration(
			HdPluginConfig.CONFIG_GROUP,
			HdPluginConfig.KEY_FRAME_TIMER_HIDDEN_GRAPHS,
			saveGraphSet(hiddenGraphs)
		);

		configManager.setConfiguration(
			HdPluginConfig.CONFIG_GROUP,
			HdPluginConfig.KEY_FRAME_TIMER_GRAPH_WIDTH,
			String.valueOf(graphPlotWidth)
		);

		configManager.setConfiguration(
			HdPluginConfig.CONFIG_GROUP,
			HdPluginConfig.KEY_FRAME_TIMER_GRAPH_HEIGHT,
			String.valueOf(graphPlotHeight)
		);

		configManager.setConfiguration(
			HdPluginConfig.CONFIG_GROUP,
			HdPluginConfig.KEY_FRAME_TIMER_MEMORY_GRAPH_HEIGHT,
			String.valueOf(memoryGraphPlotHeight)
		);
	}

	public void selectTab(Tab tab) {
		if (tab == Tab.ALL || (!hiddenTabs.contains(tab) && !detachedTabs.contains(tab))) {
			selectedTab = tab;
			if (!settingsDetached)
				settingsOpen = false;
			notifyChanged();
		}
	}

	public void toggleSettings() {
		if (settingsDetached) {
			setSettingsDetached(false);
			return;
		}
		settingsOpen = !settingsOpen;
	}

	public void setSettingsDetached(boolean detached) {
		if (settingsDetached == detached)
			return;

		settingsDetached = detached;
		settingsOpen = false;
		notifyChanged();
	}

	public void detachSettings() {
		setSettingsDetached(true);
	}

	public boolean isInlineSettingsOpen() {
		return settingsOpen && !settingsDetached;
	}

	private void notifyChanged() {
		persist();
		if (changeListener != null)
			changeListener.accept(this);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static void loadTabSet(@Nullable String value, String defaultValue, Set<Tab> target) {
		target.clear();
		String source = value != null ? value : defaultValue;
		if (source == null || source.isEmpty())
			return;

		for (String part : source.split(",")) {
			Tab tab = parseTab(part.trim(), null);
			if (tab != null && tab.isDetachable())
				target.add(tab);
		}
	}

	private static void loadGraphSet(@Nullable String value, String defaultValue, Set<Graph> target) {
		target.clear();
		String source = value != null ? value : defaultValue;
		if (source == null || source.isEmpty())
			return;

		for (String part : source.split(",")) {
			Graph graph = parseGraph(part.trim());
			if (graph != null)
				target.add(graph);
		}
	}

	private static String saveTabSet(Set<Tab> tabs) {
		return tabs.stream()
			.map(Tab::name)
			.collect(Collectors.joining(","));
	}

	private static String saveGraphSet(Set<Graph> graphs) {
		return graphs.stream()
			.map(Graph::name)
			.collect(Collectors.joining(","));
	}

	@Nullable
	private static Graph parseGraph(@Nullable String value) {
		if (value == null || value.isEmpty())
			return null;

		try {
			return Graph.valueOf(value);
		} catch (IllegalArgumentException ex) {
			log.warn("Unknown frame timer graph preference: {}", value);
			return null;
		}
	}

	private static Tab parseTab(@Nullable String value, @Nullable String defaultValue) {
		String source = value != null && !value.isEmpty() ? value : defaultValue;
		if (source == null || source.isEmpty())
			return Tab.ALL;

		if ("SCENE".equals(source) || "STREAMING".equals(source))
			return Tab.STATS;

		try {
			return Tab.valueOf(source);
		} catch (IllegalArgumentException ex) {
			log.warn("Unknown frame timer tab preference: {}", source);
			return Tab.ALL;
		}
	}

	private static boolean parseBoolean(@Nullable String value, boolean defaultValue) {
		return value != null ? Boolean.parseBoolean(value) : defaultValue;
	}

	private static int parseInt(@Nullable String value, int defaultValue) {
		if (value == null || value.isEmpty())
			return defaultValue;

		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException ex) {
			log.warn("Invalid frame timer integer preference: {}", value);
			return defaultValue;
		}
	}
}
