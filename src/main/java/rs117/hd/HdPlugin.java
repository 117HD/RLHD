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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import net.runelite.client.ui.DrawManager;
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
import rs117.hd.config.UIScalingMode;
import rs117.hd.config.VanillaShadowMode;
import rs117.hd.model.ModelHasher;
import rs117.hd.model.ModelOffsets;
import rs117.hd.model.ModelPusher;
import rs117.hd.opengl.AsyncUICopy;
import rs117.hd.opengl.compute.ComputeMode;
import rs117.hd.opengl.compute.OpenCLManager;
import rs117.hd.opengl.shader.ModelPassthroughComputeProgram;
import rs117.hd.opengl.shader.ModelSortingComputeProgram;
import rs117.hd.opengl.shader.SceneShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.shader.ShadowShaderProgram;
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
import rs117.hd.scene.SceneUploader;
import rs117.hd.scene.TextureManager;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.WaterTypeManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.DeveloperTools;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.HDVariables;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.NpcDisplacementCache;
import rs117.hd.utils.PopupUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.ShaderRecompile;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.buffer.SharedGLBuffer;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Constants.SCENE_SIZE;
import static net.runelite.api.Perspective.*;
import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;
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
public class HdPlugin extends Plugin implements DrawCallbacks {
	public static final ResourcePath PLUGIN_DIR = Props
		.getFolder("rlhd.plugin-dir", () -> path(RuneLite.RUNELITE_DIR, "117hd"));

	public static final String DISCORD_URL = "https://discord.gg/U4p6ChjgSE";
	public static final String RUNELITE_URL = "https://runelite.net";
	public static final String AMD_DRIVER_URL = "https://www.amd.com/en/support";
	public static final String INTEL_DRIVER_URL = "https://www.intel.com/content/www/us/en/support/detect.html";
	public static final String NVIDIA_DRIVER_URL = "https://www.nvidia.com/en-us/geforce/drivers/";

	public static int MAX_TEXTURE_UNITS;
	public static int TEXTURE_UNIT_COUNT = 0;
	public static final int TEXTURE_UNIT_UI = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_GAME = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_SHADOW_MAP = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_TILE_HEIGHT_MAP = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;
	public static final int TEXTURE_UNIT_TILED_LIGHTING_MAP = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;

	public static int MAX_IMAGE_UNITS;
	public static int IMAGE_UNIT_COUNT = 0;
	public static final int IMAGE_UNIT_TILED_LIGHTING = IMAGE_UNIT_COUNT++;

	public static final int UNIFORM_BLOCK_GLOBAL = 0;
	public static final int UNIFORM_BLOCK_MATERIALS = 1;
	public static final int UNIFORM_BLOCK_WATER_TYPES = 2;
	public static final int UNIFORM_BLOCK_LIGHTS = 3;
	public static final int UNIFORM_BLOCK_LIGHTS_CULLING = 4;
	public static final int UNIFORM_BLOCK_COMPUTE = 5;
	public static final int UNIFORM_BLOCK_UI = 6;

	public static final float NEAR_PLANE = 50;
	public static final int MAX_FACE_COUNT = 6144;
	public static final int MAX_DISTANCE = EXTENDED_SCENE_SIZE;
	public static final int GROUND_MIN_Y = 350; // how far below the ground models extend
	public static final int MAX_FOG_DEPTH = 100;
	public static final int VERTEX_SIZE = 4; // 4 ints per vertex
	public static final int UV_SIZE = 4; // 4 floats per vertex
	public static final int NORMAL_SIZE = 4; // 4 floats per vertex
	public static final int TILED_LIGHTING_TILE_SIZE = 16;

	public static final float ORTHOGRAPHIC_ZOOM = .0002f;
	public static final float WIND_DISPLACEMENT_NOISE_RESOLUTION = 0.04f;

	public static float BUFFER_GROWTH_MULTIPLIER = 2; // can be less than 2 if trying to conserve memory

	private static final float COLOR_FILTER_FADE_DURATION = 500;

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
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private HdPluginConfig config;

	@Inject
	private OpenCLManager clManager;

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
	private ProceduralGenerator proceduralGenerator;

	@Inject
	private SceneUploader sceneUploader;

	@Inject
	private AsyncUICopy asyncUICopy;

	@Inject
	private ModelPusher modelPusher;

	@Inject
	private ModelHasher modelHasher;

	@Inject
	private FishingSpotReplacer fishingSpotReplacer;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	@Inject
	private DeveloperTools developerTools;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private SceneShaderProgram sceneProgram;

	@Inject
	private ShadowShaderProgram shadowProgram;

	@Inject
	private UIShaderProgram uiProgram;

	@Inject
	private ModelPassthroughComputeProgram modelPassthroughComputeProgram;

	@Getter
	@Inject
	private TiledLightingShaderProgram tiledLightingImageStoreProgram;

	private final List<ModelSortingComputeProgram> modelSortingComputePrograms = new ArrayList<>();

	private final List<TiledLightingShaderProgram> tiledLightingShaderPrograms = new ArrayList<>();

	@Inject
	private GammaCalibrationOverlay gammaCalibrationOverlay;

	@Inject
	private ShadowMapOverlay shadowMapOverlay;

	@Inject
	private TiledLightingOverlay tiledLightingOverlay;

	@Inject
	public HDVariables vars;

	public static boolean SKIP_GL_ERROR_CHECKS;
	public static GLCapabilities GL_CAPS;

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

	private static final ResourcePath SHADER_PATH = Props
		.getFolder("rlhd.shader-path", () -> path(HdPlugin.class));

	public int vaoQuad;
	private int vboQuad;

	public int vaoTri;
	private int vboTri;

	private int vaoScene;

	@Getter
	@Nullable
	private int[] uiResolution;
	private final int[] actualUiResolution = { 0, 0 }; // Includes stretched mode and DPI scaling
	private int texUi;
	private int pboUi;

	@Nullable
	private int[] sceneViewport;
	private final float[] sceneViewportScale = { 1, 1 };
	private int msaaSamples;

	private int[] sceneResolution;
	private int fboScene;
	private int rboSceneColor;
	private int rboSceneDepth;
	private int fboSceneResolve;
	private int rboSceneResolveColor;

	private int shadowMapResolution;
	private int fboShadowMap;
	private int texShadowMap;

	private int[] tiledLightingResolution;
	private int fboTiledLighting;
	private int texTiledLighting;

	private int texTileHeightMap;

	private final SharedGLBuffer hStagingBufferVertices = new SharedGLBuffer(
		"Staging Vertices", GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW, CL_MEM_READ_ONLY);
	private final SharedGLBuffer hStagingBufferUvs = new SharedGLBuffer(
		"Staging UVs", GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW, CL_MEM_READ_ONLY);
	private final SharedGLBuffer hStagingBufferNormals = new SharedGLBuffer(
		"Staging Normals", GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW, CL_MEM_READ_ONLY);
	private final SharedGLBuffer hRenderBufferVertices = new SharedGLBuffer(
		"Render Vertices", GL_ARRAY_BUFFER, GL_STREAM_COPY, CL_MEM_WRITE_ONLY);
	private final SharedGLBuffer hRenderBufferUvs = new SharedGLBuffer(
		"Render UVs", GL_ARRAY_BUFFER, GL_STREAM_COPY, CL_MEM_WRITE_ONLY);
	private final SharedGLBuffer hRenderBufferNormals = new SharedGLBuffer(
		"Render Normals", GL_ARRAY_BUFFER, GL_STREAM_COPY, CL_MEM_WRITE_ONLY);

	private int numPassthroughModels;
	private GpuIntBuffer modelPassthroughBuffer;
	private final SharedGLBuffer hModelPassthroughBuffer = new SharedGLBuffer(
		"Model Passthrough", GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);

	// ordered by face count from small to large
	public int numSortingBins;
	public int maxComputeThreadCount;
	public int[] modelSortingBinFaceCounts; // facesPerThread * threadCount
	public int[] modelSortingBinThreadCounts;
	private int[] numModelsToSort;
	private GpuIntBuffer[] modelSortingBuffers;
	private SharedGLBuffer[] hModelSortingBuffers;

