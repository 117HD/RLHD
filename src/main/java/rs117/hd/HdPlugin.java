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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.entityhider.EntityHiderPlugin;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.OSType;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.BufferUtils;
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
import rs117.hd.opengl.AsyncUICopy;
import rs117.hd.opengl.GLBinding;
import rs117.hd.opengl.buffer.ShaderStructuredBuffer;
import rs117.hd.opengl.buffer.uniforms.UBODisplacement;
import rs117.hd.opengl.buffer.uniforms.UBOGlobal;
import rs117.hd.opengl.buffer.uniforms.UBOLights;
import rs117.hd.opengl.buffer.uniforms.UBOUI;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.shader.TiledLightingShaderProgram;
import rs117.hd.opengl.shader.UIShaderProgram;
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
import rs117.hd.utils.DeveloperTools;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.HDVariables;
import rs117.hd.utils.NpcDisplacementCache;
import rs117.hd.utils.PopupUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.ShaderRecompile;

import static net.runelite.api.Constants.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.opengl.GLBinding.BINDING_IMG_TILE_LIGHTING_MAP;
import static rs117.hd.opengl.GLBinding.BINDING_TEX_SHADOW_MAP;
import static rs117.hd.opengl.GLBinding.BINDING_TEX_UI;
import static rs117.hd.opengl.GLBinding.BINDING_UBO_GLOBAL;
import static rs117.hd.opengl.GLBinding.BINDING_UBO_LIGHTS;
import static rs117.hd.opengl.GLBinding.BINDING_UBO_LIGHTS_CULLING;
import static rs117.hd.opengl.GLBinding.BINDING_UBO_UI;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;

@PluginDescriptor(
	name = "117 HD",
	description = "GPU renderer with a suite of graphical enhancements",
	tags = { "hd", "high", "detail", "graphics", "shaders", "textures", "gpu", "shadows", "lights" },
	conflicts = "GPU"
)
@PluginDependency(EntityHiderPlugin.class)
@Slf4j
public class HdPlugin extends Plugin {
	public static final ResourcePath PLUGIN_DIR = Props
		.getFolder("rlhd.plugin-dir", () -> path(RuneLite.RUNELITE_DIR, "117hd"));

	public static final String DISCORD_URL = "https://discord.gg/U4p6ChjgSE";
	public static final String RUNELITE_URL = "https://runelite.net";
	public static final String AMD_DRIVER_URL = "https://www.amd.com/en/support";
	public static final String INTEL_DRIVER_URL = "https://www.intel.com/content/www/us/en/support/detect.html";
	public static final String NVIDIA_DRIVER_URL = "https://www.nvidia.com/en-us/geforce/drivers/";

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
	private AsyncUICopy asyncUICopy;

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

	public Canvas canvas;
	public AWTContext awtContext;
	private Callback debugCallback;

	private static final ResourcePath SHADER_PATH = Props
		.getFolder("rlhd.shader-path", () -> path(HdPlugin.class));

	public int vaoQuad;
	private int vboQuad;

	public int vaoTri;
	private int vboTri;

	@Getter
	@Nullable
	private int[] uiResolution;
	private final int[] actualUiResolution = { 0, 0 }; // Includes stretched mode and DPI scaling
	private int texUi;
	private int pboUi;

	@Nullable
	public int[] sceneViewport;
	public final float[] sceneViewportScale = { 1, 1 };
	public int msaaSamples;

	public int[] sceneResolution;
	public int fboScene;
	private int rboSceneColor;
	private int rboSceneDepth;
	public int fboSceneResolve;
	private int rboSceneResolveColor;

	public int shadowMapResolution;
	public int fboShadowMap;
	private int texShadowMap;

	public int[] tiledLightingResolution;
	public int tiledLightingLayerCount;
	public int fboTiledLighting;
	public int texTiledLighting;

