/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * Copyright (c) 2023, Hooder <ahooder@protonmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.hooks.*;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.entityhider.EntityHiderPlugin;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.OSType;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.system.Callback;
import org.lwjgl.system.Configuration;
import rs117.hd.config.AntiAliasingMode;
import rs117.hd.config.ColorFilter;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.config.ShadingMode;
import rs117.hd.config.ShadowMode;
import rs117.hd.config.UIScalingMode;
import rs117.hd.config.VanillaShadowMode;
import rs117.hd.data.WaterType;
import rs117.hd.data.environments.Area;
import rs117.hd.data.materials.Material;
import rs117.hd.model.ModelHasher;
import rs117.hd.model.ModelOffsets;
import rs117.hd.model.ModelPusher;
import rs117.hd.opengl.compute.ComputeMode;
import rs117.hd.opengl.compute.OpenCLManager;
import rs117.hd.opengl.shader.Shader;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.Template;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.FishingSpotReplacer;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.SceneUploader;
import rs117.hd.scene.TextureManager;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.ObjectType;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.DeveloperTools;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.PopupUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Constants.SCENE_SIZE;
import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL43C.*;
import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.scene.SceneUploader.SCENE_OFFSET;
import static rs117.hd.utils.HDUtils.MAX_FLOAT_WITH_128TH_PRECISION;
import static rs117.hd.utils.HDUtils.PI;
import static rs117.hd.utils.HDUtils.clamp;
import static rs117.hd.utils.ResourcePath.path;

@PluginDescriptor(
	name = "117 HD",
	description = "GPU renderer with a suite of graphical enhancements",
	tags = { "hd", "high", "detail", "graphics", "shaders", "textures", "gpu", "shadows", "lights" },
	conflicts = "GPU"
)
@PluginDependency(EntityHiderPlugin.class)
@Slf4j
public class HdPlugin extends Plugin implements DrawCallbacks {
	public static final String DISCORD_URL = "https://discord.gg/U4p6ChjgSE";
	public static final String RUNELITE_URL = "https://runelite.net";
	public static final String AMD_DRIVER_URL = "https://www.amd.com/en/support";
	public static final String INTEL_DRIVER_URL = "https://www.intel.com/content/www/us/en/support/detect.html";
	public static final String NVIDIA_DRIVER_URL = "https://www.nvidia.com/en-us/geforce/drivers/";

	public static final int TEXTURE_UNIT_BASE = GL_TEXTURE0;
	public static final int TEXTURE_UNIT_UI = TEXTURE_UNIT_BASE; // default state
	public static final int TEXTURE_UNIT_GAME = TEXTURE_UNIT_BASE + 1;
	public static final int TEXTURE_UNIT_SHADOW_MAP = TEXTURE_UNIT_BASE + 2;
	public static final int TEXTURE_UNIT_TILE_HEIGHT_MAP = TEXTURE_UNIT_BASE + 3;

	public static final int UNIFORM_BLOCK_CAMERA = 0;
	public static final int UNIFORM_BLOCK_MATERIALS = 1;
	public static final int UNIFORM_BLOCK_WATER_TYPES = 2;
	public static final int UNIFORM_BLOCK_LIGHTS = 3;

	public static final float NEAR_PLANE = 50;
	public static final int MAX_FACE_COUNT = 6144;
	public static final int MAX_DISTANCE = EXTENDED_SCENE_SIZE;
	public static final int GROUND_MIN_Y = 350; // how far below the ground models extend
	public static final int MAX_FOG_DEPTH = 100;
	public static final int SCALAR_BYTES = 4;
	public static final int VERTEX_SIZE = 4; // 4 ints per vertex
	public static final int UV_SIZE = 4; // 4 floats per vertex
	public static final int NORMAL_SIZE = 4; // 4 floats per vertex

	public static float BUFFER_GROWTH_MULTIPLIER = 2; // can be less than 2 if trying to conserve memory

	private static final float COLOR_FILTER_FADE_DURATION = 3000;

	private static final int[] eightIntWrite = new int[8];

	private static final int[] RENDERBUFFER_FORMATS_SRGB = {
		GL_SRGB8,
		GL_SRGB8_ALPHA8 // should be guaranteed
	};
	private static final int[] RENDERBUFFER_FORMATS_SRGB_WITH_ALPHA = {
		GL_SRGB8_ALPHA8 // should be guaranteed
	};
	private static final int[] RENDERBUFFER_FORMATS_LINEAR = {
		GL_RGB8,
		GL_RGBA8,
		GL_RGB, // should be guaranteed
		GL_RGBA // should be guaranteed
	};
	private static final int[] RENDERBUFFER_FORMATS_LINEAR_WITH_ALPHA = {
		GL_RGBA8,
		GL_RGBA // should be guaranteed
	};

	@Inject
	private Client client;

	@Inject
	private ClientUI clientUI;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private OpenCLManager openCLManager;

	@Inject
	private TextureManager textureManager;

	@Inject
	private LightManager lightManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private ProceduralGenerator proceduralGenerator;

	@Inject
	private SceneUploader sceneUploader;

	@Inject
	private ModelPusher modelPusher;

	@Inject
	private ModelHasher modelHasher;

	@Inject
	private FishingSpotReplacer fishingSpotReplacer;

	@Inject
	private DeveloperTools developerTools;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	public HdPluginConfig config;

	@Getter
	private Gson gson;

	public GLCapabilities glCaps;
	private Canvas canvas;
	private AWTContext awtContext;
	private Callback debugCallback;
	private ComputeMode computeMode = ComputeMode.OPENGL;

	private static final String LINUX_VERSION_HEADER =
		"#version 420\n" +
		"#extension GL_ARB_compute_shader : require\n" +
		"#extension GL_ARB_shader_storage_buffer_object : require\n" +
		"#extension GL_ARB_explicit_attrib_location : require\n";
	private static final String WINDOWS_VERSION_HEADER = "#version 430\n";

	private static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vert.glsl")
		.add(GL_GEOMETRY_SHADER, "geom.glsl")
		.add(GL_FRAGMENT_SHADER, "frag.glsl");

