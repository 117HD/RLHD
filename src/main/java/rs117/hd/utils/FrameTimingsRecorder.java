package rs117.hd.utils;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.FrameTimings;
import rs117.hd.overlays.Timer;

import static com.sun.jna.platform.win32.GL.GL_VENDOR;
import static com.sun.jna.platform.win32.GL.GL_VERSION;
import static org.lwjgl.opengl.GL11C.GL_RENDERER;
import static org.lwjgl.opengl.GL11C.glGetString;

@Slf4j
@Singleton
public class FrameTimingsRecorder implements FrameTimer.Listener {
	private static final ResourcePath SNAPSHOTS_DIR = HdPlugin.PLUGIN_DIR.resolve("snapshots");
	private static final int SNAPSHOT_DURATION_MS = 20_000;
	private static final int SNAPSHOT_INTERVAL_MS = 1_000;

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

	@Getter
	private boolean capturingSnapshot = false;

	private long snapshotStartTime;

	@Getter
	private final List<SnapshotEntry> snapshotData = new ArrayList<>();

	public void recordSnapshot() {
		if (capturingSnapshot)
			return;

		snapshotData.clear();
		snapshotStartTime = System.currentTimeMillis();
		capturingSnapshot = true;
		frameTimer.addTimingsListener(this);

		sendGameMessage("HD snapshot started (" + (SNAPSHOT_DURATION_MS / 1000) + " seconds)...");
	}

	@Override
	public void onFrameCompletion(FrameTimings timings) {
		long now = System.currentTimeMillis();
		long elapsed = now - snapshotStartTime;

		if (elapsed > SNAPSHOT_DURATION_MS) {
			capturingSnapshot = false;
			saveSnapshot();
			return;
		}

		if (snapshotData.isEmpty() ||
			elapsed - snapshotData.get(snapshotData.size() - 1).elapsed >= SNAPSHOT_INTERVAL_MS) {
			snapshotData.add(new SnapshotEntry(
				timings,
				elapsed,
				now,
				plugin.getDrawnTileCount(),
				plugin.getDrawnStaticRenderableCount(),
				plugin.getDrawnDynamicRenderableCount(),
				npcDisplacementCache.size()
			));
		}
	}

	private void saveSnapshot() {
		frameTimer.removeTimingsListener(this);

		SNAPSHOTS_DIR.mkdirs();
		String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());

		var csvPath = SNAPSHOTS_DIR.resolve("snapshot.csv");
		try (PrintWriter pw = new PrintWriter(csvPath.toOutputStream())) {
			pw.print("FrameIndex,Time");
			for (Timer t : Timer.values())
				pw.print("," + t.name());
			pw.println(",EstimatedBottleneck,EstimatedFPS,DrawnTiles,DrawnStatic,DrawnDynamic,NpcCacheSize");

			int frameIndex = 0;
			for (SnapshotEntry e : snapshotData) {
				StringBuilder sb = new StringBuilder()
					.append(frameIndex++)
					.append(',').append(e.currentTime);

				for (long n : e.timings)
					sb.append(',').append(String.format("%.3f", n / 1_000_000.0));

				sb.append(',').append(e.bottleneck)
					.append(',').append(String.format("%.1f", e.estimatedFps))
					.append(',').append(e.drawnTiles)
					.append(',').append(e.drawnStatic)
					.append(',').append(e.drawnDynamic)
					.append(',').append(e.npcCacheSize);

				pw.println(sb);
			}
		} catch (Exception ex) {
			log.error("Error while saving snapshot:", ex);
		}

		try (PrintWriter pw = new PrintWriter(SNAPSHOTS_DIR.resolve("web_import.json").toOutputStream())) {
			Gson gson = plugin.getGson();
			WebImportData data = new WebImportData();
			data.timestamp = timestamp;
			data.frames = snapshotData;

			data.osName = System.getProperty("os.name");
			data.osArch = System.getProperty("os.arch");
			data.osVersion = System.getProperty("os.version");
			data.javaVersion = System.getProperty("java.version");
			data.cpuCores = Runtime.getRuntime().availableProcessors();
			data.totalMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
			data.gpuName = getOpenGLGPUInfo();

			for (String config : configManager.getConfigurationKeys("hd")) {
				String configClean = config.replace("hd.", "");
				data.pluginSettings.put(configClean, configManager.getConfiguration("hd", configClean));
			}

			pw.write(gson.toJson(data));
		} catch (Exception ex) {
			log.error("Error while saving snapshot:", ex);
		}

		sendGameMessage("HD snapshot complete. Saved to " + csvPath);
	}


	private String getOpenGLGPUInfo() {
		String renderer = glGetString(GL_RENDERER);
		String vendor = glGetString(GL_VENDOR);
		String version = glGetString(GL_VERSION);

		return String.format("%s (%s, OpenGL %s)", renderer, vendor, version);
	}

	private static class WebImportData {
		String timestamp;
		Map<String, String> pluginSettings = new HashMap<>();
		List<SnapshotEntry> frames;
		String osName;
		String osArch;
		String osVersion;
		String javaVersion;
		int cpuCores;
		long totalMemoryMB;
		String gpuName;
	}

	private void sendGameMessage(String message) {
		clientThread.invoke(() ->
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null)
		);
	}
}