	public final UBOGlobal uboGlobal = new UBOGlobal();
	public final UBODisplacement uboDisplacement = new UBODisplacement();
	public final UBOLights uboLights = new UBOLights(false);
	public final UBOLights uboLightsCulling = new UBOLights(true);
	public final UBOUI uboUI = new UBOUI();

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
	public boolean configRoofShadows;
	public boolean configExpandShadowDraw;
	public boolean configUseFasterModelHashing;
	public boolean configZoneStreaming;
	public boolean configUndoVanillaShading;
	public boolean configPreserveVanillaNormals;
	public boolean configAsyncUICopy;
	public boolean configWindDisplacement;
	public boolean configCharacterDisplacement;
	public boolean configTiledLighting;
	public boolean configTiledLightingImageLoadStore;
	public int configDetailDrawDistance;
	public DynamicLights configDynamicLights;
	public ShadowMode configShadowMode;
	public SeasonalTheme configSeasonalTheme;
	public SeasonalHemisphere configSeasonalHemisphere;
	public VanillaShadowMode configVanillaShadowMode;
	public ColorFilter configColorFilter = ColorFilter.NONE;
	public ColorFilter configColorFilterPrevious;

	public boolean useLowMemoryMode;
	public boolean enableDetailedTimers;
	public boolean enableFreezeFrame;
	public boolean orthographicProjection;

	@Getter
	private boolean isActive;
	private boolean lwjglInitialized;
	public boolean hasLoggedIn;
	public boolean redrawPreviousFrame;
	public boolean isInChambersOfXeric;
	public boolean isInHouse;
	public boolean justChangedArea;
	public Scene skipScene;

	public final ConcurrentHashMap.KeySetView<String, ?> pendingConfigChanges = ConcurrentHashMap.newKeySet();

	// Camera position and orientation may be reused from the old scene while hopping, prior to drawScene being called
	public final float[] cameraPosition = new float[3];
	public final int[] cameraShift = new int[2];
	public final int[] cameraFocalPoint = new int[2];
	public final float[] cameraOrientation = new float[2];
	public final float[][] cameraFrustum = new float[6][4];
	public float[] viewMatrix = new float[16];
	public float[] viewProjMatrix = new float[16];
	public float[] invViewProjMatrix = new float[16];

	@Getter
	public int drawnTileCount;
	@Getter
	public int drawnZoneCount;
	@Getter
	public int drawCallCount;
	@Getter
	public int drawnStaticRenderableCount;
	@Getter
	public int drawnDynamicRenderableCount;
	@Getter
	public long garbageCollectionCount;

	public double elapsedTime;
	public double elapsedClientTime;
	public float deltaTime;
	public float deltaClientTime;
	public long lastFrameTimeMillis;
	public double lastFrameClientTime;
	public float windOffset;
	public long colorFilterChangedAt;

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

				fboScene = 0;
				rboSceneColor = 0;
				rboSceneDepth = 0;
				fboSceneResolve = 0;
				rboSceneResolveColor = 0;
				fboShadowMap = 0;
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

				SKIP_GL_ERROR_CHECKS = false;
				GL_CAPS = GL.createCapabilities();
				useLowMemoryMode = config.lowMemoryMode();
				BUFFER_GROWTH_MULTIPLIER = useLowMemoryMode ? 1.333f : 2;

				OSType osType = OSType.getOSType();
				String arch = System.getProperty("os.arch", "Unknown");
				String wordSize = System.getProperty("sun.arch.data.model", "Unknown");
				log.info("Operating system: {}", osType);
				log.info("Architecture: {}", arch);
				log.info("Client is {}-bit", wordSize);
				APPLE = osType == OSType.MacOS;
				APPLE_ARM = APPLE && arch.equals("aarch64");

				String glRenderer = Objects.requireNonNullElse(glGetString(GL_RENDERER), "Unknown");
				String glVendor = Objects.requireNonNullElse(glGetString(GL_VENDOR), "Unknown");
				log.info("Using device: {} ({})", glRenderer, glVendor);
				log.info("Using driver: {}", glGetString(GL_VERSION));
				AMD_GPU = glRenderer.contains("AMD") || glRenderer.contains("Radeon") || glVendor.contains("ATI");
				INTEL_GPU = glRenderer.contains("Intel");
				NVIDIA_GPU = glRenderer.toLowerCase().contains("nvidia");

				SUPPORTS_INDIRECT_DRAW = NVIDIA_GPU && !APPLE || config.forceIndirectDraw();

				renderer = config.legacyRenderer() ?
					injector.getInstance(LegacyRenderer.class) :
					injector.getInstance(ZoneRenderer.class);
				log.info("Using renderer: {}", renderer.getClass().getSimpleName());