	private static final Shader SHADOW_PROGRAM_FAST = new Shader()
		.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "shadow_frag.glsl");

	private static final Shader SHADOW_PROGRAM_DETAILED = new Shader()
		.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
		.add(GL_GEOMETRY_SHADER, "shadow_geom.glsl")
		.add(GL_FRAGMENT_SHADER, "shadow_frag.glsl");

	private static final Shader COMPUTE_PROGRAM = new Shader()
		.add(GL_COMPUTE_SHADER, "comp.glsl");

	private static final Shader UNORDERED_COMPUTE_PROGRAM = new Shader()
		.add(GL_COMPUTE_SHADER, "comp_unordered.glsl");

	private static final Shader UI_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vertui.glsl")
		.add(GL_FRAGMENT_SHADER, "fragui.glsl");

	private static final ResourcePath SHADER_PATH = Props
		.getPathOrDefault("rlhd.shader-path", () -> path(HdPlugin.class))
		.chroot();

	public int glSceneProgram;
	public int glUiProgram;
	public int glShadowProgram;
	public int glModelPassthroughComputeProgram;
	public int[] glModelSortingComputePrograms = {};

	private int interfaceTexture;
	private int interfacePbo;

	private int vaoUiHandle;
	private int vboUiHandle;

	private int vaoSceneHandle;
	private int fboSceneHandle;
	private int rboSceneHandle;

	private int shadowMapResolution;
	private int fboShadowMap;
	private int texShadowMap;

	private int texTileHeightMap;

	private final GLBuffer hStagingBufferVertices = new GLBuffer(); // temporary scene vertex buffer
	private final GLBuffer hStagingBufferUvs = new GLBuffer(); // temporary scene uv buffer
	private final GLBuffer hStagingBufferNormals = new GLBuffer(); // temporary scene normal buffer
	private final GLBuffer hRenderBufferVertices = new GLBuffer(); // target vertex buffer for compute shaders
	private final GLBuffer hRenderBufferUvs = new GLBuffer(); // target uv buffer for compute shaders
	private final GLBuffer hRenderBufferNormals = new GLBuffer(); // target normal buffer for compute shaders

	private int numPassthroughModels;
	private GpuIntBuffer modelPassthroughBuffer;
	private final GLBuffer hModelPassthroughBuffer = new GLBuffer(); // scene model buffer, unordered

	// ordered by face count from small to large
	public int numSortingBins;
	public int maxComputeThreadCount;
	public int[] modelSortingBinFaceCounts; // facesPerThread * threadCount
	public int[] modelSortingBinThreadCounts;
	private int[] numModelsToSort;
	private GpuIntBuffer[] modelSortingBuffers;
	private GLBuffer[] hModelSortingBuffers;

	private final GLBuffer hUniformBufferCamera = new GLBuffer();
	private final GLBuffer hUniformBufferMaterials = new GLBuffer();
	private final GLBuffer hUniformBufferWaterTypes = new GLBuffer();
	private final GLBuffer hUniformBufferLights = new GLBuffer();
	private ByteBuffer uniformBufferCamera;
	private ByteBuffer uniformBufferLights;

	@Getter
	@Nullable
	private SceneContext sceneContext;
	private SceneContext nextSceneContext;

	private int dynamicOffsetVertices;
	private int dynamicOffsetUvs;
	private int renderBufferOffset;

	private int lastCanvasWidth;
	private int lastCanvasHeight;
	private int lastStretchedCanvasWidth;
	private int lastStretchedCanvasHeight;
	private AntiAliasingMode lastAntiAliasingMode;
	private int numSamples;

	private int viewportOffsetX;
	private int viewportOffsetY;

	// Uniforms
	private int uniColorBlindnessIntensity;
	private int uniUiColorBlindnessIntensity;
	private int uniUseFog;
	private int uniFogColor;
	private int uniFogDepth;
	private int uniDrawDistance;
	private int uniExpandedMapLoadingChunks;
	private int uniWaterColorLight;
	private int uniWaterColorMid;
	private int uniWaterColorDark;
	private int uniAmbientStrength;
	private int uniAmbientColor;
	private int uniLightStrength;
	private int uniLightColor;
	private int uniUnderglowStrength;
	private int uniUnderglowColor;
	private int uniGroundFogStart;
	private int uniGroundFogEnd;
	private int uniGroundFogOpacity;
	private int uniLightningBrightness;
	private int uniSaturation;
	private int uniContrast;
	private int uniLightDir;
	private int uniShadowMaxBias;
	private int uniShadowsEnabled;
	private int uniUnderwaterEnvironment;
	private int uniUnderwaterCaustics;
	private int uniUnderwaterCausticsColor;
	private int uniUnderwaterCausticsStrength;
	private int uniCameraPos;
	private int uniColorFilter;
	private int uniColorFilterPrevious;
	private int uniColorFilterFade;

	// Shadow program uniforms
	private int uniShadowLightProjectionMatrix;
	private int uniShadowElapsedTime;
	private int uniShadowCameraPos;

	// Point light uniforms
	private int uniPointLightsCount;

	private int uniProjectionMatrix;
	private int uniLightProjectionMatrix;
	private int uniShadowMap;
	private int uniUiTexture;
	private int uniTexSourceDimensions;
	private int uniTexTargetDimensions;
	private int uniUiAlphaOverlay;
	private int uniTextureArray;
	private int uniElapsedTime;

	private int uniBlockMaterials;
	private int uniBlockWaterTypes;
	private int uniBlockPointLights;

	// Configs used frequently enough to be worth caching
	public boolean configGroundTextures;
	public boolean configGroundBlending;
	public boolean configModelTextures;
	public boolean configTzhaarHD;
	public boolean configProjectileLights;
	public boolean configNpcLights;
	public boolean configHideFakeShadows;
	public boolean configLegacyGreyColors;
	public boolean configModelBatching;
	public boolean configModelCaching;
	public boolean configShadowsEnabled;
	public boolean configExpandShadowDraw;
	public boolean configUseFasterModelHashing;
	public boolean configUndoVanillaShading;
	public boolean configPreserveVanillaNormals;
	public int configMaxDynamicLights;
	public ShadowMode configShadowMode;
	public SeasonalTheme configSeasonalTheme;
	public VanillaShadowMode configVanillaShadowMode;
	public ColorFilter configColorFilter = ColorFilter.NONE;
	public ColorFilter configColorFilterPrevious;

	public boolean useLowMemoryMode;
	public boolean enableDetailedTimers;
	public boolean enableShadowMapOverlay;
	public boolean enableFreezeFrame;

	@Getter
	private boolean isActive;
	private boolean lwjglInitialized;
	private boolean hasLoggedIn;
	private boolean redrawPreviousFrame;
	private boolean isInChambersOfXeric;
	private boolean isInHouse;
	private Scene skipScene;
	private int previousPlane;

	private final ConcurrentHashMap.KeySetView<String, ?> pendingConfigChanges = ConcurrentHashMap.newKeySet();
	private final Map<Long, ModelOffsets> frameModelInfoMap = new HashMap<>();

	// Camera position and orientation may be reused from the old scene while hopping, prior to drawScene being called
	public final float[] cameraPosition = new float[3];
	public final float[] cameraOrientation = new float[2];
	public final int[] cameraFocalPoint = new int[2];
	public final int[] cameraShift = new int[2];

	public double elapsedTime;
	public double elapsedClientTime;
	public float deltaTime;
	public float deltaClientTime;
	private long lastFrameTimeMillis;
	private double lastFrameClientTime;
	private int gameTicksUntilSceneReload = 0;
	private long colorFilterChangedAt;

	@Provides
	HdPluginConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(HdPluginConfig.class);
	}

	@Override
	protected void startUp() {
		gson = injector.getInstance(Gson.class).newBuilder().setLenient().create();

		clientThread.invoke(() -> {
			try {
				if (!textureManager.vanillaTexturesAvailable())
					return false;

				renderBufferOffset = 0;
				fboSceneHandle = rboSceneHandle = 0; // AA FBO
				fboShadowMap = 0;
				numPassthroughModels = 0;
				numModelsToSort = null;
				elapsedTime = 0;
				elapsedClientTime = 0;
				deltaTime = 0;
				deltaClientTime = 0;
				lastFrameTimeMillis = 0;
				lastFrameClientTime = 0;

				AWTContext.loadNatives();
				canvas = client.getCanvas();

				synchronized (canvas.getTreeLock()) {
					// Delay plugin startup until the client's canvas is valid
					if (!canvas.isValid())
						return false;

					awtContext = new AWTContext(canvas);
					awtContext.configurePixelFormat(0, 0, 0);
				}

				awtContext.createGLContext();

				canvas.setIgnoreRepaint(true);

				// lwjgl defaults to lwjgl- + user.name, but this breaks if the username would cause an invalid path
				// to be created.
				Configuration.SHARED_LIBRARY_EXTRACT_DIRECTORY.set("lwjgl-rl");

				glCaps = GL.createCapabilities();
				useLowMemoryMode = config.lowMemoryMode();
				BUFFER_GROWTH_MULTIPLIER = useLowMemoryMode ? 1.333f : 2;

				String glRenderer = glGetString(GL_RENDERER);
				String arch = System.getProperty("sun.arch.data.model", "Unknown");
				if (glRenderer == null)
					glRenderer = "Unknown";
				log.info("Using device: {}", glRenderer);
				log.info("Using driver: {}", glGetString(GL_VERSION));
				log.info("Client is {}-bit", arch);
				log.info("Low memory mode: {}", useLowMemoryMode);

				computeMode = OSType.getOSType() == OSType.MacOS ? ComputeMode.OPENCL : ComputeMode.OPENGL;

				List<String> fallbackDevices = List.of(
					"GDI Generic",
					"D3D12 (Microsoft Basic Render Driver)",
					"softpipe"
				);
				boolean isFallbackGpu = fallbackDevices.contains(glRenderer) && !Props.has("rlhd.allowFallbackGpu");
				boolean isUnsupportedGpu = isFallbackGpu || (computeMode == ComputeMode.OPENGL ? !glCaps.OpenGL43 : !glCaps.OpenGL31);
				if (isUnsupportedGpu) {
					log.error(
						"The GPU is lacking OpenGL {} support. Stopping the plugin...",
						computeMode == ComputeMode.OPENGL ? "4.3" : "3.1"
					);
					displayUnsupportedGpuMessage(isFallbackGpu, glRenderer);
					stopPlugin();
					return true;
				}

				lwjglInitialized = true;
				checkGLErrors();

				if (log.isDebugEnabled() && glCaps.glDebugMessageControl != 0) {
					debugCallback = GLUtil.setupDebugMessageCallback();
					if (debugCallback != null) {
						//	GLDebugEvent[ id 0x20071
						//		type Warning: generic
						//		severity Unknown (0x826b)
						//		source GL API
						//		msg Buffer detailed info: Buffer object 11 (bound to GL_ARRAY_BUFFER_ARB, and GL_SHADER_STORAGE_BUFFER (4), usage hint is GL_STREAM_DRAW) will use VIDEO memory as the source for buffer object operations.
						glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_OTHER,
							GL_DONT_CARE, 0x20071, false
						);

						//	GLDebugMessageHandler: GLDebugEvent[ id 0x20052
						//		type Warning: implementation dependent performance
						//		severity Medium: Severe performance/deprecation/other warnings
						//		source GL API
						//		msg Pixel-path performance warning: Pixel transfer is synchronized with 3D rendering.
						glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_PERFORMANCE,
							GL_DONT_CARE, 0x20052, false
						);
					}
				}

				updateCachedConfigs();
				developerTools.activate();

				modelPassthroughBuffer = new GpuIntBuffer();

				int maxComputeThreadCount;
				if (computeMode == ComputeMode.OPENCL) {
					openCLManager.startUp(awtContext);
					maxComputeThreadCount = openCLManager.getMaxWorkGroupSize();
				} else {
					maxComputeThreadCount = glGetInteger(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS);
				}
				initModelSortingBins(maxComputeThreadCount);

				setupSyncMode();
				initVaos();
				initBuffers();

				// Materials need to be initialized before compiling shader programs
				textureManager.startUp();

				initPrograms();
				initShaderHotswapping();
				initInterfaceTexture();
				initShadowMapFbo();

				checkGLErrors();

				client.setDrawCallbacks(this);
				client.setGpuFlags(DrawCallbacks.GPU | DrawCallbacks.HILLSKEW | DrawCallbacks.NORMALS);
				client.setExpandedMapLoading(getExpandedMapLoadingChunks());
				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				lastCanvasWidth = lastCanvasHeight = 0;
				lastStretchedCanvasWidth = lastStretchedCanvasHeight = 0;
				lastAntiAliasingMode = null;

				tileOverrideManager.startUp();
				modelOverrideManager.startUp();
				modelPusher.startUp();
				lightManager.startUp();
				environmentManager.startUp();
				fishingSpotReplacer.startUp();

				isActive = true;
				hasLoggedIn = client.getGameState().getState() > GameState.LOGGING_IN.getState();
				redrawPreviousFrame = false;
				skipScene = null;
				isInHouse = false;
				isInChambersOfXeric = false;

				if (client.getGameState() == GameState.LOGGED_IN) {
					// We need to force the client to reload the scene if GPU flags have changed
					client.setGameState(GameState.LOADING);
				}

				checkGLErrors();

				clientThread.invokeLater(this::displayUpdateMessage);
			}
			catch (Throwable err)
			{
				log.error("Error while starting 117HD", err);
				stopPlugin();
			}
			return true;
		});
	}

	@Override
	protected void shutDown() {
		isActive = false;
		FileWatcher.destroy();

		clientThread.invoke(() -> {
			var scene = client.getScene();
			if (scene != null)
				scene.setMinLevel(0);

			client.setGpuFlags(0);
			client.setDrawCallbacks(null);
			client.setUnlockedFps(false);
			client.setExpandedMapLoading(0);

			developerTools.deactivate();
			modelPusher.shutDown();
			tileOverrideManager.shutDown();
			modelOverrideManager.shutDown();
			lightManager.shutDown();
			environmentManager.shutDown();
			fishingSpotReplacer.shutDown();

			if (lwjglInitialized) {
				lwjglInitialized = false;
				waitUntilIdle();

				textureManager.shutDown();

				destroyBuffers();
				destroyInterfaceTexture();
				destroyPrograms();
				destroyVaos();
				destroySceneFbo();
				destroyShadowMapFbo();
				destroyTileHeightMap();
				destroyModelSortingBins();

				openCLManager.shutDown();
			}

			if (awtContext != null)
				awtContext.destroy();
			awtContext = null;

			if (debugCallback != null)
				debugCallback.free();
			debugCallback = null;

			if (sceneContext != null)
				sceneContext.destroy();
			sceneContext = null;

			synchronized (this) {
				if (nextSceneContext != null)
					nextSceneContext.destroy();
				nextSceneContext = null;
			}

			if (modelPassthroughBuffer != null)
				modelPassthroughBuffer.destroy();
			modelPassthroughBuffer = null;

			// force main buffer provider rebuild to turn off alpha channel
			client.resizeCanvas();
		});
	}

	public void stopPlugin()
	{
		SwingUtilities.invokeLater(() ->
		{
			try
			{
				pluginManager.setPluginEnabled(this, false);
				pluginManager.stopPlugin(this);
			}
			catch (PluginInstantiationException ex)
			{
				log.error("Error while stopping 117HD:", ex);
			}
		});

		shutDown();
	}

	public void restartPlugin() {
		// For some reason, it's necessary to delay this like below to prevent the canvas from locking up on Linux
		SwingUtilities.invokeLater(() -> clientThread.invokeLater(() -> {
			shutDown();
			startUp();
		}));
	}

	public void toggleFreezeFrame() {
		clientThread.invoke(() -> {
			enableFreezeFrame = !enableFreezeFrame;
			if (enableFreezeFrame)
				redrawPreviousFrame = true;
		});
	}

	private String generateFetchCases(String array, int from, int to)
	{
		int length = to - from;
		if (length <= 1)
		{
			return array + "[" + from + "]";
		}
		int middle = from + length / 2;
		return "i < " + middle +
			" ? " + generateFetchCases(array, from, middle) +
			" : " + generateFetchCases(array, middle, to);
	}

	private String generateGetter(String type, int arrayLength)
	{
		StringBuilder include = new StringBuilder();

		boolean isAppleM1 = OSType.getOSType() == OSType.MacOS && System.getProperty("os.arch").equals("aarch64");
		if (config.macosIntelWorkaround() && !isAppleM1)
		{
			// Workaround wrapper for drivers that do not support dynamic indexing,
			// particularly Intel drivers on macOS
			include
				.append(type)
				.append(" ")
				.append("get")
				.append(type)
				.append("(int i) { return ")
				.append(generateFetchCases(type + "Array", 0, arrayLength))
				.append("; }\n");
		}
		else
		{
			include
				.append("#define get")
				.append(type)
				.append("(i) ")
				.append(type)
				.append("Array[i]\n");
		}

		return include.toString();
	}

	private void initPrograms() throws ShaderException, IOException
	{
		String versionHeader = OSType.getOSType() == OSType.Linux ? LINUX_VERSION_HEADER : WINDOWS_VERSION_HEADER;
		Template template = new Template()
			.addInclude("VERSION_HEADER", versionHeader)
			.define("UI_SCALING_MODE", config.uiScalingMode().getMode())
			.define("COLOR_BLINDNESS", config.colorBlindness())
			.define("APPLY_COLOR_FILTER", configColorFilter != ColorFilter.NONE)
			.define("MATERIAL_CONSTANTS", () -> {
				StringBuilder include = new StringBuilder();
				for (Material m : Material.values())
				{
					include
						.append("#define MAT_")
						.append(m.name().toUpperCase())
						.append(" getMaterial(")
						.append(textureManager.getMaterialIndex(m, m.vanillaTextureIndex))
						.append(")\n");
				}
				return include.toString();
			})
			.define("MATERIAL_COUNT", Material.values().length)
			.define("MATERIAL_GETTER", () -> generateGetter("Material", Material.values().length))
			.define("WATER_TYPE_COUNT", WaterType.values().length)
			.define("WATER_TYPE_GETTER", () -> generateGetter("WaterType", WaterType.values().length))
			.define("LIGHT_COUNT", Math.max(1, configMaxDynamicLights))
			.define("LIGHT_GETTER", () -> generateGetter("PointLight", configMaxDynamicLights))
			.define("NORMAL_MAPPING", config.normalMapping())
			.define("PARALLAX_OCCLUSION_MAPPING", config.parallaxOcclusionMapping())
			.define("SHADOW_MODE", configShadowMode)
			.define("SHADOW_TRANSPARENCY", config.enableShadowTransparency())
			.define("VANILLA_COLOR_BANDING", config.vanillaColorBanding())
			.define("UNDO_VANILLA_SHADING", configUndoVanillaShading)
			.define("LEGACY_GREY_COLORS", configLegacyGreyColors)
			.define("DISABLE_DIRECTIONAL_SHADING", config.shadingMode() != ShadingMode.DEFAULT)
			.define("FLAT_SHADING", config.flatShading())
			.define("SHADOW_MAP_OVERLAY", enableShadowMapOverlay)
			.addIncludePath(SHADER_PATH);

		glSceneProgram = PROGRAM.compile(template);
		glUiProgram = UI_PROGRAM.compile(template);

		switch (configShadowMode) {
			case FAST:
				glShadowProgram = SHADOW_PROGRAM_FAST.compile(template);
				break;
			case DETAILED:
				glShadowProgram = SHADOW_PROGRAM_DETAILED.compile(template);
				break;
		}

		if (computeMode == ComputeMode.OPENCL) {
			openCLManager.initPrograms();
		} else {
			glModelPassthroughComputeProgram = UNORDERED_COMPUTE_PROGRAM.compile(template);

			glModelSortingComputePrograms = new int[numSortingBins];
			for (int i = 0; i < numSortingBins; i++) {
				int faceCount = modelSortingBinFaceCounts[i];
				int threadCount = modelSortingBinThreadCounts[i];
				int facesPerThread = (int) Math.ceil((float) faceCount / threadCount);
				glModelSortingComputePrograms[i] = COMPUTE_PROGRAM.compile(template
					.copy()
					.define("THREAD_COUNT", threadCount)
					.define("FACES_PER_THREAD", facesPerThread)
				);
			}
		}

		initUniforms();

		// Bind texture samplers before validating, else the validation fails
		glUseProgram(glSceneProgram);
		glUniform1i(uniTextureArray, TEXTURE_UNIT_GAME - TEXTURE_UNIT_BASE);
		glUniform1i(uniShadowMap, TEXTURE_UNIT_SHADOW_MAP - TEXTURE_UNIT_BASE);

		// Bind a VOA, else validation may fail on older Intel-based Macs
		glBindVertexArray(vaoSceneHandle);

		// Validate program
		glValidateProgram(glSceneProgram);
		if (glGetProgrami(glSceneProgram, GL_VALIDATE_STATUS) == GL_FALSE) {
			String err = glGetProgramInfoLog(glSceneProgram);
			throw new ShaderException(err);
		}

		glUseProgram(glUiProgram);
		glUniform1i(uniUiTexture, TEXTURE_UNIT_UI - TEXTURE_UNIT_BASE);

		glUseProgram(0);
	}

	private void initUniforms() {
		uniProjectionMatrix = glGetUniformLocation(glSceneProgram, "projectionMatrix");
		uniLightProjectionMatrix = glGetUniformLocation(glSceneProgram, "lightProjectionMatrix");
		uniShadowMap = glGetUniformLocation(glSceneProgram, "shadowMap");
		uniSaturation = glGetUniformLocation(glSceneProgram, "saturation");
		uniContrast = glGetUniformLocation(glSceneProgram, "contrast");
		uniUseFog = glGetUniformLocation(glSceneProgram, "useFog");
		uniFogColor = glGetUniformLocation(glSceneProgram, "fogColor");
		uniFogDepth = glGetUniformLocation(glSceneProgram, "fogDepth");
		uniWaterColorLight = glGetUniformLocation(glSceneProgram, "waterColorLight");
		uniWaterColorMid = glGetUniformLocation(glSceneProgram, "waterColorMid");
		uniWaterColorDark = glGetUniformLocation(glSceneProgram, "waterColorDark");
		uniDrawDistance = glGetUniformLocation(glSceneProgram, "drawDistance");
		uniExpandedMapLoadingChunks = glGetUniformLocation(glSceneProgram, "expandedMapLoadingChunks");
		uniAmbientStrength = glGetUniformLocation(glSceneProgram, "ambientStrength");
		uniAmbientColor = glGetUniformLocation(glSceneProgram, "ambientColor");
		uniLightStrength = glGetUniformLocation(glSceneProgram, "lightStrength");
		uniLightColor = glGetUniformLocation(glSceneProgram, "lightColor");
		uniUnderglowStrength = glGetUniformLocation(glSceneProgram, "underglowStrength");
		uniUnderglowColor = glGetUniformLocation(glSceneProgram, "underglowColor");
		uniGroundFogStart = glGetUniformLocation(glSceneProgram, "groundFogStart");
		uniGroundFogEnd = glGetUniformLocation(glSceneProgram, "groundFogEnd");
		uniGroundFogOpacity = glGetUniformLocation(glSceneProgram, "groundFogOpacity");
		uniLightningBrightness = glGetUniformLocation(glSceneProgram, "lightningBrightness");
		uniPointLightsCount = glGetUniformLocation(glSceneProgram, "pointLightsCount");
		uniColorBlindnessIntensity = glGetUniformLocation(glSceneProgram, "colorBlindnessIntensity");
		uniLightDir = glGetUniformLocation(glSceneProgram, "lightDir");
		uniShadowMaxBias = glGetUniformLocation(glSceneProgram, "shadowMaxBias");
		uniShadowsEnabled = glGetUniformLocation(glSceneProgram, "shadowsEnabled");
		uniUnderwaterEnvironment = glGetUniformLocation(glSceneProgram, "underwaterEnvironment");
		uniUnderwaterCaustics = glGetUniformLocation(glSceneProgram, "underwaterCaustics");
		uniUnderwaterCausticsColor = glGetUniformLocation(glSceneProgram, "underwaterCausticsColor");
		uniUnderwaterCausticsStrength = glGetUniformLocation(glSceneProgram, "underwaterCausticsStrength");
		uniCameraPos = glGetUniformLocation(glSceneProgram, "cameraPos");
		uniTextureArray = glGetUniformLocation(glSceneProgram, "textureArray");
		uniElapsedTime = glGetUniformLocation(glSceneProgram, "elapsedTime");

		if (configColorFilter != ColorFilter.NONE) {
			uniColorFilter = glGetUniformLocation(glSceneProgram, "colorFilter");
			uniColorFilterPrevious = glGetUniformLocation(glSceneProgram, "colorFilterPrevious");
			uniColorFilterFade = glGetUniformLocation(glSceneProgram, "colorFilterFade");
		}

		uniUiTexture = glGetUniformLocation(glUiProgram, "uiTexture");
		uniTexTargetDimensions = glGetUniformLocation(glUiProgram, "targetDimensions");
		uniTexSourceDimensions = glGetUniformLocation(glUiProgram, "sourceDimensions");
		uniUiColorBlindnessIntensity = glGetUniformLocation(glUiProgram, "colorBlindnessIntensity");
		uniUiAlphaOverlay = glGetUniformLocation(glUiProgram, "alphaOverlay");

		uniBlockMaterials = glGetUniformBlockIndex(glSceneProgram, "MaterialUniforms");
		uniBlockWaterTypes = glGetUniformBlockIndex(glSceneProgram, "WaterTypeUniforms");
		uniBlockPointLights = glGetUniformBlockIndex(glSceneProgram, "PointLightUniforms");

		if (computeMode == ComputeMode.OPENGL) {
			for (int sortingProgram : glModelSortingComputePrograms) {
				int uniBlockCamera = glGetUniformBlockIndex(sortingProgram, "CameraUniforms");
				glUniformBlockBinding(sortingProgram, uniBlockCamera, UNIFORM_BLOCK_CAMERA);
			}
		}

		// Shadow program uniforms
		switch (configShadowMode) {
			case DETAILED:
				int uniShadowBlockMaterials = glGetUniformBlockIndex(glShadowProgram, "MaterialUniforms");
				int uniShadowTextureArray = glGetUniformLocation(glShadowProgram, "textureArray");
				glUseProgram(glShadowProgram);
				glUniform1i(uniShadowTextureArray, TEXTURE_UNIT_GAME - TEXTURE_UNIT_BASE);
				glUniformBlockBinding(glShadowProgram, uniShadowBlockMaterials, UNIFORM_BLOCK_MATERIALS);
				uniShadowElapsedTime = glGetUniformLocation(glShadowProgram, "elapsedTime");
				uniShadowCameraPos = glGetUniformLocation(glShadowProgram, "cameraPos");
				// fall-through
			case FAST:
				uniShadowLightProjectionMatrix = glGetUniformLocation(glShadowProgram, "lightProjectionMatrix");
		}

		// Initialize uniform buffers that may depend on compile-time settings
		initCameraUniformBuffer();
		initLightsUniformBuffer();
	}

	private void destroyPrograms() {
		if (glSceneProgram != 0)
			glDeleteProgram(glSceneProgram);
		glSceneProgram = 0;

		if (glUiProgram != 0)
			glDeleteProgram(glUiProgram);
		glUiProgram = 0;

		if (glShadowProgram != 0)
			glDeleteProgram(glShadowProgram);
		glShadowProgram = 0;

		if (computeMode == ComputeMode.OPENGL) {
			if (glModelPassthroughComputeProgram != 0)
				glDeleteProgram(glModelPassthroughComputeProgram);
			glModelPassthroughComputeProgram = 0;

			if (glModelSortingComputePrograms != null)
				for (int program : glModelSortingComputePrograms)
					glDeleteProgram(program);
			glModelSortingComputePrograms = null;
		} else {
			openCLManager.destroyPrograms();
		}
	}

	public void recompilePrograms() throws ShaderException, IOException {
		// Avoid recompiling if we haven't already compiled once
		if (glSceneProgram == 0)
			return;

		destroyPrograms();
		initPrograms();
	}

	private void initModelSortingBins(int maxThreadCount) {
		maxComputeThreadCount = maxThreadCount;

		int[] targetFaceCounts = {
			128,
			512,
			2048,
			4096,
			MAX_FACE_COUNT
		};

		int numBins = 0;
		int[] binFaceCounts = new int[targetFaceCounts.length];
		int[] binThreadCounts = new int[targetFaceCounts.length];

		int faceCount = 0;
		for (int targetFaceCount : targetFaceCounts) {
			if (faceCount >= targetFaceCount)
				continue;

			int facesPerThread = 1;
			int threadCount;
			while (true) {
				threadCount = (int) Math.ceil((float) targetFaceCount / facesPerThread);
				if (threadCount <= maxThreadCount)
					break;
				++facesPerThread;
			}

			faceCount = threadCount * facesPerThread;
			binFaceCounts[numBins] = faceCount;
			binThreadCounts[numBins] = threadCount;
			++numBins;
		}

		numSortingBins = numBins;
		modelSortingBinFaceCounts = Arrays.copyOf(binFaceCounts, numBins);
		modelSortingBinThreadCounts = Arrays.copyOf(binThreadCounts, numBins);
		numModelsToSort = new int[numBins];

		modelSortingBuffers = new GpuIntBuffer[numSortingBins];
		for (int i = 0; i < numSortingBins; i++)
			modelSortingBuffers[i] = new GpuIntBuffer();

		hModelSortingBuffers = new GLBuffer[numSortingBins];
		for (int i = 0; i < numSortingBins; i++) {
			hModelSortingBuffers[i] = new GLBuffer();
			initGlBuffer(hModelSortingBuffers[i], GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);
		}

		log.debug("Spreading model sorting across {} bins: {}", numBins, modelSortingBinFaceCounts);
	}

	private void destroyModelSortingBins() {
		// Don't allow redrawing the previous frame if the model sorting buffers are no longer valid
		redrawPreviousFrame = false;

		numSortingBins = 0;
		modelSortingBinFaceCounts = null;
		modelSortingBinThreadCounts = null;
		numModelsToSort = null;

		if (modelSortingBuffers != null)
			for (var buffer : modelSortingBuffers)
				buffer.destroy();
		modelSortingBuffers = null;

		if (hModelSortingBuffers != null)
			for (var buffer : hModelSortingBuffers)
				destroyGlBuffer(buffer);
		hModelSortingBuffers = null;
	}

	private void initVaos() {
		// Create scene VAO
		vaoSceneHandle = glGenVertexArrays();

		// Create UI VAO
		vaoUiHandle = glGenVertexArrays();
		// Create UI buffer
		vboUiHandle = glGenBuffers();
		glBindVertexArray(vaoUiHandle);

		FloatBuffer vboUiData = BufferUtils.createFloatBuffer(5 * 4)
			.put(new float[] {
				// vertices, UVs
				1, 1, 0, 1, 0, // top right
				1, -1, 0, 1, 1, // bottom right
				-1, -1, 0, 0, 1, // bottom left
				-1, 1, 0, 0, 0  // top left
			})
			.flip();
		glBindBuffer(GL_ARRAY_BUFFER, vboUiHandle);
		glBufferData(GL_ARRAY_BUFFER, vboUiData, GL_STATIC_DRAW);

		// position attribute
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
		glEnableVertexAttribArray(0);

		// texture coord attribute
		glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
		glEnableVertexAttribArray(1);
	}

	private void updateSceneVao(GLBuffer vertexBuffer, GLBuffer uvBuffer, GLBuffer normalBuffer) {
		glBindVertexArray(vaoSceneHandle);

		glEnableVertexAttribArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer.glBufferId);
		glVertexAttribIPointer(0, 4, GL_INT, 0, 0);

		glEnableVertexAttribArray(1);
		glBindBuffer(GL_ARRAY_BUFFER, uvBuffer.glBufferId);
		glVertexAttribPointer(1, 4, GL_FLOAT, false, 0, 0);

		glEnableVertexAttribArray(2);
		glBindBuffer(GL_ARRAY_BUFFER, normalBuffer.glBufferId);
		glVertexAttribPointer(2, 4, GL_FLOAT, false, 0, 0);
	}

	private void destroyVaos() {
		if (vaoSceneHandle != 0)
			glDeleteVertexArrays(vaoSceneHandle);
		vaoSceneHandle = 0;

		if (vboUiHandle != 0)
			glDeleteBuffers(vboUiHandle);
		vboUiHandle = 0;

		if (vaoUiHandle != 0)
			glDeleteVertexArrays(vaoUiHandle);
		vaoUiHandle = 0;
	}

	private void initBuffers() {
		initGlBuffer(hUniformBufferCamera, GL_UNIFORM_BUFFER, GL_DYNAMIC_DRAW, CL_MEM_READ_ONLY);
		initGlBuffer(hUniformBufferMaterials, GL_UNIFORM_BUFFER, GL_STATIC_DRAW, CL_MEM_READ_ONLY);
		initGlBuffer(hUniformBufferWaterTypes, GL_UNIFORM_BUFFER, GL_STATIC_DRAW, CL_MEM_READ_ONLY);
		initGlBuffer(hUniformBufferLights, GL_UNIFORM_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);

		glBindBufferBase(GL_UNIFORM_BUFFER, UNIFORM_BLOCK_CAMERA, hUniformBufferCamera.glBufferId);
		glBindBufferBase(GL_UNIFORM_BUFFER, UNIFORM_BLOCK_MATERIALS, hUniformBufferMaterials.glBufferId);
		glBindBufferBase(GL_UNIFORM_BUFFER, UNIFORM_BLOCK_WATER_TYPES, hUniformBufferWaterTypes.glBufferId);
		glBindBufferBase(GL_UNIFORM_BUFFER, UNIFORM_BLOCK_LIGHTS, hUniformBufferLights.glBufferId);

		initGlBuffer(hStagingBufferVertices, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);
		initGlBuffer(hStagingBufferUvs, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);
		initGlBuffer(hStagingBufferNormals, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);

		initGlBuffer(hRenderBufferVertices, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_WRITE_ONLY);
		initGlBuffer(hRenderBufferUvs, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_WRITE_ONLY);
		initGlBuffer(hRenderBufferNormals, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_WRITE_ONLY);

		initGlBuffer(hModelPassthroughBuffer, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);
	}

	private void initGlBuffer(GLBuffer glBuffer, int target, int glUsage, int clUsage) {
		glBuffer.glBufferId = glGenBuffers();
		// Initialize both GL and CL buffers to dummy buffers of a single byte,
		// to ensure that valid buffers are given to compute dispatches.
		// This is particularly important on Apple M2 Max, where an uninitialized buffer leads to a crash
		updateBuffer(glBuffer, target, 1, glUsage, clUsage);
	}

	private void destroyBuffers() {
		destroyGlBuffer(hUniformBufferCamera);
		destroyGlBuffer(hUniformBufferMaterials);
		destroyGlBuffer(hUniformBufferWaterTypes);
		destroyGlBuffer(hUniformBufferLights);

		destroyGlBuffer(hStagingBufferVertices);
		destroyGlBuffer(hStagingBufferUvs);
		destroyGlBuffer(hStagingBufferNormals);

		destroyGlBuffer(hRenderBufferVertices);
		destroyGlBuffer(hRenderBufferUvs);
		destroyGlBuffer(hRenderBufferNormals);

		destroyGlBuffer(hModelPassthroughBuffer);

		uniformBufferCamera = null;
		uniformBufferLights = null;
	}

	private void destroyGlBuffer(GLBuffer glBuffer) {
		glBuffer.size = -1;

		if (glBuffer.glBufferId != 0) {
			glDeleteBuffers(glBuffer.glBufferId);
			glBuffer.glBufferId = 0;
		}

		if (glBuffer.clBuffer != 0) {
			clReleaseMemObject(glBuffer.clBuffer);
			glBuffer.clBuffer = 0;
		}
	}

	private void initInterfaceTexture()
	{
		interfacePbo = glGenBuffers();

		interfaceTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void destroyInterfaceTexture()
	{
		if (interfacePbo != 0)
		{
			glDeleteBuffers(interfacePbo);
			interfacePbo = 0;
		}

		if (interfaceTexture != 0)
		{
			glDeleteTextures(interfaceTexture);
			interfaceTexture = 0;
		}
	}

	private void initCameraUniformBuffer()
	{
		IntBuffer uniformBuf = BufferUtils.createIntBuffer(8 + 2048 * 4);
		uniformBuf.put(new int[8]); // uniform block
		final int[] pad = new int[2];
		for (int i = 0; i < 2048; i++) {
			uniformBuf.put(SINE[i]);
			uniformBuf.put(COSINE[i]);
			uniformBuf.put(pad); // ivec2 alignment in std140 is 16 bytes
		}
		uniformBuf.flip();

		updateBuffer(hUniformBufferCamera, GL_UNIFORM_BUFFER, uniformBuf, GL_DYNAMIC_DRAW, CL_MEM_READ_ONLY);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);

		uniformBufferCamera = BufferUtils.createByteBuffer(8 * SCALAR_BYTES);
	}

	public void updateMaterialUniformBuffer(ByteBuffer buffer) {
		updateBuffer(hUniformBufferMaterials, GL_UNIFORM_BUFFER, buffer, GL_STATIC_DRAW, CL_MEM_READ_ONLY);
	}

	public void updateWaterTypeUniformBuffer(ByteBuffer buffer) {
		updateBuffer(hUniformBufferWaterTypes, GL_UNIFORM_BUFFER, buffer, GL_STATIC_DRAW, CL_MEM_READ_ONLY);
	}

	private void initLightsUniformBuffer()
	{
		// Allowing a buffer size of zero causes Apple M1/M2 to revert to software rendering
		uniformBufferLights = BufferUtils.createByteBuffer(Math.max(1, configMaxDynamicLights) * 8 * SCALAR_BYTES);
		updateBuffer(hUniformBufferLights, GL_UNIFORM_BUFFER, uniformBufferLights, GL_STREAM_DRAW, CL_MEM_READ_ONLY);
	}

	private void initSceneFbo(int width, int height, AntiAliasingMode antiAliasingMode) {
		// Bind default FBO to check whether anti-aliasing is forced
		int defaultFramebuffer = awtContext.getFramebuffer(false);
		glBindFramebuffer(GL_FRAMEBUFFER, defaultFramebuffer);
		final int forcedAASamples = glGetInteger(GL_SAMPLES);
		final int maxSamples = glGetInteger(GL_MAX_SAMPLES);
		numSamples = forcedAASamples != 0 ? forcedAASamples :
			Math.min(antiAliasingMode.getSamples(), maxSamples);

		// Since there's seemingly no reliable way to check if the default framebuffer will do sRGB conversions with GL_FRAMEBUFFER_SRGB
		// enabled, we always replace the default framebuffer with an sRGB one. We could technically support rendering to the default
		// framebuffer when sRGB conversions aren't needed, but the goal is to transition to linear blending in the future anyway.
		boolean sRGB = false; // This is currently unused

		// Some implementations (*cough* Apple) complain when blitting from an FBO without an alpha channel to a (default) FBO with alpha.
		// To work around this, we select a format which includes an alpha channel, even though we don't need it.
		int defaultColorAttachment = defaultFramebuffer == 0 ? GL_BACK_LEFT : GL_COLOR_ATTACHMENT0;
		int alphaBits = glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER, defaultColorAttachment, GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE);
		checkGLErrors();
		boolean alpha = alphaBits > 0;

		int[] desiredFormats = sRGB ?
			alpha ? RENDERBUFFER_FORMATS_SRGB_WITH_ALPHA : RENDERBUFFER_FORMATS_SRGB :
			alpha ? RENDERBUFFER_FORMATS_LINEAR_WITH_ALPHA : RENDERBUFFER_FORMATS_LINEAR;

		int[] resolution = applyDpiScaling(width, height);

		// Create and bind the FBO
		fboSceneHandle = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboSceneHandle);

		// Create color render buffer
		rboSceneHandle = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboSceneHandle);

		// Flush out all pending errors, so we can check whether the next step succeeds
		clearGLErrors();

		for (int format : desiredFormats) {
			glRenderbufferStorageMultisample(GL_RENDERBUFFER, numSamples, format, resolution[0], resolution[1]);

			if (glGetError() == GL_NO_ERROR) {
				// Found a usable format. Bind the RBO to the scene FBO
				glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rboSceneHandle);
				checkGLErrors();

				// Reset
				glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
				glBindRenderbuffer(GL_RENDERBUFFER, 0);
				return;
			}
		}

		throw new RuntimeException("No supported " + (sRGB ? "sRGB" : "linear") + " formats");
	}

	private void destroySceneFbo()
	{
		if (fboSceneHandle != 0)
		{
			glDeleteFramebuffers(fboSceneHandle);
			fboSceneHandle = 0;
		}

		if (rboSceneHandle != 0)
		{
			glDeleteRenderbuffers(rboSceneHandle);
			rboSceneHandle = 0;
		}
	}

	private void initShadowMapFbo()
	{
		// Bind shadow map, or dummy 1x1 texture
		glActiveTexture(TEXTURE_UNIT_SHADOW_MAP);

		if (configShadowsEnabled)
		{
			// Create and bind the FBO
			fboShadowMap = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, fboShadowMap);

			// Create texture
			texShadowMap = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, texShadowMap);

			shadowMapResolution = config.shadowResolution().getValue();
			int maxResolution = glGetInteger(GL_MAX_TEXTURE_SIZE);
			if (maxResolution < shadowMapResolution) {
				log.info("Capping shadow resolution from {} to {}", shadowMapResolution, maxResolution);
				shadowMapResolution = maxResolution;
			}

			glTexImage2D(
				GL_TEXTURE_2D,
				0,
				GL_DEPTH_COMPONENT24,
				shadowMapResolution,
				shadowMapResolution,
				0,
				GL_DEPTH_COMPONENT,
				GL_FLOAT,
				0
			);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);

			float[] color = { 1, 1, 1, 1 };
			glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, color);

			// Bind texture
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texShadowMap, 0);
			glDrawBuffer(GL_NONE);
			glReadBuffer(GL_NONE);

			// Reset FBO
			glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		}
		else
		{
			initDummyShadowMap();
		}

		// Reset active texture to UI texture
		glActiveTexture(TEXTURE_UNIT_UI);
	}

	private void initDummyShadowMap()
	{
		// Create texture
		texShadowMap = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texShadowMap);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, 1, 1, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);

		// Reset
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void destroyShadowMapFbo() {
		if (texShadowMap != 0)
			glDeleteTextures(texShadowMap);
		texShadowMap = 0;

		if (fboShadowMap != 0)
			glDeleteFramebuffers(fboShadowMap);
		fboShadowMap = 0;
	}

	private void initTileHeightMap(Scene scene) {
		final int TILE_HEIGHT_BUFFER_SIZE = Constants.MAX_Z * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE * Short.BYTES;
		ShortBuffer tileBuffer = ByteBuffer
			.allocateDirect(TILE_HEIGHT_BUFFER_SIZE)
			.order(ByteOrder.nativeOrder())
			.asShortBuffer();

		int[][][] tileHeights = scene.getTileHeights();
		for (int z = 0; z < Constants.MAX_Z; ++z) {
			for (int y = 0; y < EXTENDED_SCENE_SIZE; ++y) {
				for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x) {
					int h = tileHeights[z][x][y];
					assert (h & 0b111) == 0;
					h >>= 3;
					tileBuffer.put((short) h);
				}
			}
		}
		tileBuffer.flip();

		glActiveTexture(TEXTURE_UNIT_TILE_HEIGHT_MAP);

		texTileHeightMap = glGenTextures();
		glBindTexture(GL_TEXTURE_3D, texTileHeightMap);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexImage3D(GL_TEXTURE_3D, 0, GL_R16I,
			EXTENDED_SCENE_SIZE, EXTENDED_SCENE_SIZE, Constants.MAX_Z,
			0, GL_RED_INTEGER, GL_SHORT, tileBuffer
		);

		glActiveTexture(TEXTURE_UNIT_UI); // default state
	}

	private void destroyTileHeightMap() {
		if (texTileHeightMap != 0)
			glDeleteTextures(texTileHeightMap);
		texTileHeightMap = 0;
	}

	@Override
	public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane) {
		if (sceneContext == null)
			return;

		frameTimer.begin(Timer.DRAW_FRAME);
		frameTimer.begin(Timer.DRAW_SCENE);

		final Scene scene = client.getScene();
		scene.setDrawDistance(getDrawDistance());

		viewportOffsetX = client.getViewportXOffset();
		viewportOffsetY = client.getViewportYOffset();

		if (!enableFreezeFrame) {
			if (!redrawPreviousFrame) {
				// Only reset the target buffer offset right before drawing the scene. That way if there are frames
				// after this that don't involve a scene draw, like during LOADING/HOPPING/CONNECTION_LOST, we can
				// still redraw the previous frame's scene to emulate the client behavior of not painting over the
				// viewport buffer.
				renderBufferOffset = sceneContext.staticVertexCount;

				// Push unordered models that should always be drawn at the start of each frame.
				// Used to fix issues like the right-click menu causing underwater tiles to disappear.
				var staticUnordered = sceneContext.staticUnorderedModelBuffer.getBuffer();
				modelPassthroughBuffer
					.ensureCapacity(staticUnordered.limit())
					.put(staticUnordered);
				staticUnordered.rewind();
				numPassthroughModels += staticUnordered.limit() / 8;
			}

			cameraPosition[0] = (float) cameraX;
			cameraPosition[1] = (float) cameraY;
			cameraPosition[2] = (float) cameraZ;
			cameraOrientation[0] = (float) cameraYaw;
			cameraOrientation[1] = (float) cameraPitch;

			if (sceneContext.scene == scene) {
				cameraFocalPoint[0] = client.getOculusOrbFocalPointX();
				cameraFocalPoint[1] = client.getOculusOrbFocalPointY();
				Arrays.fill(cameraShift, 0);

				try {
					frameTimer.begin(Timer.UPDATE_ENVIRONMENT);
					environmentManager.update(sceneContext);
					frameTimer.end(Timer.UPDATE_ENVIRONMENT);

					frameTimer.begin(Timer.UPDATE_LIGHTS);
					lightManager.update(sceneContext);
					frameTimer.end(Timer.UPDATE_LIGHTS);
				} catch (Exception ex) {
					log.error("Error while updating environment or lights:", ex);
					stopPlugin();
					return;
				}
			} else {
				cameraShift[0] = cameraFocalPoint[0] - client.getOculusOrbFocalPointX();
				cameraShift[1] = cameraFocalPoint[1] - client.getOculusOrbFocalPointY();
				cameraPosition[0] += cameraShift[0];
				cameraPosition[2] += cameraShift[1];
			}

			uniformBufferCamera
				.clear()
				.putFloat(cameraOrientation[0])
				.putFloat(cameraOrientation[1])
				.putInt(client.getCenterX())
				.putInt(client.getCenterY())
				.putInt(client.getScale())
				.putFloat(cameraPosition[0])
				.putFloat(cameraPosition[1])
				.putFloat(cameraPosition[2])
				.flip();
			glBindBuffer(GL_UNIFORM_BUFFER, hUniformBufferCamera.glBufferId);
			glBufferSubData(GL_UNIFORM_BUFFER, 0, uniformBufferCamera);
		}

		if (sceneContext.scene == scene) {
			// Update lights UBO
			uniformBufferLights.clear();
			assert sceneContext.numVisibleLights <= configMaxDynamicLights;
			for (int i = 0; i < sceneContext.numVisibleLights; i++) {
				Light light = sceneContext.lights.get(i);
				uniformBufferLights.putFloat(light.pos[0] + cameraShift[0]);
				uniformBufferLights.putFloat(light.pos[1]);
				uniformBufferLights.putFloat(light.pos[2] + cameraShift[1]);
				uniformBufferLights.putFloat(light.radius * light.radius);
				uniformBufferLights.putFloat(light.color[0] * light.strength);
				uniformBufferLights.putFloat(light.color[1] * light.strength);
				uniformBufferLights.putFloat(light.color[2] * light.strength);
				uniformBufferLights.putFloat(0); // pad
			}
			uniformBufferLights.flip();
			if (configMaxDynamicLights > 0) {
				glBindBuffer(GL_UNIFORM_BUFFER, hUniformBufferLights.glBufferId);
				glBufferSubData(GL_UNIFORM_BUFFER, 0, uniformBufferLights);
				glBindBuffer(GL_UNIFORM_BUFFER, 0);
			}
		}
	}

	@Override
	public void postDrawScene() {
		if (sceneContext == null)
			return;

		frameTimer.end(Timer.DRAW_SCENE);
		frameTimer.begin(Timer.UPLOAD_GEOMETRY);

		// The client only updates animations once per client tick, so we can skip updating geometry buffers,
		// but the compute shaders should still be executed in case the camera angle has changed.
		// Technically we could skip compute shaders as well when the camera is unchanged,
		// but it would only lead to micro stuttering when rotating the camera, compared to no rotation.
		if (!redrawPreviousFrame) {
			// Geometry buffers
			sceneContext.stagingBufferVertices.flip();
			sceneContext.stagingBufferUvs.flip();
			sceneContext.stagingBufferNormals.flip();
			updateBuffer(
				hStagingBufferVertices,
				GL_ARRAY_BUFFER,
				dynamicOffsetVertices * VERTEX_SIZE,
				sceneContext.stagingBufferVertices.getBuffer(),
				GL_STREAM_DRAW, CL_MEM_READ_ONLY
			);
			updateBuffer(
				hStagingBufferUvs,
				GL_ARRAY_BUFFER,
				dynamicOffsetUvs * UV_SIZE,
				sceneContext.stagingBufferUvs.getBuffer(),
				GL_STREAM_DRAW, CL_MEM_READ_ONLY
			);
			updateBuffer(
				hStagingBufferNormals,
				GL_ARRAY_BUFFER,
				dynamicOffsetVertices * NORMAL_SIZE,
				sceneContext.stagingBufferNormals.getBuffer(),
				GL_STREAM_DRAW, CL_MEM_READ_ONLY
			);
			sceneContext.stagingBufferVertices.clear();
			sceneContext.stagingBufferUvs.clear();
			sceneContext.stagingBufferNormals.clear();

			// Model buffers
			modelPassthroughBuffer.flip();
			updateBuffer(hModelPassthroughBuffer, GL_ARRAY_BUFFER, modelPassthroughBuffer.getBuffer(), GL_STREAM_DRAW, CL_MEM_READ_ONLY);
			modelPassthroughBuffer.clear();

			for (int i = 0; i < modelSortingBuffers.length; i++) {
				var buffer = modelSortingBuffers[i];
				buffer.flip();
				updateBuffer(hModelSortingBuffers[i], GL_ARRAY_BUFFER, buffer.getBuffer(), GL_STREAM_DRAW, CL_MEM_READ_ONLY);
				buffer.clear();
			}

			// Output buffers
			updateBuffer(
				hRenderBufferVertices,
				GL_ARRAY_BUFFER,
				renderBufferOffset * 16L, // each vertex is an ivec4, which is 16 bytes
				GL_STREAM_DRAW,
				CL_MEM_WRITE_ONLY
			);
			updateBuffer(
				hRenderBufferUvs,
				GL_ARRAY_BUFFER,
				renderBufferOffset * 16L, // each vertex is an ivec4, which is 16 bytes
				GL_STREAM_DRAW,
				CL_MEM_WRITE_ONLY
			);
			updateBuffer(
				hRenderBufferNormals,
				GL_ARRAY_BUFFER,
				renderBufferOffset * 16L, // each vertex is an ivec4, which is 16 bytes
				GL_STREAM_DRAW,
				CL_MEM_WRITE_ONLY
			);
			updateSceneVao(hRenderBufferVertices, hRenderBufferUvs, hRenderBufferNormals);
		}

		frameTimer.end(Timer.UPLOAD_GEOMETRY);
		frameTimer.begin(Timer.COMPUTE);

		if (computeMode == ComputeMode.OPENCL) {
			// The docs for clEnqueueAcquireGLObjects say all pending GL operations must be completed before calling
			// clEnqueueAcquireGLObjects, and recommends calling glFinish() as the only portable way to do that.
			// However, no issues have been observed from not calling it, and so will leave disabled for now.
			// glFinish();

			openCLManager.compute(
				hUniformBufferCamera,
				numPassthroughModels, numModelsToSort,
				hModelPassthroughBuffer, hModelSortingBuffers,
				hStagingBufferVertices, hStagingBufferUvs, hStagingBufferNormals,
				hRenderBufferVertices, hRenderBufferUvs, hRenderBufferNormals
			);
		} else {
			// Compute is split into a passthrough shader for unsorted models,
			// and multiple sizes of sorting shaders to better utilize the GPU

			// Bind shared buffers
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, hStagingBufferVertices.glBufferId);
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, hStagingBufferUvs.glBufferId);
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, hStagingBufferNormals.glBufferId);
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, hRenderBufferVertices.glBufferId);
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, hRenderBufferUvs.glBufferId);
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, hRenderBufferNormals.glBufferId);

			// unordered
			glUseProgram(glModelPassthroughComputeProgram);
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, hModelPassthroughBuffer.glBufferId);
			glDispatchCompute(numPassthroughModels, 1, 1);

			for (int i = 0; i < numModelsToSort.length; i++) {
				if (numModelsToSort[i] == 0)
					continue;

				glUseProgram(glModelSortingComputePrograms[i]);
				glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, hModelSortingBuffers[i].glBufferId);
				glDispatchCompute(numModelsToSort[i], 1, 1);
			}
		}

		frameTimer.end(Timer.COMPUTE);

		checkGLErrors();

		if (!redrawPreviousFrame) {
			numPassthroughModels = 0;
			Arrays.fill(numModelsToSort, 0);
		}
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileY) {
		if (redrawPreviousFrame || paint.getBufferLen() <= 0)
			return;

		int vertexCount = paint.getBufferLen();

		++numPassthroughModels;
		modelPassthroughBuffer
			.ensureCapacity(16)
			.getBuffer()
			.put(paint.getBufferOffset())
			.put(paint.getUvBufferOffset())
			.put(vertexCount / 3)
			.put(renderBufferOffset)
			.put(0)
			.put(tileX * LOCAL_TILE_SIZE)
			.put(0)
			.put(tileY * LOCAL_TILE_SIZE);

		renderBufferOffset += vertexCount;
	}

	public void initShaderHotswapping() {
		SHADER_PATH.watch("\\.(glsl|cl)$", path -> {
			log.info("Recompiling shaders: {}", path);
			clientThread.invoke(() -> {
				try {
					waitUntilIdle();
					recompilePrograms();
				} catch (ShaderException | IOException ex) {
					log.error("Error while recompiling shaders:", ex);
					stopPlugin();
				}
			});
		});
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileY) {
		if (redrawPreviousFrame || model.getBufferLen() <= 0)
			return;

		final int localX = tileX * LOCAL_TILE_SIZE;
		final int localY = 0;
		final int localZ = tileY * LOCAL_TILE_SIZE;

		GpuIntBuffer b = modelPassthroughBuffer;
		b.ensureCapacity(16);
		IntBuffer buffer = b.getBuffer();

		int bufferLength = model.getBufferLen();

		// we packed a boolean into the buffer length of tiles so we can tell
		// which tiles have procedurally-generated underwater terrain.
		// unpack the boolean:
		boolean underwaterTerrain = (bufferLength & 1) == 1;
		// restore the bufferLength variable:
		bufferLength = bufferLength >> 1;

		if (underwaterTerrain) {
			// draw underwater terrain tile before surface tile

			// buffer length includes the generated underwater terrain, so it must be halved
			bufferLength /= 2;

			++numPassthroughModels;

			buffer.put(model.getBufferOffset() + bufferLength);
			buffer.put(model.getUvBufferOffset() + bufferLength);
			buffer.put(bufferLength / 3);
			buffer.put(renderBufferOffset);
			buffer.put(0);
			buffer.put(localX).put(localY).put(localZ);

			renderBufferOffset += bufferLength;
		}

		++numPassthroughModels;

		buffer.put(model.getBufferOffset());
		buffer.put(model.getUvBufferOffset());
		buffer.put(bufferLength / 3);
		buffer.put(renderBufferOffset);
		buffer.put(0);
		buffer.put(localX).put(localY).put(localZ);

		renderBufferOffset += bufferLength;
	}

	private void prepareInterfaceTexture(int canvasWidth, int canvasHeight) {
		frameTimer.begin(Timer.UPLOAD_UI);

		if (canvasWidth != lastCanvasWidth || canvasHeight != lastCanvasHeight) {
			lastCanvasWidth = canvasWidth;
			lastCanvasHeight = canvasHeight;

			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
			glBufferData(GL_PIXEL_UNPACK_BUFFER, canvasWidth * canvasHeight * 4L, GL_STREAM_DRAW);
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

			glBindTexture(GL_TEXTURE_2D, interfaceTexture);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, canvasWidth, canvasHeight, 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
			glBindTexture(GL_TEXTURE_2D, 0);
		}

		final BufferProvider bufferProvider = client.getBufferProvider();
		final int[] pixels = bufferProvider.getPixels();
		final int width = bufferProvider.getWidth();
		final int height = bufferProvider.getHeight();

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		ByteBuffer mappedBuffer = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
		if (mappedBuffer == null) {
			log.error("Unable to map interface PBO. Skipping UI...");
		} else {
			mappedBuffer.asIntBuffer().put(pixels, 0, width * height);
			glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
			glBindTexture(GL_TEXTURE_2D, interfaceTexture);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		}
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, 0);

		frameTimer.end(Timer.UPLOAD_UI);
	}

	@Override
	public void draw(int overlayColor) {
		final GameState gameState = client.getGameState();
		if (gameState == GameState.STARTING) {
			frameTimer.end(Timer.DRAW_FRAME);
			return;
		}

		if (lastFrameTimeMillis > 0) {
			deltaTime = (float) ((System.currentTimeMillis() - lastFrameTimeMillis) / 1000.);

			// Restart the plugin to avoid potential buffer corruption if the computer has likely resumed from suspension
			if (deltaTime > 300) {
				log.debug("Restarting the plugin after probable OS suspend ({} second delta)", deltaTime);
				restartPlugin();
				return;
			}

			// If system time changes between frames, clamp the delta to a more sensible value
			if (Math.abs(deltaTime) > 10)
				deltaTime = 1 / 60.f;
			elapsedTime += deltaTime;

			// The client delta doesn't need clamping
			deltaClientTime = (float) (elapsedClientTime - lastFrameClientTime);
		}
		lastFrameTimeMillis = System.currentTimeMillis();
		lastFrameClientTime = elapsedClientTime;

		final int canvasWidth = client.getCanvasWidth();
		final int canvasHeight = client.getCanvasHeight();

		try {
			prepareInterfaceTexture(canvasWidth, canvasHeight);
		} catch (Exception ex) {
			// Fixes: https://github.com/runelite/runelite/issues/12930
			// Gracefully Handle loss of opengl buffers and context
			log.warn("prepareInterfaceTexture exception", ex);
			restartPlugin();
			return;
		}

		// Upon logging in, the client will draw some frames with zero geometry before it hides the login screen
		if (renderBufferOffset > 0)
			hasLoggedIn = true;

		// Draw 3d scene
		final TextureProvider textureProvider = client.getTextureProvider();
		if (
			hasLoggedIn &&
			sceneContext != null &&
			textureProvider != null &&
			client.getGameState().getState() >= GameState.LOADING.getState()
		) {
			final int viewportHeight = client.getViewportHeight();
			final int viewportWidth = client.getViewportWidth();

			int renderWidthOff = viewportOffsetX;
			int renderHeightOff = viewportOffsetY;
			int renderCanvasHeight = canvasHeight;
			int renderViewportHeight = viewportHeight;
			int renderViewportWidth = viewportWidth;

			if (client.isStretchedEnabled()) {
				Dimension dim = client.getStretchedDimensions();
				renderCanvasHeight = dim.height;

				double scaleFactorY = dim.getHeight() / canvasHeight;
				double scaleFactorX = dim.getWidth() / canvasWidth;

				// Pad the viewport a little because having ints for our viewport dimensions can introduce off-by-one errors.
				final int padding = 1;

				// Ceil the sizes because even if the size is 599.1 we want to treat it as size 600 (i.e. render to the x=599 pixel).
				renderViewportHeight = (int) Math.ceil(scaleFactorY * (renderViewportHeight)) + padding * 2;
				renderViewportWidth  = (int) Math.ceil(scaleFactorX * (renderViewportWidth )) + padding * 2;

				// Floor the offsets because even if the offset is 4.9, we want to render to the x=4 pixel anyway.
				renderHeightOff      = (int) Math.floor(scaleFactorY * (renderHeightOff)) - padding;
				renderWidthOff       = (int) Math.floor(scaleFactorX * (renderWidthOff )) - padding;
			}

			int[] dpiViewport = applyDpiScaling(
				renderWidthOff,
				renderCanvasHeight - renderViewportHeight - renderHeightOff,
				renderViewportWidth,
				renderViewportHeight
			);

			// Before reading the SSBOs written to from postDrawScene() we must insert a barrier
			if (computeMode == ComputeMode.OPENCL) {
				openCLManager.finish();
			} else {
				glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
			}

			glBindVertexArray(vaoSceneHandle);

			float[] lightViewMatrix = Mat4.rotateX(PI + environmentManager.currentSunAngles[0]);
			Mat4.mul(lightViewMatrix, Mat4.rotateY(PI - environmentManager.currentSunAngles[1]));

			float[] lightProjectionMatrix = Mat4.identity();
			if (configShadowsEnabled && fboShadowMap != 0 && environmentManager.currentDirectionalStrength > 0) {
				frameTimer.begin(Timer.RENDER_SHADOWS);

				// Render to the shadow depth map
				glViewport(0, 0, shadowMapResolution, shadowMapResolution);
				glBindFramebuffer(GL_FRAMEBUFFER, fboShadowMap);
				glClearDepthf(1);
				glClear(GL_DEPTH_BUFFER_BIT);
				glDepthFunc(GL_LEQUAL);

				glUseProgram(glShadowProgram);

				final int camX = cameraFocalPoint[0];
				final int camY = cameraFocalPoint[1];

				final int drawDistanceSceneUnits = Math.min(config.shadowDistance().getValue(), getDrawDistance()) * LOCAL_TILE_SIZE / 2;
				final int east = Math.min(camX + drawDistanceSceneUnits, LOCAL_TILE_SIZE * SCENE_SIZE);
				final int west = Math.max(camX - drawDistanceSceneUnits, 0);
				final int north = Math.min(camY + drawDistanceSceneUnits, LOCAL_TILE_SIZE * SCENE_SIZE);
				final int south = Math.max(camY - drawDistanceSceneUnits, 0);
				final int width = east - west;
				final int height = north - south;
				final int near = 10000;

				final int maxDrawDistance = 90;
				final float maxScale = 0.7f;
				final float minScale = 0.4f;
				final float scaleMultiplier = 1.0f - (getDrawDistance() / (maxDrawDistance * maxScale));
				float scale = HDUtils.lerp(maxScale, minScale, scaleMultiplier);
				Mat4.mul(lightProjectionMatrix, Mat4.scale(scale, scale, scale));
				Mat4.mul(lightProjectionMatrix, Mat4.ortho(width, height, near));
				Mat4.mul(lightProjectionMatrix, lightViewMatrix);
				Mat4.mul(lightProjectionMatrix, Mat4.translate(-(width / 2f + west), 0, -(height / 2f + south)));
				glUniformMatrix4fv(uniShadowLightProjectionMatrix, false, lightProjectionMatrix);

				// bind uniforms
				if (configShadowMode == ShadowMode.DETAILED) {
					glUniform1f(uniShadowElapsedTime, (float) (elapsedTime % MAX_FLOAT_WITH_128TH_PRECISION));
					glUniform3fv(uniShadowCameraPos, cameraPosition);
				}

				glEnable(GL_CULL_FACE);
				glEnable(GL_DEPTH_TEST);

				// Draw with buffers bound to scene VAO
				glDrawArrays(GL_TRIANGLES, 0, renderBufferOffset);

				glDisable(GL_CULL_FACE);
				glDisable(GL_DEPTH_TEST);

				glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

				glUseProgram(0);

				frameTimer.end(Timer.RENDER_SHADOWS);
			}

			glUseProgram(glSceneProgram);

			final AntiAliasingMode antiAliasingMode = config.antiAliasingMode();
			final Dimension stretchedDimensions = client.getStretchedDimensions();
			final int stretchedCanvasWidth = client.isStretchedEnabled() ? stretchedDimensions.width : canvasWidth;
			final int stretchedCanvasHeight = client.isStretchedEnabled() ? stretchedDimensions.height : canvasHeight;

			// Check if scene FBO needs to be recreated
			if (lastAntiAliasingMode != antiAliasingMode ||
				lastStretchedCanvasWidth != stretchedCanvasWidth ||
				lastStretchedCanvasHeight != stretchedCanvasHeight
			) {
				lastAntiAliasingMode = antiAliasingMode;
				lastStretchedCanvasWidth = stretchedCanvasWidth;
				lastStretchedCanvasHeight = stretchedCanvasHeight;

				destroySceneFbo();
				try {
					initSceneFbo(stretchedCanvasWidth, stretchedCanvasHeight, antiAliasingMode);
				} catch (Exception ex) {
					log.error("Error while initializing scene FBO:", ex);
					stopPlugin();
					return;
				}
			}

			float[] fogColor = ColorUtils.linearToSrgb(environmentManager.currentFogColor);
			float fogDepth = 0;
			switch (config.fogDepthMode()) {
				case USER_DEFINED:
					fogDepth = config.fogDepth();
					break;
				case DYNAMIC:
					fogDepth = environmentManager.currentFogDepth;
					break;
			}
			fogDepth *= Math.min(getDrawDistance(), 90) / 10.f;
			glUniform1i(uniUseFog, fogDepth > 0 ? 1 : 0);
			glUniform1f(uniFogDepth, fogDepth);
			glUniform3fv(uniFogColor, fogColor);

			glUniform1f(uniDrawDistance, getDrawDistance());
			glUniform1i(uniExpandedMapLoadingChunks, sceneContext.expandedMapLoadingChunks);
			glUniform1f(uniColorBlindnessIntensity, config.colorBlindnessIntensity() / 100.f);

			float[] waterColorHsv = ColorUtils.srgbToHsv(environmentManager.currentWaterColor);
			float lightBrightnessMultiplier = 0.8f;
			float midBrightnessMultiplier = 0.45f;
			float darkBrightnessMultiplier = 0.05f;
			float[] waterColorLight = ColorUtils.linearToSrgb(ColorUtils.hsvToSrgb(new float[] {
				waterColorHsv[0],
				waterColorHsv[1],
				waterColorHsv[2] * lightBrightnessMultiplier
			}));
			float[] waterColorMid = ColorUtils.linearToSrgb(ColorUtils.hsvToSrgb(new float[] {
				waterColorHsv[0],
				waterColorHsv[1],
				waterColorHsv[2] * midBrightnessMultiplier
			}));
			float[] waterColorDark = ColorUtils.linearToSrgb(ColorUtils.hsvToSrgb(new float[] {
				waterColorHsv[0],
				waterColorHsv[1],
				waterColorHsv[2] * darkBrightnessMultiplier
			}));
			glUniform3fv(uniWaterColorLight, waterColorLight);
			glUniform3fv(uniWaterColorMid, waterColorMid);
			glUniform3fv(uniWaterColorDark, waterColorDark);

			float brightness = config.brightness() / 20f;
			glUniform1f(uniAmbientStrength, environmentManager.currentAmbientStrength * brightness);
			glUniform3fv(uniAmbientColor, environmentManager.currentAmbientColor);
			glUniform1f(uniLightStrength, environmentManager.currentDirectionalStrength * brightness);
			glUniform3fv(uniLightColor, environmentManager.currentDirectionalColor);

			glUniform1f(uniUnderglowStrength, environmentManager.currentUnderglowStrength);
			glUniform3fv(uniUnderglowColor, environmentManager.currentUnderglowColor);

			glUniform1f(uniGroundFogStart, environmentManager.currentGroundFogStart);
			glUniform1f(uniGroundFogEnd, environmentManager.currentGroundFogEnd);
			glUniform1f(uniGroundFogOpacity, config.groundFog() ? environmentManager.currentGroundFogOpacity : 0);

			// Lights & lightning
			glUniform1i(uniPointLightsCount, sceneContext.numVisibleLights);
			glUniform1f(uniLightningBrightness, environmentManager.getLightningBrightness());

			glUniform1f(uniSaturation, config.saturation() / 100f);
			glUniform1f(uniContrast, config.contrast() / 100f);
			glUniform1i(uniUnderwaterEnvironment, environmentManager.isUnderwater() ? 1 : 0);
			glUniform1i(uniUnderwaterCaustics, config.underwaterCaustics() ? 1 : 0);
			glUniform3fv(uniUnderwaterCausticsColor, environmentManager.currentUnderwaterCausticsColor);
			glUniform1f(uniUnderwaterCausticsStrength, environmentManager.currentUnderwaterCausticsStrength);
			glUniform1f(uniElapsedTime, (float) (elapsedTime % MAX_FLOAT_WITH_128TH_PRECISION));
			glUniform3fv(uniCameraPos, cameraPosition);

			// Extract the 3rd column from the light view matrix (the float array is column-major)
			// This produces the view matrix's forward direction vector in world space,
			// which in our case is the negative of the light's direction
			glUniform3f(uniLightDir, lightViewMatrix[2], lightViewMatrix[6], lightViewMatrix[10]);

			// use a curve to calculate max bias value based on the density of the shadow map
			float shadowPixelsPerTile = (float) shadowMapResolution / config.shadowDistance().getValue();
			float maxBias = 26f * (float) Math.pow(0.925f, (0.4f * shadowPixelsPerTile - 10f)) + 13f;
			glUniform1f(uniShadowMaxBias, maxBias / 10000f);

			glUniform1i(uniShadowsEnabled, configShadowsEnabled ? 1 : 0);

			if (configColorFilter != ColorFilter.NONE) {
				glUniform1i(uniColorFilter, configColorFilter.ordinal());
				glUniform1i(uniColorFilterPrevious, configColorFilterPrevious.ordinal());
				long timeSinceChange = System.currentTimeMillis() - colorFilterChangedAt;
				glUniform1f(uniColorFilterFade, clamp(timeSinceChange / COLOR_FILTER_FADE_DURATION, 0, 1));
			}

			// Calculate projection matrix
			float[] projectionMatrix = Mat4.scale(client.getScale(), client.getScale(), 1);
			Mat4.mul(projectionMatrix, Mat4.projection(viewportWidth, viewportHeight, NEAR_PLANE));
			Mat4.mul(projectionMatrix, Mat4.rotateX(cameraOrientation[1]));
			Mat4.mul(projectionMatrix, Mat4.rotateY(cameraOrientation[0]));
			Mat4.mul(projectionMatrix, Mat4.translate(
				-cameraPosition[0],
				-cameraPosition[1],
				-cameraPosition[2]
			));
			glUniformMatrix4fv(uniProjectionMatrix, false, projectionMatrix);

			// Bind directional light projection matrix
			glUniformMatrix4fv(uniLightProjectionMatrix, false, lightProjectionMatrix);

			// Bind uniforms
			glUniformBlockBinding(glSceneProgram, uniBlockMaterials, UNIFORM_BLOCK_MATERIALS);
			glUniformBlockBinding(glSceneProgram, uniBlockWaterTypes, UNIFORM_BLOCK_WATER_TYPES);
			glUniformBlockBinding(glSceneProgram, uniBlockPointLights, UNIFORM_BLOCK_LIGHTS);

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboSceneHandle);
			glToggle(GL_MULTISAMPLE, numSamples > 1);
			glViewport(dpiViewport[0], dpiViewport[1], dpiViewport[2], dpiViewport[3]);

			// Clear scene
			frameTimer.begin(Timer.CLEAR_SCENE);
			glClearColor(fogColor[0], fogColor[1], fogColor[2], 1f);
			glClear(GL_COLOR_BUFFER_BIT);
			frameTimer.end(Timer.CLEAR_SCENE);

			frameTimer.begin(Timer.RENDER_SCENE);

			// We just allow the GL to do face culling. Note this requires the priority renderer
			// to have logic to disregard culled faces in the priority depth testing.
			glEnable(GL_CULL_FACE);
			glCullFace(GL_BACK);

			// Enable blending for alpha
			glEnable(GL_BLEND);
			glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE);

			// Draw with buffers bound to scene VAO
			glBindVertexArray(vaoSceneHandle);

			glDrawArrays(GL_TRIANGLES, 0, renderBufferOffset);

			frameTimer.end(Timer.RENDER_SCENE);

			glDisable(GL_BLEND);
			glDisable(GL_CULL_FACE);
			glDisable(GL_MULTISAMPLE);

			glUseProgram(0);

			// Blit from the scene FBO to the default FBO
			int[] dimensions = applyDpiScaling(stretchedCanvasWidth, stretchedCanvasHeight);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, fboSceneHandle);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
			glBlitFramebuffer(
				0, 0, dimensions[0], dimensions[1],
				0, 0, dimensions[0], dimensions[1],
				GL_COLOR_BUFFER_BIT, GL_NEAREST
			);

			// Reset
			glBindFramebuffer(GL_READ_FRAMEBUFFER, awtContext.getFramebuffer(false));
		} else {
			glClearColor(0, 0, 0, 1f);
			glClear(GL_COLOR_BUFFER_BIT);
		}

		// Texture on UI
		drawUi(overlayColor, canvasHeight, canvasWidth);

		try {
			frameTimer.begin(Timer.SWAP_BUFFERS);
			awtContext.swapBuffers();
			frameTimer.end(Timer.SWAP_BUFFERS);
			drawManager.processDrawComplete(this::screenshot);
		} catch (RuntimeException ex) {
			// this is always fatal
			if (!canvas.isValid()) {
				// this might be AWT shutting down on VM shutdown, ignore it
				return;
			}

			log.error("Unable to swap buffers:", ex);
		}

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

		frameTimer.end(Timer.DRAW_FRAME);
		frameTimer.endFrameAndReset();
		frameModelInfoMap.clear();
		checkGLErrors();

		// Process pending config changes after the EDT is done with any pending work, which could include further config changes
		if (!pendingConfigChanges.isEmpty())
			SwingUtilities.invokeLater(this::processPendingConfigChanges);
	}

	private void drawUi(int overlayColor, final int canvasHeight, final int canvasWidth) {
		frameTimer.begin(Timer.RENDER_UI);

		// Fix vanilla bug causing the overlay to remain on the login screen in areas like Fossil Island underwater
		if (client.getGameState().getState() < GameState.LOADING.getState())
			overlayColor = 0;

		glEnable(GL_BLEND);

		glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);

		// Use the texture bound in the first pass
		glUseProgram(glUiProgram);
		glUniform2i(uniTexSourceDimensions, canvasWidth, canvasHeight);
		glUniform1f(uniUiColorBlindnessIntensity, config.colorBlindnessIntensity() / 100f);
		glUniform4fv(uniUiAlphaOverlay, ColorUtils.srgba(overlayColor));

		if (client.isStretchedEnabled()) {
			Dimension dim = client.getStretchedDimensions();
			glDpiAwareViewport(0, 0, dim.width, dim.height);
			glUniform2i(uniTexTargetDimensions, dim.width, dim.height);
		} else {
			glDpiAwareViewport(0, 0, canvasWidth, canvasHeight);
			glUniform2i(uniTexTargetDimensions, canvasWidth, canvasHeight);
		}

		// Set the sampling function used when stretching the UI.
		// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
		// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
		// GL_NEAREST makes sampling for bicubic/xBR simpler, so it should be used whenever linear isn't
		final int function = config.uiScalingMode() == UIScalingMode.LINEAR ? GL_LINEAR : GL_NEAREST;
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, function);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, function);

		// Texture on UI
		glBindVertexArray(vaoUiHandle);
		glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

		frameTimer.end(Timer.RENDER_UI);

		// Reset
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindVertexArray(0);
		glUseProgram(0);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glDisable(GL_BLEND);
	}

	/**
	 * Convert the front framebuffer to an Image
	 */
	private Image screenshot()
	{
		int width  = client.getCanvasWidth();
		int height = client.getCanvasHeight();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			width  = dim.width;
			height = dim.height;
		}

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		width = getScaledValue(t.getScaleX(), width);
		height = getScaledValue(t.getScaleY(), height);

		ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);

		glReadBuffer(awtContext.getBufferMode());
		glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		for (int y = 0; y < height; ++y)
		{
			for (int x = 0; x < width; ++x)
			{
				int r = buffer.get() & 0xff;
				int g = buffer.get() & 0xff;
				int b = buffer.get() & 0xff;
				buffer.get(); // alpha

				pixels[(height - y - 1) * width + x] = (r << 16) | (g << 8) | b;
			}
		}

		return image;
	}

	@Override
	public void animate(Texture texture, int diff) {}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN) {
			renderBufferOffset = 0;
			hasLoggedIn = false;
			environmentManager.reset();
		}
	}

	public void reuploadScene() {
		assert client.isClientThread() : "Loading a scene is unsafe while the client can modify it";
		if (client.getGameState().getState() < GameState.LOGGED_IN.getState())
			return;
		Scene scene = client.getScene();
		loadScene(scene);
		if (skipScene == scene)
			skipScene = null;
		swapScene(scene);
	}

	@Override
	public void loadScene(Scene scene) {
		if (!isActive)
			return;

		if (skipScene != scene && HDUtils.sceneIntersects(scene, getExpandedMapLoadingChunks(), Area.THE_GAUNTLET)) {
			// Some game objects in The Gauntlet are spawned in too late for the initial scene load,
			// so we skip the first scene load and trigger another scene load the next game tick
			reloadSceneNextGameTick();
			skipScene = scene;
			return;
		}

		if (useLowMemoryMode)
			return; // Force scene loading to happen on the client thread

		loadSceneInternal(scene);
	}

	private synchronized void loadSceneInternal(Scene scene) {
		if (nextSceneContext != null)
			nextSceneContext.destroy();
		nextSceneContext = null;

		try {
			// Because scene contexts are always swapped on the client thread, it is guaranteed to only be
			// in use by the client thread, meaning we can reuse all of its buffers if we are loading the
			// next scene also on the client thread
			boolean reuseBuffers = client.isClientThread();
			var context = new SceneContext(scene, getExpandedMapLoadingChunks(), reuseBuffers, sceneContext);
			// noinspection SynchronizationOnLocalVariableOrMethodParameter
			synchronized (context) {
				nextSceneContext = context;
				proceduralGenerator.generateSceneData(context);
				environmentManager.loadSceneEnvironments(context);
				sceneUploader.upload(context);
			}
		} catch (OutOfMemoryError oom) {
			log.error("Ran out of memory while loading scene (32-bit: {}, low memory mode: {})",
				HDUtils.is32Bit(), useLowMemoryMode, oom
			);
			displayOutOfMemoryMessage();
			stopPlugin();
		} catch (Throwable ex) {
			log.error("Error while loading scene:", ex);
			stopPlugin();
		}
	}

	@Override
	public synchronized void swapScene(Scene scene) {
		if (skipScene == scene) {
			redrawPreviousFrame = true;
			return;
		}

		// If the scene wasn't loaded by a call to loadScene, load it synchronously instead
		if (nextSceneContext == null) {
			loadSceneInternal(scene);
			if (nextSceneContext == null)
				return; // Return early if scene loading failed
		}

		if (computeMode == ComputeMode.OPENCL) {
			openCLManager.uploadTileHeights(scene);
		} else {
			initTileHeightMap(scene);
		}

		lightManager.loadSceneLights(nextSceneContext, sceneContext);
		fishingSpotReplacer.despawnRuneLiteObjects();

		if (sceneContext != null)
			sceneContext.destroy();
		sceneContext = nextSceneContext;
		nextSceneContext = null;
		assert sceneContext != null;

		// Gaps need to be filled in swapScene, since map regions aren't updated earlier
		if (config.fillGapsInTerrain())
			sceneUploader.fillGaps(sceneContext);
		sceneContext.staticUnorderedModelBuffer.flip();

		dynamicOffsetVertices = sceneContext.getVertexOffset();
		dynamicOffsetUvs = sceneContext.getUvOffset();

		sceneContext.stagingBufferVertices.flip();
		sceneContext.stagingBufferUvs.flip();
		sceneContext.stagingBufferNormals.flip();
		updateBuffer(
			hStagingBufferVertices,
			GL_ARRAY_BUFFER,
			sceneContext.stagingBufferVertices.getBuffer(),
			GL_STREAM_DRAW,
			CL_MEM_READ_ONLY
		);
		updateBuffer(
			hStagingBufferUvs,
			GL_ARRAY_BUFFER,
			sceneContext.stagingBufferUvs.getBuffer(),
			GL_STREAM_DRAW,
			CL_MEM_READ_ONLY
		);
		updateBuffer(
			hStagingBufferNormals,
			GL_ARRAY_BUFFER,
			sceneContext.stagingBufferNormals.getBuffer(),
			GL_STREAM_DRAW,
			CL_MEM_READ_ONLY
		);
		sceneContext.stagingBufferVertices.clear();
		sceneContext.stagingBufferUvs.clear();
		sceneContext.stagingBufferNormals.clear();

		if (sceneContext.intersects(Area.PLAYER_OWNED_HOUSE)) {
			if (!isInHouse) {
				// POH takes 1 game tick to enter, then 2 game ticks to load per floor
				reloadSceneIn(7);
				isInHouse = true;
			}

			isInChambersOfXeric = false;
		} else {
			// Avoid an unnecessary scene reload if the player is leaving the POH
			if (isInHouse) {
				abortSceneReload();
				isInHouse = false;
			}

			isInChambersOfXeric = sceneContext.intersects(Area.CHAMBERS_OF_XERIC);
		}
	}

	public void reloadSceneNextGameTick()
	{
		reloadSceneIn(1);
	}

	public void reloadSceneIn(int gameTicks) {
		assert gameTicks > 0 : "A value <= 0 will not reload the scene";
		if (gameTicks > gameTicksUntilSceneReload)
			gameTicksUntilSceneReload = gameTicks;
	}

	public void abortSceneReload() {
		gameTicksUntilSceneReload = 0;
	}

	private void updateCachedConfigs() {
		configShadowMode = config.shadowMode();
		configShadowsEnabled = configShadowMode != ShadowMode.OFF;
		configGroundTextures = config.groundTextures();
		configGroundBlending = config.groundBlending();
		configModelTextures = config.modelTextures();
		configTzhaarHD = config.hdTzHaarReskin();
		configProjectileLights = config.projectileLights();
		configNpcLights = config.npcLights();
		configVanillaShadowMode = config.vanillaShadowMode();
		configHideFakeShadows = configVanillaShadowMode != VanillaShadowMode.SHOW;
		configLegacyGreyColors = config.legacyGreyColors();
		configModelBatching = config.modelBatching();
		configModelCaching = config.modelCaching();
		configMaxDynamicLights = config.maxDynamicLights().getValue();
		configExpandShadowDraw = config.expandShadowDraw();
		configUseFasterModelHashing = config.fasterModelHashing();
		configUndoVanillaShading = config.shadingMode() != ShadingMode.VANILLA;
		configPreserveVanillaNormals = config.preserveVanillaNormals();
		configSeasonalTheme = config.seasonalTheme();

		var newColorFilter = config.colorFilter();
		if (newColorFilter != configColorFilter) {
			configColorFilterPrevious = configColorFilter;
			configColorFilter = newColorFilter;
			colorFilterChangedAt = System.currentTimeMillis();
		}

		if (configSeasonalTheme == SeasonalTheme.AUTOMATIC) {
			var time = ZonedDateTime.now(ZoneOffset.UTC);
			switch (time.getMonth()) {
				case SEPTEMBER:
				case OCTOBER:
				case NOVEMBER:
					configSeasonalTheme = SeasonalTheme.AUTUMN;
					break;
				case DECEMBER:
				case JANUARY:
				case FEBRUARY:
					configSeasonalTheme = SeasonalTheme.WINTER;
					break;
				default:
					configSeasonalTheme = SeasonalTheme.SUMMER;
					break;
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		// Exit if the plugin is off, the config is unrelated to the plugin, or if switching to a profile with the plugin turned off
		if (!isActive || !event.getGroup().equals(CONFIG_GROUP) || !pluginManager.isPluginEnabled(this))
			return;

		pendingConfigChanges.add(event.getKey());
	}

	private void processPendingConfigChanges() {
		clientThread.invoke(() -> {
			if (pendingConfigChanges.isEmpty())
				return;

			try {
				// Synchronize with scene loading
				synchronized (this) {
					updateCachedConfigs();

					log.debug("Processing {} pending config changes: {}", pendingConfigChanges.size(), pendingConfigChanges);

					boolean recompilePrograms = false;
					boolean recreateShadowMapFbo = false;
					boolean reloadTexturesAndMaterials = false;
					boolean reloadEnvironments = false;
					boolean reloadModelOverrides = false;
					boolean reloadTileOverrides = false;
					boolean reloadScene = false;
					boolean clearModelCache = false;
					boolean resizeModelCache = false;

					for (var key : pendingConfigChanges) {
						switch (key) {
							case KEY_SEASONAL_THEME:
							case KEY_GROUND_BLENDING:
							case KEY_GROUND_TEXTURES:
								reloadTileOverrides = true;
								break;
						}

						switch (key) {
							case KEY_EXPANDED_MAP_LOADING_CHUNKS:
								client.setExpandedMapLoading(getExpandedMapLoadingChunks());
								if (client.getGameState() == GameState.LOGGED_IN)
									client.setGameState(GameState.LOADING);
								break;
							case KEY_COLOR_BLINDNESS:
							case KEY_MACOS_INTEL_WORKAROUND:
							case KEY_MAX_DYNAMIC_LIGHTS:
							case KEY_NORMAL_MAPPING:
							case KEY_PARALLAX_OCCLUSION_MAPPING:
							case KEY_UI_SCALING_MODE:
							case KEY_VANILLA_COLOR_BANDING:
							case KEY_COLOR_FILTER:
								recompilePrograms = true;
								break;
							case KEY_SHADOW_MODE:
							case KEY_SHADOW_TRANSPARENCY:
								recompilePrograms = true;
								// fall-through
							case KEY_SHADOW_RESOLUTION:
								recreateShadowMapFbo = true;
								break;
							case KEY_ATMOSPHERIC_LIGHTING:
								reloadEnvironments = true;
								break;
							case KEY_SEASONAL_THEME:
								reloadEnvironments = true;
								reloadModelOverrides = true;
								// fall-through
							case KEY_ANISOTROPIC_FILTERING_LEVEL:
							case KEY_GROUND_TEXTURES:
							case KEY_MODEL_TEXTURES:
							case KEY_TEXTURE_RESOLUTION:
							case KEY_HD_INFERNAL_CAPE:
								reloadTexturesAndMaterials = true;
								// fall-through
							case KEY_GROUND_BLENDING:
							case KEY_FILL_GAPS_IN_TERRAIN:
							case KEY_HD_TZHAAR_RESKIN:
								clearModelCache = true;
								reloadScene = true;
								break;
							case KEY_VANILLA_SHADOW_MODE:
								reloadModelOverrides = true;
								reloadScene = true;
								break;
							case KEY_LEGACY_GREY_COLORS:
							case KEY_PRESERVE_VANILLA_NORMALS:
							case KEY_SHADING_MODE:
							case KEY_FLAT_SHADING:
								recompilePrograms = true;
								clearModelCache = true;
								reloadScene = true;
								break;
							case KEY_FPS_TARGET:
							case KEY_UNLOCK_FPS:
							case KEY_VSYNC_MODE:
								setupSyncMode();
								break;
							case KEY_MODEL_CACHE_SIZE:
							case KEY_MODEL_CACHING:
								resizeModelCache = true;
								break;
							case KEY_LOW_MEMORY_MODE:
								restartPlugin();
								// since we'll be restarting the plugin anyway, skip pending changes
								return;
							case KEY_REPLACE_FISHING_SPOTS:
								reloadModelOverrides = true;
								fishingSpotReplacer.despawnRuneLiteObjects();
								clientThread.invokeLater(fishingSpotReplacer::update);
								break;
						}
					}

					if (reloadTexturesAndMaterials || recompilePrograms)
						waitUntilIdle();

					if (reloadTexturesAndMaterials) {
						textureManager.reloadTextures();
						recompilePrograms = true;
						clearModelCache = true;
					} else if (reloadModelOverrides) {
						modelOverrideManager.reload();
						clearModelCache = true;
					}

					if (reloadTileOverrides) {
						tileOverrideManager.reload(false);
						reloadScene = true;
					}

					if (recompilePrograms)
						recompilePrograms();

					if (resizeModelCache) {
						modelPusher.shutDown();
						modelPusher.startUp();
					} else if (clearModelCache) {
						modelPusher.clearModelCache();
					}

					if (reloadScene)
						reuploadScene();

					if (recreateShadowMapFbo) {
						destroyShadowMapFbo();
						initShadowMapFbo();
					}

					if (reloadEnvironments)
						environmentManager.triggerTransition();
				}
			} catch (Throwable ex) {
				log.error("Error while changing settings:", ex);
				stopPlugin();
			} finally {
				pendingConfigChanges.clear();
			}
		});
	}

	private void setupSyncMode()
	{
		final boolean unlockFps = config.unlockFps();
		client.setUnlockedFps(unlockFps);

		// Without unlocked fps, the client manages sync on its 20ms timer
		HdPluginConfig.SyncMode syncMode = unlockFps ? config.syncMode() : HdPluginConfig.SyncMode.OFF;

		int swapInterval;
		switch (syncMode)
		{
			case ON:
				swapInterval = 1;
				break;
			case ADAPTIVE:
				swapInterval = -1;
				break;
			default:
			case OFF:
				swapInterval = 0;
				break;
		}

		int actualSwapInterval = awtContext.setSwapInterval(swapInterval);
		if (actualSwapInterval != swapInterval) {
			log.info("unsupported swap interval {}, got {}", swapInterval, actualSwapInterval);
		}

		client.setUnlockedFpsTarget(actualSwapInterval == 0 ? config.fpsTarget() : 0);
		checkGLErrors();
	}

	@Override
	public boolean tileInFrustum(
		Scene scene,
		int pitchSin,
		int pitchCos,
		int yawSin,
		int yawCos,
		int cameraX,
		int cameraY,
		int cameraZ,
		int plane,
		int tileExX,
		int tileExY
	) {
		if (sceneContext == null)
			return false;

		int[][][] tileHeights = scene.getTileHeights();
		int x = ((tileExX - SCENE_OFFSET) << Perspective.LOCAL_COORD_BITS) + 64;
		int z = ((tileExY - SCENE_OFFSET) << Perspective.LOCAL_COORD_BITS) + 64;
		int y = Math.max(
			Math.max(tileHeights[plane][tileExX][tileExY], tileHeights[plane][tileExX][tileExY + 1]),
			Math.max(tileHeights[plane][tileExX + 1][tileExY], tileHeights[plane][tileExX + 1][tileExY + 1])
		) + GROUND_MIN_Y;

		if (sceneContext.scene == scene) {
			int depthLevel = sceneContext.underwaterDepthLevels[plane][tileExX][tileExY];
			if (depthLevel > 0)
				y += ProceduralGenerator.DEPTH_LEVEL_SLOPE[depthLevel - 1] - GROUND_MIN_Y;
		}

		x -= (int) cameraPosition[0];
		y -= (int) cameraPosition[1];
		z -= (int) cameraPosition[2];

		int radius = 96; // ~ 64 * sqrt(2)

		int zoom = (configShadowsEnabled && configExpandShadowDraw) ? client.get3dZoom() / 2 : client.get3dZoom();
		int Rasterizer3D_clipMidX2 = client.getRasterizer3D_clipMidX2();
		int Rasterizer3D_clipNegativeMidX = client.getRasterizer3D_clipNegativeMidX();
		int Rasterizer3D_clipNegativeMidY = client.getRasterizer3D_clipNegativeMidY();

		int var11 = yawCos * z - yawSin * x >> 16;
		int var12 = pitchSin * y + pitchCos * var11 >> 16;
		int var13 = pitchCos * radius >> 16;
		int depth = var12 + var13;
		if (depth > NEAR_PLANE) {
			int rx = z * yawSin + yawCos * x >> 16;
			int var16 = (rx - radius) * zoom;
			int var17 = (rx + radius) * zoom;
			// left && right
			if (var16 < Rasterizer3D_clipMidX2 * depth && var17 > Rasterizer3D_clipNegativeMidX * depth) {
				int ry = pitchCos * y - var11 * pitchSin >> 16;
				int ybottom = pitchSin * radius >> 16;
				int var20 = (ry + ybottom) * zoom;
				// top
				// we don't test the bottom so we don't have to find the height of all the models on the tile
				return var20 > Rasterizer3D_clipNegativeMidY * depth;
			}
		}
		return false;
	}

	/**
	 * Check is a model is visible and should be drawn.
	 */
	private boolean isOutsideViewport(Model model, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z) {
		if (sceneContext == null)
			return true;

		final int XYZMag = model.getXYZMag();
		final int bottomY = model.getBottomY();
		final int zoom = (configShadowsEnabled && configExpandShadowDraw) ? client.get3dZoom() / 2 : client.get3dZoom();
		final int modelHeight = model.getModelHeight();

		int Rasterizer3D_clipMidX2 = client.getRasterizer3D_clipMidX2();
		int Rasterizer3D_clipNegativeMidX = client.getRasterizer3D_clipNegativeMidX();
		int Rasterizer3D_clipNegativeMidY = client.getRasterizer3D_clipNegativeMidY();
		int Rasterizer3D_clipMidY2 = client.getRasterizer3D_clipMidY2();

		int var11 = yawCos * z - yawSin * x >> 16;
		int var12 = pitchSin * y + pitchCos * var11 >> 16;
		int var13 = pitchCos * XYZMag >> 16;
		int depth = var12 + var13;
		if (depth > NEAR_PLANE) {
			int rx = z * yawSin + yawCos * x >> 16;
			int var16 = (rx - XYZMag) * zoom;
			if (var16 / depth < Rasterizer3D_clipMidX2) {
				int var17 = (rx + XYZMag) * zoom;
				if (var17 / depth > Rasterizer3D_clipNegativeMidX) {
					int ry = pitchCos * y - var11 * pitchSin >> 16;
					int yheight = pitchSin * XYZMag >> 16;
					int ybottom = (pitchCos * bottomY >> 16) + yheight;
					int var20 = (ry + ybottom) * zoom;
					if (var20 / depth > Rasterizer3D_clipNegativeMidY) {
						int ytop = (pitchCos * modelHeight >> 16) + yheight;
						int var22 = (ry - ytop) * zoom;
						return var22 / depth >= Rasterizer3D_clipMidY2;
					}
				}
			}
		}
		return true;
	}

	/**
	 * Draw a Renderable in the scene
	 *
	 * @param projection
	 * @param scene
	 * @param renderable  Can be an Actor (Player or NPC), DynamicObject, GraphicsObject, TileItem, Projectile or a raw Model.
	 * @param orientation Rotation around the up-axis, from 0 to 2048 exclusive, 2048 indicating a complete rotation.
	 * @param x           The Renderable's X offset relative to {@link Client#getCameraX()}.
	 * @param y           The Renderable's Y offset relative to {@link Client#getCameraZ()}.
	 * @param z           The Renderable's Z offset relative to {@link Client#getCameraY()}.
	 * @param hash        A unique hash of the renderable consisting of some useful information. See {@link rs117.hd.utils.ModelHash} for more details.
	 */
	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash) {
		if (sceneContext == null)
			return;

		if (enableDetailedTimers)
			frameTimer.begin(Timer.GET_MODEL);

		Model model, offsetModel;
		try {
			// getModel may throw an exception from vanilla client code
			if (renderable instanceof Model) {
				model = (Model) renderable;
				offsetModel = model.getUnskewedModel();
				if (offsetModel == null)
					offsetModel = model;
			} else {
				offsetModel = model = renderable.getModel();
			}
			if (model == null || model.getFaceCount() == 0) {
				// skip models with zero faces
				// this does seem to happen sometimes (mostly during loading)
				// should save some CPU cycles here and there
				return;
			}
		} catch (Exception ex) {
			// Vanilla happens to handle exceptions thrown here gracefully, but we handle them explicitly anyway
			return;
		} finally {
			if (enableDetailedTimers)
				frameTimer.end(Timer.GET_MODEL);
		}

		// Apply height to renderable from the model
		if (model != renderable)
			renderable.setModelHeight(model.getModelHeight());

		model.calculateBoundsCylinder();

		if (projection instanceof IntProjection) {
			var p = (IntProjection) projection;
			if (isOutsideViewport(
				model,
				p.getPitchSin(),
				p.getPitchCos(),
				p.getYawSin(),
				p.getYawCos(),
				x - p.getCameraX(),
				y - p.getCameraY(),
				z - p.getCameraZ()
			)) {
				return;
			}
		}

		client.checkClickbox(projection, model, orientation, x, y, z, hash);

		if (redrawPreviousFrame)
			return;

		if (enableDetailedTimers)
			frameTimer.begin(Timer.DRAW_RENDERABLE);

		eightIntWrite[3] = renderBufferOffset;
		eightIntWrite[4] = model.getRadius() << 12 | orientation;
		eightIntWrite[5] = x;
		eightIntWrite[6] = y;
		eightIntWrite[7] = z;

		int plane = ModelHash.getPlane(hash);

		int faceCount;
		if (sceneContext.id == (offsetModel.getSceneId() & SceneUploader.SCENE_ID_MASK)) {
			// The model is part of the static scene buffer
			assert model == renderable;

			faceCount = Math.min(MAX_FACE_COUNT, offsetModel.getFaceCount());
			int vertexOffset = offsetModel.getBufferOffset();
			int uvOffset = offsetModel.getUvBufferOffset();
			boolean hillskew = offsetModel != model;

			eightIntWrite[0] = vertexOffset;
			eightIntWrite[1] = uvOffset;
			eightIntWrite[2] = faceCount;
			eightIntWrite[4] |= (hillskew ? 1 : 0) << 26 | plane << 24;
		} else {
			// Temporary model (animated or otherwise not a static Model already in the scene buffer)
			if (enableDetailedTimers)
				frameTimer.begin(Timer.MODEL_BATCHING);
			ModelOffsets modelOffsets = null;
			long batchHash = 0;
			if (configModelBatching || configModelCaching) {
				modelHasher.setModel(model);
				// Disable model batching for models which have been excluded from the scene buffer,
				// because we want to avoid having to fetch the model override
				if (configModelBatching && offsetModel.getSceneId() != SceneUploader.EXCLUDED_FROM_SCENE_BUFFER) {
					batchHash = modelHasher.vertexHash;
					modelOffsets = frameModelInfoMap.get(batchHash);
				}
			}
			if (enableDetailedTimers)
				frameTimer.end(Timer.MODEL_BATCHING);

			if (modelOffsets != null && modelOffsets.faceCount == model.getFaceCount()) {
				faceCount = modelOffsets.faceCount;
				eightIntWrite[0] = modelOffsets.vertexOffset;
				eightIntWrite[1] = modelOffsets.uvOffset;
				eightIntWrite[2] = modelOffsets.faceCount;
			} else {
				if (enableDetailedTimers)
					frameTimer.begin(Timer.MODEL_PUSHING);

				int uuid = ModelHash.generateUuid(client, hash, renderable);
				int[] worldPos = HDUtils.localToWorld(sceneContext.scene, x, z, plane);
				ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
				if (modelOverride.hide)
					return;

				int vertexOffset = dynamicOffsetVertices + sceneContext.getVertexOffset();
				int uvOffset = dynamicOffsetUvs + sceneContext.getUvOffset();

				int preOrientation = 0;
				if (ModelHash.getType(hash) == ModelHash.TYPE_OBJECT) {
					int tileExX = x / LOCAL_TILE_SIZE + SCENE_OFFSET;
					int tileExY = z / LOCAL_TILE_SIZE + SCENE_OFFSET;
					if (0 <= tileExX && tileExX < EXTENDED_SCENE_SIZE && 0 <= tileExY && tileExY < EXTENDED_SCENE_SIZE) {
						Tile tile = sceneContext.scene.getExtendedTiles()[plane][tileExX][tileExY];
						int config;
						if (tile != null && (config = sceneContext.getObjectConfig(tile, hash)) != -1) {
							preOrientation = HDUtils.getBakedOrientation(config);
						} else if (plane > 0) {
							// Might be on a bridge tile
							tile = sceneContext.scene.getExtendedTiles()[plane - 1][tileExX][tileExY];
							if (tile != null && tile.getBridge() != null && (config = sceneContext.getObjectConfig(tile, hash)) != -1)
								preOrientation = HDUtils.getBakedOrientation(config);
						}
					}
				}

				modelPusher.pushModel(sceneContext, null, uuid, model, modelOverride, ObjectType.NONE, preOrientation, true);

				faceCount = sceneContext.modelPusherResults[0];
				if (sceneContext.modelPusherResults[1] == 0)
					uvOffset = -1;

				if (enableDetailedTimers)
					frameTimer.end(Timer.MODEL_PUSHING);

				eightIntWrite[0] = vertexOffset;
				eightIntWrite[1] = uvOffset;
				eightIntWrite[2] = faceCount;

				// add this temporary model to the map for batching purposes
				if (configModelBatching)
					frameModelInfoMap.put(batchHash, new ModelOffsets(faceCount, vertexOffset, uvOffset));
			}
		}

		if (enableDetailedTimers)
			frameTimer.end(Timer.DRAW_RENDERABLE);

		if (eightIntWrite[0] == -1)
			return; // Hidden model

		bufferForTriangles(faceCount)
			.ensureCapacity(8)
			.put(eightIntWrite);
		renderBufferOffset += faceCount * 3;
	}

	/**
	 * returns the correct buffer based on triangle count and updates model count
	 */
	private GpuIntBuffer bufferForTriangles(int triangles) {
		for (int i = 0; i < numSortingBins; i++) {
			if (modelSortingBinFaceCounts[i] >= triangles) {
				++numModelsToSort[i];
				return modelSortingBuffers[i];
			}
		}

		throw new IllegalStateException(
			"Ran into a model with more triangles than the plugin supports (" +
			triangles + " > " + MAX_FACE_COUNT + ")");
	}

	private int getScaledValue(final double scale, final int value) {
		return (int) (value * scale + .5);
	}

	// Assumes alternating x/y
	private int[] applyDpiScaling(int... coordinates) {
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		if (graphicsConfiguration == null)
			return coordinates;

		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		for (int i = 0; i < coordinates.length; i++)
			coordinates[i] = getScaledValue(i % 2 == 0 ? t.getScaleX() : t.getScaleY(), coordinates[i]);
		return coordinates;
	}

	private void glDpiAwareViewport(int... xywh) {
		applyDpiScaling(xywh);
		glViewport(xywh[0], xywh[1], xywh[2], xywh[3]);
	}

	public int getDrawDistance() {
		return clamp(config.drawDistance(), 0, MAX_DISTANCE);
	}

	private int getExpandedMapLoadingChunks() {
		if (useLowMemoryMode)
			return 0;
		return config.expandedMapLoadingChunks();
	}

	private void logBufferResize(GLBuffer glBuffer, long newSize) {
		if (!log.isTraceEnabled())
			return;

		log.trace("Buffer resize: {} {}", glBuffer, String.format("%.2f MB -> %.2f MB", glBuffer.size / 1e6, newSize / 1e6));
	}

	private void updateBuffer(@Nonnull GLBuffer glBuffer, int target, @Nonnull ByteBuffer data, int usage, long clFlags) {
		glBindBuffer(target, glBuffer.glBufferId);
		long size = data.remaining();
		if (size > glBuffer.size) {
			size = HDUtils.ceilPow2(size);
			logBufferResize(glBuffer, size);

			glBuffer.size = size;
			glBufferData(target, size, usage);

			if (computeMode == ComputeMode.OPENCL)
				openCLManager.recreateCLBuffer(glBuffer, clFlags);
		}
		glBufferSubData(target, 0, data);
	}

	private void updateBuffer(@Nonnull GLBuffer glBuffer, int target, @Nonnull IntBuffer data, int usage, long clFlags)
	{
		updateBuffer(glBuffer, target, 0, data, usage, clFlags);
	}

	private void updateBuffer(@Nonnull GLBuffer glBuffer, int target, int offset, @Nonnull IntBuffer data, int usage, long clFlags)
	{
		long size = 4L * (offset + data.remaining());
		if (size > glBuffer.size) {
			size = HDUtils.ceilPow2(size);
			logBufferResize(glBuffer, size);

			if (offset > 0) {
				int oldBuffer = glBuffer.glBufferId;
				glBuffer.glBufferId = glGenBuffers();
				glBindBuffer(target, glBuffer.glBufferId);
				glBufferData(target, size, usage);

				glBindBuffer(GL_COPY_READ_BUFFER, oldBuffer);
				glCopyBufferSubData(GL_COPY_READ_BUFFER, target, 0, 0, offset * 4L);
				glDeleteBuffers(oldBuffer);
			} else {
				glBindBuffer(target, glBuffer.glBufferId);
				glBufferData(target, size, usage);
			}

			glBuffer.size = size;

			if (computeMode == ComputeMode.OPENCL)
				openCLManager.recreateCLBuffer(glBuffer, clFlags);
		}
		else
		{
			glBindBuffer(target, glBuffer.glBufferId);
		}
		glBufferSubData(target, offset * 4L, data);
	}

	private void updateBuffer(@Nonnull GLBuffer glBuffer, int target, @Nonnull FloatBuffer data, int usage, long clFlags)
	{
		updateBuffer(glBuffer, target, 0, data, usage, clFlags);
	}

	private void updateBuffer(@Nonnull GLBuffer glBuffer, int target, int offset, @Nonnull FloatBuffer data, int usage, long clFlags)
	{
		long size = 4L * (offset + data.remaining());
		if (size > glBuffer.size) {
			size = HDUtils.ceilPow2(size);
			logBufferResize(glBuffer, size);

			if (offset > 0) {
				int oldBuffer = glBuffer.glBufferId;
				glBuffer.glBufferId = glGenBuffers();
				glBindBuffer(target, glBuffer.glBufferId);
				glBufferData(target, size, usage);

				glBindBuffer(GL_COPY_READ_BUFFER, oldBuffer);
				glCopyBufferSubData(GL_COPY_READ_BUFFER, target, 0, 0, offset * 4L);
				glDeleteBuffers(oldBuffer);
			} else {
				glBindBuffer(target, glBuffer.glBufferId);
				glBufferData(target, size, usage);
			}

			glBuffer.size = size;

			if (computeMode == ComputeMode.OPENCL)
				openCLManager.recreateCLBuffer(glBuffer, clFlags);
		} else {
			glBindBuffer(target, glBuffer.glBufferId);
		}
		glBufferSubData(target, offset * 4L, data);
	}

	private void updateBuffer(@Nonnull GLBuffer glBuffer, int target, long size, int usage, long clFlags) {
		if (size > glBuffer.size) {
			size = HDUtils.ceilPow2(size);
			logBufferResize(glBuffer, size);

			glBuffer.size = size;
			glBindBuffer(target, glBuffer.glBufferId);
			glBufferData(target, size, usage);

			if (computeMode == ComputeMode.OPENCL)
				openCLManager.recreateCLBuffer(glBuffer, clFlags);
		}
	}

	@Subscribe(priority = -1) // Run after the low detail plugin
	public void onBeforeRender(BeforeRender beforeRender) {
		if (client.getScene() == null)
			return;
		// The game runs significantly slower with lower planes in Chambers of Xeric
		client.getScene().setMinLevel(isInChambersOfXeric ? client.getPlane() : client.getScene().getMinLevel());
	}

	@Subscribe
	public void onClientTick(ClientTick clientTick) {
		elapsedClientTime += 1 / 50f;

		if (!enableFreezeFrame && skipScene != client.getScene())
			redrawPreviousFrame = false;
	}

	@Subscribe
	public void onGameTick(GameTick gameTick) {
		if (!isActive)
			return;

		if (gameTicksUntilSceneReload > 0) {
			if (gameTicksUntilSceneReload == 1)
				reuploadScene();
			--gameTicksUntilSceneReload;
		}

		fishingSpotReplacer.update();

		// reload the scene if the player is in a house and their plane changed
		// this greatly improves the performance as it keeps the scene buffer up to date
		if (isInHouse) {
			int plane = client.getPlane();
			if (previousPlane != plane) {
				reloadSceneNextGameTick();
				previousPlane = plane;
			}
		}
	}

	private void waitUntilIdle() {
		if (computeMode == ComputeMode.OPENCL)
			openCLManager.finish();
		glFinish();
	}

	private void glToggle(int target, boolean enable) {
		if (enable) {
			glEnable(target);
		} else {
			glDisable(target);
		}
	}

	@SuppressWarnings("StatementWithEmptyBody")
	public void clearGLErrors() {
		// @formatter:off
		while (glGetError() != GL_NO_ERROR);
		// @formatter:on
	}

	public void checkGLErrors() {
		if (!log.isDebugEnabled())
			return;

		while (true) {
			int err = glGetError();
			if (err == GL_NO_ERROR)
				return;

			String errStr;
			switch (err) {
				case GL_INVALID_ENUM:
					errStr = "INVALID_ENUM";
					break;
				case GL_INVALID_VALUE:
					errStr = "INVALID_VALUE";
					break;
				case GL_INVALID_OPERATION:
					errStr = "INVALID_OPERATION";
					break;
				case GL_INVALID_FRAMEBUFFER_OPERATION:
					errStr = "INVALID_FRAMEBUFFER_OPERATION";
					break;
				default:
					errStr = String.valueOf(err);
					break;
			}

			log.debug("glGetError:", new Exception(errStr));
		}
	}

	private void displayUpdateMessage() {
		int messageId = 1;
		if (config.getPluginUpdateMessage() >= messageId)
			return; // Don't show the same message multiple times

//		PopupUtils.displayPopupMessage(client, "117HD Update",
//			"<br><br>" +
//			"If you experience any issues, please report them in the <a href=\"" + DISCORD_URL +"\">117HD Discord</a>.",
//			new String[] { "Remind me later", "Got it!" },
//			i -> {
//				if (i == 1) {
//					config.setPluginUpdateMessage(messageId);
//				}
//			}
//		);
	}

	private void displayUnsupportedGpuMessage(boolean isGenericGpu, String glRenderer) {
		String hint32Bit = "";
		if (HDUtils.is32Bit()) {
			hint32Bit =
				"&nbsp;• Install the 64-bit version of RuneLite from " +
				"<a href=\"" + HdPlugin.RUNELITE_URL + "\">the official website</a>. You are currently using 32-bit.<br>";
		}

		String driverLinks =
			"<br>" +
			"Links to drivers for each graphics card vendor:<br>" +
			"&nbsp;• <a href=\"" + HdPlugin.AMD_DRIVER_URL + "\">AMD drivers</a><br>" +
			"&nbsp;• <a href=\"" + HdPlugin.INTEL_DRIVER_URL + "\">Intel drivers</a><br>" +
			"&nbsp;• <a href=\"" + HdPlugin.NVIDIA_DRIVER_URL + "\">Nvidia drivers</a><br>";

		String errorMessage =
			(
				isGenericGpu ? (
					"Your graphics driver appears to be broken.<br>"
					+ "<br>"
					+ "Some things to try:<br>"
					+ "&nbsp;• Reinstall the drivers for <b>both</b> your processor's integrated graphics <b>and</b> your graphics card.<br>"
				) :
					(
						"Your GPU is currently not supported by 117 HD.<br><br>GPU name: " + glRenderer + "<br>"
						+ "<br>"
						+ "Your computer might not be letting RuneLite access your most powerful GPU.<br>"
						+ "To find out if your system is supported, try the following steps:<br>"
						+ "&nbsp;• Reinstall the drivers for your graphics card. You can find a link below.<br>"
					)
			)
			+ hint32Bit
			+ "&nbsp;• Tell your machine to use your high performance GPU for RuneLite.<br>"
			+ "&nbsp;• If you are on a desktop PC, make sure your monitor is plugged into your graphics card instead of<br>"
			+ "&nbsp;&nbsp;&nbsp;&nbsp;your motherboard. The graphics card's display outputs are usually lower down behind the computer.<br>"
			+ driverLinks
			+ "<br>"
			+ "If the issue persists even after <b>all of the above</b>, please join our "
			+ "<a href=\"" + HdPlugin.DISCORD_URL + "\">Discord server</a>, and click the <br>"
			+ "\"Open logs folder\" button below, find the file named \"client\" or \"client.log\", then drag and drop<br>"
			+ "that file into one of our support channels.";

		PopupUtils.displayPopupMessage(client, "117 HD Error", errorMessage,
			new String[] { "Open logs folder", "Ok, let me try that..." },
			i -> {
				if (i == 0) {
					LinkBrowser.open(RuneLite.LOGS_DIR.toString());
					return false;
				}
				return true;
			}
		);
	}

	private void displayOutOfMemoryMessage() {
		String errorMessage;
		if (HDUtils.is32Bit()) {
			String lowMemoryModeHint = useLowMemoryMode ? "" : (
				"If you are unable to install 64-bit RuneLite, you can instead turn on <b>Low Memory Mode</b> in the<br>" +
				"Miscellaneous section of 117 HD settings.<br>"
			);
			errorMessage =
				"The plugin ran out of memory because you are using the 32-bit version of RuneLite.<br>"
				+ "We would recommend installing the 64-bit version from "
				+ "<a href=\"" + HdPlugin.RUNELITE_URL + "\">RuneLite's website</a> if possible.<br>"
				+ "<br>"
				+ lowMemoryModeHint
				+ "<br>"
				+ "If you need further assistance, please join our "
				+ "<a href=\"" + HdPlugin.DISCORD_URL + "\">Discord</a> server, and click the \"Open logs folder\"<br>"
				+ "button below, find the file named \"client\" or \"client.log\", then drag and drop that file into one of<br>"
				+ "our support channels.";
		} else {
			errorMessage =
				"The plugin ran out of memory. "
				+ "Try " + (useLowMemoryMode ? "" : "reducing your model cache size or ") + "closing other programs.<br>"
				+ "<br>"
				+ "If the issue persists, please join our "
				+ "<a href=\"" + HdPlugin.DISCORD_URL + "\">Discord</a> server, and click the \"Open logs folder\" button<br>"
				+ "below, find the file named \"client\" or \"client.log\", then drag and drop that file into one of our<br>"
				+ "support channels.";
		}

		PopupUtils.displayPopupMessage(client, "117 HD Error", errorMessage,
			new String[] { "Open logs folder", "Ok, let me try that..." },
			i -> {
				if (i == 0) {
					LinkBrowser.open(RuneLite.LOGS_DIR.toString());
					return false;
				}
				return true;
			}
		);
	}
}
