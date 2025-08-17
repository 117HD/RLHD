package rs117.hd.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.FrameTimings;
import rs117.hd.overlays.Timer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class FrameTimingsRecorder implements FrameTimer.Listener {
	private static final ResourcePath SNAPSHOTS_PATH = HdPlugin.PLUGIN_DIR.resolve("snapshots");
	private static final int SNAPSHOT_DURATION_MS = 20_000;

	@Inject
	private Gson gson;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	private static class Snapshot {
		public long timestamp = System.currentTimeMillis();
		public String osName;
		public String osArch;
		public String osVersion;
		public String javaVersion;
		public int cpuCores;
		public long memoryMaxMiB;
		public String gpuName;
		public Map<String, String> settings = new HashMap<>();
		public ArrayDeque<Frame> frames = new ArrayDeque<>(SNAPSHOT_DURATION_MS * 200 / 1000); // Allocate for 200 FPS

		public static class Frame {
			public long timestamp;
			public long drawnTiles;
			public long drawnStatic;
			public long drawnDynamic;
			public long npcDisplacementCacheSize;
			public long memoryUsed;
			public long memoryTotal;
			public long memoryFree;
			public long memoryMax;
			public LinkedHashMap<String, Long> cpu;
			public LinkedHashMap<String, Long> gpu;

			public transient long[] rawTimings;

			public Frame(FrameTimings frameTimings) {
				timestamp = frameTimings.frameTimestamp;
				rawTimings = frameTimings.timers;
				Runtime rt = Runtime.getRuntime();
				memoryTotal = rt.totalMemory() / MiB;
				memoryFree = rt.freeMemory() / MiB;
				memoryMax = rt.maxMemory() / MiB;
				memoryUsed = memoryTotal - memoryFree;
			}
		}
	}

	private Snapshot snapshot;

	public boolean isCapturingSnapshot() {
		return snapshot != null;
	}

	public void recordSnapshot() {
		clientThread.invoke(() -> {
			if (isCapturingSnapshot()) {
				sendGameMessage(String.format("Already capturing a snapshot (%d%% complete)", getProgressPercentage()));
				return;
			}

			snapshot = new Snapshot();
			snapshot.osName = System.getProperty("os.name");
			snapshot.osArch = System.getProperty("os.arch");
			snapshot.osVersion = System.getProperty("os.version");
			snapshot.javaVersion = System.getProperty("java.version");
			snapshot.cpuCores = Runtime.getRuntime().availableProcessors();
			snapshot.memoryMaxMiB = Runtime.getRuntime().maxMemory() / MiB;
			snapshot.gpuName = String.format(
				"%s (%s, OpenGL %s)",
				glGetString(GL_RENDERER),
				glGetString(GL_VENDOR),
				glGetString(GL_VERSION)
			);

			String prefix = HdPluginConfig.CONFIG_GROUP + ".";
			for (String config : configManager.getConfigurationKeys(prefix)) {
				String key = config.substring(prefix.length());
				snapshot.settings.put(key, configManager.getConfiguration("hd", key));
			}

			frameTimer.addTimingsListener(this);
			sendGameMessage(String.format("Capturing frame timings for %.0f seconds...", SNAPSHOT_DURATION_MS / 1e3f));
		});
	}

	public int getProgressPercentage() {
		if (isCapturingSnapshot())
			return round((float) (System.currentTimeMillis() - snapshot.timestamp) / SNAPSHOT_DURATION_MS * 100);
		return 100;
	}

	@Override
	public void onFrameCompletion(FrameTimings timings) {
		if (!isCapturingSnapshot()) {
			frameTimer.removeTimingsListener(this);
			return;
		}

		if (timings.frameTimestamp - snapshot.timestamp > SNAPSHOT_DURATION_MS) {
			saveSnapshot();
			return;
		}

		var frame = new Snapshot.Frame(timings);
		frame.drawnTiles = plugin.getDrawnTileCount();
		frame.drawnStatic = plugin.getDrawnStaticRenderableCount();
		frame.drawnDynamic = plugin.getDrawnDynamicRenderableCount();
		frame.npcDisplacementCacheSize = npcDisplacementCache.size();
		snapshot.frames.add(frame);
	}

	private void saveSnapshot() {
		frameTimer.removeTimingsListener(this);

		for (var frame : snapshot.frames) {
			frame.cpu = new LinkedHashMap<>();
			frame.gpu = new LinkedHashMap<>();
			for (Timer t : Timer.values())
				(t.isGpuTimer ? frame.gpu : frame.cpu).put(t.name, frame.rawTimings[t.ordinal()]);
		}

		try {
			SNAPSHOTS_PATH.mkdirs();
			String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(snapshot.timestamp);
			var path = SNAPSHOTS_PATH.resolve("snapshot-" + timestamp);
			path.setExtension("json").writeString(gson.toJson(snapshot));
			saveCsvSnapshot(path);
			sendGameMessage("Snapshot complete! Saved to: " + path + ".csv & json");
		} catch (IOException ex) {
			log.error("Error while saving snapshot:", ex);
		}

		snapshot = null;
	}

	private String escapeCsv(String string) {
		string = string.replaceAll("\"", "\"\"");
		if (string.contains(",") || string.contains("\n"))
			string = '"' + string + '"';
		return string;
	}

	private void writeCsvObject(PrintWriter out, boolean header, String prefix, JsonObject obj) {
		String comma = "";
		for (var entry : obj.entrySet()) {
			out.write(comma);
			var value = entry.getValue();
			if (value.isJsonObject()) {
				writeCsvObject(out, header, prefix + entry.getKey() + ".", value.getAsJsonObject());
			} else {
				out.write(escapeCsv(header ? prefix + entry.getKey() : value.toString()));
			}
			comma = ",";
		}
	}

	private void saveCsvSnapshot(ResourcePath path) throws IOException {
		if (snapshot.frames.isEmpty())
			return;

		var frames = gson.toJsonTree(snapshot.frames).getAsJsonArray();
		try (var out = new PrintWriter(path.setExtension("csv").toWriter())) {
			writeCsvObject(out, true, "", frames.get(0).getAsJsonObject());
			out.println();
			for (var frame : frames) {
				writeCsvObject(out, false, "", frame.getAsJsonObject());
				out.println();
			}
		}
	}

	private void sendGameMessage(String message) {
		clientThread.invoke(() -> client.addChatMessage(
			ChatMessageType.GAMEMESSAGE, "117 HD", "<col=ffff00>[117 HD] " + message + "</col>", "117 HD"));
	}
}