				log.info("Low memory mode: {}", useLowMemoryMode);

				if (!Props.has("rlhd.skipGpuChecks")) {
					List<String> fallbackDevices = List.of(
						"GDI Generic",
						"D3D12 (Microsoft Basic Render Driver)",
						"softpipe"
					);
					boolean isFallbackGpu = fallbackDevices.contains(glRenderer);
					if (isFallbackGpu || !renderer.supportsGpu(GL_CAPS)) {
						log.error("Unsupported GPU. Stopping the plugin...");
						displayUnsupportedGpuMessage(isFallbackGpu, glRenderer);
						stopPlugin();
						return true;
					}
				}

				lwjglInitialized = true;
				checkGLErrors();

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

				renderer.initialize();
				eventBus.register(renderer);
				int gpuFlags = DrawCallbacks.GPU | renderer.gpuFlags();
				if (config.removeVertexSnapping())
					gpuFlags |= DrawCallbacks.NO_VERTEX_SNAPPING;

				initializeShaders();
				initializeShaderHotswapping();
				initializeUiTexture();
				initializeShadowMapFbo();

				checkGLErrors();

				client.setDrawCallbacks(renderer);
				client.setGpuFlags(gpuFlags);
				client.setExpandedMapLoading(getExpandedMapLoadingChunks());
				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				gamevalManager.startUp();
				areaManager.startUp();
				groundMaterialManager.startUp();
				tileOverrideManager.startUp();
				modelOverrideManager.startUp();
				lightManager.startUp();
				environmentManager.startUp();
				fishingSpotReplacer.startUp();
				gammaCalibrationOverlay.initialize();
				npcDisplacementCache.initialize();

				isActive = true;
				hasLoggedIn = client.getGameState().getState() > GameState.LOGGING_IN.getState();
				redrawPreviousFrame = false;
				skipScene = null;
				isInHouse = false;
				isInChambersOfXeric = false;

				// Force the client to reload the scene since we're changing GPU flags, and to restore any removed tiles
				if (client.getGameState() == GameState.LOGGED_IN)
					client.setGameState(GameState.LOADING);

				checkGLErrors();