	private final UBOGlobal uboGlobal = new UBOGlobal();
	private final UBOLights uboLights = new UBOLights(false);
	private final UBOLights uboLightsCulling = new UBOLights(true);
	private final UBOCompute uboCompute = new UBOCompute();
	private final UBOUI uboUI = new UBOUI();

	@Getter
	@Nullable
	private SceneContext sceneContext;
	private SceneContext nextSceneContext;

	private int dynamicOffsetVertices;
	private int dynamicOffsetUvs;
	private int renderBufferOffset;

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
	public boolean configAsyncUICopy;
	public boolean configWindDisplacement;
	public boolean configCharacterDisplacement;
	public boolean configTiledLighting;
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
	private boolean hasLoggedIn;
	private boolean redrawPreviousFrame;
	private boolean isInChambersOfXeric;
	private boolean isInHouse;
	private boolean justChangedArea;
	private Scene skipScene;

	private final ConcurrentHashMap.KeySetView<String, ?> pendingConfigChanges = ConcurrentHashMap.newKeySet();
	private final Map<Long, ModelOffsets> frameModelInfoMap = new HashMap<>();

	// Camera position and orientation may be reused from the old scene while hopping, prior to drawScene being called
	public float[] viewMatrix;
	public final float[] cameraPosition = new float[3];
	public final float[] cameraOrientation = new float[2];
	public final int[] cameraFocalPoint = new int[2];
	private final int[] cameraShift = new int[2];
	private int visibilityCheckZoom;
	private boolean tileVisibilityCached;
	private final boolean[][][] tileIsVisible = new boolean[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE];

	@Getter
	private int drawnTileCount;
	@Getter
	private int drawnStaticRenderableCount;
	@Getter
	private int drawnDynamicRenderableCount;

	public double elapsedTime;
	public double elapsedClientTime;
	public float deltaTime;
	public float deltaClientTime;
	private long lastFrameTimeMillis;
	private double lastFrameClientTime;
	private float windOffset;
	private int gameTicksUntilSceneReload = 0;
	private long colorFilterChangedAt;

