/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
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
import com.google.inject.Binder;
import com.google.inject.Provider;
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
import java.nio.FloatBuffer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.hooks.*;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.entityhider.EntityHiderPlugin;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.OSType;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.BufferUtils;
import org.lwjgl.Version;
import org.lwjgl.opengl.*;
import org.lwjgl.system.Callback;
import org.lwjgl.system.Configuration;
import rs117.hd.config.ColorFilter;
import rs117.hd.config.DynamicLights;
import rs117.hd.config.SeasonalHemisphere;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.config.ShadingMode;
import rs117.hd.config.ShadowMode;
import rs117.hd.config.VanillaShadowMode;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.shader.TiledLightingShaderProgram;
import rs117.hd.opengl.shader.UIShaderProgram;
import rs117.hd.opengl.uniforms.UBOCompute;
import rs117.hd.opengl.uniforms.UBOGlobal;
import rs117.hd.opengl.uniforms.UBOLights;
import rs117.hd.opengl.uniforms.UBOUI;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.GammaCalibrationOverlay;
import rs117.hd.overlays.ShadowMapOverlay;
import rs117.hd.overlays.TiledLightingOverlay;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.Renderer;
import rs117.hd.renderer.legacy.LegacyRenderer;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.FishingSpotReplacer;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.GroundMaterialManager;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.TextureManager;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.WaterTypeManager;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.DeveloperTools;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.HDVariables;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.NpcDisplacementCache;
import rs117.hd.utils.PopupUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.ShaderRecompile;
import rs117.hd.utils.jobs.JobSystem;
import rs117.hd.utils.texture.GLAttachmentSlot;
import rs117.hd.utils.texture.GLFrameBuffer;
import rs117.hd.utils.texture.GLFrameBufferDesc;
import rs117.hd.utils.texture.GLSamplerMode;
import rs117.hd.utils.texture.GLTexture;
import rs117.hd.utils.texture.GLTextureFormat;
import rs117.hd.utils.texture.GLTextureParams;
import rs117.hd.utils.texture.GLTextureType;

import static net.runelite.api.Constants.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;
import static rs117.hd.utils.buffer.GLBuffer.DEBUG_MAC_OS;

@Slf4j
@Singleton
@PluginDescriptor(
	name = "117 HD",
	description = "GPU renderer with a suite of graphical enhancements",
	tags = { "hd", "high", "detail", "graphics", "shaders", "textures", "gpu", "shadows", "lights" },
	conflicts = "GPU"
)
@PluginDependency(EntityHiderPlugin.class)
public class HdPlugin extends Plugin {
	public static final ResourcePath PLUGIN_DIR = Props
		.getFolder("rlhd.plugin-dir", () -> path(RuneLite.RUNELITE_DIR, "117hd"));

	public static final String DISCORD_URL = "https://discord.gg/U4p6ChjgSE";
	public static final String RUNELITE_URL = "https://runelite.net";
	public static final String AMD_DRIVER_URL = "https://www.amd.com/en/support";
	public static final String INTEL_DRIVER_URL = "https://www.intel.com/content/www/us/en/support/detect.html";
	public static final String NVIDIA_DRIVER_URL = "https://www.nvidia.com/en-us/geforce/drivers/";

	public static int MAX_TEXTURE_UNITS;
	public static int TEXTURE_UNIT_COUNT = 0;
	public static final int TEXTURE_UNIT_UI = GL_TEXTURE1 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_GAME = GL_TEXTURE1 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_SHADOW_MAP = GL_TEXTURE1 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_TILE_HEIGHT_MAP = GL_TEXTURE1 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_TILED_LIGHTING_MAP = GL_TEXTURE1 + TEXTURE_UNIT_COUNT++;

	public static int MAX_IMAGE_UNITS;
	public static int IMAGE_UNIT_COUNT = 0;
	public static final int IMAGE_UNIT_TILED_LIGHTING = IMAGE_UNIT_COUNT++;

	public static int UNIFORM_BLOCK_COUNT = 0;
	public static final int UNIFORM_BLOCK_GLOBAL = UNIFORM_BLOCK_COUNT++;
	public static final int UNIFORM_BLOCK_MATERIALS = UNIFORM_BLOCK_COUNT++;
	public static final int UNIFORM_BLOCK_WATER_TYPES = UNIFORM_BLOCK_COUNT++;
	public static final int UNIFORM_BLOCK_LIGHTS = UNIFORM_BLOCK_COUNT++;
	public static final int UNIFORM_BLOCK_LIGHTS_CULLING = UNIFORM_BLOCK_COUNT++;
	public static final int UNIFORM_BLOCK_UI = UNIFORM_BLOCK_COUNT++;

	public static final float NEAR_PLANE = 50;
	public static final int MAX_FACE_COUNT = 6144;
	public static final int MAX_DISTANCE = EXTENDED_SCENE_SIZE;
	public static final int MAX_FOG_DEPTH = 100;
	public static final int TILED_LIGHTING_TILE_SIZE = 16;

	public static final float ORTHOGRAPHIC_ZOOM = .0002f;
	public static final float WIND_DISPLACEMENT_NOISE_RESOLUTION = 0.04f;

	public static float BUFFER_GROWTH_MULTIPLIER = 2; // can be less than 2 if trying to conserve memory

	public static final float COLOR_FILTER_FADE_DURATION = 500;

	public static final int[] RENDERBUFFER_FORMATS_SRGB = {
		GL_SRGB8,
		GL_SRGB8_ALPHA8 // should be guaranteed
	};
	public static final int[] RENDERBUFFER_FORMATS_SRGB_WITH_ALPHA = {
		GL_SRGB8_ALPHA8 // should be guaranteed
	};
	public static final int[] RENDERBUFFER_FORMATS_LINEAR = {
		GL_RGB8,
		GL_RGBA8,
		GL_RGB, // should be guaranteed
		GL_RGBA // should be guaranteed
	};
	public static final int[] RENDERBUFFER_FORMATS_LINEAR_WITH_ALPHA = {
		GL_RGBA8,
		GL_RGBA // should be guaranteed
	};

	public static final int PROCESSOR_COUNT = Runtime.getRuntime().availableProcessors();

	// Manually instantiate singletons and lazily inject them to avoid circular dependencies
	private static final List<Class<?>> LAZY_SINGLETONS = List.of(
		AreaManager.class,
		EnvironmentManager.class,
		GamevalManager.class,
		GroundMaterialManager.class,
		LightManager.class,
		MaterialManager.class,
		ModelOverrideManager.class,
		ProceduralGenerator.class,
		TextureManager.class,
		TileOverrideManager.class,
		WaterTypeManager.class,
		SceneManager.class
	);

	@Getter
	private Gson gson;

	@Inject
	private Client client;

	@Inject
	private ClientUI clientUI;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private HdPluginConfig config;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private AreaManager areaManager;

	@Inject
	private LightManager lightManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private TextureManager textureManager;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private WaterTypeManager waterTypeManager;

	@Inject
	private GroundMaterialManager groundMaterialManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private FishingSpotReplacer fishingSpotReplacer;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	@Inject
	private DeveloperTools developerTools;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private UIShaderProgram uiProgram;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private JobSystem jobSystem;

	@Getter
	@Inject
	public TiledLightingShaderProgram tiledLightingImageStoreProgram;

	public final List<TiledLightingShaderProgram> tiledLightingShaderPrograms = new ArrayList<>();

	@Inject
	private GammaCalibrationOverlay gammaCalibrationOverlay;

	@Inject
	private ShadowMapOverlay shadowMapOverlay;

	@Inject
	private TiledLightingOverlay tiledLightingOverlay;

	@Inject
	public HDVariables vars;

	public Renderer renderer;

