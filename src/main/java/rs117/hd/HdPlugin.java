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
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
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
import rs117.hd.config.AntiAliasingMode;
import rs117.hd.config.ShadowMode;
import rs117.hd.config.UIScalingMode;
import rs117.hd.data.WaterType;
import rs117.hd.data.materials.Material;
import rs117.hd.model.ModelHasher;
import rs117.hd.model.ModelPusher;
import rs117.hd.model.TempModelInfo;
import rs117.hd.opengl.compute.ComputeMode;
import rs117.hd.opengl.compute.OpenCLManager;
import rs117.hd.opengl.shader.Shader;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.Template;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.SceneUploader;
import rs117.hd.scene.TextureManager;
import rs117.hd.scene.lights.SceneLight;
import rs117.hd.scene.model_overrides.ObjectType;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.DeveloperTools;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.PopupUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Perspective.*;
import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opencl.CL10GL.*;
import static org.lwjgl.opengl.GL43C.*;
import static rs117.hd.HdPluginConfig.*;
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

	public static final int TEXTURE_UNIT_UI = GL_TEXTURE0; // default state
	public static final int TEXTURE_UNIT_GAME = GL_TEXTURE1;
	public static final int TEXTURE_UNIT_SHADOW_MAP = GL_TEXTURE2;

	/**
	 * The maximum number of triangles supported by the large compute shader
	 */
	public static final int MAX_TRIANGLE = 6144;
	public static final int SMALL_TRIANGLE_COUNT = 512;
	public static final int MAX_DISTANCE = 90;
	public static final int MAX_FOG_DEPTH = 100;
	public static final int SCALAR_BYTES = 4;
	public static final int VERTEX_SIZE = 4; // 4 ints per vertex
	public static final int UV_SIZE = 4; // 4 floats per vertex
	public static final int NORMAL_SIZE = 4; // 4 floats per vertex

	private static final int[] eightIntWrite = new int[8];

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
	private OpenCLManager openCLManager;

	@Inject
	private TextureManager textureManager;

	@Inject
	private LightManager lightManager;

	@Inject
	private EnvironmentManager environmentManager;

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
	@Named("developerMode")
	private boolean developerMode;

	@Inject
	private DeveloperTools developerTools;

	@Inject
	private HdPluginConfig config;

	@Inject
	private Gson rlGson;
	@Getter
	private Gson gson;

	private Canvas canvas;
	private AWTContext awtContext;
	private GLCapabilities glCaps;
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

	private static final Shader SMALL_COMPUTE_PROGRAM = new Shader()
		.add(GL_COMPUTE_SHADER, "comp_small.glsl");

	private static final Shader UNORDERED_COMPUTE_PROGRAM = new Shader()
		.add(GL_COMPUTE_SHADER, "comp_unordered.glsl");

	private static final Shader UI_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vertui.glsl")
		.add(GL_FRAGMENT_SHADER, "fragui.glsl");

	private static final ResourcePath SHADER_PATH = Props
		.getPathOrDefault("rlhd.shader-path", () -> path(HdPlugin.class))
		.chroot();

	private int glProgram;
	private int glLargeComputeProgram;
	private int glSmallComputeProgram;
	private int glUnorderedComputeProgram;
	private int glUiProgram;
	private int glShadowProgram;

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

	private final GLBuffer hStagingBufferVertices = new GLBuffer(); // temporary scene vertex buffer
	private final GLBuffer hStagingBufferUvs = new GLBuffer(); // temporary scene uv buffer
	private final GLBuffer hStagingBufferNormals = new GLBuffer(); // temporary scene normal buffer
	private final GLBuffer hModelBufferUnordered = new GLBuffer(); // scene model buffer, unordered
	private final GLBuffer hModelBufferSmall = new GLBuffer(); // scene model buffer, small
	private final GLBuffer hModelBufferLarge = new GLBuffer(); // scene model buffer, large
	private final GLBuffer hRenderBufferVertices = new GLBuffer(); // target vertex buffer for compute shaders
	private final GLBuffer hRenderBufferUvs = new GLBuffer(); // target uv buffer for compute shaders
	private final GLBuffer hRenderBufferNormals = new GLBuffer(); // target normal buffer for compute shaders

	private final GLBuffer hUniformBufferCamera = new GLBuffer();
	private final GLBuffer hUniformBufferMaterials = new GLBuffer();
	private final GLBuffer hUniformBufferWaterTypes = new GLBuffer();
	private final GLBuffer hUniformBufferLights = new GLBuffer();
	private ByteBuffer uniformBufferLights;

	@Getter
	@Nullable
	private SceneContext sceneContext;
	private SceneContext nextSceneContext;

	private GpuIntBuffer modelBufferUnordered;
	private GpuIntBuffer modelBufferSmall;
	private GpuIntBuffer modelBufferLarge;

	private int numModelsUnordered;
	private int numModelsSmall;
	private int numModelsLarge;

	private int dynamicOffsetVertices;
	private int dynamicOffsetUvs;
	private int renderBufferOffset;

	private int lastCanvasWidth;
	private int lastCanvasHeight;
	private int lastStretchedCanvasWidth;
	private int lastStretchedCanvasHeight;
	private AntiAliasingMode lastAntiAliasingMode;

	private int yaw;
	private int pitch;
	private int viewportOffsetX;
	private int viewportOffsetY;

	// Uniforms
	private int uniColorBlindnessIntensity;
	private int uniUiColorBlindnessIntensity;
	private int uniUseFog;
	private int uniFogColor;
	private int uniFogDepth;
	private int uniDrawDistance;
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

	// Shadow program uniforms
	private int uniShadowLightProjectionMatrix;
	private int uniShadowElapsedTime;

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

	private int uniBlockCameraComputeSmall;
	private int uniBlockCameraComputeLarge;
	private int uniBlockCamera;
	private int uniBlockMaterials;
	private int uniBlockWaterTypes;
	private int uniBlockPointLights;

	// Animation things
	private long lastFrameTime = System.currentTimeMillis();

	// Generic scalable animation timer used in shaders
	private float elapsedTime;

	private int gameTicksUntilSceneReload = 0;

	// Configs used frequently enough to be worth caching
	public boolean configGroundTextures;
	public boolean configGroundBlending;
	public boolean configModelTextures;
	public boolean configTzhaarHD;
	public boolean configProjectileLights;
	public boolean configNpcLights;
	public boolean configHideFakeShadows;
	public boolean configWinterTheme;
	public boolean configLegacyGreyColors;
	public boolean configModelBatching;
	public boolean configModelCaching;
	public boolean configShadowsEnabled;
	public boolean configExpandShadowDraw;
	public ShadowMode configShadowMode;
	public int configMaxDynamicLights;

	public int[] camTarget = new int[3];

	private boolean lwjglInitialized;
	private boolean isRunning;
	private boolean hasLoggedIn;
	private boolean shouldSkipModelUpdates;

	public boolean isInGauntlet;
	public boolean isInChambersOfXeric;

	private final Map<Integer, TempModelInfo> frameModelInfoMap = new HashMap<>();

	@Subscribe
	public void onChatMessage(final ChatMessage event) {
		// Reload the scene if the player is in the gauntlet and opening a new room, to pull the new data into static buffers
		if (isInGauntlet && event.getMessage().equals("You light the nodes in the corridor to help guide the way."))
			reloadSceneNextGameTick();
	}

	@Provides
	HdPluginConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(HdPluginConfig.class);
	}

	@Override
	protected void startUp() {
		gson = rlGson.newBuilder().setLenient().create();

		clientThread.invoke(() -> {
			try {
				renderBufferOffset = 0;
				fboSceneHandle = rboSceneHandle = 0; // AA FBO
				fboShadowMap = 0;
				numModelsUnordered = numModelsSmall = numModelsLarge = 0;
				elapsedTime = 0;

				AWTContext.loadNatives();
				canvas = client.getCanvas();

				synchronized (canvas.getTreeLock()) {
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

				String glRenderer = glGetString(GL_RENDERER);
				String arch = System.getProperty("sun.arch.data.model", "Unknown");
				if (glRenderer == null)
					glRenderer = "Unknown";
				log.info("Using device: {}", glRenderer);
				log.info("Using driver: {}", glGetString(GL_VERSION));
				log.info("Client is {}-bit", arch);

				computeMode = OSType.getOSType() == OSType.MacOS ? ComputeMode.OPENCL : ComputeMode.OPENGL;

				boolean isGenericGpu = glRenderer.equals("GDI Generic");
				boolean isUnsupportedGpu = isGenericGpu || (computeMode == ComputeMode.OPENGL ? !glCaps.OpenGL43 : !glCaps.OpenGL31);
				if (isUnsupportedGpu)
				{
					log.error("The GPU is lacking OpenGL {} support. Stopping the plugin...",
						computeMode == ComputeMode.OPENGL ? "4.3" : "3.1");
					PopupUtils.displayPopupMessage(client, "117HD Error",
						(isGenericGpu ?
							"117HD was unable to access your GPU." :
							"Your GPU is currently not supported by 117HD.<br><br>GPU name: " + glRenderer
						) +
						"<br><br>" +
						"If your system actually has a supported GPU, try the following steps:<br>" +
						(!arch.equals("32") ? "" :
							"&nbsp;• Install the 64-bit version of RuneLite from " +
								"<a href=\"" + HdPlugin.RUNELITE_URL + "\">the official website</a>.<br>"
						) +
						"&nbsp;• If you're on a desktop PC, make sure your monitor is plugged into the graphics card<br>" +
						"&nbsp;&nbsp;&nbsp;&nbsp;instead of the motherboard's display output.<br>" +
						"&nbsp;• Reinstall the drivers for your graphics card and restart your system.<br>" +
						"<br>" +
						"If you're still seeing this error after following the steps above, please join our " +
							"<a href=\"" + HdPlugin.DISCORD_URL + "\">Discord</a><br>" +
						"server, and drag and drop your client log file into one of our support channels.",
						new String[] { "Open log folder", "Ok, let me try that..." },
						i -> { if (i == 0) LinkBrowser.open(RuneLite.LOGS_DIR.toString()); });
					stopPlugin();
					return true;
				}

				lwjglInitialized = true;
				checkGLErrors();

				if (log.isDebugEnabled() && glCaps.glDebugMessageControl != 0)
				{
					debugCallback = GLUtil.setupDebugMessageCallback();
					if (debugCallback != null)
					{
						//	GLDebugEvent[ id 0x20071
						//		type Warning: generic
						//		severity Unknown (0x826b)
						//		source GL API
						//		msg Buffer detailed info: Buffer object 11 (bound to GL_ARRAY_BUFFER_ARB, and GL_SHADER_STORAGE_BUFFER (4), usage hint is GL_STREAM_DRAW) will use VIDEO memory as the source for buffer object operations.
						glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_OTHER,
							GL_DONT_CARE, 0x20071, false);

						//	GLDebugMessageHandler: GLDebugEvent[ id 0x20052
						//		type Warning: implementation dependent performance
						//		severity Medium: Severe performance/deprecation/other warnings
						//		source GL API
						//		msg Pixel-path performance warning: Pixel transfer is synchronized with 3D rendering.
						glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_PERFORMANCE,
							GL_DONT_CARE, 0x20052, false);
					}
				}

				if (computeMode == ComputeMode.OPENCL)
				{
					openCLManager.startUp(awtContext);
				}

				modelBufferUnordered = new GpuIntBuffer();
				modelBufferSmall = new GpuIntBuffer();
				modelBufferLarge = new GpuIntBuffer();

				if (developerMode)
				{
					developerTools.activate();
				}

				lastFrameTime = System.currentTimeMillis();

				updateCachedConfigs();
				setupSyncMode();
				initVaos();
				initBuffers();
				initPrograms();
				initShaderHotswapping();
				initInterfaceTexture();
				initShadowMapFbo();

				client.setDrawCallbacks(this);
				client.setGpu(true);
				textureManager.startUp();
				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				lastCanvasWidth = lastCanvasHeight = 0;
				lastStretchedCanvasWidth = lastStretchedCanvasHeight = 0;
				lastAntiAliasingMode = null;

				modelPusher.startUp();
				modelOverrideManager.startUp();
				lightManager.startUp();

				isRunning = true;
				hasLoggedIn = client.getGameState().getState() > GameState.LOGGING_IN.getState();
				shouldSkipModelUpdates = false;
				isInGauntlet = false;
				isInChambersOfXeric = false;

				if (client.getGameState() == GameState.LOGGED_IN)
					uploadScene();

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
		isRunning = false;

		FileWatcher.destroy();
		developerTools.deactivate();

		clientThread.invoke(() -> {
			var scene = client.getScene();
			if (scene != null)
				scene.setMinLevel(0);

			client.setGpu(false);
			client.setDrawCallbacks(null);
			client.setUnlockedFps(false);

			modelPusher.shutDown();
			lightManager.shutDown();
			environmentManager.reset();

			if (lwjglInitialized) {
				textureManager.shutDown();

				destroyBuffers();
				destroyInterfaceTexture();
				destroyPrograms();
				destroyVaos();
				destroyAAFbo();
				destroyShadowMapFbo();

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

			if (nextSceneContext != null)
				nextSceneContext.destroy();
			nextSceneContext = null;

			if (modelBufferSmall != null)
				modelBufferSmall.destroy();
			modelBufferSmall = null;

			if (modelBufferLarge != null)
				modelBufferLarge.destroy();
			modelBufferLarge = null;

			if (modelBufferUnordered != null)
				modelBufferUnordered.destroy();
			modelBufferUnordered = null;

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
				log.error("Error while stopping 117HD", ex);
			}
		});

		shutDown();
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
			// particularly Intel drivers on MacOS
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
		configShadowMode = config.shadowMode();
		configShadowsEnabled = configShadowMode != ShadowMode.OFF;

		String versionHeader = OSType.getOSType() == OSType.Linux ? LINUX_VERSION_HEADER : WINDOWS_VERSION_HEADER;
		Template template = new Template()
			.addInclude("VERSION_HEADER", versionHeader)
			.define("UI_SCALING_MODE", config.uiScalingMode().getMode())
			.define("COLOR_BLINDNESS", config.colorBlindness())
			.define("MATERIAL_CONSTANTS", () -> {
				StringBuilder include = new StringBuilder();
				for (Material m : Material.values())
				{
					include
						.append("#define MAT_")
						.append(m.name().toUpperCase())
						.append(" getMaterial(")
						.append(m.ordinal())
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
			.addIncludePath(SHADER_PATH);

		glProgram = PROGRAM.compile(template);
		glUiProgram = UI_PROGRAM.compile(template);

		switch (configShadowMode) {
			case FAST:
				glShadowProgram = SHADOW_PROGRAM_FAST.compile(template);
				break;
			case DETAILED:
				glShadowProgram = SHADOW_PROGRAM_DETAILED.compile(template);
				break;
		}

		if (computeMode == ComputeMode.OPENCL)
		{
			openCLManager.initPrograms();
		}
		else
		{
			glLargeComputeProgram = COMPUTE_PROGRAM.compile(template);
			glSmallComputeProgram = SMALL_COMPUTE_PROGRAM.compile(template);
			glUnorderedComputeProgram = UNORDERED_COMPUTE_PROGRAM.compile(template);
		}

		initUniforms();

		// Bind texture samplers before validating, else the validation fails
		glUseProgram(glProgram);
		glUniform1i(uniTextureArray, 1);
		glUniform1i(uniShadowMap, 2);

		// Validate program
		glValidateProgram(glProgram);
		if (glGetProgrami(glProgram, GL_VALIDATE_STATUS) == GL_FALSE)
		{
			String err = glGetProgramInfoLog(glProgram);
			throw new ShaderException(err);
		}

		glUseProgram(glUiProgram);
		glUniform1i(uniUiTexture, 0);

		glUseProgram(0);
	}

	private void initUniforms()
	{
		uniProjectionMatrix = glGetUniformLocation(glProgram, "projectionMatrix");
		uniLightProjectionMatrix = glGetUniformLocation(glProgram, "lightProjectionMatrix");
		uniShadowMap = glGetUniformLocation(glProgram, "shadowMap");
		uniSaturation = glGetUniformLocation(glProgram, "saturation");
		uniContrast = glGetUniformLocation(glProgram, "contrast");
		uniUseFog = glGetUniformLocation(glProgram, "useFog");
		uniFogColor = glGetUniformLocation(glProgram, "fogColor");
		uniFogDepth = glGetUniformLocation(glProgram, "fogDepth");
		uniWaterColorLight = glGetUniformLocation(glProgram, "waterColorLight");
		uniWaterColorMid = glGetUniformLocation(glProgram, "waterColorMid");
		uniWaterColorDark = glGetUniformLocation(glProgram, "waterColorDark");
		uniDrawDistance = glGetUniformLocation(glProgram, "drawDistance");
		uniAmbientStrength = glGetUniformLocation(glProgram, "ambientStrength");
		uniAmbientColor = glGetUniformLocation(glProgram, "ambientColor");
		uniLightStrength = glGetUniformLocation(glProgram, "lightStrength");
		uniLightColor = glGetUniformLocation(glProgram, "lightColor");
		uniUnderglowStrength = glGetUniformLocation(glProgram, "underglowStrength");
		uniUnderglowColor = glGetUniformLocation(glProgram, "underglowColor");
		uniGroundFogStart = glGetUniformLocation(glProgram, "groundFogStart");
		uniGroundFogEnd = glGetUniformLocation(glProgram, "groundFogEnd");
		uniGroundFogOpacity = glGetUniformLocation(glProgram, "groundFogOpacity");
		uniLightningBrightness = glGetUniformLocation(glProgram, "lightningBrightness");
		uniPointLightsCount = glGetUniformLocation(glProgram, "pointLightsCount");
		uniColorBlindnessIntensity = glGetUniformLocation(glProgram, "colorBlindnessIntensity");
		uniLightDir = glGetUniformLocation(glProgram, "lightDir");
		uniShadowMaxBias = glGetUniformLocation(glProgram, "shadowMaxBias");
		uniShadowsEnabled = glGetUniformLocation(glProgram, "shadowsEnabled");
		uniUnderwaterEnvironment = glGetUniformLocation(glProgram, "underwaterEnvironment");
		uniUnderwaterCaustics = glGetUniformLocation(glProgram, "underwaterCaustics");
		uniUnderwaterCausticsColor = glGetUniformLocation(glProgram, "underwaterCausticsColor");
		uniUnderwaterCausticsStrength = glGetUniformLocation(glProgram, "underwaterCausticsStrength");
		uniTextureArray = glGetUniformLocation(glProgram, "textureArray");
		uniElapsedTime = glGetUniformLocation(glProgram, "elapsedTime");

		uniUiTexture = glGetUniformLocation(glUiProgram, "uiTexture");
		uniTexTargetDimensions = glGetUniformLocation(glUiProgram, "targetDimensions");
		uniTexSourceDimensions = glGetUniformLocation(glUiProgram, "sourceDimensions");
		uniUiColorBlindnessIntensity = glGetUniformLocation(glUiProgram, "colorBlindnessIntensity");
		uniUiAlphaOverlay = glGetUniformLocation(glUiProgram, "alphaOverlay");

		if (computeMode == ComputeMode.OPENGL)
		{
			uniBlockCameraComputeSmall = glGetUniformBlockIndex(glSmallComputeProgram, "CameraUniforms");
			uniBlockCameraComputeLarge = glGetUniformBlockIndex(glLargeComputeProgram, "CameraUniforms");
		}
		uniBlockCamera = glGetUniformBlockIndex(glProgram, "CameraUniforms");
		uniBlockMaterials = glGetUniformBlockIndex(glProgram, "MaterialUniforms");
		uniBlockWaterTypes = glGetUniformBlockIndex(glProgram, "WaterTypeUniforms");
		uniBlockPointLights = glGetUniformBlockIndex(glProgram, "PointLightUniforms");

		// Shadow program uniforms
		switch (configShadowMode)
		{
			case DETAILED:
				int uniShadowBlockMaterials = glGetUniformBlockIndex(glShadowProgram, "MaterialUniforms");
				int uniShadowTextureArray = glGetUniformLocation(glShadowProgram, "textureArray");
				glUseProgram(glShadowProgram);
				glUniform1i(uniShadowTextureArray, 1);
				glUniformBlockBinding(glShadowProgram, uniShadowBlockMaterials, 1);
				uniShadowElapsedTime = glGetUniformLocation(glShadowProgram, "elapsedTime");
				// fall-through
			case FAST:
				uniShadowLightProjectionMatrix = glGetUniformLocation(glShadowProgram, "lightProjectionMatrix");
		}

		// Initialize uniform buffers that may depend on compile-time settings
		initCameraUniformBuffer();
		initLightsUniformBuffer();
	}

	private void destroyPrograms() {
		if (glProgram != 0)
			glDeleteProgram(glProgram);
		glProgram = 0;

		if (glUiProgram != 0)
			glDeleteProgram(glUiProgram);
		glUiProgram = 0;

		if (glShadowProgram != 0)
			glDeleteProgram(glShadowProgram);
		glShadowProgram = 0;

		if (computeMode == ComputeMode.OPENGL) {
			if (glLargeComputeProgram != 0)
				glDeleteProgram(glLargeComputeProgram);
			glLargeComputeProgram = 0;

			if (glSmallComputeProgram != 0)
				glDeleteProgram(glSmallComputeProgram);
			glSmallComputeProgram = 0;

			if (glUnorderedComputeProgram != 0)
				glDeleteProgram(glUnorderedComputeProgram);
			glUnorderedComputeProgram = 0;
		} else {
			openCLManager.destroyPrograms();
		}
	}

	public void recompilePrograms() {
		try {
			destroyPrograms();
			initPrograms();
		} catch (ShaderException | IOException ex) {
			log.error("Failed to recompile shader program:", ex);
			stopPlugin();
		}
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

		initGlBuffer(hStagingBufferVertices, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);
		initGlBuffer(hStagingBufferUvs, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);
		initGlBuffer(hStagingBufferNormals, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);

		initGlBuffer(hModelBufferLarge, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);
		initGlBuffer(hModelBufferSmall, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);
		initGlBuffer(hModelBufferUnordered, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);

		initGlBuffer(hRenderBufferVertices, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_WRITE_ONLY);
		initGlBuffer(hRenderBufferUvs, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_WRITE_ONLY);
		initGlBuffer(hRenderBufferNormals, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_WRITE_ONLY);
	}

	private void initGlBuffer(GLBuffer glBuffer, int target, int glUsage, int clUsage) {
		glBuffer.glBufferId = glGenBuffers();
		// Initialize both GL and CL buffers to dummy buffers of a single byte,
		// to ensure that valid buffers are given to compute dispatches.
		// This is particularly important on Apple M2 Max, where an uninitialized buffer leads to a crash
		updateBuffer(glBuffer, target, 1, glUsage, clUsage);
	}

	private void destroyBuffers()
	{
		destroyGlBuffer(hUniformBufferCamera);
		destroyGlBuffer(hUniformBufferMaterials);
		destroyGlBuffer(hUniformBufferWaterTypes);
		destroyGlBuffer(hUniformBufferLights);

		destroyGlBuffer(hStagingBufferVertices);
		destroyGlBuffer(hStagingBufferUvs);
		destroyGlBuffer(hStagingBufferNormals);

		destroyGlBuffer(hModelBufferLarge);
		destroyGlBuffer(hModelBufferSmall);
		destroyGlBuffer(hModelBufferUnordered);

		destroyGlBuffer(hRenderBufferVertices);
		destroyGlBuffer(hRenderBufferUvs);
		destroyGlBuffer(hRenderBufferNormals);
	}

	private void destroyGlBuffer(GLBuffer glBuffer)
	{
		glBuffer.size = -1;

		if (glBuffer.glBufferId != 0)
		{
			glDeleteBuffers(glBuffer.glBufferId);
			glBuffer.glBufferId = 0;
		}

		if (glBuffer.clBuffer != 0)
		{
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
	}

	public void updateMaterialUniformBuffer(float[] textureAnimations)
	{
		ByteBuffer buffer = BufferUtils.createByteBuffer(Material.values().length * 20 * SCALAR_BYTES);
		for (Material material : Material.values())
		{
			material = textureManager.getEffectiveMaterial(material);
			int index = textureManager.getTextureIndex(material);
			float scrollSpeedX = material.scrollSpeed[0];
			float scrollSpeedY = material.scrollSpeed[1];
			if (index != -1)
			{
				scrollSpeedX += textureAnimations[index * 2];
				scrollSpeedY += textureAnimations[index * 2 + 1];
			}
			buffer
				.putInt(index)
				.putInt(textureManager.getTextureIndex(material.normalMap))
				.putInt(textureManager.getTextureIndex(material.displacementMap))
				.putInt(textureManager.getTextureIndex(material.roughnessMap))
				.putInt(textureManager.getTextureIndex(material.ambientOcclusionMap))
				.putInt(textureManager.getTextureIndex(material.flowMap))
				.putInt(material.overrideBaseColor ? 1 : 0)
				.putInt(material.unlit ? 1 : 0)
				.putFloat(material.brightness)
				.putFloat(material.displacementScale)
				.putFloat(material.specularStrength)
				.putFloat(material.specularGloss)
				.putFloat(material.flowMapStrength)
				.putFloat(0) // pad vec2
				.putFloat(material.flowMapDuration[0])
				.putFloat(material.flowMapDuration[1])
				.putFloat(scrollSpeedX)
				.putFloat(scrollSpeedY)
				.putFloat(material.textureScale[0])
				.putFloat(material.textureScale[1]);
				// vec4 aligned
		}
		buffer.flip();

		updateBuffer(hUniformBufferMaterials, GL_UNIFORM_BUFFER, buffer, GL_STATIC_DRAW, CL_MEM_READ_ONLY);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);
	}

	public void updateWaterTypeUniformBuffer()
	{
		ByteBuffer buffer = BufferUtils.createByteBuffer(WaterType.values().length * 28 * SCALAR_BYTES);
		for (WaterType type : WaterType.values())
		{
			buffer
				.putInt(type.flat ? 1 : 0)
				.putFloat(type.specularStrength)
				.putFloat(type.specularGloss)
				.putFloat(type.normalStrength)
				.putFloat(type.baseOpacity)
				.putInt(type.hasFoam ? 1 : 0)
				.putFloat(type.duration)
				.putFloat(type.fresnelAmount)
				.putFloat(type.surfaceColor[0])
				.putFloat(type.surfaceColor[1])
				.putFloat(type.surfaceColor[2])
				.putFloat(0) // pad vec4
				.putFloat(type.foamColor[0])
				.putFloat(type.foamColor[1])
				.putFloat(type.foamColor[2])
				.putFloat(0) // pad vec4
				.putFloat(type.depthColor[0])
				.putFloat(type.depthColor[1])
				.putFloat(type.depthColor[2])
				.putFloat(0) // pad vec4
				.putFloat(type.causticsStrength)
				.putInt(textureManager.getTextureIndex(type.normalMap))
				.putInt(textureManager.getTextureIndex(Material.WATER_FOAM))
				.putInt(textureManager.getTextureIndex(Material.WATER_FLOW_MAP))
				.putInt(textureManager.getTextureIndex(Material.UNDERWATER_FLOW_MAP))
				.putFloat(0).putFloat(0).putFloat(0); // pad vec4
		}
		buffer.flip();

		updateBuffer(hUniformBufferWaterTypes, GL_UNIFORM_BUFFER, buffer, GL_STATIC_DRAW, CL_MEM_READ_ONLY);
	}

	private void initLightsUniformBuffer()
	{
		// Allowing a buffer size of zero causes Apple M1/M2 to revert to software rendering
		uniformBufferLights = BufferUtils.createByteBuffer(Math.max(1, configMaxDynamicLights) * 8 * SCALAR_BYTES);
		updateBuffer(hUniformBufferLights, GL_UNIFORM_BUFFER, uniformBufferLights, GL_STREAM_DRAW, CL_MEM_READ_ONLY);
	}

	private void initAAFbo(int width, int height, int aaSamples)
	{
		if (OSType.getOSType() != OSType.MacOS)
		{
			final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
			final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

			width = getScaledValue(transform.getScaleX(), width);
			height = getScaledValue(transform.getScaleY(), height);
		}

		// Create and bind the FBO
		fboSceneHandle = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboSceneHandle);

		// Create color render buffer
		rboSceneHandle = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboSceneHandle);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, aaSamples, GL_RGBA, width, height);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rboSceneHandle);

		// Reset
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		glBindRenderbuffer(GL_RENDERBUFFER, 0);
	}

	private void destroyAAFbo()
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

	private void destroyShadowMapFbo()
	{
		if (texShadowMap != 0)
		{
			glDeleteTextures(texShadowMap);
			texShadowMap = 0;
		}

		if (fboShadowMap != 0)
		{
			glDeleteFramebuffers(fboShadowMap);
			fboShadowMap = 0;
		}
	}

	@Override
	public void drawScene(int cameraX, int cameraY, int cameraZ, int cameraPitch, int cameraYaw, int plane)
	{
		final Scene scene = client.getScene();
		if (sceneContext == null || sceneContext.scene != scene)
		{
			log.error("Scene being drawn is not the current scene context", new Throwable());
			stopPlugin();
			return;
		}

		scene.setDrawDistance(getDrawDistance());

		yaw = client.getCameraYaw();
		pitch = client.getCameraPitch();
		viewportOffsetX = client.getViewportXOffset();
		viewportOffsetY = client.getViewportYOffset();

		// Update the camera target only when not loading, to keep drawing correct shadows while loading
		if (client.getGameState().getState() > GameState.LOADING.getState())
			camTarget = getCameraFocalPoint();

		if (!shouldSkipModelUpdates) {
			// Only reset the target buffer offset right before drawing the scene. That way if there are frames
			// after this that don't involve a scene draw, like during LOADING/HOPPING/CONNECTION_LOST, we can
			// still redraw the previous frame's scene to emulate the client behavior of not painting over the
			// viewport buffer.
			renderBufferOffset = 0;
		}

		// UBO. Only the first 32 bytes get modified here, the rest is the constant sin/cos table.
		// We can reuse the vertex buffer since it isn't used yet.
		sceneContext.stagingBufferVertices.clear();
		sceneContext.stagingBufferVertices.ensureCapacity(32);
		IntBuffer uniformBuf = sceneContext.stagingBufferVertices.getBuffer();
		uniformBuf
			.put(yaw)
			.put(pitch)
			.put(client.getCenterX())
			.put(client.getCenterY())
			.put(client.getScale())
			.put(cameraX)
			.put(cameraY)
			.put(cameraZ);
		uniformBuf.flip();

		glBindBuffer(GL_UNIFORM_BUFFER, hUniformBufferCamera.glBufferId);
		glBufferSubData(GL_UNIFORM_BUFFER, 0, uniformBuf);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);

		glBindBufferBase(GL_UNIFORM_BUFFER, 0, hUniformBufferCamera.glBufferId);
		uniformBuf.clear();

		// Bind materials UBO
		glBindBufferBase(GL_UNIFORM_BUFFER, 1, hUniformBufferMaterials.glBufferId);
		glBindBufferBase(GL_UNIFORM_BUFFER, 2, hUniformBufferWaterTypes.glBufferId);

		// Update lights UBO
		uniformBufferLights.clear();
		ArrayList<SceneLight> visibleLights = lightManager.getVisibleLights(getDrawDistance(), configMaxDynamicLights);
		sceneContext.visibleLightCount = visibleLights.size();
		for (SceneLight light : visibleLights)
		{
			uniformBufferLights.putInt(light.x);
			uniformBufferLights.putInt(light.y);
			uniformBufferLights.putInt(light.z);
			uniformBufferLights.putFloat(light.currentSize);
			uniformBufferLights.putFloat(light.currentColor[0]);
			uniformBufferLights.putFloat(light.currentColor[1]);
			uniformBufferLights.putFloat(light.currentColor[2]);
			uniformBufferLights.putFloat(light.currentStrength);
		}
		uniformBufferLights.flip();
		if (configMaxDynamicLights > 0)
		{
			glBindBuffer(GL_UNIFORM_BUFFER, hUniformBufferLights.glBufferId);
			glBufferSubData(GL_UNIFORM_BUFFER, 0, uniformBufferLights);
			glBindBuffer(GL_UNIFORM_BUFFER, 0);
		}
		uniformBufferLights.clear();

		glBindBufferBase(GL_UNIFORM_BUFFER, 3, hUniformBufferLights.glBufferId);
	}

	@Override
	public void postDrawScene() {
		if (!isRunning)
			return;

		// The client only updates animations once per client tick, so we can skip updating geometry buffers,
		// but the compute shaders should still be executed in case the camera angle has changed.
		// Technically we could skip compute shaders as well when the camera is unchanged,
		// but it would only lead to micro stuttering when rotating the camera, compared to no rotation.
		if (!shouldSkipModelUpdates) {
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
			modelBufferUnordered.flip();
			modelBufferSmall.flip();
			modelBufferLarge.flip();
			updateBuffer(hModelBufferLarge, GL_ARRAY_BUFFER, modelBufferLarge.getBuffer(), GL_STREAM_DRAW, CL_MEM_READ_ONLY);
			updateBuffer(hModelBufferSmall, GL_ARRAY_BUFFER, modelBufferSmall.getBuffer(), GL_STREAM_DRAW, CL_MEM_READ_ONLY);
			updateBuffer(hModelBufferUnordered, GL_ARRAY_BUFFER, modelBufferUnordered.getBuffer(), GL_STREAM_DRAW, CL_MEM_READ_ONLY);
			modelBufferUnordered.clear();
			modelBufferSmall.clear();
			modelBufferLarge.clear();

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

			// Once geometry buffers have been updated, they can be reused until the client actually modifies the scene
			shouldSkipModelUpdates = true;
		}

		if (computeMode == ComputeMode.OPENCL) {
			// The docs for clEnqueueAcquireGLObjects say all pending GL operations must be completed before calling
			// clEnqueueAcquireGLObjects, and recommends calling glFinish() as the only portable way to do that.
			// However, no issues have been observed from not calling it, and so will leave disabled for now.
			// glFinish();

			openCLManager.compute(
				hUniformBufferCamera,
				numModelsUnordered, numModelsSmall, numModelsLarge,
				hModelBufferUnordered, hModelBufferSmall, hModelBufferLarge,
				hStagingBufferVertices, hStagingBufferUvs, hStagingBufferNormals,
				hRenderBufferVertices, hRenderBufferUvs, hRenderBufferNormals);
		}
		else
		{
			/*
			 * Compute is split into three separate programs: 'unordered', 'small', and 'large'
			 * to save on GPU resources. Small will sort <= 512 faces, large will do <= 4096.
			 */

			// Bind UBO to compute programs
			glUniformBlockBinding(glSmallComputeProgram, uniBlockCameraComputeSmall, 0);
			glUniformBlockBinding(glLargeComputeProgram, uniBlockCameraComputeLarge, 0);

			// Bind shared buffers
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, hStagingBufferVertices.glBufferId);
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, hStagingBufferUvs.glBufferId);
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, hStagingBufferNormals.glBufferId);
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, hRenderBufferVertices.glBufferId);
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, hRenderBufferUvs.glBufferId);
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, hRenderBufferNormals.glBufferId);

			// unordered
			glUseProgram(glUnorderedComputeProgram);
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, hModelBufferUnordered.glBufferId);
			glDispatchCompute(numModelsUnordered, 1, 1);

			// small
			glUseProgram(glSmallComputeProgram);
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, hModelBufferSmall.glBufferId);
			glDispatchCompute(numModelsSmall, 1, 1);

			// large
			glUseProgram(glLargeComputeProgram);
			glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, hModelBufferLarge.glBufferId);
			glDispatchCompute(numModelsLarge, 1, 1);
		}

		checkGLErrors();

		numModelsUnordered = numModelsSmall = numModelsLarge = 0;
	}

	@Override
	public void drawScenePaint(
		int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z,
		SceneTilePaint paint, int tileZ, int tileX, int tileY,
		int zoom, int centerX, int centerY
	) {
		if (shouldSkipModelUpdates || paint.getBufferLen() <= 0)
			return;

		final int localX = tileX * LOCAL_TILE_SIZE;
		final int localY = 0;
		final int localZ = tileY * LOCAL_TILE_SIZE;

		GpuIntBuffer b = modelBufferUnordered;
		b.ensureCapacity(16);
		IntBuffer buffer = b.getBuffer();

		int bufferLength = paint.getBufferLen();

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

			++numModelsUnordered;

			buffer.put(paint.getBufferOffset() + bufferLength);
			buffer.put(paint.getUvBufferOffset() + bufferLength);
			buffer.put(bufferLength / 3);
			buffer.put(renderBufferOffset);
			buffer.put(0);
			buffer.put(localX).put(localY).put(localZ);

			renderBufferOffset += bufferLength;
		}

		++numModelsUnordered;

		buffer.put(paint.getBufferOffset());
		buffer.put(paint.getUvBufferOffset());
		buffer.put(bufferLength / 3);
		buffer.put(renderBufferOffset);
		buffer.put(0);
		buffer.put(localX).put(localY).put(localZ);

		renderBufferOffset += bufferLength;
	}

	public void initShaderHotswapping() {
		SHADER_PATH.watch("\\.(glsl|cl)$", path -> {
			log.info("Reloading shader: {}", path);
			clientThread.invoke(this::recompilePrograms);
		});
	}

	@Override
	public void drawSceneModel(
		int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z,
		SceneTileModel model, int tileZ, int tileX, int tileY,
		int zoom, int centerX, int centerY
	) {
		if (shouldSkipModelUpdates || model.getBufferLen() <= 0)
			return;

		final int localX = tileX * LOCAL_TILE_SIZE;
		final int localY = 0;
		final int localZ = tileY * LOCAL_TILE_SIZE;

		GpuIntBuffer b = modelBufferUnordered;
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

			++numModelsUnordered;

			buffer.put(model.getBufferOffset() + bufferLength);
			buffer.put(model.getUvBufferOffset() + bufferLength);
			buffer.put(bufferLength / 3);
			buffer.put(renderBufferOffset);
			buffer.put(0);
			buffer.put(localX).put(localY).put(localZ);

			renderBufferOffset += bufferLength;
		}

		++numModelsUnordered;

		buffer.put(model.getBufferOffset());
		buffer.put(model.getUvBufferOffset());
		buffer.put(bufferLength / 3);
		buffer.put(renderBufferOffset);
		buffer.put(0);
		buffer.put(localX).put(localY).put(localZ);

		renderBufferOffset += bufferLength;
	}

	private void prepareInterfaceTexture(int canvasWidth, int canvasHeight)
	{
		if (canvasWidth != lastCanvasWidth || canvasHeight != lastCanvasHeight)
		{
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
		if (mappedBuffer == null)
		{
			log.error("Unable to map interface PBO. Skipping UI...");
		}
		else
		{
			mappedBuffer.asIntBuffer().put(pixels, 0, width * height);
			glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
			glBindTexture(GL_TEXTURE_2D, interfaceTexture);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		}
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	@Override
	public void draw(int overlayColor)
	{
		// reset the plugin if the last frame took >1min to draw
		// why? because the user's computer was probably suspended and the buffers are no longer valid
		if (System.currentTimeMillis() - lastFrameTime > 60000) {
			log.debug("resetting the plugin after probable OS suspend");
			shutDown();
			startUp();
			return;
		}

		// shader variables for water, lava animations
		long frameDeltaTime = System.currentTimeMillis() - lastFrameTime;
		// if system time changes dramatically between frames,
		// very large values may be added to elapsedTime,
		// which causes floating point precision to break down,
		// leading to texture animations and water appearing frozen
		if (Math.abs(frameDeltaTime) > 10000)
			frameDeltaTime = 16;
		elapsedTime += frameDeltaTime / 1000f;
		lastFrameTime = System.currentTimeMillis();

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		try {
			prepareInterfaceTexture(canvasWidth, canvasHeight);
		} catch (Exception ex) {
			// Fixes: https://github.com/runelite/runelite/issues/12930
			// Gracefully Handle loss of opengl buffers and context
			log.warn("prepareInterfaceTexture exception", ex);
			shutDown();
			startUp();
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
			try {
				WorldPoint targetWorldPosition = sceneContext.localToWorld(new LocalPoint(camTarget[0], camTarget[1]), client.getPlane());
				environmentManager.update(sceneContext, targetWorldPosition);
				lightManager.update(sceneContext);
			} catch (Exception ex) {
				log.error("Error while updating environment or lights:", ex);
				stopPlugin();
				return;
			}

			// lazy init textures as they may not be loaded at plugin start.
			textureManager.ensureTexturesLoaded(textureProvider);

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

			// Before reading the SSBOs written to from postDrawScene() we must insert a barrier
			if (computeMode == ComputeMode.OPENCL) {
				openCLManager.finish();
			} else {
				glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
			}

			glBindVertexArray(vaoSceneHandle);

			float lightPitch = (float) Math.toRadians(environmentManager.currentLightPitch);
			float lightYaw = (float) Math.toRadians(environmentManager.currentLightYaw);
			float[] lightViewMatrix = Mat4.rotateX(lightPitch);
			Mat4.mul(lightViewMatrix, Mat4.rotateY(-lightYaw));

			float[] lightProjectionMatrix = Mat4.identity();
			if (configShadowsEnabled && fboShadowMap != 0 && environmentManager.currentDirectionalStrength > 0) {
				// Render to the shadow depth map
				glViewport(0, 0, shadowMapResolution, shadowMapResolution);
				glBindFramebuffer(GL_FRAMEBUFFER, fboShadowMap);
				glClearDepthf(1);
				glClear(GL_DEPTH_BUFFER_BIT);
				glDepthFunc(GL_LEQUAL);

				glUseProgram(glShadowProgram);

				final int camX = camTarget[0];
				final int camY = camTarget[1];
				final int camZ = camTarget[2];

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
				Mat4.mul(lightProjectionMatrix, Mat4.translate(-(width / 2f + west), -camZ, -(height / 2f + south)));
				glUniformMatrix4fv(uniShadowLightProjectionMatrix, false, lightProjectionMatrix);

				// bind uniforms
				if (configShadowMode == ShadowMode.DETAILED)
					glUniform1f(uniShadowElapsedTime, elapsedTime);

				glEnable(GL_CULL_FACE);
				glEnable(GL_DEPTH_TEST);

				// Draw with buffers bound to scene VAO
				glDrawArrays(GL_TRIANGLES, 0, renderBufferOffset);

				glDisable(GL_CULL_FACE);
				glDisable(GL_DEPTH_TEST);

				glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

				glUseProgram(0);
			}

			glDpiAwareViewport(renderWidthOff, renderCanvasHeight - renderViewportHeight - renderHeightOff, renderViewportWidth, renderViewportHeight);

			glUseProgram(glProgram);

			// Setup anti-aliasing
			final AntiAliasingMode antiAliasingMode = config.antiAliasingMode();
			final boolean aaEnabled = antiAliasingMode != AntiAliasingMode.DISABLED;
			if (aaEnabled) {
				glEnable(GL_MULTISAMPLE);

				final Dimension stretchedDimensions = client.getStretchedDimensions();

				final int stretchedCanvasWidth = client.isStretchedEnabled() ? stretchedDimensions.width : canvasWidth;
				final int stretchedCanvasHeight = client.isStretchedEnabled() ? stretchedDimensions.height : canvasHeight;

				// Re-create fbo
				if (
					lastStretchedCanvasWidth != stretchedCanvasWidth ||
					lastStretchedCanvasHeight != stretchedCanvasHeight ||
					lastAntiAliasingMode != antiAliasingMode
				) {
					destroyAAFbo();

					// Bind default FBO to check whether anti-aliasing is forced
					glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
					final int forcedAASamples = glGetInteger(GL_SAMPLES);
					final int maxSamples = glGetInteger(GL_MAX_SAMPLES);
					final int samples = forcedAASamples != 0 ? forcedAASamples :
						Math.min(antiAliasingMode.getSamples(), maxSamples);

					log.debug("AA samples: {}, max samples: {}, forced samples: {}", samples, maxSamples, forcedAASamples);

					initAAFbo(stretchedCanvasWidth, stretchedCanvasHeight, samples);

					lastStretchedCanvasWidth = stretchedCanvasWidth;
					lastStretchedCanvasHeight = stretchedCanvasHeight;
				}

				glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboSceneHandle);
			} else {
				glDisable(GL_MULTISAMPLE);
				destroyAAFbo();
			}

			lastAntiAliasingMode = antiAliasingMode;

			// Clear scene
			float[] fogColor = ColorUtils.linearToSrgb(environmentManager.currentFogColor);
			glClearColor(fogColor[0], fogColor[1], fogColor[2], 1f);
			glClear(GL_COLOR_BUFFER_BIT);

			final int drawDistance = getDrawDistance();
			int fogDepth = 0;
			switch (config.fogDepthMode()) {
				case USER_DEFINED:
					fogDepth = config.fogDepth() * 10;
					break;
				case DYNAMIC:
					fogDepth = environmentManager.currentFogDepth;
					break;
			}
			glUniform1i(uniUseFog, fogDepth > 0 ? 1 : 0);
			glUniform1i(uniFogDepth, fogDepth);
			glUniform3fv(uniFogColor, fogColor);

			glUniform1i(uniDrawDistance, drawDistance * LOCAL_TILE_SIZE);
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
			glUniform1i(uniPointLightsCount, sceneContext == null ? 0 : sceneContext.visibleLightCount);
			glUniform1f(uniLightningBrightness, environmentManager.getLightningBrightness());

			glUniform1f(uniSaturation, config.saturation() / 100f);
			glUniform1f(uniContrast, config.contrast() / 100f);
			glUniform1i(uniUnderwaterEnvironment, environmentManager.isUnderwater() ? 1 : 0);
			glUniform1i(uniUnderwaterCaustics, config.underwaterCaustics() ? 1 : 0);
			glUniform3fv(uniUnderwaterCausticsColor, environmentManager.currentUnderwaterCausticsColor);
			glUniform1f(uniUnderwaterCausticsStrength, environmentManager.currentUnderwaterCausticsStrength);

			// Extract the 3rd column from the light view matrix (the float array is column-major)
			// This produces the light's forward direction vector in world space
			glUniform3f(uniLightDir, lightViewMatrix[2], lightViewMatrix[6], lightViewMatrix[10]);

			// use a curve to calculate max bias value based on the density of the shadow map
			float shadowPixelsPerTile = (float) shadowMapResolution / config.shadowDistance().getValue();
			float maxBias = 26f * (float) Math.pow(0.925f, (0.4f * shadowPixelsPerTile - 10f)) + 13f;
			glUniform1f(uniShadowMaxBias, maxBias / 10000f);

			glUniform1i(uniShadowsEnabled, configShadowsEnabled ? 1 : 0);

			// Calculate projection matrix
			float[] projectionMatrix = Mat4.scale(client.getScale(), client.getScale(), 1);
			Mat4.mul(projectionMatrix, Mat4.projection(viewportWidth, viewportHeight, 50));
			Mat4.mul(projectionMatrix, Mat4.rotateX((float) (pitch * UNIT - Math.PI)));
			Mat4.mul(projectionMatrix, Mat4.rotateY((float) (yaw * UNIT)));
			Mat4.mul(projectionMatrix, Mat4.translate(-client.getCameraX2(), -client.getCameraY2(), -client.getCameraZ2()));
			glUniformMatrix4fv(uniProjectionMatrix, false, projectionMatrix);

			// Bind directional light projection matrix
			glUniformMatrix4fv(uniLightProjectionMatrix, false, lightProjectionMatrix);

			// Bind uniforms
			glUniformBlockBinding(glProgram, uniBlockCamera, 0);
			glUniformBlockBinding(glProgram, uniBlockMaterials, 1);
			glUniformBlockBinding(glProgram, uniBlockWaterTypes, 2);
			glUniformBlockBinding(glProgram, uniBlockPointLights, 3);
			glUniform1f(uniElapsedTime, elapsedTime);

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

			glDisable(GL_BLEND);
			glDisable(GL_CULL_FACE);

			glUseProgram(0);

			if (aaEnabled) {
				int width = lastStretchedCanvasWidth;
				int height = lastStretchedCanvasHeight;

				if (OSType.getOSType() != OSType.MacOS) {
					final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
					final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

					width = getScaledValue(transform.getScaleX(), width);
					height = getScaledValue(transform.getScaleY(), height);
				}

				glBindFramebuffer(GL_READ_FRAMEBUFFER, fboSceneHandle);
				glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
				glBlitFramebuffer(
					0, 0, width, height,
					0, 0, width, height,
					GL_COLOR_BUFFER_BIT, GL_NEAREST
				);

				// Reset
				glBindFramebuffer(GL_READ_FRAMEBUFFER, awtContext.getFramebuffer(false));
			}

			frameModelInfoMap.clear();
		} else {
			glClearColor(0, 0, 0, 1f);
			glClear(GL_COLOR_BUFFER_BIT);
		}

		// Texture on UI
		drawUi(overlayColor, canvasHeight, canvasWidth);

		try {
			awtContext.swapBuffers();
			drawManager.processDrawComplete(this::screenshot);
		} catch (Exception ex) {
			log.error("Unable to swap buffers:", ex);
		}

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

		checkGLErrors();
	}

	private void drawUi(int overlayColor, final int canvasHeight, final int canvasWidth) {
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
		glUniform4fv(uniUiAlphaOverlay, ColorUtils.unpackARGB(overlayColor));

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
		if (client.isStretchedEnabled()) {
			// GL_NEAREST makes sampling for bicubic/xBR simpler, so it should be used whenever linear isn't
			final int function = config.uiScalingMode() == UIScalingMode.LINEAR ? GL_LINEAR : GL_NEAREST;
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, function);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, function);
		}

		// Texture on UI
		glBindVertexArray(vaoUiHandle);
		glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

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

		if (OSType.getOSType() != OSType.MacOS)
		{
			final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
			final AffineTransform t = graphicsConfiguration.getDefaultTransform();
			width = getScaledValue(t.getScaleX(), width);
			height = getScaledValue(t.getScaleY(), height);
		}

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
		if (!isRunning)
			return;

		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN) {
			renderBufferOffset = 0;
			sceneContext = null;
			hasLoggedIn = false;
			environmentManager.reset();
		}
	}

	public void uploadScene()
	{
		assert client.isClientThread() : "Loading a scene is unsafe while the client can simultaneously initiate a scene load";
		Scene scene = client.getScene();
		loadScene(scene);
		swapScene(scene);
	}

	public void loadScene(Scene scene)
	{
		if (nextSceneContext != null)
		{
			SceneContext handle = nextSceneContext;
			nextSceneContext = null;
			handle.destroy();
		}

		nextSceneContext = new SceneContext(scene, sceneContext);
		proceduralGenerator.generateSceneData(nextSceneContext);
		environmentManager.loadSceneEnvironments(nextSceneContext);
		lightManager.loadSceneLights(nextSceneContext);
		sceneUploader.upload(nextSceneContext);
	}

	public void swapScene(Scene scene)
	{
		if (nextSceneContext == null)
		{
			log.error("No new scene to swap to", new Throwable());
			stopPlugin();
			return;
		}

		if (sceneContext != null)
		{
			// Copy over NPC and projectile lights
			for (SceneLight light : sceneContext.lights)
				if (light.npc != null || light.projectile != null)
					nextSceneContext.lights.add(light);

			sceneContext.destroy();
		}

		sceneContext = nextSceneContext;
		nextSceneContext = null;

		dynamicOffsetVertices = sceneContext.getVertexOffset();
		dynamicOffsetUvs = sceneContext.getUvOffset();

		sceneContext.stagingBufferVertices.flip();
		sceneContext.stagingBufferUvs.flip();
		sceneContext.stagingBufferNormals.flip();
		updateBuffer(hStagingBufferVertices, GL_ARRAY_BUFFER,
			sceneContext.stagingBufferVertices.getBuffer(),
			GL_STREAM_DRAW, CL_MEM_READ_ONLY);
		updateBuffer(hStagingBufferUvs, GL_ARRAY_BUFFER,
			sceneContext.stagingBufferUvs.getBuffer(),
			GL_STREAM_DRAW, CL_MEM_READ_ONLY);
		updateBuffer(hStagingBufferNormals, GL_ARRAY_BUFFER,
			sceneContext.stagingBufferNormals.getBuffer(),
			GL_STREAM_DRAW, CL_MEM_READ_ONLY);
		sceneContext.stagingBufferVertices.clear();
		sceneContext.stagingBufferUvs.clear();
		sceneContext.stagingBufferNormals.clear();
	}

	public void reloadSceneNextGameTick()
	{
		reloadSceneIn(1);
	}

	public void reloadSceneIn(int gameTicks) {
		assert gameTicks > 0 : "A value <= 0 will not reload the scene";
		if (gameTicks > gameTicksUntilSceneReload) {
			gameTicksUntilSceneReload = gameTicks;
		}
	}

	public void abortSceneReload() {
		gameTicksUntilSceneReload = 0;
	}

	private void updateCachedConfigs() {
		configGroundTextures = config.groundTextures();
		configGroundBlending = config.groundBlending();
		configModelTextures = config.modelTextures();
		configTzhaarHD = config.hdTzHaarReskin();
		configProjectileLights = config.projectileLights();
		configNpcLights = config.npcLights();
		configHideFakeShadows = config.hideFakeShadows();
		configWinterTheme = config.winterTheme();
		configLegacyGreyColors = config.legacyGreyColors();
		configModelBatching = config.modelBatching();
		configModelCaching = config.modelCaching();
		configMaxDynamicLights = config.maxDynamicLights().getValue();
		configExpandShadowDraw = config.expandShadowDraw();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (!event.getGroup().equals(CONFIG_GROUP))
			return;

		clientThread.invoke(() -> {
			updateCachedConfigs();

			switch (event.getKey()) {
				case KEY_MAX_DYNAMIC_LIGHTS:
				case KEY_UI_SCALING_MODE:
				case KEY_COLOR_BLINDNESS:
				case KEY_MACOS_INTEL_WORKAROUND:
				case KEY_NORMAL_MAPPING:
				case KEY_PARALLAX_OCCLUSION_MAPPING:
				case KEY_VANILLA_COLOR_BANDING:
					recompilePrograms();
					break;
				case KEY_SHADOW_MODE:
				case KEY_SHADOW_TRANSPARENCY:
					recompilePrograms();
					// fall-through
				case KEY_SHADOW_RESOLUTION:
					destroyShadowMapFbo();
					initShadowMapFbo();
					break;
				case KEY_TEXTURE_RESOLUTION:
				case KEY_ANISOTROPIC_FILTERING_LEVEL:
				case KEY_HD_INFERNAL_CAPE:
					textureManager.freeTextures();
					break;
				case KEY_ATMOSPHERIC_LIGHTING:
					environmentManager.reset();
					break;
				case KEY_WINTER_THEME:
					environmentManager.reset();
					// fall-through
				case KEY_MODEL_TEXTURES:
					textureManager.freeTextures();
					// fall-through
				case KEY_HIDE_FAKE_SHADOWS:
				case KEY_GROUND_BLENDING:
				case KEY_GROUND_TEXTURES:
				case KEY_HD_TZHAAR_RESKIN:
				case KEY_LEGACY_GREY_COLORS:
					modelPusher.clearModelCache();
					uploadScene();
					break;
				case KEY_UNLOCK_FPS:
				case KEY_VSYNC_MODE:
				case KEY_FPS_TARGET:
					setupSyncMode();
					break;
				case KEY_MODEL_CACHING:
				case KEY_MODEL_CACHE_SIZE:
					modelPusher.shutDown();
					modelPusher.startUp();
					break;
			}
		});
	}

	private void setupSyncMode()
	{
		final boolean unlockFps = config.unlockFps();
		client.setUnlockedFps(unlockFps);

		// Without unlocked fps, the client manages sync on its 20ms timer
		HdPluginConfig.SyncMode syncMode = unlockFps
				? this.config.syncMode()
				: HdPluginConfig.SyncMode.OFF;

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
		if (actualSwapInterval != swapInterval)
		{
			log.info("unsupported swap interval {}, got {}", swapInterval, actualSwapInterval);
		}

		client.setUnlockedFpsTarget(actualSwapInterval == 0 ? config.fpsTarget() : 0);
		checkGLErrors();
	}

	/**
	 * Check is a model is visible and should be drawn.
	 */
	private boolean isOutsideViewport(Model model, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z)
	{
		model.calculateBoundsCylinder();

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
		if (depth > 50)
		{
			int rx = z * yawSin + yawCos * x >> 16;
			int var16 = (rx - XYZMag) * zoom;
			if (var16 / depth < Rasterizer3D_clipMidX2)
			{
				int var17 = (rx + XYZMag) * zoom;
				if (var17 / depth > Rasterizer3D_clipNegativeMidX)
				{
					int ry = pitchCos * y - var11 * pitchSin >> 16;
					int yheight = pitchSin * XYZMag >> 16;
					int ybottom = (pitchCos * bottomY >> 16) + yheight;
					int var20 = (ry + ybottom) * zoom;
					if (var20 / depth > Rasterizer3D_clipNegativeMidY)
					{
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
	 * @param renderable  Can be an Actor (Player or NPC), DynamicObject, GraphicsObject, TileItem, Projectile or a raw Model.
	 * @param orientation Rotation around the up-axis, from 0 to 2048 exclusive, 2048 indicating a complete rotation.
	 * @param pitchSin    The sine of the camera's rotation about the horizontal axis, in the range from -65536 to 65536.
	 * @param pitchCos    The cosine of the camera's rotation about the horizontal axis, in the range from -65536 to 65536.
	 * @param yawSin      The sine of the camera's rotation about the vertical axis, in the range from -65536 to 65536.
	 * @param yawCos      The cosine of the camera's rotation about the vertical axis, in the range from -65536 to 65536.
	 * @param x           The Renderable's X offset relative to {@link Client#getCameraX2()}.
	 * @param y           The Renderable's Y offset relative to {@link Client#getCameraY2()}.
	 * @param z           The Renderable's Z offset relative to {@link Client#getCameraZ2()}.
	 * @param hash        A unique hash of the renderable consisting of some useful information. See {@link rs117.hd.utils.ModelHash} for more details.
	 */
	@Override
	public void draw(Renderable renderable, int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z, long hash)
	{
		Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
		if (model == null || model.getFaceCount() == 0) {
			// skip models with zero faces
			// this does seem to happen sometimes (mostly during loading)
			// should save some CPU cycles here and there
			return;
		}

		// Model may be in the scene buffer
		assert sceneContext != null;
		if (model.getSceneId() == sceneContext.id) {
			// check if the object was marked to be skipped
			if ((model.getBufferOffset() & 0b11) == 0b11)
				return;

			if (isOutsideViewport(model, pitchSin, pitchCos, yawSin, yawCos, x, y, z))
				return;

			client.checkClickbox(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash);

			if (shouldSkipModelUpdates || modelOverrideManager.shouldHideModel(hash, x, z))
				return;

			int faceCount = Math.min(MAX_TRIANGLE, model.getFaceCount());
			int uvOffset = model.getUvBufferOffset();

			eightIntWrite[0] = model.getBufferOffset() >> 2;
			eightIntWrite[1] = uvOffset;
			eightIntWrite[2] = faceCount;
			eightIntWrite[3] = renderBufferOffset;
			eightIntWrite[4] = model.getRadius() << 12 | orientation;
			eightIntWrite[5] = x + client.getCameraX2();
			eightIntWrite[6] = y + client.getCameraY2();
			eightIntWrite[7] = z + client.getCameraZ2();

			bufferForTriangles(faceCount).ensureCapacity(8).put(eightIntWrite);

			renderBufferOffset += faceCount * 3;
		} else {
			// Temporary model (animated or otherwise not a static Model on the scene)
			// Apply height to renderable from the model
			if (model != renderable) {
				renderable.setModelHeight(model.getModelHeight());
			}

			// check if the object was marked to be skipped
			if ((model.getBufferOffset() & 0b11) == 0b11)
				return;

			if (isOutsideViewport(model, pitchSin, pitchCos, yawSin, yawCos, x, y, z))
				return;

			client.checkClickbox(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash);

			if (shouldSkipModelUpdates || modelOverrideManager.shouldHideModel(hash, x, z))
				return;

			eightIntWrite[3] = renderBufferOffset;
			eightIntWrite[4] = model.getRadius() << 12 | orientation;
			eightIntWrite[5] = x + client.getCameraX2();
			eightIntWrite[6] = y + client.getCameraY2();
			eightIntWrite[7] = z + client.getCameraZ2();

			TempModelInfo tempModelInfo = null;
			int batchHash = 0;

			if (configModelBatching || configModelCaching) {
				modelHasher.setModel(model);
				if (configModelBatching) {
					batchHash = modelHasher.calculateBatchHash();
					tempModelInfo = frameModelInfoMap.get(batchHash);
				}
			}

			if (tempModelInfo != null && tempModelInfo.getFaceCount() == model.getFaceCount()) {
				eightIntWrite[0] = tempModelInfo.getTempOffset();
				eightIntWrite[1] = tempModelInfo.getTempUvOffset();
				eightIntWrite[2] = tempModelInfo.getFaceCount();

				bufferForTriangles(tempModelInfo.getFaceCount()).ensureCapacity(8).put(eightIntWrite);

				renderBufferOffset += tempModelInfo.getFaceCount() * 3;
			} else {
				int vertexOffset = dynamicOffsetVertices + sceneContext.getVertexOffset();
				int uvOffset = dynamicOffsetUvs + sceneContext.getUvOffset();

				modelPusher.pushModel(sceneContext, null, hash, model, ObjectType.NONE, 0, true);
				final int faceCount = sceneContext.modelPusherResults[0] / 3;
				if (sceneContext.modelPusherResults[1] <= 0)
					uvOffset = -1;

				eightIntWrite[0] = vertexOffset;
				eightIntWrite[1] = uvOffset;
				eightIntWrite[2] = faceCount;
				bufferForTriangles(faceCount).ensureCapacity(8).put(eightIntWrite);

				renderBufferOffset += sceneContext.modelPusherResults[0];

				// add this temporary model to the map for batching purposes
				if (configModelBatching) {
					tempModelInfo = new TempModelInfo();
					tempModelInfo
						.setTempOffset(vertexOffset)
						.setTempUvOffset(uvOffset)
						.setFaceCount(faceCount);
					frameModelInfoMap.put(batchHash, tempModelInfo);
				}
			}
		}
	}

	@Override
	public boolean drawFace(Model model, int face) {
		return false;
	}

	/**
	 * returns the correct buffer based on triangle count and updates model count
	 */
	private GpuIntBuffer bufferForTriangles(int triangles) {
		if (triangles <= SMALL_TRIANGLE_COUNT) {
			++numModelsSmall;
			return modelBufferSmall;
		} else {
			++numModelsLarge;
			return modelBufferLarge;
		}
	}

	private int getScaledValue(final double scale, final int value)
	{
		return (int) (value * scale + .5);
	}

	private void glDpiAwareViewport(final int x, final int y, final int width, final int height)
	{
		if (OSType.getOSType() == OSType.MacOS)
		{
			// macos handles DPI scaling for us already
			glViewport(x, y, width, height);
		}
		else
		{
			final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
			if (graphicsConfiguration == null) return;
			final AffineTransform t = graphicsConfiguration.getDefaultTransform();
			glViewport(
				getScaledValue(t.getScaleX(), x),
				getScaledValue(t.getScaleY(), y),
				getScaledValue(t.getScaleX(), width),
				getScaledValue(t.getScaleY(), height));
		}
	}

	private int getDrawDistance()
	{
		return HDUtils.clamp(config.drawDistance(), 0, MAX_DISTANCE);
	}

	/**
	 * Calculates the approximate position of the point on which the camera is focused.
	 *
	 * @return The camera target's x, y, z coordinates
	 */
	public int[] getCameraFocalPoint()
	{
		int camX = client.getOculusOrbFocalPointX();
		int camY = client.getOculusOrbFocalPointY();
		// approximate the Z position of the point the camera is aimed at.
		// the difference in height between the camera at lowest and highest pitch
		int camPitch = client.getCameraPitch();
		final int minCamPitch = 128;
		final int maxCamPitch = 512;
		int camPitchDiff = maxCamPitch - minCamPitch;
		float camHeight = (camPitch - minCamPitch) / (float)camPitchDiff;
		final int camHeightDiff = 2200;
		int camZ = (int)(client.getCameraZ() + (camHeight * camHeightDiff));

		return new int[]{camX, camY, camZ};
	}

	private void updateBuffer(@Nonnull GLBuffer glBuffer, int target, @Nonnull ByteBuffer data, int usage, long clFlags)
	{
		glBindBuffer(target, glBuffer.glBufferId);
		long size = data.remaining();
		if (size > glBuffer.size)
		{
			size = HDUtils.ceilPow2(size);
			log.debug("Buffer resize: {} {} -> {}", glBuffer, glBuffer.size, size);

			glBuffer.size = size;
			glBufferData(target, size, usage);
			recreateCLBuffer(glBuffer, clFlags);
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
		if (size > glBuffer.size)
		{
			size = HDUtils.ceilPow2(size);
			log.debug("Buffer resize: {} {} -> {}", glBuffer, glBuffer.size, size);

			if (offset > 0)
			{
				int oldBuffer = glBuffer.glBufferId;
				glBuffer.glBufferId = glGenBuffers();
				glBindBuffer(target, glBuffer.glBufferId);
				glBufferData(target, size, usage);

				glBindBuffer(GL_COPY_READ_BUFFER, oldBuffer);
				glCopyBufferSubData(GL_COPY_READ_BUFFER, target, 0, 0, offset * 4L);
				glDeleteBuffers(oldBuffer);
			}
			else
			{
				glBindBuffer(target, glBuffer.glBufferId);
				glBufferData(target, size, usage);
			}

			glBuffer.size = size;
			recreateCLBuffer(glBuffer, clFlags);
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
		if (size > glBuffer.size)
		{
			size = HDUtils.ceilPow2(size);
			log.debug("Buffer resize: {} {} -> {}", glBuffer, glBuffer.size, size);

			if (offset > 0)
			{
				int oldBuffer = glBuffer.glBufferId;
				glBuffer.glBufferId = glGenBuffers();
				glBindBuffer(target, glBuffer.glBufferId);
				glBufferData(target, size, usage);

				glBindBuffer(GL_COPY_READ_BUFFER, oldBuffer);
				glCopyBufferSubData(GL_COPY_READ_BUFFER, target, 0, 0, offset * 4L);
				glDeleteBuffers(oldBuffer);
			}
			else
			{
				glBindBuffer(target, glBuffer.glBufferId);
				glBufferData(target, size, usage);
			}

			glBuffer.size = size;
			recreateCLBuffer(glBuffer, clFlags);
		}
		else
		{
			glBindBuffer(target, glBuffer.glBufferId);
		}
		glBufferSubData(target, offset * 4L, data);
	}

	private void updateBuffer(@Nonnull GLBuffer glBuffer, int target, long size, int usage, long clFlags)
	{
		if (size > glBuffer.size)
		{
			size = HDUtils.ceilPow2(size);
			log.debug("Buffer resize: {} {} -> {}", glBuffer, glBuffer.size, size);

			glBuffer.size = size;
			glBindBuffer(target, glBuffer.glBufferId);
			glBufferData(target, size, usage);
			recreateCLBuffer(glBuffer, clFlags);
		}
	}

	private void recreateCLBuffer(GLBuffer glBuffer, long clFlags)
	{
		if (computeMode == ComputeMode.OPENCL)
		{
			// cleanup previous buffer
			if (glBuffer.clBuffer != 0)
			{
				clReleaseMemObject(glBuffer.clBuffer);
			}

			// allocate new
			if (glBuffer.size == 0) {
				// opencl does not allow 0-size gl buffers, it will segfault on macos
				glBuffer.clBuffer = 0;
			} else {
				assert glBuffer.size > 0 : "Size <= 0 should not reach this point";
				glBuffer.clBuffer = clCreateFromGLBuffer(openCLManager.getContext(), clFlags, glBuffer.glBufferId, (IntBuffer) null);
			}
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender beforeRender) {
		// The game runs significantly slower when drawing lower planes, even though it in certain areas makes useful visual difference
		client.getScene().setMinLevel(isInChambersOfXeric ? client.getPlane() : 0);
	}

	@Subscribe
	public void onClientTick(ClientTick clientTick) {
		shouldSkipModelUpdates = false;
	}

	@Subscribe
	public void onGameTick(GameTick gameTick) {
		if (--gameTicksUntilSceneReload == 0)
			uploadScene();
	}

	private void checkGLErrors() {
		if (!log.isDebugEnabled())
			return;

		for (; ; ) {
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
//			"If you experience any issues, please report them in the <a href=\"https://discord.gg/U4p6ChjgSE\">117HD Discord</a>.",
//			new String[] { "Remind me later", "Got it!" },
//			i -> {
//				if (i == 1) {
//					config.setPluginUpdateMessage(messageId);
//				}
//			}
//		);
	}
}