	@Provides
	HdPluginConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(HdPluginConfig.class);
	}

	@Override
	protected void startUp() {
		gson = GsonUtils.wrap(injector.getInstance(Gson.class));

		clientThread.invoke(() -> {
			try {
				if (!textureManager.vanillaTexturesAvailable())
					return false;

				renderBufferOffset = 0;
				fboScene = 0;
				rboSceneColor = 0;
				rboSceneDepth = 0;
				fboSceneResolve = 0;
				rboSceneResolveColor = 0;
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

				SKIP_GL_ERROR_CHECKS = false;
				GL_CAPS = GL.createCapabilities();
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
				boolean isUnsupportedGpu = isFallbackGpu || (computeMode == ComputeMode.OPENGL ? !GL_CAPS.OpenGL43 : !GL_CAPS.OpenGL31);
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

				modelPassthroughBuffer = new GpuIntBuffer();

				int maxComputeThreadCount;
				if (computeMode == ComputeMode.OPENCL) {
					clManager.startUp(awtContext);
					maxComputeThreadCount = clManager.getMaxWorkGroupSize();
				} else {
					maxComputeThreadCount = glGetInteger(GL43C.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS);
				}
				initModelSortingBins(maxComputeThreadCount);

				setupSyncMode();
				initVaos();
				initBuffers();

				// Materials need to be initialized before compiling shader programs
				textureManager.startUp();
				materialManager.startUp();
				waterTypeManager.startUp();

				initPrograms();
				initShaderHotswapping();
				initUiTexture();
				initShadowMapFbo();

				checkGLErrors();

				client.setDrawCallbacks(this);
				client.setGpuFlags(
					DrawCallbacks.GPU |
					DrawCallbacks.HILLSKEW |
					DrawCallbacks.NORMALS |
					(config.removeVertexSnapping() ? DrawCallbacks.NO_VERTEX_SNAPPING : 0)
				);
				client.setExpandedMapLoading(getExpandedMapLoadingChunks());
				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				gamevalManager.startUp();
				areaManager.startUp();
				groundMaterialManager.startUp();
				tileOverrideManager.startUp();
				modelOverrideManager.startUp();
				modelPusher.startUp();
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

				// We need to force the client to reload the scene since we're changing GPU flags
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
			developerTools.deactivate();
			modelPusher.shutDown();
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

			if (lwjglInitialized) {
				lwjglInitialized = false;
				waitUntilIdle();

				waterTypeManager.shutDown();
				materialManager.shutDown();
				textureManager.shutDown();

				destroyBuffers();
				destroyUiTexture();
				destroyPrograms();
				destroyVaos();
				destroySceneFbo();
				destroyShadowMapFbo();
				destroyTiledLightingFbo();
				destroyTileHeightMap();
				destroyModelSortingBins();

				clManager.shutDown();
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

	private String generateGetter(String type, int arrayLength) {
		StringBuilder include = new StringBuilder();

		boolean isAppleM1 = OSType.getOSType() == OSType.MacOS && System.getProperty("os.arch").equals("aarch64");
		if (config.macosIntelWorkaround() && !isAppleM1) {
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
		String versionHeader = OSType.getOSType() == OSType.Linux ? LINUX_VERSION_HEADER : WINDOWS_VERSION_HEADER;
		return new ShaderIncludes()
			.addIncludePath(SHADER_PATH)
			.addInclude("VERSION_HEADER", versionHeader)
			.define("UI_SCALING_MODE", config.uiScalingMode().getMode())
			.define("COLOR_BLINDNESS", config.colorBlindness())
			.define("APPLY_COLOR_FILTER", configColorFilter != ColorFilter.NONE)
			.define("MATERIAL_COUNT", MaterialManager.MATERIALS.length)
			.define("WATER_TYPE_COUNT", waterTypeManager.uboWaterTypes.getCount())
			.define("DYNAMIC_LIGHTS", configDynamicLights != DynamicLights.NONE)
			.define("TILED_LIGHTING", configTiledLighting)
			.define("TILED_LIGHTING_LAYER_COUNT", configDynamicLights.getLightsPerTile() / 4)
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
			.define("MAX_CHARACTER_POSITION_COUNT", max(1, UBOCompute.MAX_CHARACTER_POSITION_COUNT))
			.define("WIREFRAME", config.wireframe())
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
			.addUniformBuffer(uboCompute)
			.addUniformBuffer(uboUI)
			.addUniformBuffer(materialManager.uboMaterials)
			.addUniformBuffer(waterTypeManager.uboWaterTypes);
	}

	private void initPrograms() throws ShaderException, IOException {
		var includes = getShaderIncludes();

		// Bind a valid VAO, otherwise validation may fail on older Intel-based Macs
		glBindVertexArray(vaoTri);

		sceneProgram.compile(includes);
		shadowProgram.setMode(configShadowMode);
		shadowProgram.compile(includes);
		uiProgram.compile(includes);

		if (configDynamicLights != DynamicLights.NONE && configTiledLighting) {
			if (GL_CAPS.GL_ARB_shader_image_load_store && tiledLightingImageStoreProgram.isViable()) {
				try {
					tiledLightingImageStoreProgram.compile(includes
						.define("TILED_IMAGE_STORE", true)
						.define("TILED_LIGHTING_LAYER", false));
				} catch (ShaderException ex) {
					log.warn("Disabling TILED_IMAGE_STORE due to:", ex);
				}
			}

			int tiledLayerCount = DynamicLights.MAX_LIGHTS_PER_TILE / 4;
			for (int layer = 0; layer < tiledLayerCount; layer++) {
				var shader = new TiledLightingShaderProgram();
				shader.compile(includes
					.define("TILED_IMAGE_STORE", false)
					.define("TILED_LIGHTING_LAYER", layer));
				tiledLightingShaderPrograms.add(shader);
			}
		}

		if (computeMode == ComputeMode.OPENCL) {
			clManager.initPrograms();
		} else {
			modelPassthroughComputeProgram.compile(includes);

			for (int i = 0; i < numSortingBins; i++) {
				int faceCount = modelSortingBinFaceCounts[i];
				int threadCount = modelSortingBinThreadCounts[i];
				int facesPerThread = ceil((float) faceCount / threadCount);
				var program = new ModelSortingComputeProgram(threadCount, facesPerThread);
				modelSortingComputePrograms.add(program);
				program.compile(includes);
			}
		}

		checkGLErrors();

		eventBus.post(new ShaderRecompile(includes));
	}

	private void destroyPrograms() {
		sceneProgram.destroy();
		shadowProgram.destroy();
		uiProgram.destroy();
		tiledLightingImageStoreProgram.destroy();

		for (var program : tiledLightingShaderPrograms)
			program.destroy();
		tiledLightingShaderPrograms.clear();

		if (computeMode == ComputeMode.OPENGL) {
			modelPassthroughComputeProgram.destroy();
			for (var program : modelSortingComputePrograms)
				program.destroy();
			modelSortingComputePrograms.clear();
		} else {
			clManager.destroyPrograms();
		}
	}

	public void recompilePrograms() {
		// Only recompile if the programs have been compiled successfully before
		if (!sceneProgram.isValid())
			return;

		clientThread.invoke(() -> {
			try {
				waitUntilIdle();
				destroyPrograms();
				initPrograms();
			} catch (ShaderException | IOException ex) {
				// TODO: If each shader compilation leaves the previous working shader intact, we wouldn't need to shut down on failure
				log.error("Error while recompiling shaders:", ex);
				stopPlugin();
			}
		});
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
				threadCount = ceil((float) targetFaceCount / facesPerThread);
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

		hModelSortingBuffers = new SharedGLBuffer[numSortingBins];
		for (int i = 0; i < numSortingBins; i++) {
			hModelSortingBuffers[i] = new SharedGLBuffer(
				"Model Sorting " + modelSortingBinFaceCounts[i], GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);
			// Initialize each model sorting buffer with capacity for 64 models
			hModelSortingBuffers[i].initialize();
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
				buffer.destroy();
		hModelSortingBuffers = null;
	}

	private void initVaos() {
		// Create scene VAO
		vaoScene = glGenVertexArrays();

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

	private void updateSceneVao(GLBuffer vertexBuffer, GLBuffer uvBuffer, GLBuffer normalBuffer) {
		glBindVertexArray(vaoScene);

		glEnableVertexAttribArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer.id);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0);

		glEnableVertexAttribArray(1);
		glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer.id);
		glVertexAttribIPointer(1, 1, GL_INT, 16, 12);

		glEnableVertexAttribArray(2);
		glBindBuffer(GL_ARRAY_BUFFER, uvBuffer.id);
		glVertexAttribPointer(2, 3, GL_FLOAT, false, 16, 0);

		glEnableVertexAttribArray(3);
		glBindBuffer(GL_ARRAY_BUFFER, uvBuffer.id);
		glVertexAttribIPointer(3, 1, GL_INT, 16, 12);

		glEnableVertexAttribArray(4);
		glBindBuffer(GL_ARRAY_BUFFER, normalBuffer.id);
		glVertexAttribPointer(4, 4, GL_FLOAT, false, 0, 0);
	}

	private void destroyVaos() {
		if (vaoScene != 0)
			glDeleteVertexArrays(vaoScene);
		vaoScene = 0;

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

	private void initBuffers() {
		hStagingBufferVertices.initialize();
		hStagingBufferUvs.initialize();
		hStagingBufferNormals.initialize();

		hRenderBufferVertices.initialize();
		hRenderBufferUvs.initialize();
		hRenderBufferNormals.initialize();

		hModelPassthroughBuffer.initialize();

		uboGlobal.initialize(UNIFORM_BLOCK_GLOBAL);
		uboLights.initialize(UNIFORM_BLOCK_LIGHTS);
		uboLightsCulling.initialize(UNIFORM_BLOCK_LIGHTS_CULLING);
		uboCompute.initialize(UNIFORM_BLOCK_COMPUTE);
		uboUI.initialize(UNIFORM_BLOCK_UI);
	}

	private void destroyBuffers() {
		hStagingBufferVertices.destroy();
		hStagingBufferUvs.destroy();
		hStagingBufferNormals.destroy();

		hRenderBufferVertices.destroy();
		hRenderBufferUvs.destroy();
		hRenderBufferNormals.destroy();

		hModelPassthroughBuffer.destroy();

		uboGlobal.destroy();
		uboLights.destroy();
		uboLightsCulling.destroy();
		uboCompute.destroy();
		uboUI.destroy();
	}

	private void initUiTexture() {
		pboUi = glGenBuffers();

		texUi = glGenTextures();
		glActiveTexture(TEXTURE_UNIT_UI);
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

	private void updateTiledLightingFbo() {
		assert configTiledLighting;

		int[] resolution = max(ivec(1), round(divide(vec(sceneResolution), TILED_LIGHTING_TILE_SIZE)));
		if (Arrays.equals(resolution, tiledLightingResolution))
			return;

		destroyTiledLightingFbo();

		tiledLightingResolution = resolution;
		fboTiledLighting = glGenFramebuffers();
		texTiledLighting = glGenTextures();
		glActiveTexture(TEXTURE_UNIT_TILED_LIGHTING_MAP);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texTiledLighting);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexImage3D(
			GL_TEXTURE_2D_ARRAY,
			0,
			GL_RGBA16I,
			tiledLightingResolution[0],
			tiledLightingResolution[1],
			DynamicLights.MAX_LIGHTS_PER_TILE / 4,
			0,
			GL_RGBA_INTEGER,
			GL_SHORT,
			0
		);
		checkGLErrors();

		if (tiledLightingImageStoreProgram.isValid())
			ARBShaderImageLoadStore.glBindImageTexture(
				IMAGE_UNIT_TILED_LIGHTING, texTiledLighting, 0, false, 0, GL_WRITE_ONLY, GL_RGBA16I);

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

	private void updateSceneFbo() {
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
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, msaaSamples, GL_DEPTH24_STENCIL8, sceneResolution[0], sceneResolution[1]);
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

	private void initShadowMapFbo() {
		if (!configShadowsEnabled) {
			initDummyShadowMap();
			return;
		}

		// Create and bind the FBO
		fboShadowMap = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboShadowMap);

		// Create texture
		texShadowMap = glGenTextures();
		glActiveTexture(TEXTURE_UNIT_SHADOW_MAP);
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

	private void initDummyShadowMap() {
		// Create dummy texture
		texShadowMap = glGenTextures();
		glActiveTexture(TEXTURE_UNIT_SHADOW_MAP);
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

		texTileHeightMap = glGenTextures();
		glActiveTexture(TEXTURE_UNIT_TILE_HEIGHT_MAP);
		glBindTexture(GL_TEXTURE_3D, texTileHeightMap);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexImage3D(GL_TEXTURE_3D, 0, GL_R16I,
			EXTENDED_SCENE_SIZE, EXTENDED_SCENE_SIZE, Constants.MAX_Z,
			0, GL_RED_INTEGER, GL_SHORT, tileBuffer
		);
	}

	private void destroyTileHeightMap() {
		if (texTileHeightMap != 0)
			glDeleteTextures(texTileHeightMap);
		texTileHeightMap = 0;
	}

	@Override
	public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane) {
		updateSceneFbo();

		if (sceneContext == null || sceneViewport == null)
			return;

		frameTimer.begin(Timer.DRAW_FRAME);
		frameTimer.begin(Timer.DRAW_SCENE);

		final Scene scene = client.getScene();
		int drawDistance = getDrawDistance();
		boolean drawDistanceChanged = false;
		if (scene.getDrawDistance() != drawDistance) {
			scene.setDrawDistance(drawDistance);
			drawDistanceChanged = true;
		}

		boolean updateUniforms = true;

		Player localPlayer = client.getLocalPlayer();
		var lp = localPlayer.getLocalLocation();
		if (sceneContext.enableAreaHiding) {
			assert sceneContext.sceneBase != null;
			int[] worldPos = {
				sceneContext.sceneBase[0] + lp.getSceneX(),
				sceneContext.sceneBase[1] + lp.getSceneY(),
				sceneContext.sceneBase[2] + client.getPlane()
			};

			// We need to check all areas contained in the scene in the order they appear in the list,
			// in order to ensure lower floors can take precedence over higher floors which include tiny
			// portions of the floor beneath around stairs and ladders
			Area newArea = null;
			for (var area : sceneContext.possibleAreas) {
				if (area.containsPoint(false, worldPos)) {
					newArea = area;
					break;
				}
			}

			// Force a scene reload if the player is no longer in the same area
			if (newArea != sceneContext.currentArea) {
				if (justChangedArea) {
					// Prevent getting stuck in a scene reloading loop if this breaks for any reason
					sceneContext.forceDisableAreaHiding = true;
					log.error("Force disabling area hiding after moving from {} to {} at {}", sceneContext.currentArea, newArea, worldPos);
				} else {
					justChangedArea = true;
				}
				// Reload the scene to reapply area hiding
				client.setGameState(GameState.LOADING);
				updateUniforms = false;
				redrawPreviousFrame = true;
			} else {
				justChangedArea = false;
			}
		} else {
			justChangedArea = false;
		}

		if (!enableFreezeFrame) {
			if (!redrawPreviousFrame) {
				// Only reset the target buffer offset right before drawing the scene. That way if there are frames
				// after this that don't involve a scene draw, like during LOADING/HOPPING/CONNECTION_LOST, we can
				// still redraw the previous frame's scene to emulate the client behavior of not painting over the
				// viewport buffer.
				renderBufferOffset = sceneContext.staticVertexCount;

				drawnTileCount = 0;
				drawnStaticRenderableCount = 0;
				drawnDynamicRenderableCount = 0;

				// TODO: this could be done only once during scene swap, but is a bit of a pain to do
				// Push unordered models that should always be drawn at the start of each frame.
				// Used to fix issues like the right-click menu causing underwater tiles to disappear.
				var staticUnordered = sceneContext.staticUnorderedModelBuffer.getBuffer();
				modelPassthroughBuffer
					.ensureCapacity(staticUnordered.limit())
					.put(staticUnordered);
				staticUnordered.rewind();
				numPassthroughModels += staticUnordered.limit() / 8;
			}

			if (updateUniforms) {
				float[] newCameraPosition = { (float) cameraX, (float) cameraY, (float) cameraZ };
				float[] newCameraOrientation = { (float) cameraYaw, (float) cameraPitch };
				int newZoom = configShadowsEnabled && configExpandShadowDraw ? client.get3dZoom() / 2 : client.get3dZoom();
				if (!Arrays.equals(cameraPosition, newCameraPosition) ||
					!Arrays.equals(cameraOrientation, newCameraOrientation) ||
					visibilityCheckZoom != newZoom ||
					drawDistanceChanged
				) {
					copyTo(cameraPosition, newCameraPosition);
					copyTo(cameraOrientation, newCameraOrientation);
					visibilityCheckZoom = newZoom;
					tileVisibilityCached = false;
				}

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

				uboCompute.yaw.set(cameraOrientation[0]);
				uboCompute.pitch.set(cameraOrientation[1]);
				uboCompute.centerX.set(client.getCenterX());
				uboCompute.centerY.set(client.getCenterY());
				uboCompute.zoom.set(client.getScale());
				uboCompute.cameraX.set(cameraPosition[0]);
				uboCompute.cameraY.set(cameraPosition[1]);
				uboCompute.cameraZ.set(cameraPosition[2]);

				uboCompute.windDirectionX.set(cos(environmentManager.currentWindAngle));
				uboCompute.windDirectionZ.set(sin(environmentManager.currentWindAngle));
				uboCompute.windStrength.set(environmentManager.currentWindStrength);
				uboCompute.windCeiling.set(environmentManager.currentWindCeiling);
				uboCompute.windOffset.set(windOffset);

				if (configCharacterDisplacement) {
					// The local player needs to be added first for distance culling
					Model playerModel = localPlayer.getModel();
					if (playerModel != null)
						uboCompute.addCharacterPosition(lp.getX(), lp.getY(), (int) (LOCAL_TILE_SIZE * 1.33f));
				}

				// Calculate the viewport dimensions before scaling in order to include the extra padding
				int viewportWidth = (int) (sceneViewport[2] / sceneViewportScale[0]);
				int viewportHeight = (int) (sceneViewport[3] / sceneViewportScale[1]);

				// Calculate projection matrix
				float[] projectionMatrix = Mat4.scale(client.getScale(), client.getScale(), 1);
				if (orthographicProjection) {
					Mat4.mul(projectionMatrix, Mat4.scale(ORTHOGRAPHIC_ZOOM, ORTHOGRAPHIC_ZOOM, -1));
					Mat4.mul(projectionMatrix, Mat4.orthographic(viewportWidth, viewportHeight, 40000));
				} else {
					Mat4.mul(projectionMatrix, Mat4.perspective(viewportWidth, viewportHeight, NEAR_PLANE));
				}

				// Calculate view matrix
				viewMatrix = Mat4.rotateX(cameraOrientation[1]);
				Mat4.mul(viewMatrix, Mat4.rotateY(cameraOrientation[0]));
				Mat4.mul(viewMatrix, Mat4.translate(-cameraPosition[0], -cameraPosition[1], -cameraPosition[2]));

				// Calculate view proj & inv matrix
				float[] viewProj = Mat4.identity();
				Mat4.mul(viewProj, projectionMatrix);
				Mat4.mul(viewProj, viewMatrix);
				float[] invProjectionMatrix = Mat4.inverse(viewProj);

				uboGlobal.cameraPos.set(cameraPosition);
				uboGlobal.viewMatrix.set(viewMatrix);
				uboGlobal.projectionMatrix.set(viewProj);
				uboGlobal.invProjectionMatrix.set(invProjectionMatrix);
				uboGlobal.upload();
			}
		}

		if (configDynamicLights != DynamicLights.NONE && sceneContext.scene == scene && updateUniforms) {
			// Update lights UBO
			assert sceneContext.numVisibleLights <= UBOLights.MAX_LIGHTS;

			final float[] lightPosition = new float[4];
			final float[] lightColor = new float[4];
			for (int i = 0; i < sceneContext.numVisibleLights; i++) {
				final Light light = sceneContext.lights.get(i);
				final float lightRadiusSq = light.radius * light.radius;
				lightPosition[0] = light.pos[0] + cameraShift[0];
				lightPosition[1] = light.pos[1];
				lightPosition[2] = light.pos[2] + cameraShift[1];
				lightPosition[3] = lightRadiusSq;

				lightColor[0] = light.color[0] * light.strength;
				lightColor[1] = light.color[1] * light.strength;
				lightColor[2] = light.color[2] * light.strength;
				lightColor[3] = 0.0f;

				uboLights.setLight(i, lightPosition, lightColor);

				// Pre-calculate the ViewSpace Position of the light, to save having to do the multiplication in the culling shader
				lightPosition[3] = 1.0f;
				Mat4.mulVec(lightPosition, viewMatrix, lightPosition);
				lightPosition[3] = lightRadiusSq; // Restore LightRadiusSq

				uboLightsCulling.setLight(i, lightPosition, lightColor);
			}

			uboLights.upload();
			uboLightsCulling.upload();

			// Perform tiled lighting culling before the compute memory barrier, so it's performed asynchronously
			if (configTiledLighting) {
				updateTiledLightingFbo();
				assert fboTiledLighting != 0;

				frameTimer.begin(Timer.DRAW_TILED_LIGHTING);
				frameTimer.begin(Timer.RENDER_TILED_LIGHTING);

				glViewport(0, 0, tiledLightingResolution[0], tiledLightingResolution[1]);
				glBindFramebuffer(GL_FRAMEBUFFER, fboTiledLighting);

				glFramebufferTexture(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texTiledLighting, 0);

				glClearColor(0, 0, 0, 0);
				glClear(GL_COLOR_BUFFER_BIT);

				glBindVertexArray(vaoTri);
				glDisable(GL_BLEND);

				if (tiledLightingImageStoreProgram.isValid()) {
					tiledLightingImageStoreProgram.use();
					glDrawArrays(GL_TRIANGLES, 0, 3);
				} else {
					int layerCount = configDynamicLights.getLightsPerTile() / 4;
					for (int layer = 0; layer < layerCount; layer++) {
						tiledLightingShaderPrograms.get(layer).use();
						glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texTiledLighting, 0, layer);
						glDrawArrays(GL_TRIANGLES, 0, 3);
					}
				}

				frameTimer.end(Timer.RENDER_TILED_LIGHTING);
				frameTimer.end(Timer.DRAW_TILED_LIGHTING);
			}
		}
	}

	@Override
	public void postDrawScene() {
		if (sceneContext == null)
			return;

		tileVisibilityCached = true;

		frameTimer.end(Timer.DRAW_SCENE);
		frameTimer.begin(Timer.RENDER_FRAME);
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
			hStagingBufferVertices.upload(sceneContext.stagingBufferVertices, dynamicOffsetVertices * 4L * VERTEX_SIZE);
			hStagingBufferUvs.upload(sceneContext.stagingBufferUvs, dynamicOffsetUvs * 4L * UV_SIZE);
			hStagingBufferNormals.upload(sceneContext.stagingBufferNormals, dynamicOffsetVertices * 4L * NORMAL_SIZE);
			sceneContext.stagingBufferVertices.clear();
			sceneContext.stagingBufferUvs.clear();
			sceneContext.stagingBufferNormals.clear();

			// Model buffers
			modelPassthroughBuffer.flip();
			hModelPassthroughBuffer.upload(modelPassthroughBuffer);
			modelPassthroughBuffer.clear();

			for (int i = 0; i < modelSortingBuffers.length; i++) {
				var buffer = modelSortingBuffers[i];
				buffer.flip();
				hModelSortingBuffers[i].upload(buffer);
				buffer.clear();
			}

			// Output buffers
			// each vertex is an ivec4, which is 16 bytes
			hRenderBufferVertices.ensureCapacity(renderBufferOffset * 16L);
			// each vertex is an ivec4, which is 16 bytes
			hRenderBufferUvs.ensureCapacity(renderBufferOffset * 16L);
			// each vertex is an ivec4, which is 16 bytes
			hRenderBufferNormals.ensureCapacity(renderBufferOffset * 16L);
			updateSceneVao(hRenderBufferVertices, hRenderBufferUvs, hRenderBufferNormals);
		}

		frameTimer.end(Timer.UPLOAD_GEOMETRY);
		frameTimer.begin(Timer.COMPUTE);

		uboCompute.upload();

		if (computeMode == ComputeMode.OPENCL) {
			// The docs for clEnqueueAcquireGLObjects say all pending GL operations must be completed before calling
			// clEnqueueAcquireGLObjects, and recommends calling glFinish() as the only portable way to do that.
			// However, no issues have been observed from not calling it, and so will leave disabled for now.
			// glFinish();

			clManager.compute(
				uboCompute.glBuffer,
				numPassthroughModels, numModelsToSort,
				hModelPassthroughBuffer, hModelSortingBuffers,
				hStagingBufferVertices, hStagingBufferUvs, hStagingBufferNormals,
				hRenderBufferVertices, hRenderBufferUvs, hRenderBufferNormals
			);
		} else {
			// Compute is split into a passthrough shader for unsorted models,
			// and multiple sizes of sorting shaders to better utilize the GPU

			// Bind shared buffers
			glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, hStagingBufferVertices.id);
			glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, hStagingBufferUvs.id);
			glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, hStagingBufferNormals.id);
			glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, hRenderBufferVertices.id);
			glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, hRenderBufferUvs.id);
			glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, hRenderBufferNormals.id);

			// unordered
			modelPassthroughComputeProgram.use();
			glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, hModelPassthroughBuffer.id);
			GL43C.glDispatchCompute(numPassthroughModels, 1, 1);

			for (int i = 0; i < numModelsToSort.length; i++) {
				if (numModelsToSort[i] == 0)
					continue;

				modelSortingComputePrograms.get(i).use();
				glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, hModelSortingBuffers[i].id);
				GL43C.glDispatchCompute(numModelsToSort[i], 1, 1);
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
		drawnTileCount++;
	}

	public void initShaderHotswapping() {
		SHADER_PATH.watch("\\.(glsl|cl)$", path -> {
			log.info("Recompiling shaders: {}", path);
			recompilePrograms();
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
			drawnTileCount++;
		}

		++numPassthroughModels;

		buffer.put(model.getBufferOffset());
		buffer.put(model.getUvBufferOffset());
		buffer.put(bufferLength / 3);
		buffer.put(renderBufferOffset);
		buffer.put(0);
		buffer.put(localX).put(localY).put(localZ);

		renderBufferOffset += bufferLength;
		drawnTileCount++;
	}

	private void prepareInterfaceTexture() {
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

			glActiveTexture(TEXTURE_UNIT_UI);
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
			glActiveTexture(TEXTURE_UNIT_UI);
			glBindTexture(GL_TEXTURE_2D, texUi);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
			frameTimer.end(Timer.UPLOAD_UI);
		}
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
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
			if (abs(deltaTime) > 10)
				deltaTime = 1 / 60.f;
			elapsedTime += deltaTime;
			windOffset += deltaTime * environmentManager.currentWindSpeed;

			// The client delta doesn't need clamping
			deltaClientTime = (float) (elapsedClientTime - lastFrameClientTime);
		}
		lastFrameTimeMillis = System.currentTimeMillis();
		lastFrameClientTime = elapsedClientTime;

		try {
			prepareInterfaceTexture();
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

		updateSceneFbo();

		// Draw 3d scene
		if (hasLoggedIn && sceneContext != null && sceneViewport != null) {
			// Before reading the SSBOs written to from postDrawScene() we must insert a barrier
			if (computeMode == ComputeMode.OPENCL) {
				clManager.finish();
			} else {
				GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
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
			fogDepth *= min(getDrawDistance(), 90) / 10.f;
			uboGlobal.useFog.set(fogDepth > 0 ? 1 : 0);
			uboGlobal.fogDepth.set(fogDepth);
			uboGlobal.fogColor.set(fogColor);

			uboGlobal.drawDistance.set((float) getDrawDistance());
			uboGlobal.expandedMapLoadingChunks.set(sceneContext.expandedMapLoadingChunks);
			uboGlobal.colorBlindnessIntensity.set(config.colorBlindnessIntensity() / 100.f);

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
			uboGlobal.waterColorLight.set(waterColorLight);
			uboGlobal.waterColorMid.set(waterColorMid);
			uboGlobal.waterColorDark.set(waterColorDark);

			uboGlobal.gammaCorrection.set(getGammaCorrection());
			float ambientStrength = environmentManager.currentAmbientStrength;
			float directionalStrength = environmentManager.currentDirectionalStrength;
			if (config.useLegacyBrightness()) {
				float factor = config.legacyBrightness() / 20f;
				ambientStrength *= factor;
				directionalStrength *= factor;
			}
			uboGlobal.ambientStrength.set(ambientStrength);
			uboGlobal.ambientColor.set(environmentManager.currentAmbientColor);
			uboGlobal.lightStrength.set(directionalStrength);
			uboGlobal.lightColor.set(environmentManager.currentDirectionalColor);

			uboGlobal.underglowStrength.set(environmentManager.currentUnderglowStrength);
			uboGlobal.underglowColor.set(environmentManager.currentUnderglowColor);

			uboGlobal.groundFogStart.set(environmentManager.currentGroundFogStart);
			uboGlobal.groundFogEnd.set(environmentManager.currentGroundFogEnd);
			uboGlobal.groundFogOpacity.set(config.groundFog() ? environmentManager.currentGroundFogOpacity : 0);

			// Lights & lightning
			uboGlobal.pointLightsCount.set(sceneContext.numVisibleLights);
			uboGlobal.lightningBrightness.set(environmentManager.getLightningBrightness());

			uboGlobal.saturation.set(config.saturation() / 100f);
			uboGlobal.contrast.set(config.contrast() / 100f);
			uboGlobal.underwaterEnvironment.set(environmentManager.isUnderwater() ? 1 : 0);
			uboGlobal.underwaterCaustics.set(config.underwaterCaustics() ? 1 : 0);
			uboGlobal.underwaterCausticsColor.set(environmentManager.currentUnderwaterCausticsColor);
			uboGlobal.underwaterCausticsStrength.set(environmentManager.currentUnderwaterCausticsStrength);
			uboGlobal.elapsedTime.set((float) (elapsedTime % MAX_FLOAT_WITH_128TH_PRECISION));

			float[] lightViewMatrix = Mat4.rotateX(environmentManager.currentSunAngles[0]);
			Mat4.mul(lightViewMatrix, Mat4.rotateY(PI - environmentManager.currentSunAngles[1]));
			// Extract the 3rd column from the light view matrix (the float array is column-major).
			// This produces the light's direction vector in world space, which we negate in order to
			// get the light's direction vector pointing away from each fragment
			uboGlobal.lightDir.set(-lightViewMatrix[2], -lightViewMatrix[6], -lightViewMatrix[10]);

			if (configColorFilter != ColorFilter.NONE) {
				uboGlobal.colorFilter.set(configColorFilter.ordinal());
				uboGlobal.colorFilterPrevious.set(configColorFilterPrevious.ordinal());
				long timeSinceChange = System.currentTimeMillis() - colorFilterChangedAt;
				uboGlobal.colorFilterFade.set(clamp(timeSinceChange / COLOR_FILTER_FADE_DURATION, 0, 1));
			}

			if (configShadowsEnabled && fboShadowMap != 0 && environmentManager.currentDirectionalStrength > 0) {
				frameTimer.begin(Timer.RENDER_SHADOWS);

				// Render to the shadow depth map
				glViewport(0, 0, shadowMapResolution, shadowMapResolution);
				glBindFramebuffer(GL_FRAMEBUFFER, fboShadowMap);
				glClearDepth(1);
				glClear(GL_DEPTH_BUFFER_BIT);
				glDepthFunc(GL_LEQUAL);

				shadowProgram.use();

				final int camX = cameraFocalPoint[0];
				final int camY = cameraFocalPoint[1];

				final int drawDistanceSceneUnits = min(config.shadowDistance().getValue(), getDrawDistance()) * LOCAL_TILE_SIZE / 2;
				final int east = min(camX + drawDistanceSceneUnits, LOCAL_TILE_SIZE * SCENE_SIZE);
				final int west = max(camX - drawDistanceSceneUnits, 0);
				final int north = min(camY + drawDistanceSceneUnits, LOCAL_TILE_SIZE * SCENE_SIZE);
				final int south = max(camY - drawDistanceSceneUnits, 0);
				final int width = east - west;
				final int height = north - south;
				final int depthScale = 10000;

				final int maxDrawDistance = 90;
				final float maxScale = 0.7f;
				final float minScale = 0.4f;
				final float scaleMultiplier = 1.0f - (getDrawDistance() / (maxDrawDistance * maxScale));
				float scale = mix(maxScale, minScale, scaleMultiplier);
				float[] lightProjectionMatrix = Mat4.identity();
				Mat4.mul(lightProjectionMatrix, Mat4.scale(scale, scale, scale));
				Mat4.mul(lightProjectionMatrix, Mat4.orthographic(width, height, depthScale));
				Mat4.mul(lightProjectionMatrix, lightViewMatrix);
				Mat4.mul(lightProjectionMatrix, Mat4.translate(-(width / 2f + west), 0, -(height / 2f + south)));

				uboGlobal.lightProjectionMatrix.set(lightProjectionMatrix);
				uboGlobal.upload();

				glEnable(GL_CULL_FACE);
				glEnable(GL_DEPTH_TEST);

				glBindVertexArray(vaoScene);
				glDrawArrays(GL_TRIANGLES, 0, renderBufferOffset);

				glDisable(GL_CULL_FACE);
				glDisable(GL_DEPTH_TEST);

				frameTimer.end(Timer.RENDER_SHADOWS);
			}

			uboGlobal.upload();
			sceneProgram.use();

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboScene);
			glToggle(GL_MULTISAMPLE, msaaSamples > 1);
			glViewport(0, 0, sceneResolution[0], sceneResolution[1]);

			// Clear scene
			frameTimer.begin(Timer.CLEAR_SCENE);

			float[] gammaCorrectedFogColor = pow(fogColor, getGammaCorrection());
			glClearColor(
				gammaCorrectedFogColor[0],
				gammaCorrectedFogColor[1],
				gammaCorrectedFogColor[2],
				1f
			);
			glClearDepth(0);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
			frameTimer.end(Timer.CLEAR_SCENE);

			frameTimer.begin(Timer.RENDER_SCENE);

			// We just allow the GL to do face culling. Note this requires the priority renderer
			// to have logic to disregard culled faces in the priority depth testing.
			glEnable(GL_CULL_FACE);
			glCullFace(GL_BACK);

			// Enable blending for alpha
			glEnable(GL_BLEND);
			glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);

			// Draw with buffers bound to scene VAO
			glBindVertexArray(vaoScene);

			// When there are custom tiles, we need depth testing to draw them in the correct order, but the rest of the
			// scene doesn't support depth testing, so we only write depths for custom tiles.
			if (sceneContext.staticCustomTilesVertexCount > 0) {
				// Draw gap filler tiles first, without depth testing
				if (sceneContext.staticGapFillerTilesVertexCount > 0) {
					glDisable(GL_DEPTH_TEST);
					glDrawArrays(
						GL_TRIANGLES,
						sceneContext.staticGapFillerTilesOffset,
						sceneContext.staticGapFillerTilesVertexCount
					);
				}

				glEnable(GL_DEPTH_TEST);
				glDepthFunc(GL_GREATER);

				// Draw custom tiles, writing depth
				glDepthMask(true);
				glDrawArrays(
					GL_TRIANGLES,
					sceneContext.staticCustomTilesOffset,
					sceneContext.staticCustomTilesVertexCount
				);

				// Draw the rest of the scene with depth testing, but not against itself
				glDepthMask(false);
				glDrawArrays(
					GL_TRIANGLES,
					sceneContext.staticVertexCount,
					renderBufferOffset - sceneContext.staticVertexCount
				);
			} else {
				// Draw everything without depth testing
				glDisable(GL_DEPTH_TEST);
				glDrawArrays(GL_TRIANGLES, 0, renderBufferOffset);
			}

			frameTimer.end(Timer.RENDER_SCENE);

			glDisable(GL_BLEND);
			glDisable(GL_CULL_FACE);
			glDisable(GL_MULTISAMPLE);
			glDisable(GL_DEPTH_TEST);
			glDepthMask(true);
			glUseProgram(0);

			glBindFramebuffer(GL_READ_FRAMEBUFFER, fboScene);
			if (fboSceneResolve != 0) {
				// Blit from the scene FBO to the multisample resolve FBO
				glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboSceneResolve);
				glBlitFramebuffer(
					0, 0, sceneResolution[0], sceneResolution[1],
					0, 0, sceneResolution[0], sceneResolution[1],
					GL_COLOR_BUFFER_BIT, GL_NEAREST
				);
				glBindFramebuffer(GL_READ_FRAMEBUFFER, fboSceneResolve);
			}

			// Blit from the resolved FBO to the default FBO
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
			glBlitFramebuffer(
				0, 0, sceneResolution[0], sceneResolution[1],
				sceneViewport[0], sceneViewport[1], sceneViewport[0] + sceneViewport[2], sceneViewport[1] + sceneViewport[3],
				GL_COLOR_BUFFER_BIT, config.sceneScalingMode().glFilter
			);
		} else {
			glClearColor(0, 0, 0, 1f);
			glClear(GL_COLOR_BUFFER_BIT);
		}

		drawUi(overlayColor);

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
		frameTimer.end(Timer.RENDER_FRAME);
		frameTimer.endFrameAndReset();
		frameModelInfoMap.clear();
		checkGLErrors();

		// Process pending config changes after the EDT is done with any pending work, which could include further config changes
		if (!pendingConfigChanges.isEmpty())
			SwingUtilities.invokeLater(this::processPendingConfigChanges);
	}

	private void drawUi(int overlayColor) {
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

		// Set the sampling function used when stretching the UI.
		// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
		// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
		// GL_NEAREST makes sampling for bicubic/xBR simpler, so it should be used whenever linear isn't
		final int function = config.uiScalingMode() == UIScalingMode.LINEAR ? GL_LINEAR : GL_NEAREST;
		glActiveTexture(TEXTURE_UNIT_UI);
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
	private Image screenshot() {
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

		int expandedChunks = getExpandedMapLoadingChunks();
		if (HDUtils.sceneIntersects(scene, expandedChunks, areaManager.getArea("PLAYER_OWNED_HOUSE"))) {
			// Reload once the POH is done loading
			if (!isInHouse)
				reloadSceneIn(2);
		} else if (skipScene != scene && HDUtils.sceneIntersects(scene, expandedChunks, areaManager.getArea("THE_GAUNTLET"))) {
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

	public boolean isLoadingScene() {
		return nextSceneContext != null;
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
			nextSceneContext = new SceneContext(client, scene, getExpandedMapLoadingChunks(), reuseBuffers, sceneContext);
			proceduralGenerator.generateSceneData(nextSceneContext);
			environmentManager.loadSceneEnvironments(nextSceneContext);
			sceneUploader.upload(nextSceneContext);
		} catch (OutOfMemoryError oom) {
			log.error("Ran out of memory while loading scene (32-bit: {}, low memory mode: {}, cache size: {})",
				HDUtils.is32Bit(), useLowMemoryMode, config.modelCacheSizeMiB(), oom
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
		if (!isActive || skipScene == scene) {
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
			clManager.uploadTileHeights(scene);
		} else {
			initTileHeightMap(scene);
		}

		tileVisibilityCached = false;
		lightManager.loadSceneLights(nextSceneContext, sceneContext);
		fishingSpotReplacer.despawnRuneLiteObjects();
		npcDisplacementCache.clear();

		if (sceneContext != null)
			sceneContext.destroy();
		sceneContext = nextSceneContext;
		nextSceneContext = null;
		assert sceneContext != null;

		sceneUploader.prepareBeforeSwap(sceneContext);

		sceneContext.staticUnorderedModelBuffer.flip();

		dynamicOffsetVertices = sceneContext.getVertexOffset();
		dynamicOffsetUvs = sceneContext.getUvOffset();

		sceneContext.stagingBufferVertices.flip();
		sceneContext.stagingBufferUvs.flip();
		sceneContext.stagingBufferNormals.flip();
		hStagingBufferVertices.upload(sceneContext.stagingBufferVertices);
		hStagingBufferUvs.upload(sceneContext.stagingBufferUvs);
		hStagingBufferNormals.upload(sceneContext.stagingBufferNormals);
		sceneContext.stagingBufferVertices.clear();
		sceneContext.stagingBufferUvs.clear();
		sceneContext.stagingBufferNormals.clear();

		if (sceneContext.intersects(areaManager.getArea("PLAYER_OWNED_HOUSE"))) {
			isInHouse = true;
			isInChambersOfXeric = false;
		} else {
			isInHouse = false;
			isInChambersOfXeric = sceneContext.intersects(areaManager.getArea("CHAMBERS_OF_XERIC"));
		}
	}

	public void reloadSceneNextGameTick() {
		reloadSceneIn(1);
	}

	public void reloadSceneIn(int gameTicks) {
		assert gameTicks > 0 : "A value <= 0 will not reload the scene";
		if (gameTicks > gameTicksUntilSceneReload)
			gameTicksUntilSceneReload = gameTicks;
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
		configDynamicLights = config.dynamicLights();
		configTiledLighting = config.tiledLighting();
		configExpandShadowDraw = config.expandShadowDraw();
		configUseFasterModelHashing = config.fasterModelHashing();
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
		}
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
					boolean recreateSceneFbo = false;
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
							case KEY_SEASONAL_HEMISPHERE:
							case KEY_GROUND_BLENDING:
							case KEY_GROUND_TEXTURES:
								reloadTileOverrides = true;
								break;
							case KEY_COLOR_FILTER:
								if (configColorFilter == ColorFilter.NONE || configColorFilterPrevious == ColorFilter.NONE)
									recompilePrograms = true;
								if (configColorFilter == ColorFilter.CEL_SHADING || configColorFilterPrevious == ColorFilter.CEL_SHADING)
									clearModelCache = reloadScene = true;
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
							case KEY_NORMAL_MAPPING:
							case KEY_PARALLAX_OCCLUSION_MAPPING:
							case KEY_UI_SCALING_MODE:
							case KEY_VANILLA_COLOR_BANDING:
							case KEY_WIND_DISPLACEMENT:
							case KEY_CHARACTER_DISPLACEMENT:
							case KEY_WIREFRAME:
							case KEY_PIXELATED_SHADOWS:
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
							case KEY_REMOVE_VERTEX_SNAPPING:
								restartPlugin();
								// since we'll be restarting the plugin anyway, skip pending changes
								return;
							case KEY_FISHING_SPOT_STYLE:
								reloadModelOverrides = true;
								fishingSpotReplacer.despawnRuneLiteObjects();
								clientThread.invokeLater(fishingSpotReplacer::update);
								break;
						}
					}

					if (reloadTexturesAndMaterials || recompilePrograms)
						waitUntilIdle();

					if (reloadTexturesAndMaterials) {
						materialManager.reload(false);
						modelOverrideManager.reload();
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

					if (recreateSceneFbo) {
						destroySceneFbo();
						updateSceneFbo();
					}

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

	@Override
	public boolean tileInFrustum(
		Scene scene,
		float pitchSin,
		float pitchCos,
		float yawSin,
		float yawCos,
		int cameraX,
		int cameraY,
		int cameraZ,
		int plane,
		int tileExX,
		int tileExY
	) {
		if (sceneContext == null)
			return false;

		if (orthographicProjection)
			return true;

		if (tileVisibilityCached)
			return tileIsVisible[plane][tileExX][tileExY];

		int[][][] tileHeights = scene.getTileHeights();
		int x = ((tileExX - SCENE_OFFSET) << Perspective.LOCAL_COORD_BITS) + 64;
		int z = ((tileExY - SCENE_OFFSET) << Perspective.LOCAL_COORD_BITS) + 64;
		int y = GROUND_MIN_Y + max(
			tileHeights[plane][tileExX][tileExY],
			tileHeights[plane][tileExX][tileExY + 1],
			tileHeights[plane][tileExX + 1][tileExY],
			tileHeights[plane][tileExX + 1][tileExY + 1]
		);

		if (sceneContext.scene == scene) {
			int depthLevel = sceneContext.underwaterDepthLevels[plane][tileExX][tileExY];
			if (depthLevel > 0)
				y += ProceduralGenerator.DEPTH_LEVEL_SLOPE[depthLevel - 1] - GROUND_MIN_Y;
		}

		x -= (int) cameraPosition[0];
		y -= (int) cameraPosition[1];
		z -= (int) cameraPosition[2];

		final int tileRadius = 96; // ~ 64 * sqrt(2)
		final int leftClip = client.getRasterizer3D_clipNegativeMidX();
		final int rightClip = client.getRasterizer3D_clipMidX2();
		final int topClip = client.getRasterizer3D_clipNegativeMidY();

		// Transform the local coordinates using the yaw (horizontal rotation)
		final float transformedZ = yawCos * z - yawSin * x;
		final float depth = pitchCos * tileRadius + pitchSin * y + pitchCos * transformedZ;

		boolean visible = false;

		// Check if the tile is within the near plane of the frustum
		if (depth > NEAR_PLANE) {
			final float transformedX = z * yawSin + yawCos * x;
			final float leftPoint = transformedX - tileRadius;
			// Check left and right bounds
			if (leftPoint * visibilityCheckZoom < rightClip * depth) {
				final float rightPoint = transformedX + tileRadius;
				if (rightPoint * visibilityCheckZoom > leftClip * depth) {
					// Transform the local Y using pitch (vertical rotation)
					final float transformedY = pitchCos * y - transformedZ * pitchSin;
					final float bottomPoint = transformedY + pitchSin * tileRadius;
					// Check top bound (we skip bottom bound to avoid computing model heights)
					visible = bottomPoint * visibilityCheckZoom > topClip * depth;
				}
			}
		}

		return tileIsVisible[plane][tileExX][tileExY] = visible;
	}

	/**
	 * Check is a model is visible and should be drawn.
	 */
	private boolean isOutsideViewport(Model model, int modelRadius, float pitchSin, float pitchCos, float yawSin, float yawCos, int x, int y, int z) {
		if (sceneContext == null)
			return true;

		if (orthographicProjection)
			return false;

		final int leftClip = client.getRasterizer3D_clipNegativeMidX();
		final int rightClip = client.getRasterizer3D_clipMidX2();
		final int topClip = client.getRasterizer3D_clipNegativeMidY();
		final int bottomClip = client.getRasterizer3D_clipMidY2();

		final float transformedZ = yawCos * z - yawSin * x;
		final float depth = pitchCos * modelRadius + pitchSin * y + pitchCos * transformedZ;

		if (depth > NEAR_PLANE) {
			final float transformedX = z * yawSin + yawCos * x;
			final float leftPoint = transformedX - modelRadius;
			if (leftPoint * visibilityCheckZoom < rightClip * depth) {
				final float rightPoint = transformedX + modelRadius;
				if (rightPoint * visibilityCheckZoom > leftClip * depth) {
					final float transformedY = pitchCos * y - transformedZ * pitchSin;
					final float transformedRadius = pitchSin * modelRadius;
					final float bottomExtent = pitchCos * model.getBottomY() + transformedRadius;
					final float bottomPoint = transformedY + bottomExtent;
					if (bottomPoint * visibilityCheckZoom > topClip * depth) {
						final float topExtent = pitchCos * model.getModelHeight() + transformedRadius;
						final float topPoint = transformedY - topExtent;
						return topPoint * visibilityCheckZoom >= bottomClip * depth; // inverted check
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
	public void draw(Projection projection, @Nullable Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash) {
		if (sceneContext == null)
			return;

		// Hide everything outside the current area if area hiding is enabled
		if (sceneContext.currentArea != null) {
			assert sceneContext.sceneBase != null;
			boolean inArea = sceneContext.currentArea.containsPoint(
				sceneContext.sceneBase[0] + (x >> LOCAL_COORD_BITS),
				sceneContext.sceneBase[1] + (z >> LOCAL_COORD_BITS),
				sceneContext.sceneBase[2] + client.getPlane()
			);
			if (!inArea)
				return;
		}

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
		int height = model.getModelHeight();
		if (model != renderable)
			renderable.setModelHeight(height);

		model.calculateBoundsCylinder();
		int modelRadius = model.getXYZMag(); // Model radius excluding height (model.getRadius() includes height)

		if (projection instanceof IntProjection) {
			var p = (IntProjection) projection;
			if (isOutsideViewport(
				model,
				modelRadius,
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
		eightIntWrite[4] = orientation;
		eightIntWrite[5] = x;
		eightIntWrite[6] = y << 16 | height & 0xFFFF; // Pack Y into the upper bits to easily preserve the sign
		eightIntWrite[7] = z;

		int plane = ModelHash.getPlane(hash);
		int faceCount;
		if (sceneContext.id == (offsetModel.getSceneId() & SceneUploader.SCENE_ID_MASK)) {
			// The model is part of the static scene buffer. The Renderable will then almost always be the Model instance, but if the scene
			// is reuploaded without triggering the LOADING game state, it's possible for static objects which may only temporarily become
			// animated to also be uploaded. This results in the Renderable being converted to a DynamicObject, whose `getModel` returns the
			// original static Model after the animation is done playing. One such example is in the POH, after it has been reuploaded in
			// order to cache newly loaded static models, and you subsequently attempt to interact with a wardrobe triggering its animation.
			faceCount = min(MAX_FACE_COUNT, offsetModel.getFaceCount());
			int vertexOffset = offsetModel.getBufferOffset();
			int uvOffset = offsetModel.getUvBufferOffset();
			boolean hillskew = offsetModel != model;

			eightIntWrite[0] = vertexOffset;
			eightIntWrite[1] = uvOffset;
			eightIntWrite[2] = faceCount;
			eightIntWrite[4] |= (hillskew ? 1 : 0) << 26 | plane << 24;

			drawnStaticRenderableCount++;
		} else {
			int uuid = ModelHash.generateUuid(client, hash, renderable);
			int[] worldPos = sceneContext.localToWorld(x, z, plane);
			ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
			if (modelOverride.hide)
				return;

			// Disable color overrides when caching is disabled, since they are expensive on dynamic models
			if (!configModelCaching && modelOverride.colorOverrides != null)
				modelOverride = ModelOverride.NONE;

			int preOrientation = 0;
			if (ModelHash.getType(hash) == ModelHash.TYPE_OBJECT) {
				int tileExX = (x >> LOCAL_COORD_BITS) + SCENE_OFFSET;
				int tileExY = (z >> LOCAL_COORD_BITS) + SCENE_OFFSET;
				if (0 <= tileExX && tileExX < EXTENDED_SCENE_SIZE && 0 <= tileExY && tileExY < EXTENDED_SCENE_SIZE) {
					Tile tile = sceneContext.scene.getExtendedTiles()[plane][tileExX][tileExY];
					int config;
					if (tile != null && (config = sceneContext.getObjectConfig(tile, hash)) != -1) {
						preOrientation = HDUtils.getModelPreOrientation(config);
					} else if (plane > 0) {
						// Might be on a bridge tile
						tile = sceneContext.scene.getExtendedTiles()[plane - 1][tileExX][tileExY];
						if (tile != null && tile.getBridge() != null && (config = sceneContext.getObjectConfig(tile, hash)) != -1)
							preOrientation = HDUtils.getModelPreOrientation(config);
					}
				}
			}

			// Temporary model (animated or otherwise not a static Model already in the scene buffer)
			if (enableDetailedTimers)
				frameTimer.begin(Timer.MODEL_BATCHING);
			ModelOffsets modelOffsets = null;
			if (configModelBatching || configModelCaching) {
				modelHasher.setModel(model, modelOverride, preOrientation);
				// Disable model batching for models which have been excluded from the scene buffer,
				// because we want to avoid having to fetch the model override
				if (configModelBatching && offsetModel.getSceneId() != SceneUploader.EXCLUDED_FROM_SCENE_BUFFER) {
					modelOffsets = frameModelInfoMap.get(modelHasher.batchHash);
					if (modelOffsets != null && modelOffsets.faceCount != model.getFaceCount())
						modelOffsets = null; // Assume there's been a hash collision
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

				int vertexOffset = dynamicOffsetVertices + sceneContext.getVertexOffset();
				int uvOffset = dynamicOffsetUvs + sceneContext.getUvOffset();

				modelPusher.pushModel(sceneContext, null, uuid, model, modelOverride, preOrientation, true);

				faceCount = sceneContext.modelPusherResults[0];
				if (sceneContext.modelPusherResults[1] == 0)
					uvOffset = -1;

				if (enableDetailedTimers)
					frameTimer.end(Timer.MODEL_PUSHING);

				eightIntWrite[0] = vertexOffset;
				eightIntWrite[1] = uvOffset;
				eightIntWrite[2] = faceCount;

				// add this temporary model to the map for batching purposes
				if (configModelBatching && modelOffsets == null)
					frameModelInfoMap.put(modelHasher.batchHash, new ModelOffsets(faceCount, vertexOffset, uvOffset));
			}

			if (eightIntWrite[0] != -1)
				drawnDynamicRenderableCount++;

			if (configCharacterDisplacement && renderable instanceof Actor) {
				if (enableDetailedTimers)
					frameTimer.begin(Timer.CHARACTER_DISPLACEMENT);
				if (renderable instanceof NPC) {
					var npc = (NPC) renderable;
					var entry = npcDisplacementCache.get(npc);
					if (entry.canDisplace) {
						int displacementRadius = entry.idleRadius;
						if (displacementRadius == -1) {
							displacementRadius = modelRadius; // Fallback to model radius since we don't know the idle radius yet
							if (npc.getIdlePoseAnimation() == npc.getPoseAnimation() && npc.getAnimation() == -1) {
								displacementRadius *= 2; // Double the idle radius, so that it fits most other animations
								entry.idleRadius = displacementRadius;
							}
						}
						uboCompute.addCharacterPosition(x, z, displacementRadius);
					}
				} else if (renderable instanceof Player && renderable != client.getLocalPlayer()) {
					uboCompute.addCharacterPosition(x, z, (int) (LOCAL_TILE_SIZE * 1.33f));
				}
				if (enableDetailedTimers)
					frameTimer.end(Timer.CHARACTER_DISPLACEMENT);
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
		return 100f / config.brightness();
	}

	private int getExpandedMapLoadingChunks() {
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

		if (gameTicksUntilSceneReload > 0) {
			if (gameTicksUntilSceneReload == 1)
				reuploadScene();
			--gameTicksUntilSceneReload;
		}

		fishingSpotReplacer.update();
	}

	public void waitUntilIdle() {
		if (computeMode == ComputeMode.OPENCL)
			clManager.finish();
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
	public static void clearGLErrors() {
		// @formatter:off
		while (glGetError() != GL_NO_ERROR);
		// @formatter:on
	}

	public static void checkGLErrors() {
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