	public static boolean SKIP_GL_ERROR_CHECKS;
	public static GLCapabilities GL_CAPS;
	public static boolean AMD_GPU;
	public static boolean INTEL_GPU;
	public static boolean NVIDIA_GPU;
	public static boolean APPLE;
	public static boolean APPLE_ARM;

	public static boolean SUPPORTS_INDIRECT_DRAW;
	public static boolean SUPPORTS_STORAGE_BUFFERS;

	public Canvas canvas;
	public JFrame clientJFrame;
	public AWTContext awtContext;
	private Callback debugCallback;

	private static final String LINUX_VERSION_HEADER =
		"#version 420\n" +
		"#extension GL_ARB_compute_shader : require\n" +
		"#extension GL_ARB_shader_storage_buffer_object : require\n" +
		"#extension GL_ARB_explicit_attrib_location : require\n";
	private static final String WINDOWS_VERSION_HEADER = "#version 430\n";

	private static final ResourcePath SHADER_PATH = Props
		.getFolder("rlhd.shader-path", () -> path(HdPlugin.class));

	public int vaoQuad;
	private int vboQuad;

	public int vaoTri;
	private int vboTri;

	private final int[] actualUiResolution = { 0, 0 }; // Includes stretched mode and DPI scaling
	@Getter
	private GLTexture texUi;

	@Nullable
	public int[] sceneViewport;
	public final float[] sceneViewportScale = { 1, 1 };

	public GLFrameBuffer fboBackBuffer;
	public GLFrameBuffer fboScene;
	public GLFrameBuffer fboShadowMap;
	public GLFrameBuffer fboTiledLighting;

	public UBOGlobal uboGlobal;
	public UBOUI uboUI;
	public UBOLights uboLights;
	public UBOLights uboLightsCulling;

	// Configs used frequently enough to be worth caching
	public boolean configGroundTextures;
	public boolean configGroundBlending;
	public boolean configModelTextures;
	public boolean configLegacyTzHaarReskin;
	public boolean configProjectileLights;
	public boolean configNpcLights;
	public boolean configHideFakeShadows;
	public boolean configLegacyGreyColors;
	public boolean configModelBatching;
	public boolean configModelCaching;
	public boolean configShadowsEnabled;
	public boolean configRoofShadows;
	public boolean configExpandShadowDraw;
	public boolean configUseFasterModelHashing;
	public boolean configZoneStreaming;
	public boolean configPowerSaving;
	public boolean configUnlitFaceColors;
	public boolean configUndoVanillaShading;
	public boolean configPreserveVanillaNormals;
	public boolean configWindDisplacement;
	public boolean configCharacterDisplacement;
	public boolean configHideVanillaWaterEffects;
	public boolean configTiledLighting;
	public boolean configTiledLightingImageLoadStore;
	public int configDetailDrawDistance;
	public DynamicLights configDynamicLights;
	public ShadowMode configShadowMode;
	public SeasonalTheme configSeasonalTheme;
	public SeasonalHemisphere configSeasonalHemisphere;
	public VanillaShadowMode configVanillaShadowMode;
	public ShadingMode configShadingMode;
	public ColorFilter configColorFilter = ColorFilter.NONE;
	public ColorFilter configColorFilterPrevious;

	public boolean useLowMemoryMode;
	public boolean enableDetailedTimers;
	public boolean enableFreezeFrame;
	public boolean orthographicProjection;
	public boolean freezeCulling;

	@Getter
	private boolean isPluginStopPending;
	@Getter
	private boolean isActive;
	private boolean lwjglInitialized;
	public boolean hasLoggedIn;
	public boolean redrawPreviousFrame;
	public boolean justChangedArea;
	public Scene skipScene;
	public int gpuFlags;

	public final ConcurrentHashMap.KeySetView<String, ?> pendingConfigChanges = ConcurrentHashMap.newKeySet();

	// Camera position and orientation may be reused from the old scene while hopping, prior to drawScene being called
	public final float[] cameraPosition = new float[3];
	public final int[] cameraShift = new int[2];
	public final int[] cameraFocalPoint = new int[2];
	public final float[] cameraOrientation = new float[2];
	public final float[][] cameraFrustum = new float[6][4];
	public float[] viewMatrix = Mat4.zero();
	public float[] viewProjMatrix = Mat4.zero();
	public float[] invViewProjMatrix = Mat4.zero();

	@Getter
	public int drawnTileCount;
	@Getter
	public int drawnStaticRenderableCount;
	@Getter
	public int drawnTempRenderableCount;
	@Getter
	public int drawnDynamicRenderableCount;
	@Getter
	public long garbageCollectionCount;

	private int startupCount;
	public int frame;
	public double elapsedTime;
	public double elapsedClientTime;
	public float deltaTime;
	public float deltaClientTime;
	private long lastFrameTimeMillis;
	private double lastFrameClientTime;
	public float windOffset;
	public long colorFilterChangedAt;

	public float clientUnfocusedTime;
	public boolean isClientInFocus = true;
	public boolean isClientMinimized;
	public boolean isPowerSaving;

