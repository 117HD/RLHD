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
import net.runelite.client.util.LinkBrowser;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.profiling.ProfileSample;
import rs117.hd.profiling.Profiler;
import rs117.hd.profiling.Timer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class FrameTimingsRecorder implements Profiler.Listener {
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
	private Profiler profiler;

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

			public Frame(ProfileSample profileSample) {
				timestamp = profileSample.frameTimestamp;
				rawTimings = profileSample.timers;
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
			snapshot.cpuCores = HdPlugin.PROCESSOR_COUNT;
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

			profiler.addTimingsListener(this);
			sendGameMessage(String.format("Capturing frame timings for %.0f seconds...", SNAPSHOT_DURATION_MS / 1e3f));
		});
	}

	public int getProgressPercentage() {
		if (isCapturingSnapshot())
			return round((float) (System.currentTimeMillis() - snapshot.timestamp) / SNAPSHOT_DURATION_MS * 100);
		return 100;
	}

	public long getRemainingMs() {
		if (!isCapturingSnapshot())
			return 0;
		return Math.max(0, SNAPSHOT_DURATION_MS - (System.currentTimeMillis() - snapshot.timestamp));
	}

	public int getRemainingSeconds() {
		return (int) Math.ceil(getRemainingMs() / 1000.0);
	}

	public int getSnapshotDurationSeconds() {
		return SNAPSHOT_DURATION_MS / 1000;
	}

	public void openSnapshotsDirectory() {
		try {
			SNAPSHOTS_PATH.mkdirs();
			LinkBrowser.open(SNAPSHOTS_PATH.toPath().toAbsolutePath().toString());
		} catch (Exception ex) {
			log.error("Failed to open snapshots directory:", ex);
			sendGameMessage("Failed to open snapshots folder.");
		}
	}

	@Override
	public void onFrameCompletion(ProfileSample timings) {
		if (!isCapturingSnapshot()) {
			profiler.removeTimingsListener(this);
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
		profiler.removeTimingsListener(this);

		for (var frame : snapshot.frames) {
			frame.cpu = new LinkedHashMap<>();
			frame.gpu = new LinkedHashMap<>();
			for (Timer t : Timer.TIMERS)
				(t.isGpuTimer() ? frame.gpu : frame.cpu).put(t.name, frame.rawTimings[t.ordinal()]);
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