				clientThread.invokeLater(this::displayUpdateMessage);
			} catch (Throwable err) {
				log.error("Error while starting 117 HD", err);
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

			asyncUICopy.complete();

			if (lwjglInitialized) {
				lwjglInitialized = false;
				renderer.waitUntilIdle();
				GLBinding.reset();


				destroyUiTexture();
				destroyShaders();
				destroyVaos();
				destroyUbos();
				destroySceneFbo();
				destroyShadowMapFbo();
				destroyTiledLightingFbo();

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

	public void stopPlugin() {
		SwingUtilities.invokeLater(() -> {
			try {
				pluginManager.setPluginEnabled(this, false);
				pluginManager.stopPlugin(this);
			} catch (PluginInstantiationException ex) {
				log.error("Error while stopping 117HD:", ex);
			}
		});

		shutDown();
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
		return renderer.getSceneContext();
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
		String versionHeader = "#version 330";
		if (ShaderStructuredBuffer.supportsShaderStorage()) {
			if (GL_CAPS.OpenGL45) versionHeader = "#version 450";
			else if (GL_CAPS.OpenGL44) versionHeader = "#version 440";
			else if (GL_CAPS.OpenGL43) versionHeader = "#version 430";
			else if (GL_CAPS.OpenGL42) versionHeader = "#version 420";
			else if (GL_CAPS.OpenGL41) versionHeader = "#version 410";
		}

		var includes = new ShaderIncludes()
			.addIncludePath(SHADER_PATH)
			.addInclude("VERSION_HEADER", versionHeader)
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
			.define("SHADOW_TRANSPARENCY", config.enableShadowTransparency())
			.define("PIXELATED_SHADOWS", config.pixelatedShadows())
			.define("VANILLA_COLOR_BANDING", config.vanillaColorBanding())
			.define("UNDO_VANILLA_SHADING", configUndoVanillaShading)
			.define("LEGACY_GREY_COLORS", configLegacyGreyColors)
			.define("DISABLE_DIRECTIONAL_SHADING", config.shadingMode() != ShadingMode.DEFAULT)
			.define("FLAT_SHADING", config.flatShading())
			.define("WIND_DISPLACEMENT", configWindDisplacement)
			.define("WIND_DISPLACEMENT_NOISE_RESOLUTION", WIND_DISPLACEMENT_NOISE_RESOLUTION)
			.define("CHARACTER_DISPLACEMENT", configCharacterDisplacement)
			.define("MAX_CHARACTER_POSITION_COUNT", max(1, UBODisplacement.MAX_CHARACTER_POSITION_COUNT))
			.define("WIREFRAME", config.wireframe())
			.define("WINDOWS_HDR_CORRECTION", config.windowsHdrCorrection())
			.define("LEGACY_RENDERER", renderer instanceof LegacyRenderer)
			.define("ZONE_RENDERER", renderer instanceof ZoneRenderer)
			.define("MAX_SIMULTANEOUS_WORLD_VIEWS", 0)
			.define("WORLD_VIEW_GETTER", "")
			.define("MODEL_DATA_GETTER", "")
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
			.addUniformBuffer(uboDisplacement)
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
		uboGlobal.initialize(BINDING_UBO_GLOBAL);
		uboLights.initialize(BINDING_UBO_LIGHTS);
		uboLightsCulling.initialize(BINDING_UBO_LIGHTS_CULLING);
		uboUI.initialize(BINDING_UBO_UI);
	}

	private void destroyUbos() {
		uboGlobal.destroy();
		uboLights.destroy();
		uboLightsCulling.destroy();
		uboUI.destroy();
	}

	private void initializeUiTexture() {
		pboUi = glGenBuffers();

		texUi = glGenTextures();
		BINDING_TEX_UI.setActive();
		glBindTexture(GL_TEXTURE_2D, texUi);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	}

	private void destroyUiTexture() {
		uiResolution = null;

		if (pboUi != 0)
			glDeleteBuffers(pboUi);
		pboUi = 0;

		if (texUi != 0)
			glDeleteTextures(texUi);
		texUi = 0;
	}

	public void updateTiledLightingFbo() {
		assert configTiledLighting;

		int[] newResolution = max(ivec(1), round(divide(vec(sceneResolution), TILED_LIGHTING_TILE_SIZE)));
		int newLayerCount = configDynamicLights.getTiledLightingLayers();
		if (Arrays.equals(newResolution, tiledLightingResolution) && tiledLightingLayerCount == newLayerCount)
			return;

		destroyTiledLightingFbo();

		tiledLightingResolution = newResolution;
		tiledLightingLayerCount = newLayerCount;

		fboTiledLighting = glGenFramebuffers();
		texTiledLighting = glGenTextures();
		BINDING_IMG_TILE_LIGHTING_MAP.setActive();
		glBindTexture(GL_TEXTURE_2D_ARRAY, texTiledLighting);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexImage3D(
			GL_TEXTURE_2D_ARRAY,
			0,
			GL_RGBA16UI,
			tiledLightingResolution[0],
			tiledLightingResolution[1],
			tiledLightingLayerCount,
			0,
			GL_RGBA_INTEGER,
			GL_UNSIGNED_SHORT,
			0
		);

		glBindFramebuffer(GL_FRAMEBUFFER, fboTiledLighting);
		glFramebufferTexture(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texTiledLighting, 0);
		checkGLErrors();

		if (tiledLightingImageStoreProgram.isValid())
			ARBShaderImageLoadStore.glBindImageTexture(
				BINDING_IMG_TILE_LIGHTING_MAP.getImageUnit(), texTiledLighting, 0, true, 0, GL_WRITE_ONLY, GL_RGBA16UI);

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

		checkGLErrors();

		uboGlobal.tiledLightingResolution.set(tiledLightingResolution);
	}

	private void destroyTiledLightingFbo() {
		tiledLightingResolution = null;

		if (fboTiledLighting != 0)
			glDeleteFramebuffers(fboTiledLighting);
		fboTiledLighting = 0;

		if (texTiledLighting != 0)
			glDeleteTextures(texTiledLighting);
		texTiledLighting = 0;
	}

	public void updateSceneFbo() {
		if (uiResolution == null)
			return;

		int[] viewport = {
			client.getViewportXOffset(),
			uiResolution[1] - (client.getViewportYOffset() + client.getViewportHeight()),
			client.getViewportWidth(),
			client.getViewportHeight()
		};

		// Skip rendering when there's no viewport to render to, which happens while world hopping
		if (viewport[2] == 0 || viewport[3] == 0)
			return;

		// DPI scaling and stretched mode also affects the game's viewport
		divide(sceneViewportScale, vec(actualUiResolution), vec(uiResolution));
		if (sceneViewportScale[0] != 1 || sceneViewportScale[1] != 1) {
			// Pad the viewport before scaling, so it always covers the game's viewport in the UI
			for (int i = 0; i < 2; i++) {
				viewport[i] -= 1;
				viewport[i + 2] += 2;
			}
			viewport = round(multiply(vec(viewport), sceneViewportScale));
		}

		// Check if scene FBO needs to be recreated
		if (Arrays.equals(sceneViewport, viewport))
			return;

		destroySceneFbo();
		sceneViewport = viewport;

		// Bind default FBO to check whether anti-aliasing is forced
		int defaultFramebuffer = awtContext.getFramebuffer(false);
		glBindFramebuffer(GL_FRAMEBUFFER, defaultFramebuffer);
		final int forcedAASamples = glGetInteger(GL_SAMPLES);
		msaaSamples = forcedAASamples != 0 ? forcedAASamples : min(config.antiAliasingMode().getSamples(), glGetInteger(GL_MAX_SAMPLES));

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

		float resolutionScale = config.sceneResolutionScale() / 100f;
		sceneResolution = round(max(vec(1), multiply(slice(vec(sceneViewport), 2), resolutionScale)));
		uboGlobal.sceneResolution.set(sceneResolution);

		// Create and bind the FBO
		fboScene = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboScene);

		// Create color render buffer
		rboSceneColor = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboSceneColor);

		// Flush out all pending errors, so we can check whether the next step succeeds
		clearGLErrors();

		int format = 0;
		for (int desiredFormat : desiredFormats) {
			glRenderbufferStorageMultisample(GL_RENDERBUFFER, msaaSamples, desiredFormat, sceneResolution[0], sceneResolution[1]);

			if (glGetError() == GL_NO_ERROR) {
				format = desiredFormat;
				break;
			}
		}

		if (format == 0)
			throw new RuntimeException("No supported " + (sRGB ? "sRGB" : "linear") + " formats");

		// Found a usable format. Bind the RBO to the scene FBO
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rboSceneColor);
		checkGLErrors();

		// Create depth render buffer
		rboSceneDepth = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboSceneDepth);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, msaaSamples, GL_DEPTH_COMPONENT32F, sceneResolution[0], sceneResolution[1]);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboSceneDepth);
		checkGLErrors();

		// If necessary, create an FBO for resolving multisampling
		if (msaaSamples > 1 && resolutionScale != 1) {
			fboSceneResolve = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, fboSceneResolve);
			rboSceneResolveColor = glGenRenderbuffers();
			glBindRenderbuffer(GL_RENDERBUFFER, rboSceneResolveColor);
			glRenderbufferStorageMultisample(GL_RENDERBUFFER, 0, format, sceneResolution[0], sceneResolution[1]);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rboSceneResolveColor);
			checkGLErrors();
		}

		// Reset
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		glBindRenderbuffer(GL_RENDERBUFFER, 0);
	}

	private void destroySceneFbo() {
		sceneViewport = null;

		if (fboScene != 0)
			glDeleteFramebuffers(fboScene);
		fboScene = 0;

		if (rboSceneColor != 0)
			glDeleteRenderbuffers(rboSceneColor);
		rboSceneColor = 0;

		if (rboSceneDepth != 0)
			glDeleteRenderbuffers(rboSceneDepth);
		rboSceneDepth = 0;

		if (fboSceneResolve != 0)
			glDeleteFramebuffers(fboSceneResolve);
		fboSceneResolve = 0;

		if (rboSceneResolveColor != 0)
			glDeleteRenderbuffers(rboSceneResolveColor);
		rboSceneResolveColor = 0;
	}

	private void initializeShadowMapFbo() {
		if (!configShadowsEnabled) {
			initializeDummyShadowMap();
			return;
		}

		// Create and bind the FBO
		fboShadowMap = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboShadowMap);

		// Create texture
		texShadowMap = glGenTextures();
		BINDING_TEX_SHADOW_MAP.setActive();
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

	private void initializeDummyShadowMap() {
		// Create dummy texture
		texShadowMap = glGenTextures();
		BINDING_TEX_SHADOW_MAP.setActive();
		glBindTexture(GL_TEXTURE_2D, texShadowMap);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, 1, 1, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
	}

	private void destroyShadowMapFbo() {
		if (texShadowMap != 0)
			glDeleteTextures(texShadowMap);
		texShadowMap = 0;

		if (fboShadowMap != 0)
			glDeleteFramebuffers(fboShadowMap);
		fboShadowMap = 0;
	}

	public void initializeShaderHotswapping() {
		SHADER_PATH.watch("\\.(glsl|cl)$", path -> {
			log.info("Recompiling shaders: {}", path);
			recompilePrograms();
		});
	}

	public void prepareInterfaceTexture() {
		int[] resolution = {
			max(1, client.getCanvasWidth()),
			max(1, client.getCanvasHeight())
		};
		boolean resize = !Arrays.equals(uiResolution, resolution);
		if (resize) {
			uiResolution = resolution;

			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pboUi);
			glBufferData(GL_PIXEL_UNPACK_BUFFER, uiResolution[0] * uiResolution[1] * 4L, GL_STREAM_DRAW);
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

			BINDING_TEX_UI.setActive();
			glBindTexture(GL_TEXTURE_2D, texUi);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, uiResolution[0], uiResolution[1], 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
		}

		if (client.isStretchedEnabled()) {
			Dimension dim = client.getStretchedDimensions();
			actualUiResolution[0] = dim.width;
			actualUiResolution[1] = dim.height;
		} else {
			copyTo(actualUiResolution, uiResolution);
		}
		round(actualUiResolution, multiply(vec(actualUiResolution), getDpiScaling()));

		if (configAsyncUICopy) {
			// Start copying the UI on a different thread, to be uploaded during the next frame
			asyncUICopy.prepare(pboUi, texUi);
			// If the window was just resized, upload once synchronously so there is something to show
			if (resize)
				asyncUICopy.complete();
			return;
		}

		final BufferProvider bufferProvider = client.getBufferProvider();
		final int[] pixels = bufferProvider.getPixels();
		final int width = bufferProvider.getWidth();
		final int height = bufferProvider.getHeight();

		frameTimer.begin(Timer.MAP_UI_BUFFER);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pboUi);
		ByteBuffer mappedBuffer = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
		frameTimer.end(Timer.MAP_UI_BUFFER);
		if (mappedBuffer == null) {
			log.error("Unable to map interface PBO. Skipping UI...");
		} else if (width > uiResolution[0] || height > uiResolution[1]) {
			log.error("UI texture resolution mismatch ({}x{} > {}). Skipping UI...", width, height, uiResolution);
		} else {
			frameTimer.begin(Timer.COPY_UI);
			mappedBuffer.asIntBuffer().put(pixels, 0, width * height);
			frameTimer.end(Timer.COPY_UI);

			frameTimer.begin(Timer.UPLOAD_UI);
			glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
			BINDING_TEX_UI.setActive();
			glBindTexture(GL_TEXTURE_2D, texUi);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
			frameTimer.end(Timer.UPLOAD_UI);
		}
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
	}

	public void drawUi(int overlayColor) {
		if (uiResolution == null || developerTools.isHideUiEnabled() && hasLoggedIn)
			return;

		// Fix vanilla bug causing the overlay to remain on the login screen in areas like Fossil Island underwater
		if (client.getGameState().getState() < GameState.LOADING.getState())
			overlayColor = 0;

		frameTimer.begin(Timer.RENDER_UI);

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		// Disable alpha writes, just in case the default FBO has an alpha channel
		glColorMask(true, true, true, false);

		glViewport(0, 0, actualUiResolution[0], actualUiResolution[1]);

		tiledLightingOverlay.render();

		uiProgram.use();
		uboUI.sourceDimensions.set(uiResolution);
		uboUI.targetDimensions.set(actualUiResolution);
		uboUI.alphaOverlay.set(ColorUtils.srgba(overlayColor));
		uboUI.upload();

		if (configAsyncUICopy) {
			frameTimer.begin(Timer.UPLOAD_UI);
			asyncUICopy.complete();
			frameTimer.end(Timer.UPLOAD_UI);
		}

		// Set the sampling function used when stretching the UI.
		// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
		// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
		// GL_NEAREST makes sampling for bicubic/xBR simpler, so it should be used whenever linear/pixel isn't
		final int function = config.uiScalingMode().glSamplingFunction;
		BINDING_TEX_UI.setActive();
		glBindTexture(GL_TEXTURE_2D, texUi);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, function);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, function);

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
		if (uiResolution == null)
			return null;

		int width = actualUiResolution[0];
		int height = actualUiResolution[1];

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
		configTzhaarHD = config.hdTzHaarReskin();
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
		configUndoVanillaShading = config.shadingMode() != ShadingMode.VANILLA;
		configPreserveVanillaNormals = config.preserveVanillaNormals();
		configAsyncUICopy = config.asyncUICopy();
		configWindDisplacement = config.windDisplacement();
		configCharacterDisplacement = config.characterDisplacement();
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
				sceneManager.getLoadingLock().lock();
				sceneManager.completeAllStreaming();

				// Synchronize with scene loading
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
							case KEY_FORCE_INDIRECT_DRAW:
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
							case KEY_ASYNC_UI_COPY:
								asyncUICopy.complete();
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
							case KEY_PIXELATED_SHADOWS:
							case KEY_WINDOWS_HDR_CORRECTION:
								recompilePrograms = true;
								break;
							case KEY_ANTI_ALIASING_MODE:
							case KEY_SCENE_RESOLUTION_SCALE:
								recreateSceneFbo = true;
								break;
							case KEY_SHADOW_MODE:
							case KEY_SHADOW_TRANSPARENCY:
								recompilePrograms = true;
								// fall-through
							case KEY_SHADOW_RESOLUTION:
								recreateShadowMapFbo = true;
								break;
							case KEY_ATMOSPHERIC_LIGHTING:
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
							case KEY_HD_INFERNAL_CAPE:
								reloadTexturesAndMaterials = true;
								// fall-through
							case KEY_GROUND_BLENDING:
							case KEY_FILL_GAPS_IN_TERRAIN:
							case KEY_HD_TZHAAR_RESKIN:
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
						materialManager.reload(false);
						modelOverrideManager.reload();
						recompilePrograms = true;
					} else if (reloadModelOverrides) {
						modelOverrideManager.reload();
					}

					if (reloadTileOverrides) {
						tileOverrideManager.reload(false);
						reloadScene = true;
					}

					if (recompilePrograms)
						recompilePrograms();

					if (recreateSceneFbo) {
						destroySceneFbo();
						updateSceneFbo();
					}

					if (reloadScene) {
						renderer.clearCaches();
						renderer.reloadScene();
					}

					if (recreateShadowMapFbo) {
						destroyShadowMapFbo();
						initializeShadowMapFbo();
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

		// Upload the UI which we began copying during the previous frame
		if (configAsyncUICopy)
			asyncUICopy.complete();

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

		fishingSpotReplacer.update();
	}

	@SuppressWarnings("StatementWithEmptyBody")
	public static void clearGLErrors() {
		// @formatter:off
		while (glGetError() != GL_NO_ERROR);
		// @formatter:on
	}

	public interface IGetContextFunction { String get(); }

	public static void checkGLErrors() {
		checkGLErrors(null);
	}

	public static void checkGLErrors(IGetContextFunction ctxDelegate) {
		if (SKIP_GL_ERROR_CHECKS)
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

			if (ctxDelegate != null) {
				log.debug("{} - glGetError:", ctxDelegate.get(), new Exception(errStr));
			} else {
				log.debug("glGetError:", new Exception(errStr));
			}
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

	private void displayUnsupportedGpuMessage(boolean isFallbackGpu, String glRenderer) {
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
				+ "Try " + (useLowMemoryMode ? "" : "reducing your model cache size from " + config.modelCacheSizeMiB() + " or ") + "closing other programs.<br>"
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