	@Provides
	HdPluginConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(HdPluginConfig.class);
	}

	@Override
	public void configure(Binder binder) {
		// Bind manually constructed instances of singletons to avoid recursive loading issues
		for (var clazz : LAZY_SINGLETONS) {
			try {
				// noinspection unchecked
				binder.bind((Class<Object>) clazz).toInstance(clazz.getDeclaredConstructor().newInstance());
			} catch (Exception ex) {
				throw new RuntimeException("Failed to instantiate singleton " + clazz, ex);
			}
		}
	}

	@Override
	protected void startUp() {
		// Lazily inject members into our singletons
		for (var clazz : LAZY_SINGLETONS)
			injector.injectMembers(injector.getInstance(clazz));

		gson = GsonUtils.wrap(injector.getInstance(Gson.class));

		clientThread.invoke(() -> {
			try {
				if (!textureManager.vanillaTexturesAvailable())
					return false;

				isPluginStopPending = false;
				isActive = true;
				startupCount++;

				frame = 0;
				elapsedTime = 0;
				elapsedClientTime = 0;
				deltaTime = 0;
				deltaClientTime = 0;
				lastFrameTimeMillis = 0;
				lastFrameClientTime = 0;

				AWTContext.loadNatives();
				canvas = client.getCanvas();
				clientJFrame = HDUtils.getJFrame(canvas);

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

				SKIP_GL_ERROR_CHECKS = false;
				GL_CAPS = GL.createCapabilities();
				useLowMemoryMode = config.lowMemoryMode();
				BUFFER_GROWTH_MULTIPLIER = useLowMemoryMode ? 1.333f : 2;

				var rendererClass = config.legacyRenderer() ? LegacyRenderer.class : ZoneRenderer.class;
				Instant buildTimestamp = Instant.ofEpochMilli(BuildInfo.TIMESTAMP).truncatedTo(ChronoUnit.SECONDS);
				String rlawtVersion = System.getProperty("runelite.rlawtpath", "Release");
				String javaVmName = System.getProperty("java.vm.name", "Unknown");
				String javaVersion = System.getProperty("java.version", "Unknown");
				OSType osType = OSType.getOSType();
				String osArch = System.getProperty("os.arch", "Unknown");
				String osVersion = System.getProperty("os.version", "Unknown");
				String wordSize = System.getProperty("sun.arch.data.model", "Unknown");
				String glRenderer = Objects.requireNonNullElse(glGetString(GL_RENDERER), "Unknown");
				String glVendor = Objects.requireNonNullElse(glGetString(GL_VENDOR), "Unknown");
				var runtime = Runtime.getRuntime();

				APPLE = osType == OSType.MacOS;
				APPLE_ARM = APPLE && osArch.equals("aarch64");
				AMD_GPU = glRenderer.contains("AMD") || glRenderer.contains("Radeon") || glVendor.contains("ATI");
				INTEL_GPU = glRenderer.contains("Intel");
				NVIDIA_GPU = glRenderer.toLowerCase().contains("nvidia");

				SUPPORTS_INDIRECT_DRAW = config.indirectDraw().get(NVIDIA_GPU && !APPLE);
				SUPPORTS_STORAGE_BUFFERS = GL_CAPS.GL_ARB_buffer_storage && !DEBUG_MAC_OS && config.storageBuffers().get(!INTEL_GPU);

				log.info("Starting 117 HD... (count: {})", startupCount);
				log.info("Renderer:          {}", rendererClass.getSimpleName());
				log.info("Build version:     {} @ {} ({})", BuildInfo.VERSION, buildTimestamp, BuildInfo.COMMIT);
				log.info("rlawt version:     {}", rlawtVersion);
				log.info("LWJGL Version:     {}", Version.getVersion());
				log.info("Java version:      {} ({})", javaVmName, javaVersion);
				log.info("Java memory limit: {} (free: {})", formatBytes(runtime.maxMemory()), formatBytes(runtime.freeMemory()));
				log.info("Operating system:  {} {} ({}-bit {})", osType, osVersion, wordSize, osArch);
				log.info("CPU:               {} ({} threads)", HDUtils.getCpuName(), runtime.availableProcessors());
				log.info("Memory:            {}", formatBytes(HDUtils.getTotalSystemMemory()));
				log.info("GPU:               {} ({})", glRenderer, glVendor);
				log.info("GPU driver:        {}", glGetString(GL_VERSION));
				log.info("Indirect draw:     {}", SUPPORTS_INDIRECT_DRAW);
				log.info("Storage buffers:   {}", SUPPORTS_STORAGE_BUFFERS);
				log.info("Low memory mode:   {}", useLowMemoryMode);

				renderer = injector.getInstance(rendererClass);

				if (!Props.has("rlhd.skipGpuChecks")) {
					List<String> fallbackDevices = List.of(
						"GDI Generic",
						"D3D12 (Microsoft Basic Render Driver)",
						"softpipe"
					);
					boolean isFallbackGpu = fallbackDevices.contains(glRenderer);
					if (isFallbackGpu || !renderer.supportsGpu(GL_CAPS)) {
						log.error("Unsupported GPU. Stopping the plugin...");
						displayUnsupportedGpuMessage(isFallbackGpu, glRenderer, renderer);
						stopPlugin();
						return true;
					}
				}

				lwjglInitialized = true;
				checkGLErrors();

				MAX_TEXTURE_UNITS = glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS); // Not the fixed pipeline MAX_TEXTURE_UNITS
				if (MAX_TEXTURE_UNITS < TEXTURE_UNIT_COUNT)
					log.warn("The GPU only supports {} texture units", MAX_TEXTURE_UNITS);
				MAX_IMAGE_UNITS = GL_CAPS.GL_ARB_shader_image_load_store ?
					glGetInteger(ARBShaderImageLoadStore.GL_MAX_IMAGE_UNITS) : 0;
				if (MAX_IMAGE_UNITS < IMAGE_UNIT_COUNT)
					log.warn("The GPU only supports {} image units", MAX_IMAGE_UNITS);

				if (log.isDebugEnabled() && GL_CAPS.glDebugMessageControl != 0) {
					debugCallback = GLUtil.setupDebugMessageCallback();
					if (debugCallback != null) {
						// Hide our own debug group messages
						GL43C.glDebugMessageControl(
							GL43C.GL_DEBUG_SOURCE_APPLICATION,
							GL43C.GL_DEBUG_TYPE_PUSH_GROUP,
							GL43C.GL_DEBUG_SEVERITY_NOTIFICATION,
							(int[]) null,
							false
						);
						GL43C.glDebugMessageControl(
							GL43C.GL_DEBUG_SOURCE_APPLICATION,
							GL43C.GL_DEBUG_TYPE_POP_GROUP,
							GL43C.GL_DEBUG_SEVERITY_NOTIFICATION,
							(int[]) null,
							false
						);

						//	GLDebugEvent[ id 0x20071
						//		type Warning: generic
						//		severity Unknown (0x826b)
						//		source GL API
						//		msg Buffer detailed info: Buffer object 11 (bound to GL_ARRAY_BUFFER_ARB, and GL_SHADER_STORAGE_BUFFER (4), usage hint is GL_STREAM_DRAW) will use VIDEO memory as the source for buffer object operations.
						GL43C.glDebugMessageControl(
							GL43C.GL_DEBUG_SOURCE_API, GL43C.GL_DEBUG_TYPE_OTHER,
							GL_DONT_CARE, 0x20071, false
						);

						//	GLDebugMessageHandler: GLDebugEvent[ id 0x20052
						//		type Warning: implementation dependent performance
						//		severity Medium: Severe performance/deprecation/other warnings
						//		source GL API
						//		msg Pixel-path performance warning: Pixel transfer is synchronized with 3D rendering.
						GL43C.glDebugMessageControl(
							GL43C.GL_DEBUG_SOURCE_API, GL43C.GL_DEBUG_TYPE_PERFORMANCE,
							GL_DONT_CARE, 0x20052, false
						);

						// [LWJGL] OpenGL debug message
						//	ID: 0x20092
						//	Source: API
						//	Type: PERFORMANCE
						//	Severity: MEDIUM
						//	Message: Program/shader state performance warning: Vertex shader in program 20 is being recompiled based on GL state.
						GL43C.glDebugMessageControl(
							GL43C.GL_DEBUG_SOURCE_API, GL43C.GL_DEBUG_TYPE_PERFORMANCE,
							GL_DONT_CARE, 0x20092, false
						);
					}
				}

				updateCachedConfigs();
				developerTools.activate();

				setupSyncMode();
				initializeVaos();
				initializeUbos();

				// Materials need to be initialized before compiling shader programs
				textureManager.startUp();
				materialManager.startUp();
				waterTypeManager.startUp();
				gamevalManager.startUp();

				gpuFlags = DrawCallbacks.GPU | renderer.gpuFlags();
				if (config.removeVertexSnapping())
					gpuFlags |= DrawCallbacks.NO_VERTEX_SNAPPING;
				if (configShadingMode.unlitFaceColors)
					gpuFlags |= DrawCallbacks.UNLIT_FACE_COLORS;
				client.setGpuFlags(gpuFlags);

				// Initialize the renderer after setting initial GPU flags,
				// to let the renderer override GPU flags even during startup
				renderer.initialize();
				eventBus.register(renderer);

				initializeShaders();
				initializeShaderHotswapping();

				fboBackBuffer = GLFrameBuffer.wrap(awtContext.getFramebuffer(false), "backBuffer");

				GLFrameBufferDesc backbufferDesc = fboBackBuffer.getDescriptor();
				if(backbufferDesc.colorDescriptors.isEmpty())
					throw new RuntimeException("Couldn't determine BackBuffer descriptor");

				final int forcedAASamples = backbufferDesc.samples;
				int msaaSamples = forcedAASamples != 0 ? forcedAASamples : min(config.antiAliasingMode().getSamples(), glGetInteger(GL_MAX_SAMPLES));

				GLTextureFormat backbufferFormat = backbufferDesc.colorDescriptors.get(0).format;
				fboScene = new GLFrameBuffer(new GLFrameBufferDesc()
					.setWidth(canvas.getWidth())
					.setHeight(canvas.getHeight())
					.setMSAASamples(msaaSamples)
					.setColorAttachment(GLAttachmentSlot.COLOR0, backbufferFormat, GLTextureParams.DEFAULT())
					.setDepthAttachment(GLTextureFormat.DEPTH32F, GLTextureParams.DEFAULT())
					.setDebugName("SceneColor"));

				if (!fboScene.isCreated())
					throw new RuntimeException("No supported " + (backbufferFormat.isSRGB() ? "sRGB" : "linear") + " formats");

				texUi = new GLTexture(1, 1, GLTextureFormat.BGRA8,
					new GLTextureParams()
						.setSampler(GLSamplerMode.LINEAR_CLAMP)
						.setTextureUnit(TEXTURE_UNIT_UI)
						.setPixelPackBufferCount(3)
						.setDebugName("UI"));

				int shadowMapResolution = configShadowsEnabled ? config.shadowResolution().getValue() : 1;
				fboShadowMap = new GLFrameBuffer(
					new GLFrameBufferDesc()
						.setWidth(shadowMapResolution)
						.setHeight(shadowMapResolution)
						.setDepthAttachment(GLTextureFormat.DEPTH32F,
							new GLTextureParams()
								.setSampler(GLSamplerMode.NEAREST_CLAMP)
								.setTextureUnit(TEXTURE_UNIT_SHADOW_MAP)
								.setBorderColor(new float[] { 1.0f, 1.0f, 1.0f, 1.0f}))
						.setDebugName("ShadowMap"));

				fboTiledLighting = new GLFrameBuffer(new GLFrameBufferDesc()
					.setShouldConstructionCreate(false)
					.setDepth(DynamicLights.MAX_LAYERS_PER_TILE)
					.setColorAttachment(
						GLAttachmentSlot.COLOR0, GLTextureFormat.RGBA16UI, new GLTextureParams()
							.setType(GLTextureType.TEXTURE2D_ARRAY)
							.setSampler(GLSamplerMode.NEAREST_CLAMP)
							.setTextureUnit(TEXTURE_UNIT_TILED_LIGHTING_MAP)
							.setImageUnit(IMAGE_UNIT_TILED_LIGHTING, GL_WRITE_ONLY))
					.setDebugName("TiledLighting"));

				checkGLErrors();

				client.setDrawCallbacks(renderer);
				client.setExpandedMapLoading(getExpandedMapLoadingChunks());
				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				areaManager.startUp();
				groundMaterialManager.startUp();
				tileOverrideManager.startUp();
				modelOverrideManager.startUp();
				lightManager.startUp();
				environmentManager.startUp();
				fishingSpotReplacer.startUp();
				gammaCalibrationOverlay.initialize();
				npcDisplacementCache.initialize();

				hasLoggedIn = client.getGameState().getState() > GameState.LOGGING_IN.getState();
				redrawPreviousFrame = false;
				skipScene = null;

				// Force the client to reload the scene since we're changing GPU flags, and to restore any removed tiles
				if (client.getGameState() == GameState.LOGGED_IN)
					client.setGameState(GameState.LOADING);

				checkGLErrors();

				clientThread.invokeLater(this::displayUpdateMessage);

				log.info("117 HD started successfully!");
			} catch (Throwable err) {
				log.error("Error while starting 117 HD", err);
				stopPlugin();
			}
			return true;
		});
	}

	@Override
	protected void shutDown() {
		clientThread.invoke(() -> {
			if (!isActive)
				return;
			isActive = false;
			FileWatcher.destroy();

			if (renderer != null)
				renderer.waitUntilIdle();

			var scene = client.getScene();
			if (scene != null)
				scene.setMinLevel(0);

			client.setGpuFlags(0);
			client.setDrawCallbacks(null);
			client.setUnlockedFps(false);
			client.setExpandedMapLoading(0);

			if (lwjglInitialized) {
				lwjglInitialized = false;

				if (texUi != null)
					texUi.destroy();
				texUi = null;

				if(fboShadowMap != null)
					fboShadowMap.destroy();
				fboShadowMap = null;

				if(fboScene != null)
					fboScene.destroy();
				fboScene = null;

				if(fboTiledLighting != null)
					fboTiledLighting.destroy();
				fboTiledLighting = null;

				destroyShaders();
				destroyVaos();
				destroyUbos();

				if (renderer != null) {
					eventBus.unregister(renderer);
					renderer.destroy();
				}
				renderer = null;
			}

			developerTools.deactivate();
			tileOverrideManager.shutDown();
			groundMaterialManager.shutDown();
			modelOverrideManager.shutDown();
			lightManager.shutDown();
			environmentManager.shutDown();
			fishingSpotReplacer.shutDown();
			areaManager.shutDown();
			gamevalManager.shutDown();
			gammaCalibrationOverlay.destroy();
			npcDisplacementCache.destroy();
			waterTypeManager.shutDown();
			materialManager.shutDown();
			textureManager.shutDown();

			DestructibleHandler.flushPendingDestruction(true);

			if (awtContext != null)
				awtContext.destroy();
			awtContext = null;

			if (debugCallback != null)
				debugCallback.free();
			debugCallback = null;

			// force main buffer provider rebuild to turn off alpha channel
			client.resizeCanvas();

			// Force the client to reload the scene to reset any scene modifications & update GPU flags
			if (client.getGameState() == GameState.LOGGED_IN)
				client.setGameState(GameState.LOADING);
		});
	}

	public void requestPluginStop() {
		if (isPluginStopPending)
			return;
		log.debug("Requesting plugin to stop when safe");
		isPluginStopPending = true;
	}

	public void stopPlugin() {
		clientThread.invoke(() -> {
			try {
				// Shut the plugin down immediately, making RuneLite's call to shutDown() a no-op
				shutDown();
			} catch (Throwable ex) {
				log.error("Error while stopping 117HD:", ex);
			}

			SwingUtilities.invokeLater(() -> {
				try {
					pluginManager.setPluginEnabled(this, false);
					pluginManager.stopPlugin(this);
				} catch (Throwable ex) {
					log.error("Error while stopping 117HD:", ex);
				}
			});
		});
	}

	public void restartPlugin() {
		clientThread.invoke(() -> {
			shutDown();
			// Validate the canvas so it becomes valid without having to manually resize the client
			canvas.validate();
			startUp();
		});
	}

	@Nullable
	public SceneContext getSceneContext() {
		return renderer == null ? null : renderer.getSceneContext();
	}

	public void toggleFreezeFrame() {
		clientThread.invoke(() -> {
			enableFreezeFrame = !enableFreezeFrame;
			if (enableFreezeFrame)
				redrawPreviousFrame = true;
		});
	}

	private String generateFetchCases(String array, int from, int to) {
		int length = to - from;
		if (length <= 1)
			return array + "[" + from + "]";
		int middle = from + length / 2;
		return "i < " + middle +
			" ? " + generateFetchCases(array, from, middle) +
			" : " + generateFetchCases(array, middle, to);
	}

	public String generateGetter(String type, int arrayLength) {
		StringBuilder include = new StringBuilder();

		if (config.macosIntelWorkaround() && !APPLE_ARM) {
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
		} else {
			include
				.append("#define get")
				.append(type)
				.append("(i) ")
				.append(type)
				.append("Array[i]\n");
		}

		return include.toString();
	}

	public ShaderIncludes getShaderIncludes() {
		var includes = new ShaderIncludes()
			.addIncludePath(SHADER_PATH)
			.addInclude("VERSION_HEADER", OSType.getOSType() == OSType.Linux ? LINUX_VERSION_HEADER : WINDOWS_VERSION_HEADER)
			.define("UI_SCALING_MODE", config.uiScalingMode())
			.define("COLOR_BLINDNESS", config.colorBlindness())
			.define("APPLY_COLOR_FILTER", configColorFilter != ColorFilter.NONE)
			.define("MATERIAL_COUNT", MaterialManager.MATERIALS.length)
			.define("WATER_TYPE_COUNT", waterTypeManager.uboWaterTypes.getCount())
			.define("DYNAMIC_LIGHTS", configDynamicLights != DynamicLights.NONE)
			.define("TILED_LIGHTING", configTiledLighting)
			.define("TILED_LIGHTING_LAYER_COUNT", configDynamicLights.getTiledLightingLayers())
			.define("TILED_LIGHTING_TILE_SIZE", TILED_LIGHTING_TILE_SIZE)
			.define("MAX_LIGHT_COUNT", configTiledLighting ? UBOLights.MAX_LIGHTS : configDynamicLights.getMaxSceneLights())
			.define("NORMAL_MAPPING", config.normalMapping())
			.define("PARALLAX_OCCLUSION_MAPPING", config.parallaxOcclusionMapping())
			.define("SHADOW_MODE", configShadowMode)
			.define("SHADOW_TRANSPARENCY", config.shadowTransparency())
			.define("SHADOW_FILTERING", config.shadowFiltering())
			.define("SHADOW_RESOLUTION", config.shadowResolution())
			.define("VANILLA_COLOR_BANDING", config.vanillaColorBanding())
			.define("UNDO_VANILLA_SHADING", configShadingMode.undoVanillaShading)
			.define("LEGACY_GREY_COLORS", configLegacyGreyColors)
			.define("DISABLE_DIRECTIONAL_SHADING", !configShadingMode.directionalShading)
			.define("FLAT_SHADING", config.flatShading())
			.define("WIND_DISPLACEMENT", configWindDisplacement)
			.define("WIND_DISPLACEMENT_NOISE_RESOLUTION", WIND_DISPLACEMENT_NOISE_RESOLUTION)
			.define("CHARACTER_DISPLACEMENT", configCharacterDisplacement)
			.define("MAX_CHARACTER_POSITION_COUNT", max(1, UBOCompute.MAX_CHARACTER_POSITION_COUNT))
			.define("WIREFRAME", config.wireframe())
			.define("WINDOWS_HDR_CORRECTION", config.windowsHdrCorrection())
			.define("LEGACY_RENDERER", renderer instanceof LegacyRenderer)
			.define("ZONE_RENDERER", renderer instanceof ZoneRenderer)
			.define("MAX_SIMULTANEOUS_WORLD_VIEWS", 0)
			.define("WORLD_VIEW_GETTER", "")
			.addInclude(
				"MATERIAL_CONSTANTS", () -> {
					StringBuilder include = new StringBuilder();
					for (var entry : MaterialManager.MATERIAL_MAP.entrySet()) {
						include
							.append("#define MAT_")
							.append(entry.getKey().toUpperCase())
							.append(" getMaterial(")
							.append(entry.getValue().uboIndex)
							.append(")\n");
					}
					return include.toString();
				}
			)
			.addInclude("MATERIAL_GETTER", () -> generateGetter("Material", MaterialManager.MATERIALS.length))
			.addInclude("WATER_TYPE_GETTER", () -> generateGetter("WaterType", waterTypeManager.uboWaterTypes.getCount()))
			.addUniformBuffer(uboGlobal)
			.addUniformBuffer(uboLights)
			.addUniformBuffer(uboLightsCulling)
			.addUniformBuffer(uboUI)
			.addUniformBuffer(materialManager.uboMaterials)
			.addUniformBuffer(waterTypeManager.uboWaterTypes);
		renderer.addShaderIncludes(includes);
		return includes;
	}

	private void initializeShaders() throws ShaderException, IOException {
		var includes = getShaderIncludes();

		// Bind a valid VAO, otherwise validation may fail on older Intel-based Macs
		glBindVertexArray(vaoTri);

		renderer.initializeShaders(includes);
		uiProgram.compile(includes);

		if (configDynamicLights != DynamicLights.NONE && configTiledLighting) {
			if (!AMD_GPU && configTiledLightingImageLoadStore &&
				GL_CAPS.GL_ARB_shader_image_load_store &&
				tiledLightingImageStoreProgram.isViable()
			) {
				try {
					tiledLightingImageStoreProgram.compile(includes
						.define("TILED_IMAGE_STORE", true)
						.define("TILED_LIGHTING_LAYER", false));
				} catch (ShaderException ex) {
					log.warn("Disabling TILED_IMAGE_STORE due to:", ex);
				}
			}

			// Compile layered version if the image store version isn't supported or failed to compile
			if (!tiledLightingImageStoreProgram.isValid()) {
				try {
					for (int layer = 0; layer < DynamicLights.MAX_LAYERS_PER_TILE; layer++) {
						var shader = new TiledLightingShaderProgram();
						shader.compile(includes
							.define("TILED_IMAGE_STORE", false)
							.define("TILED_LIGHTING_LAYER", layer));
						tiledLightingShaderPrograms.add(shader);
					}
				} catch (ShaderException ex) {
					log.warn("Disabling TILED_LIGHTING_LAYERED due to:", ex);
					// If both tiled lighting implementations fail, fall back to the old lighting, and warn about it
					if (!Props.DEVELOPMENT) {
						config.tiledLighting(false);
						PopupUtils.displayPopupMessage(
							client, "117 HD Error",
							"Tiled lighting has been automatically disabled, since it failed to compile on your GPU.<br>" +
							"<br>GPU name: " + glGetString(GL_RENDERER) + "<br><br>" +
							"If you want to help us make it work on your system, please join our " +
							"<a href=\"" + HdPlugin.DISCORD_URL + "\">Discord</a> server, and<br>" +
							"click the \"Open logs folder\" button below, find the file named \"client\" or \"client.log\",<br>" +
							"then drag and drop that file into one of our support channels.",
							new String[] { "Open logs folder", "Ok" },
							i -> {
								if (i == 0) {
									LinkBrowser.open(RuneLite.LOGS_DIR.toString());
									return false;
								}
								return true;
							}
						);
					}
					configTiledLighting = false;
				}
			}
		}

		checkGLErrors();

		eventBus.post(new ShaderRecompile(includes));
	}

	private void destroyShaders() {
		renderer.destroyShaders();
		uiProgram.destroy();

		tiledLightingImageStoreProgram.destroy();
		for (var program : tiledLightingShaderPrograms)
			program.destroy();
		tiledLightingShaderPrograms.clear();
	}

	public void recompilePrograms() {
		// Only recompile if the programs have been compiled successfully before
		if (!uiProgram.isValid())
			return;

		clientThread.invoke(() -> {
			try {
				renderer.waitUntilIdle();
				destroyShaders();
				initializeShaders();
			} catch (ShaderException | IOException ex) {
				// TODO: If each shader compilation leaves the previous working shader intact, we wouldn't need to shut down on failure
				log.error("Error while recompiling shaders:", ex);
				stopPlugin();
			}
		});
	}

	private void initializeVaos() {
		{
			// Create quad VAO
			vaoQuad = glGenVertexArrays();
			vboQuad = glGenBuffers();
			glBindVertexArray(vaoQuad);

			FloatBuffer vboQuadData = BufferUtils.createFloatBuffer(16)
				.put(new float[] {
					// x, y, u, v
					1, 1, 1, 1, // top right
					-1, 1, 0, 1, // top left
					-1, -1, 0, 0, // bottom left
					1, -1, 1, 0 // bottom right
				})
				.flip();
			glBindBuffer(GL_ARRAY_BUFFER, vboQuad);
			glBufferData(GL_ARRAY_BUFFER, vboQuadData, GL_STATIC_DRAW);

			// position attribute
			glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0);
			glEnableVertexAttribArray(0);

			// texture coord attribute
			glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
			glEnableVertexAttribArray(1);
		}

		{
			// Create tri VAO
			vaoTri = glGenVertexArrays();
			vboTri = glGenBuffers();
			glBindVertexArray(vaoTri);

			FloatBuffer vboTriData = BufferUtils.createFloatBuffer(12)
				.put(new float[] {
					// x, y, u, v
					-1, -1, 0, 0, // bottom left
					3, -1, 2, 0, // bottom right (off-screen)
					-1, 3, 0, 2 // top left (off-screen)
				})
				.flip();
			glBindBuffer(GL_ARRAY_BUFFER, vboTri);
			glBufferData(GL_ARRAY_BUFFER, vboTriData, GL_STATIC_DRAW);

			// position attribute
			glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0);
			glEnableVertexAttribArray(0);

			// texture coord attribute
			glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
			glEnableVertexAttribArray(1);
		}
	}

	private void destroyVaos() {
		if (vboQuad != 0)
			glDeleteBuffers(vboQuad);
		vboQuad = 0;

		if (vaoQuad != 0)
			glDeleteVertexArrays(vaoQuad);
		vaoQuad = 0;

		if (vboTri != 0)
			glDeleteBuffers(vboTri);
		vboTri = 0;

		if (vaoTri != 0)
			glDeleteVertexArrays(vaoTri);
		vaoTri = 0;
	}

	private void initializeUbos() {
		uboGlobal = new UBOGlobal();
		uboGlobal.initialize(UNIFORM_BLOCK_GLOBAL);

		uboUI = new UBOUI();
		uboUI.initialize(UNIFORM_BLOCK_UI);

		uboLights = new UBOLights(false);
		uboLights.initialize(UNIFORM_BLOCK_LIGHTS);

		uboLightsCulling = new UBOLights(true);
		uboLightsCulling.initialize(UNIFORM_BLOCK_LIGHTS_CULLING);
	}

	private void destroyUbos() {
		if (uboGlobal != null)
			uboGlobal.destroy();
		uboGlobal = null;

		if (uboUI != null)
			uboUI.destroy();
		uboUI = null;

		if (uboLights != null)
			uboLights.destroy();
		uboLights = null;

		if (uboLightsCulling != null)
			uboLightsCulling.destroy();
		uboLightsCulling = null;
	}

	public void updateSceneFbo() {
		if (texUi == null)
			return;

		int[] viewport = {
			client.getViewportXOffset(),
			texUi.getHeight() - (client.getViewportYOffset() + client.getViewportHeight()),
			client.getViewportWidth(),
			client.getViewportHeight()
		};

		// Skip rendering when there's no viewport to render to, which happens while world hopping
		if (viewport[2] == 0 || viewport[3] == 0)
			return;

		// DPI scaling and stretched mode also affects the game's viewport
		divide(sceneViewportScale, vec(actualUiResolution), vec(texUi.getWidth(), texUi.getHeight()));
		if (sceneViewportScale[0] != 1 || sceneViewportScale[1] != 1) {
			// Pad the viewport before scaling, so it always covers the game's viewport in the UI
			for (int i = 0; i < 2; i++) {
				viewport[i] -= 1;
				viewport[i + 2] += 2;
			}
			viewport = round(multiply(vec(viewport), sceneViewportScale));
		}

		final int forcedAASamples = fboBackBuffer.getDescriptor().samples;
		int samples = forcedAASamples != 0 ? forcedAASamples : min(config.antiAliasingMode().getSamples(), glGetInteger(GL_MAX_SAMPLES));
		sceneViewport = viewport;

		float resolutionScale = config.sceneResolutionScale() / 100f;
		int[] sceneResolution = round(max(vec(1), multiply(slice(vec(sceneViewport), 2), resolutionScale)));
		uboGlobal.sceneResolution.set(sceneResolution);

		fboScene.resize(sceneResolution[0], sceneResolution[1], samples);
	}

	public void initializeShaderHotswapping() {
		SHADER_PATH.watch("\\.(glsl|cl)$", path -> {
			log.info("Recompiling shaders: {}", path);
			recompilePrograms();
		});
	}

	public void prepareInterfaceTexture() {
		final BufferProvider bufferProvider = client.getBufferProvider();
		if(bufferProvider == null)
			return;

		if (client.isStretchedEnabled()) {
			Dimension dim = client.getStretchedDimensions();
			actualUiResolution[0] = dim.width;
			actualUiResolution[1] = dim.height;
		} else {
			actualUiResolution[0] = client.getCanvasWidth();
			actualUiResolution[1] = client.getCanvasHeight();
		}
		round(actualUiResolution, multiply(vec(actualUiResolution), getDpiScaling()));

		texUi.resize(client.getCanvasWidth(), client.getCanvasHeight());
		if(isPowerSaving || !hasLoggedIn) {
			texUi.uploadSubPixels2D(
				bufferProvider.getWidth(),
				bufferProvider.getHeight(),
				bufferProvider.getPixels(),
				GLTextureFormat.BGRA_INT_8_8_8_8
			);
		} else {
			texUi.uploadSubPixelsAsync2D(
				bufferProvider.getWidth(),
				bufferProvider.getHeight(),
				bufferProvider.getPixels(),
				GLTextureFormat.BGRA_INT_8_8_8_8
			);
		}
	}

	public void drawUi(int overlayColor) {
		if (texUi == null || developerTools.isHideUiEnabled() && hasLoggedIn)
			return;

		// Fix vanilla bug causing the overlay to remain on the login screen in areas like Fossil Island underwater
		if (client.getGameState().getState() < GameState.LOADING.getState())
			overlayColor = 0;

		frameTimer.begin(Timer.RENDER_UI);

		texUi.completeUploadSubPixelsAsync();
		texUi.setSampler(config.uiScalingMode().glSamplingMode);

		glBindFramebuffer(GL_FRAMEBUFFER, fboBackBuffer.getFboId());
		// Disable alpha writes, just in case the default FBO has an alpha channel
		glColorMask(true, true, true, false);

		glViewport(0, 0, actualUiResolution[0], actualUiResolution[1]);

		tiledLightingOverlay.render();

		uiProgram.use();
		uboUI.sourceDimensions.set(texUi.getWidth(), texUi.getHeight());
		uboUI.targetDimensions.set(actualUiResolution);
		uboUI.alphaOverlay.set(ColorUtils.srgba(overlayColor));
		uboUI.upload();

		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		glBindVertexArray(vaoTri);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		shadowMapOverlay.render();
		gammaCalibrationOverlay.render();

		// Reset
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		glDisable(GL_BLEND);
		glColorMask(true, true, true, true);

		frameTimer.end(Timer.RENDER_UI);
	}

	/**
     * Convert the front framebuffer to an Image
     */
	public Image screenshot() {
		if (texUi == null)
			return null;

		int width = actualUiResolution[0];
		int height = actualUiResolution[1];

		ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);

		glReadBuffer(awtContext.getBufferMode());
		glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		for (int y = 0; y < height; ++y) {
			for (int x = 0; x < width; ++x) {
				int r = buffer.get() & 0xff;
				int g = buffer.get() & 0xff;
				int b = buffer.get() & 0xff;
				buffer.get(); // alpha

				pixels[(height - y - 1) * width + x] = (r << 16) | (g << 8) | b;
			}
		}

		return image;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN) {
			hasLoggedIn = false;
			environmentManager.reset();
		}
	}

	public boolean isLoadingScene() {
		return renderer.isLoadingScene();
	}

	private void updateCachedConfigs() {
		configShadowMode = config.shadowMode();
		configShadowsEnabled = configShadowMode != ShadowMode.OFF;
		configRoofShadows = config.roofShadows();
		configGroundTextures = config.groundTextures();
		configGroundBlending = config.groundBlending();
		configModelTextures = config.modelTextures();
		configLegacyTzHaarReskin = config.legacyTzHaarReskin();
		configProjectileLights = config.projectileLights();
		configNpcLights = config.npcLights();
		configVanillaShadowMode = config.vanillaShadowMode();
		configHideFakeShadows = configVanillaShadowMode != VanillaShadowMode.SHOW;
		configLegacyGreyColors = config.legacyGreyColors();
		configModelBatching = config.modelBatching();
		configModelCaching = config.modelCaching();
		configDynamicLights = config.dynamicLights();
		configTiledLighting = config.tiledLighting();
		configTiledLightingImageLoadStore = config.tiledLightingImageLoadStore();
		configDetailDrawDistance = config.detailDrawDistance();
		configExpandShadowDraw = config.expandShadowDraw();
		configUseFasterModelHashing = config.fasterModelHashing();
		configZoneStreaming = config.zoneStreaming();
		configPowerSaving = config.powerSaving();
		configShadingMode = config.shadingMode();
		configUnlitFaceColors = configShadingMode.unlitFaceColors;
		configUndoVanillaShading = configShadingMode.undoVanillaShading;
		configPreserveVanillaNormals = config.preserveVanillaNormals();
		configWindDisplacement = config.windDisplacement();
		configCharacterDisplacement = config.characterDisplacement();
		configHideVanillaWaterEffects = config.hideVanillaWaterEffects();
		configSeasonalTheme = config.seasonalTheme();
		configSeasonalHemisphere = config.seasonalHemisphere();

		var newColorFilter = config.colorFilter();
		if (newColorFilter != configColorFilter) {
			configColorFilterPrevious = configColorFilter;
			configColorFilter = newColorFilter;
			colorFilterChangedAt = System.currentTimeMillis();
		}
		if (configColorFilter == ColorFilter.CEL_SHADING) {
			configGroundTextures = false;
			configModelTextures = false;
		}

		if (configSeasonalTheme == SeasonalTheme.AUTOMATIC) {
			var time = ZonedDateTime.now(ZoneOffset.UTC);

			if (configSeasonalHemisphere == SeasonalHemisphere.NORTHERN) {
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
			} else {
				switch (time.getMonth()) {
					case MARCH:
					case APRIL:
					case MAY:
						configSeasonalTheme = SeasonalTheme.AUTUMN;
						break;
					case JUNE:
					case JULY:
					case AUGUST:
						configSeasonalTheme = SeasonalTheme.WINTER;
						break;
					default:
						configSeasonalTheme = SeasonalTheme.SUMMER;
						break;
				}
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		// Exit if the plugin is off, the config is unrelated to the plugin, or if switching to a profile with the plugin turned off
		if (!isActive || !event.getGroup().equals(CONFIG_GROUP) || !pluginManager.isPluginEnabled(this))
			return;

		synchronized (this) {
			pendingConfigChanges.add(event.getKey());
			// Process pending config changes after the EDT is done with any pending work, which could include further config changes
			SwingUtilities.invokeLater(this::processPendingConfigChanges);
		}
	}

	public void processPendingConfigChanges() {
		clientThread.invoke(() -> {
			if (pendingConfigChanges.isEmpty())
				return;

			try {
				// TODO: Move this synchronization into ZoneRenderer
				sceneManager.getLoadingLock().lock();
				sceneManager.completeAllStreaming();

				// Synchronize with scene loading
				// TODO: Move this synchronization into LegacyRenderer
				synchronized (this) {
					updateCachedConfigs();

					log.debug("Processing {} pending config changes: {}", pendingConfigChanges.size(), pendingConfigChanges);

					renderer.processConfigChanges(pendingConfigChanges);

					boolean recompilePrograms = false;
					boolean recreateSceneFbo = false;
					boolean recreateShadowMapFbo = false;
					boolean reloadTexturesAndMaterials = false;
					boolean reloadEnvironments = false;
					boolean reloadModelOverrides = false;
					boolean reloadTileOverrides = false;
					boolean reloadScene = false;

					for (var key : pendingConfigChanges) {
						switch (key) {
							case KEY_LOW_MEMORY_MODE:
							case KEY_REMOVE_VERTEX_SNAPPING:
							case KEY_LEGACY_RENDERER:
							case KEY_INDIRECT_DRAW:
							case KEY_STORAGE_BUFFERS:
							case KEY_SHADING_MODE:
								restartPlugin();
								// since we'll be restarting the plugin anyway, skip pending changes
								return;
							case KEY_SEASONAL_THEME:
							case KEY_SEASONAL_HEMISPHERE:
							case KEY_GROUND_BLENDING:
							case KEY_GROUND_TEXTURES:
								reloadTileOverrides = true;
								break;
							case KEY_COLOR_FILTER:
								if (configColorFilter == ColorFilter.NONE || configColorFilterPrevious == ColorFilter.NONE)
									recompilePrograms = true;
								if (configColorFilter == ColorFilter.CEL_SHADING || configColorFilterPrevious == ColorFilter.CEL_SHADING)
									reloadScene = true;
								break;
							case KEY_CPU_USAGE_LIMIT:
								if (jobSystem.isActive()) {
									// Restart the job system with the new worker count
									jobSystem.shutDown();
									jobSystem.startUp(config.cpuUsageLimit());
								}
								break;
						}

						switch (key) {
							case KEY_EXPANDED_MAP_LOADING_CHUNKS:
								client.setExpandedMapLoading(getExpandedMapLoadingChunks());
								// fall-through
							case KEY_HIDE_UNRELATED_AREAS:
								if (client.getGameState() == GameState.LOGGED_IN)
									client.setGameState(GameState.LOADING);
								break;
							case KEY_COLOR_BLINDNESS:
							case KEY_MACOS_INTEL_WORKAROUND:
							case KEY_DYNAMIC_LIGHTS:
							case KEY_TILED_LIGHTING:
							case KEY_TILED_LIGHTING_IMAGE_STORE:
							case KEY_NORMAL_MAPPING:
							case KEY_PARALLAX_OCCLUSION_MAPPING:
							case KEY_UI_SCALING_MODE:
							case KEY_VANILLA_COLOR_BANDING:
							case KEY_WIND_DISPLACEMENT:
							case KEY_CHARACTER_DISPLACEMENT:
							case KEY_WIREFRAME:
							case KEY_SHADOW_FILTERING:
							case KEY_WINDOWS_HDR_CORRECTION:
								recompilePrograms = true;
								break;
							case KEY_ANTI_ALIASING_MODE:
							case KEY_SCENE_RESOLUTION_SCALE:
								recreateSceneFbo = true;
								break;
							case KEY_SHADOW_MODE:
							case KEY_SHADOW_RESOLUTION:
							case KEY_SHADOW_TRANSPARENCY:
								recompilePrograms = true;
								recreateShadowMapFbo = true;
								break;
							case KEY_ATMOSPHERIC_LIGHTING:
							case KEY_POH_THEME_ENVIRONMENTS:
							case KEY_LEGACY_TOB_ENVIRONMENT:
								reloadEnvironments = true;
								break;
							case KEY_SEASONAL_THEME:
							case KEY_SEASONAL_HEMISPHERE:
								reloadEnvironments = true;
								reloadModelOverrides = true;
								// fall-through
							case KEY_ANISOTROPIC_FILTERING_LEVEL:
							case KEY_GROUND_TEXTURES:
							case KEY_MODEL_TEXTURES:
							case KEY_TEXTURE_RESOLUTION:
							case KEY_INFERNAL_CAPE:
								reloadTexturesAndMaterials = true;
								// fall-through
							case KEY_GROUND_BLENDING:
							case KEY_FILL_GAPS_IN_TERRAIN:
							case KEY_LEGACY_TZHAAR_RESKIN:
								reloadScene = true;
								break;
							case KEY_HIDE_VANILLA_WATER_EFFECTS:
								reloadModelOverrides = true;
								// fall-through
							case KEY_VANILLA_SHADOW_MODE:
								reloadScene = true;
								break;
							case KEY_LEGACY_GREY_COLORS:
							case KEY_PRESERVE_VANILLA_NORMALS:
							case KEY_FLAT_SHADING:
								recompilePrograms = true;
								reloadScene = true;
								break;
							case KEY_FPS_TARGET:
							case KEY_UNLOCK_FPS:
							case KEY_VSYNC_MODE:
								setupSyncMode();
								break;
						}
					}

					if (reloadTexturesAndMaterials || recompilePrograms)
						renderer.waitUntilIdle();

					if (reloadTexturesAndMaterials) {
						materialManager.reload(reloadScene);
						modelOverrideManager.reload();
						recompilePrograms = true;
					} else if (reloadModelOverrides) {
						modelOverrideManager.reload();
					}

					if (reloadTileOverrides)
						tileOverrideManager.reload(reloadScene);

					if (recompilePrograms)
						recompilePrograms();

					if (recreateSceneFbo)
						updateSceneFbo();

					if (reloadScene) {
						renderer.clearCaches();
						renderer.reloadScene();
					}

					if(recreateShadowMapFbo && fboShadowMap != null) {
						int shadowMapResolution = configShadowsEnabled ? config.shadowResolution().getValue() : 1;
						fboShadowMap.resize(shadowMapResolution, shadowMapResolution);
					}

					if (reloadEnvironments)
						environmentManager.reload();
				}
			} catch (Throwable ex) {
				log.error("Error while changing settings:", ex);
				stopPlugin();
			} finally {
				sceneManager.getLoadingLock().unlock();
				log.trace("loadingLock unlocked - holdCount: {}", sceneManager.getLoadingLock().getHoldCount());
				pendingConfigChanges.clear();
				frameTimer.reset();
			}
		});
	}

	public void setupSyncMode() {
		// Without unlocked fps, the client manages sync on its 20ms timer
		boolean unlockFps = config.unlockFps();
		HdPluginConfig.SyncMode syncMode = unlockFps ? config.syncMode() : HdPluginConfig.SyncMode.OFF;

		if (frameTimer.isActive()) {
			unlockFps = true;
			syncMode = SyncMode.OFF;
		}

		client.setUnlockedFps(unlockFps);
		int swapInterval;
		switch (syncMode) {
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

	private float[] getDpiScaling() {
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		if (graphicsConfiguration == null)
			return new float[] { 1, 1 };

		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		return new float[] { (float) t.getScaleX(), (float) t.getScaleY() };
	}

	public int getDrawDistance() {
		return clamp(config.drawDistance(), 0, MAX_DISTANCE);
	}

	public float getGammaCorrection() {
		if (config.useLegacyBrightness())
			return 1;
		return 100f / config.brightness();
	}

	public int getExpandedMapLoadingChunks() {
		if (useLowMemoryMode)
			return 0;
		return config.expandedMapLoadingChunks();
	}

	@Subscribe(priority = -1) // Run after the low detail plugin
	public void onBeforeRender(BeforeRender beforeRender) {
		SKIP_GL_ERROR_CHECKS = !log.isDebugEnabled() || developerTools.isFrameTimingsOverlayEnabled();

		frame = (frame + 1) & Integer.MAX_VALUE;

		if (isPluginStopPending) {
			log.debug("Shutdown has been requested, stopping plugin");
			isPluginStopPending = false;
			stopPlugin();
			return;
		}

		if (lastFrameTimeMillis > 0) {
			deltaTime = (float) ((System.currentTimeMillis() - lastFrameTimeMillis) / 1000.);

			// Restart the to avoid potential buffer corruption if the computer has likely resumed from suspension
			if (deltaTime > 300) {
				log.debug("Restarting the after probable OS suspend ({} second delta)", deltaTime);
				restartPlugin();
			}

			// If system time changes between frames, clamp the delta to a more sensible value
			if (abs(deltaTime) > 10)
				deltaTime = 1 / 60.f;
			// The client delta doesn't need clamping
			deltaClientTime = (float) (elapsedClientTime - lastFrameClientTime);

			elapsedTime += deltaTime;
			windOffset += deltaTime * environmentManager.currentWindSpeed;
		}
		lastFrameTimeMillis = System.currentTimeMillis();
		lastFrameClientTime = elapsedClientTime;

		isClientMinimized = HDUtils.isJFrameMinimized(clientJFrame);
		if (isClientInFocus) {
			clientUnfocusedTime = 0;
		} else {
			clientUnfocusedTime += deltaTime;
		}
		isPowerSaving = isClientMinimized || configPowerSaving && clientUnfocusedTime >= 15;

		// The game runs significantly slower with lower planes in Chambers of Xeric
		var ctx = getSceneContext();
		if (ctx != null)
			ctx.scene.setMinLevel(ctx.isInChambersOfXeric ? client.getPlane() : ctx.scene.getMinLevel());

		DestructibleHandler.flushPendingDestruction();
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

		fishingSpotReplacer.update();
	}

	@Subscribe
	public void onFocusChanged(FocusChanged event) {
		isClientInFocus = event.isFocused();
	}

	@SuppressWarnings("StatementWithEmptyBody")
	public static void clearGLErrors() {
		// @formatter:off
		while (glGetError() != GL_NO_ERROR);
		// @formatter:on
	}

	public static boolean checkGLErrors() {
		return checkGLErrors(null);
	}

	public static boolean checkGLErrors(@Nullable Provider<String> contextProvider) {
		if (SKIP_GL_ERROR_CHECKS)
			return false;

		boolean hasGLError = false;
		String context = null;
		while (true) {
			int err = glGetError();
			if (err == GL_NO_ERROR)
				return hasGLError;

			String errStr;
			switch (err) {
				case GL_INVALID_ENUM:
					errStr = "INVALID_ENUM";
					break;
				case GL_INVALID_VALUE:
					errStr = "INVALID_VALUE";
					break;
				case GL_STACK_OVERFLOW:
					errStr = "STACK_OVERFLOW";
					break;
				case GL_STACK_UNDERFLOW:
					errStr = "STACK_UNDERFLOW";
					break;
				case GL_INVALID_OPERATION:
					errStr = "INVALID_OPERATION";
					break;
				case GL_INVALID_FRAMEBUFFER_OPERATION:
					errStr = "INVALID_FRAMEBUFFER_OPERATION";
					break;
				default:
					errStr = String.format("Error code: %d", err);
					break;
			}
			if (contextProvider != null && context == null)
				context = contextProvider.get();
			if (context != null) {
				log.debug("glGetError({}):", context, new Exception(errStr));
			} else {
				log.debug("GL error:", new Exception(errStr));
			}
			hasGLError = true;
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

	private void displayUnsupportedGpuMessage(boolean isFallbackGpu, String glRenderer, Renderer renderer) {
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
				isFallbackGpu ? (
					"Your graphics driver appears to be broken.<br>"
					+ "<br>"
					+ "Some things to try:<br>"
					+ "&nbsp;• Reinstall the drivers for <b>both</b> your processor's integrated graphics <b>and</b> your graphics card.<br>"
				) :
					(
						(
							renderer instanceof LegacyRenderer && GL_CAPS.OpenGL31 ?
								"The legacy renderer does not support your GPU. Try disabling it in the Legacy settings section." :
								"Your GPU is currently not supported by 117 HD."
						)
						+ "<br><br>GPU name: " + glRenderer + "<br><br>"
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

	public void displayOutOfMemoryMessage() {
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
				+ "Try " + (useLowMemoryMode ? "" : "reducing your model cache size from " + config.modelCacheSizeMiB() + " or ")
				+ "closing other programs.<br>"
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
