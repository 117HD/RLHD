/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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

import com.google.common.primitives.Ints;
import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.entityhider.EntityHiderPlugin;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.OSType;
import net.runelite.rlawt.AWTContext;
import org.jocl.CL;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.Configuration;
import rs117.hd.config.*;
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
import rs117.hd.scene.*;
import rs117.hd.scene.lights.SceneLight;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.ObjectType;
import rs117.hd.utils.*;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GpuFloatBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.*;
import java.awt.*;
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

import static org.jocl.CL.*;
import static org.lwjgl.opengl.GL43C.*;
import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.utils.ResourcePath.path;

@PluginDescriptor(
	name = "117 HD",
	description = "GPU renderer with a suite of graphical enhancements",
	tags = {"hd", "high", "detail", "graphics", "shaders", "textures", "gpu", "shadows", "lights"},
	conflicts = "GPU"
)
@PluginDependency(EntityHiderPlugin.class)
@Slf4j
public class HdPlugin extends Plugin implements DrawCallbacks
{
	private static final String ENV_SHADER_PATH = "RLHD_SHADER_PATH";

	public static final int TEXTURE_UNIT_UI = GL_TEXTURE0; // default state
	public static final int TEXTURE_UNIT_GAME = GL_TEXTURE1;
	public static final int TEXTURE_UNIT_SHADOW_MAP = GL_TEXTURE2;

	// This is the maximum number of triangles the compute shaders support
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
	private OpenCLManager openCLManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPluginConfig config;

	@Inject
	private TextureManager textureManager;

	@Inject
	private LightManager lightManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private SceneUploader sceneUploader;

	@Inject
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ProceduralGenerator proceduralGenerator;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ModelPusher modelPusher;

	@Inject
	private ModelHasher modelHasher;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	@Inject
	private DeveloperTools developerTools;
	private ComputeMode computeMode = ComputeMode.OPENGL;

	@Inject
	private Gson rlGson;
	@Getter
	private Gson gson;

	private Canvas canvas;
	private AWTContext awtContext;
	private Callback debugCallback;

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

	private static final Shader SHADOW_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
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

	private static final ResourcePath shaderPath = Env
		.getPathOrDefault(ENV_SHADER_PATH, () -> path(HdPlugin.class))
		.chroot();

	private int glProgram;
	private int glComputeProgram;
	private int glSmallComputeProgram;
	private int glUnorderedComputeProgram;
	private int glUiProgram;
	private int glShadowProgram;

	private int vaoHandle;

	private int interfaceTexture;
	private int interfacePbo;

	private int vaoUiHandle;
	private int vboUiHandle;

	private int fboSceneHandle;
	private int rboSceneHandle;

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

	public GpuIntBuffer stagingBufferVertices;
	public GpuFloatBuffer stagingBufferUvs;
	public GpuFloatBuffer stagingBufferNormals;

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
	private int uniLightDirection;
	private int uniShadowMaxBias;
	private int uniShadowsEnabled;
	private int uniUnderwaterEnvironment;
	private int uniUnderwaterCaustics;
	private int uniUnderwaterCausticsColor;
	private int uniUnderwaterCausticsStrength;

	// Shadow program uniforms
	private int uniShadowLightProjectionMatrix;
	private int uniShadowTextureArray;
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

	private int uniBlockSmall;
	private int uniBlockLarge;
	private int uniBlockMain;
	private int uniBlockMaterials;
	private int uniBlockWaterTypes;
	private int uniShadowBlockMaterials;
	private int uniBlockPointLights;

	// Animation things
	private long lastFrameTime = System.currentTimeMillis();

	// Generic scalable animation timer used in shaders
	private float elapsedTime;

	private int gameTicksUntilSceneReload = 0;

	// some necessary data for reloading the scene while in POH to fix major performance loss
	@Setter
	private boolean isInHouse = false;
	private int previousPlane;

	// Config settings used very frequently - thousands/frame
	public boolean configGroundTextures = false;
	public boolean configGroundBlending = false;
	public boolean configModelTextures = true;
	public boolean configTzhaarHD = true;
	public boolean configProjectileLights = true;
	public boolean configNpcLights = true;
	public boolean configShadowsEnabled = false;
	public boolean configExpandShadowDraw = false;
	public boolean configHideBakedEffects = false;
	public boolean configHdInfernalTexture = true;
	public boolean configWinterTheme = true;
	public boolean configReduceOverExposure = false;
	public boolean configEnableModelBatching = false;
	public boolean configEnableModelCaching = false;
	public int configMaxDynamicLights;

	public int[] camTarget = new int[3];

	private boolean hasLoggedIn;
	private boolean lwjglInitted = false;

	@Setter
	private boolean isInGauntlet = false;

	private final Map<Integer, TempModelInfo> frameModelInfoMap = new HashMap<>();

	@Subscribe
	public void onChatMessage(final ChatMessage event) {
		if (!isInGauntlet) {
			return;
		}

		// reload the scene if the player is in the gauntlet and opening a new room to pull the new data into the buffer
		if (event.getMessage().equals("You light the nodes in the corridor to help guide the way.")) {
			reloadSceneNextGameTick();
		}
	}

	@Override
	protected void startUp()
	{
		gson = rlGson.newBuilder().setLenient().create();

		configGroundTextures = config.groundTextures();
		configGroundBlending = config.groundBlending();
		configModelTextures = config.objectTextures();
		configTzhaarHD = config.tzhaarHD();
		configProjectileLights = config.projectileLights();
		configNpcLights = config.npcLights();
		configShadowsEnabled = config.shadowsEnabled();
		configExpandShadowDraw = config.expandShadowDraw();
		configHideBakedEffects = config.hideBakedEffects();
		configHdInfernalTexture = config.hdInfernalTexture();
		configWinterTheme = config.winterTheme();
		configReduceOverExposure = config.enableLegacyGreyColors();
		configEnableModelBatching = config.enableModelBatching();
		configEnableModelCaching = config.enableModelCaching();
		configMaxDynamicLights = config.maxDynamicLights().getValue();

		clientThread.invoke(() ->
		{
			try
			{
				renderBufferOffset = 0;
				fboSceneHandle = rboSceneHandle = 0; // AA FBO
				fboShadowMap = 0;
				numModelsUnordered = numModelsSmall = numModelsLarge = 0;
				elapsedTime = 0;

				AWTContext.loadNatives();
				canvas = client.getCanvas();

				synchronized (canvas.getTreeLock())
				{
					if (!canvas.isValid())
					{
						return false;
					}

					awtContext = new AWTContext(canvas);
					awtContext.configurePixelFormat(0, 0, 0);
				}

				awtContext.createGLContext();

				canvas.setIgnoreRepaint(true);

				computeMode = OSType.getOSType() == OSType.MacOS ? ComputeMode.OPENCL : ComputeMode.OPENGL;

				// lwjgl defaults to lwjgl- + user.name, but this breaks if the username would cause an invalid path
				// to be created, and also breaks if both 32 and 64 bit lwjgl versions try to run at once.
				Configuration.SHARED_LIBRARY_EXTRACT_DIRECTORY.set("lwjgl-rl-" + System.getProperty("os.arch", "unknown"));

				GL.createCapabilities();

				log.info("Using device: {}", glGetString(GL_RENDERER));
				log.info("Using driver: {}", glGetString(GL_VERSION));
				log.info("Client is {}-bit", System.getProperty("sun.arch.data.model"));

				GLCapabilities caps = GL.getCapabilities();
				if (computeMode == ComputeMode.OPENGL)
				{
					if (!caps.OpenGL43)
					{
						throw new RuntimeException("OpenGL 4.3 is required but not available");
					}
				}
				else
				{
					if (!caps.OpenGL31)
					{
						throw new RuntimeException("OpenGL 3.1 is required but not available");
					}
				}

				lwjglInitted = true;

				checkGLErrors();
				if (log.isDebugEnabled() && caps.glDebugMessageControl != 0)
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

				stagingBufferVertices = new GpuIntBuffer();
				stagingBufferUvs = new GpuFloatBuffer();
				stagingBufferNormals = new GpuFloatBuffer();

				modelBufferUnordered = new GpuIntBuffer();
				modelBufferSmall = new GpuIntBuffer();
				modelBufferLarge = new GpuIntBuffer();

				initShaderHotswapping();
				if (developerMode)
				{
					developerTools.activate();
				}

				lastFrameTime = System.currentTimeMillis();

				setupSyncMode();
				initVao();
				initBuffers();
				try
				{
					initPrograms();
				}
				catch (ShaderException ex)
				{
					throw new RuntimeException(ex);
				}
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

				lightManager.startUp();
				modelOverrideManager.startUp();
				modelPusher.startUp();

				if (client.getGameState() == GameState.LOGGED_IN)
				{
					uploadScene();
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
	protected void shutDown()
	{
		FileWatcher.destroy();
		developerTools.deactivate();
		lightManager.shutDown();

		clientThread.invoke(() ->
		{
			client.setGpu(false);
			client.setDrawCallbacks(null);
			client.setUnlockedFps(false);
			modelPusher.shutDown();

			if (lwjglInitted)
			{
				textureManager.shutDown();

				shutdownBuffers();
				shutdownInterfaceTexture();
				shutdownPrograms();
				shutdownVao();
				shutdownAAFbo();
				shutdownShadowMapFbo();
			}

			if (awtContext != null)
				awtContext.destroy();
			awtContext = null;

			if (debugCallback != null)
				debugCallback.free();
			debugCallback = null;

			if (stagingBufferVertices != null)
				stagingBufferVertices.destroy();
			stagingBufferVertices = null;

			if (stagingBufferUvs != null)
				stagingBufferUvs.destroy();
			stagingBufferUvs = null;

			if (stagingBufferNormals != null)
				stagingBufferNormals.destroy();
			stagingBufferNormals = null;

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

	@Provides
	HdPluginConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(HdPluginConfig.class);
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

	private void initPrograms() throws ShaderException
	{
		String versionHeader = OSType.getOSType() == OSType.Linux ? LINUX_VERSION_HEADER : WINDOWS_VERSION_HEADER;
		Template template = new Template().add(key -> {
			switch (key)
			{
				case "version_header":
					return versionHeader;
				case "UI_SCALING_MODE":
					return String.format("#define %s %d", key, config.uiScalingMode().getMode());
				case "COLOR_BLINDNESS":
					return String.format("#define %s %d", key, config.colorBlindness().ordinal());
				case "MATERIAL_CONSTANTS":
				{
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
				}
				case "MATERIAL_COUNT":
					return String.format("#define %s %d", key, Material.values().length);
				case "MATERIAL_GETTER":
					return generateGetter("Material", Material.values().length);
				case "WATER_TYPE_COUNT":
					return String.format("#define %s %d", key, WaterType.values().length);
				case "WATER_TYPE_GETTER":
					return generateGetter("WaterType", WaterType.values().length);
				case "LIGHT_COUNT":
					return String.format("#define %s %d", key, Math.max(1, configMaxDynamicLights));
				case "LIGHT_GETTER":
					return generateGetter("PointLight", configMaxDynamicLights);
				case "PARALLAX_MAPPING":
					return String.format("#define %s %d", key, ParallaxMappingMode.OFF.ordinal()); // config.parallaxMappingMode().ordinal());
			}
			return null;
		});

		template.add(key -> {
			try {
				return shaderPath.resolve(key).loadString();
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});

		glProgram = PROGRAM.compile(template);
		glUiProgram = UI_PROGRAM.compile(template);
		glShadowProgram = SHADOW_PROGRAM.compile(template);

		if (computeMode == ComputeMode.OPENCL)
		{
			openCLManager.init(awtContext);
		}
		else
		{
			glComputeProgram = COMPUTE_PROGRAM.compile(template);
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

		glUseProgram(glShadowProgram);
		glUniform1i(uniShadowTextureArray, 1);

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
		uniLightDirection = glGetUniformLocation(glProgram, "lightDirection");
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
			uniBlockSmall = glGetUniformBlockIndex(glSmallComputeProgram, "CameraUniforms");
			uniBlockLarge = glGetUniformBlockIndex(glComputeProgram, "CameraUniforms");
			uniBlockMain = glGetUniformBlockIndex(glProgram, "CameraUniforms");
		}
		uniBlockMaterials = glGetUniformBlockIndex(glProgram, "MaterialUniforms");
		uniBlockWaterTypes = glGetUniformBlockIndex(glProgram, "WaterTypeUniforms");
		uniBlockPointLights = glGetUniformBlockIndex(glProgram, "PointLightUniforms");

		// Shadow program uniforms
		uniShadowBlockMaterials = glGetUniformBlockIndex(glShadowProgram, "MaterialUniforms");
		uniShadowLightProjectionMatrix = glGetUniformLocation(glShadowProgram, "lightProjectionMatrix");
		uniShadowTextureArray = glGetUniformLocation(glShadowProgram, "textureArray");
		uniShadowElapsedTime = glGetUniformLocation(glShadowProgram, "elapsedTime");

		// Initialize uniform buffers that may depend on compile-time settings
		initCameraUniformBuffer();
		initLightsUniformBuffer();
	}

	private void shutdownPrograms()
	{
		openCLManager.cleanup();

		if (glProgram != 0)
		{
			glDeleteProgram(glProgram);
			glProgram = 0;
		}

		if (glComputeProgram != 0)
		{
			glDeleteProgram(glComputeProgram);
			glComputeProgram = 0;
		}

		if (glSmallComputeProgram != 0)
		{
			glDeleteProgram(glSmallComputeProgram);
			glSmallComputeProgram = 0;
		}

		if (glUnorderedComputeProgram != 0)
		{
			glDeleteProgram(glUnorderedComputeProgram);
			glUnorderedComputeProgram = 0;
		}

		if (glUiProgram != 0)
		{
			glDeleteProgram(glUiProgram);
			glUiProgram = 0;
		}

		if (glShadowProgram != 0)
		{
			glDeleteProgram(glShadowProgram);
			glShadowProgram = 0;
		}
	}

	public void recompilePrograms()
	{
		try
		{
			shutdownPrograms();
			shutdownVao();
			initVao();
			initPrograms();
		}
		catch (ShaderException ex)
		{
			log.error("Failed to recompile shader program", ex);
			stopPlugin();
		}
	}

	private void initVao()
	{
		// Create VAO
		vaoHandle = glGenVertexArrays();

		// Create UI VAO
		vaoUiHandle = glGenVertexArrays();
		// Create UI buffer
		vboUiHandle = glGenBuffers();
		glBindVertexArray(vaoUiHandle);

		FloatBuffer vboUiBuf = BufferUtils.createFloatBuffer(5 * 4);
		vboUiBuf.put(new float[]{
			// positions     // texture coords
			1f, 1f, 0.0f, 1.0f, 0f, // top right
			1f, -1f, 0.0f, 1.0f, 1f, // bottom right
			-1f, -1f, 0.0f, 0.0f, 1f, // bottom left
			-1f, 1f, 0.0f, 0.0f, 0f  // top left
		});
		vboUiBuf.rewind();
		glBindBuffer(GL_ARRAY_BUFFER, vboUiHandle);
		glBufferData(GL_ARRAY_BUFFER, vboUiBuf, GL_STATIC_DRAW);

		// position attribute
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
		glEnableVertexAttribArray(0);

		// texture coord attribute
		glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
		glEnableVertexAttribArray(1);

		// unbind VBO
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	private void shutdownVao()
	{
		if (vaoHandle != 0)
		{
			glDeleteVertexArrays(vaoHandle);
			vaoHandle = 0;
		}

		if (vboUiHandle != 0)
		{
			glDeleteBuffers(vboUiHandle);
			vboUiHandle = 0;
		}

		if (vaoUiHandle != 0)
		{
			glDeleteVertexArrays(vaoUiHandle);
			vaoUiHandle = 0;
		}
	}

	private void initBuffers()
	{
		initGlBuffer(hUniformBufferCamera);
		initGlBuffer(hUniformBufferMaterials);
		initGlBuffer(hUniformBufferWaterTypes);
		initGlBuffer(hUniformBufferLights);

		initGlBuffer(hStagingBufferVertices);
		initGlBuffer(hStagingBufferUvs);
		initGlBuffer(hStagingBufferNormals);

		initGlBuffer(hModelBufferLarge);
		initGlBuffer(hModelBufferSmall);
		initGlBuffer(hModelBufferUnordered);

		initGlBuffer(hRenderBufferVertices);
		initGlBuffer(hRenderBufferUvs);
		initGlBuffer(hRenderBufferNormals);
	}

	private void initGlBuffer(GLBuffer glBuffer)
	{
		glBuffer.glBufferId = glGenBuffers();
	}

	private void shutdownBuffers()
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
		if (glBuffer.glBufferId != 0)
		{
			glDeleteBuffers(glBuffer.glBufferId);
			glBuffer.glBufferId = 0;
		}
		glBuffer.size = -1;

		if (glBuffer.cl_mem != null)
		{
			CL.clReleaseMemObject(glBuffer.cl_mem);
			glBuffer.cl_mem = null;
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

	private void shutdownInterfaceTexture()
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
		for (int i = 0; i < 2048; i++)
		{
			uniformBuf.put(Perspective.SINE[i]);
			uniformBuf.put(Perspective.COSINE[i]);
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
				.putFloat(material.displacementScale)
				.putFloat(material.specularStrength)
				.putFloat(material.specularGloss)
				.putFloat(material.flowMapStrength)
				.putFloat(material.flowMapDuration[0])
				.putFloat(material.flowMapDuration[1])
				.putFloat(scrollSpeedX)
				.putFloat(scrollSpeedY)
				.putFloat(material.textureScale[0])
				.putFloat(material.textureScale[1])
				.putFloat(0).putFloat(0); // pad vec4
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

	private void shutdownAAFbo()
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
			glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, config.shadowResolution().getValue(), config.shadowResolution().getValue(), 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);

			float[] color = { 1.0f, 1.0f, 1.0f, 1.0f };
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

	private void shutdownShadowMapFbo()
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
		yaw = client.getCameraYaw();
		pitch = client.getCameraPitch();
		viewportOffsetX = client.getViewportXOffset();
		viewportOffsetY = client.getViewportYOffset();

		final Scene scene = client.getScene();
		scene.setDrawDistance(getDrawDistance());

		environmentManager.update();
		lightManager.update();

		// Only reset the target buffer offset right before drawing the scene. That way if there are frames
		// after this that don't involve a scene draw, like during LOADING/HOPPING/CONNECTION_LOST, we can
		// still redraw the previous frame's scene to emulate the client behavior of not painting over the
		// viewport buffer.
		renderBufferOffset = 0;


		// UBO. Only the first 32 bytes get modified here, the rest is the constant sin/cos table.
		// We can reuse the vertex buffer since it isn't used yet.
		stagingBufferVertices.clear();
		stagingBufferVertices.ensureCapacity(32);
		IntBuffer uniformBuf = stagingBufferVertices.getBuffer();
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

		if (configMaxDynamicLights > 0)
		{
			// Update lights UBO
			uniformBufferLights.clear();
			ArrayList<SceneLight> visibleLights = lightManager.getVisibleLights(getDrawDistance(), configMaxDynamicLights);
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

			glBindBuffer(GL_UNIFORM_BUFFER, hUniformBufferLights.glBufferId);
			glBufferSubData(GL_UNIFORM_BUFFER, 0, uniformBufferLights);
			uniformBufferLights.clear();
			glBindBuffer(GL_UNIFORM_BUFFER, 0);
		}
		glBindBufferBase(GL_UNIFORM_BUFFER, 3, hUniformBufferLights.glBufferId);
	}

	@Override
	public void postDrawScene()
	{
		// Upload buffers
		stagingBufferVertices.flip();
		stagingBufferUvs.flip();
		stagingBufferNormals.flip();
		modelBufferUnordered.flip();
		modelBufferSmall.flip();
		modelBufferLarge.flip();

		// temp buffers
		updateBuffer(hStagingBufferVertices, GL_ARRAY_BUFFER,
			dynamicOffsetVertices * VERTEX_SIZE, stagingBufferVertices.getBuffer(), GL_STREAM_DRAW, CL_MEM_READ_ONLY);
		updateBuffer(hStagingBufferUvs, GL_ARRAY_BUFFER,
			dynamicOffsetUvs * UV_SIZE, stagingBufferUvs.getBuffer(), GL_STREAM_DRAW, CL_MEM_READ_ONLY);
		updateBuffer(hStagingBufferNormals, GL_ARRAY_BUFFER,
			dynamicOffsetVertices * NORMAL_SIZE, stagingBufferNormals.getBuffer(), GL_STREAM_DRAW, CL_MEM_READ_ONLY);

		// model buffers
		updateBuffer(hModelBufferLarge, GL_ARRAY_BUFFER, modelBufferLarge.getBuffer(), GL_STREAM_DRAW, CL_MEM_READ_ONLY);
		updateBuffer(hModelBufferSmall, GL_ARRAY_BUFFER, modelBufferSmall.getBuffer(), GL_STREAM_DRAW, CL_MEM_READ_ONLY);
		updateBuffer(hModelBufferUnordered, GL_ARRAY_BUFFER, modelBufferUnordered.getBuffer(), GL_STREAM_DRAW, CL_MEM_READ_ONLY);

		// Output buffers
		updateBuffer(hRenderBufferVertices,
			GL_ARRAY_BUFFER,
			renderBufferOffset * 16L, // each vertex is an ivec4, which is 16 bytes
			GL_STREAM_DRAW,
			CL_MEM_WRITE_ONLY);
		updateBuffer(hRenderBufferUvs,
			GL_ARRAY_BUFFER,
			renderBufferOffset * 16L, // each vertex is an ivec4, which is 16 bytes
			GL_STREAM_DRAW,
			CL_MEM_WRITE_ONLY);
		updateBuffer(hRenderBufferNormals,
			GL_ARRAY_BUFFER,
			renderBufferOffset * 16L, // each vertex is an ivec4, which is 16 bytes
			GL_STREAM_DRAW,
			CL_MEM_WRITE_ONLY);

		if (computeMode == ComputeMode.OPENCL)
		{
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

			checkGLErrors();
			return;
		}

		/*
		 * Compute is split into three separate programs: 'unordered', 'small', and 'large'
		 * to save on GPU resources. Small will sort <= 512 faces, large will do <= 4096.
		 */

		// Bind UBO to compute programs
		glUniformBlockBinding(glSmallComputeProgram, uniBlockSmall, 0);
		glUniformBlockBinding(glComputeProgram, uniBlockLarge, 0);

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
		glUseProgram(glComputeProgram);
		glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, hModelBufferLarge.glBufferId);
		glDispatchCompute(numModelsLarge, 1, 1);

		checkGLErrors();
	}

	@Override
	public void drawScenePaint(int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z,
		SceneTilePaint paint, int tileZ, int tileX, int tileY,
		int zoom, int centerX, int centerY)
	{
		if (paint.getBufferLen() > 0)
		{
			final int localX = tileX * Perspective.LOCAL_TILE_SIZE;
			final int localY = 0;
			final int localZ = tileY * Perspective.LOCAL_TILE_SIZE;

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

			if (underwaterTerrain)
			{
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
	}

	public void initShaderHotswapping() {
		shaderPath.watch("\\.(glsl|cl)$", path -> {
			log.info("Reloading shader: {}", path);
			clientThread.invoke(this::recompilePrograms);
		});
	}

	@Override
	public void drawSceneModel(int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z,
			SceneTileModel model, int tileZ, int tileX, int tileY,
			int zoom, int centerX, int centerY)
	{
		if (model.getBufferLen() > 0)
		{
			final int localX = tileX * Perspective.LOCAL_TILE_SIZE;
			final int localY = 0;
			final int localZ = tileY * Perspective.LOCAL_TILE_SIZE;

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

			if (underwaterTerrain)
			{
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
		glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY)
			.asIntBuffer()
			.put(pixels, 0, width * height);
		glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
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

		try
		{
			prepareInterfaceTexture(canvasWidth, canvasHeight);
		}
		catch (Exception ex)
		{
			// Fixes: https://github.com/runelite/runelite/issues/12930
			// Gracefully Handle loss of opengl buffers and context
			log.warn("prepareInterfaceTexture exception", ex);
			shutDown();
			startUp();
			return;
		}

		glClearColor(0, 0, 0, 1f);
		glClear(GL_COLOR_BUFFER_BIT);

		// Draw 3d scene
		final TextureProvider textureProvider = client.getTextureProvider();
		if (textureProvider != null && client.getGameState().getState() >= GameState.LOADING.getState())
		{
			// lazy init textures as they may not be loaded at plugin start.
			textureManager.ensureTexturesLoaded(textureProvider);

			// reload the scene if the player is in a house and their plane changed
			// this greatly improves the performance as it keeps the scene buffer up to date
			if (isInHouse) {
				int plane = client.getPlane();
				if (previousPlane != plane) {
					reloadSceneNextGameTick();
					previousPlane = plane;
				}
			}

			final int viewportHeight = client.getViewportHeight();
			final int viewportWidth = client.getViewportWidth();

			int renderWidthOff = viewportOffsetX;
			int renderHeightOff = viewportOffsetY;
			int renderCanvasHeight = canvasHeight;
			int renderViewportHeight = viewportHeight;
			int renderViewportWidth = viewportWidth;

			if (client.isStretchedEnabled())
			{
				Dimension dim = client.getStretchedDimensions();
				renderCanvasHeight = dim.height;

				double scaleFactorY = dim.getHeight() / canvasHeight;
				double scaleFactorX = dim.getWidth()  / canvasWidth;

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
			if (computeMode == ComputeMode.OPENCL)
			{
				openCLManager.finish();
			}
			else
			{
				glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
			}

			// Draw using the output buffer of the compute
			int vertexBuffer = hRenderBufferVertices.glBufferId;
			int uvBuffer = hRenderBufferUvs.glBufferId;
			int normalBuffer = hRenderBufferNormals.glBufferId;

			// Update the camera target only when not loading, to keep drawing correct shadows while loading
			if (client.getGameState() != GameState.LOADING)
			{
				camTarget = getCameraFocalPoint();
			}

			float[] lightProjectionMatrix = Mat4.identity();
			float lightPitch = environmentManager.currentLightPitch;
			float lightYaw = environmentManager.currentLightYaw;

			if (configShadowsEnabled && fboShadowMap != 0 && environmentManager.currentDirectionalStrength > 0.0f)
			{
				// render shadow depth map
				glViewport(0, 0, config.shadowResolution().getValue(), config.shadowResolution().getValue());
				glBindFramebuffer(GL_FRAMEBUFFER, fboShadowMap);
				glClear(GL_DEPTH_BUFFER_BIT);

				glUseProgram(glShadowProgram);

				final int camX = camTarget[0];
				final int camY = camTarget[1];
				final int camZ = camTarget[2];

				final int drawDistanceSceneUnits = Math.min(config.shadowDistance().getValue(), getDrawDistance()) * Perspective.LOCAL_TILE_SIZE / 2;
				final int east = Math.min(camX + drawDistanceSceneUnits, Perspective.LOCAL_TILE_SIZE * Perspective.SCENE_SIZE);
				final int west = Math.max(camX - drawDistanceSceneUnits, 0);
				final int north = Math.min(camY + drawDistanceSceneUnits, Perspective.LOCAL_TILE_SIZE * Perspective.SCENE_SIZE);
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
				Mat4.mul(lightProjectionMatrix, Mat4.rotateX((float) Math.toRadians(lightPitch)));
				Mat4.mul(lightProjectionMatrix, Mat4.rotateY((float) -Math.toRadians(lightYaw)));
				Mat4.mul(lightProjectionMatrix, Mat4.translate(-(width / 2f + west), -camZ, -(height / 2f + south)));
				glUniformMatrix4fv(uniShadowLightProjectionMatrix, false, lightProjectionMatrix);

				// bind uniforms
				glUniform1f(uniShadowElapsedTime, elapsedTime);
				glUniformBlockBinding(glShadowProgram, uniShadowBlockMaterials, 1);

				glEnable(GL_CULL_FACE);
				glEnable(GL_DEPTH_TEST);

				// Draw buffers
				glBindVertexArray(vaoHandle);

				glEnableVertexAttribArray(0);
				glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
				glVertexAttribIPointer(0, 4, GL_INT, 0, 0);

				glEnableVertexAttribArray(1);
				glBindBuffer(GL_ARRAY_BUFFER, uvBuffer);
				glVertexAttribPointer(1, 4, GL_FLOAT, false, 0, 0);

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
			if (aaEnabled)
			{
				glEnable(GL_MULTISAMPLE);

				final Dimension stretchedDimensions = client.getStretchedDimensions();

				final int stretchedCanvasWidth = client.isStretchedEnabled() ? stretchedDimensions.width : canvasWidth;
				final int stretchedCanvasHeight = client.isStretchedEnabled() ? stretchedDimensions.height : canvasHeight;

				// Re-create fbo
				if (lastStretchedCanvasWidth != stretchedCanvasWidth
					|| lastStretchedCanvasHeight != stretchedCanvasHeight
					|| lastAntiAliasingMode != antiAliasingMode)
				{
					shutdownAAFbo();

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
			}
			else
			{
				glDisable(GL_MULTISAMPLE);
				shutdownAAFbo();
			}

			lastAntiAliasingMode = antiAliasingMode;

			// Clear scene
			float[] fogColor = hasLoggedIn ? environmentManager.getFogColor() : EnvironmentManager.BLACK_COLOR;
			for (int i = 0; i < fogColor.length; i++)
			{
				fogColor[i] = HDUtils.linearToSrgb(fogColor[i]);
			}
			glClearColor(fogColor[0], fogColor[1], fogColor[2], 1f);
			glClear(GL_COLOR_BUFFER_BIT);

			final int drawDistance = getDrawDistance();
			int fogDepth = config.fogDepth();
			fogDepth *= 10;

			if (config.fogDepthMode() == FogDepthMode.DYNAMIC)
			{
				fogDepth = environmentManager.currentFogDepth;
			}
			else if (config.fogDepthMode() == FogDepthMode.NONE)
			{
				fogDepth = 0;
			}
			glUniform1i(uniUseFog, fogDepth > 0 ? 1 : 0);
			glUniform1i(uniFogDepth, fogDepth);

			glUniform4f(uniFogColor, fogColor[0], fogColor[1], fogColor[2], 1f);

			glUniform1i(uniDrawDistance, drawDistance * Perspective.LOCAL_TILE_SIZE);
			glUniform1f(uniColorBlindnessIntensity, config.colorBlindnessIntensity() / 100.f);

			float[] waterColor = environmentManager.currentWaterColor;
			float[] waterColorHSB = Color.RGBtoHSB((int) (waterColor[0] * 255f), (int) (waterColor[1] * 255f), (int) (waterColor[2] * 255f), null);
			float lightBrightnessMultiplier = 0.8f;
			float midBrightnessMultiplier = 0.45f;
			float darkBrightnessMultiplier = 0.05f;
			float[] waterColorLight = new Color(Color.HSBtoRGB(waterColorHSB[0], waterColorHSB[1], waterColorHSB[2] * lightBrightnessMultiplier)).getRGBColorComponents(null);
			float[] waterColorMid = new Color(Color.HSBtoRGB(waterColorHSB[0], waterColorHSB[1], waterColorHSB[2] * midBrightnessMultiplier)).getRGBColorComponents(null);
			float[] waterColorDark = new Color(Color.HSBtoRGB(waterColorHSB[0], waterColorHSB[1], waterColorHSB[2] * darkBrightnessMultiplier)).getRGBColorComponents(null);
			for (int i = 0; i < waterColorLight.length; i++)
			{
				waterColorLight[i] = HDUtils.linearToSrgb(waterColorLight[i]);
			}
			for (int i = 0; i < waterColorMid.length; i++)
			{
				waterColorMid[i] = HDUtils.linearToSrgb(waterColorMid[i]);
			}
			for (int i = 0; i < waterColorDark.length; i++)
			{
				waterColorDark[i] = HDUtils.linearToSrgb(waterColorDark[i]);
			}
			glUniform3f(uniWaterColorLight, waterColorLight[0], waterColorLight[1], waterColorLight[2]);
			glUniform3f(uniWaterColorMid, waterColorMid[0], waterColorMid[1], waterColorMid[2]);
			glUniform3f(uniWaterColorDark, waterColorDark[0], waterColorDark[1], waterColorDark[2]);

			// get ambient light strength from either the config or the current area
			float ambientStrength = environmentManager.currentAmbientStrength;
			ambientStrength *= (double)config.brightness() / 20;
			glUniform1f(uniAmbientStrength, ambientStrength);

			// and ambient color
			float[] ambientColor = environmentManager.currentAmbientColor;
			glUniform3f(uniAmbientColor, ambientColor[0], ambientColor[1], ambientColor[2]);

			// get light strength from either the config or the current area
			float lightStrength = environmentManager.currentDirectionalStrength;
			lightStrength *= (double)config.brightness() / 20;
			glUniform1f(uniLightStrength, lightStrength);

			// and light color
			float[] lightColor = environmentManager.currentDirectionalColor;
			glUniform3f(uniLightColor, lightColor[0], lightColor[1], lightColor[2]);

			// get underglow light strength from the current area
			float underglowStrength = environmentManager.currentUnderglowStrength;
			glUniform1f(uniUnderglowStrength, underglowStrength);
			// and underglow color
			float[] underglowColor = environmentManager.currentUnderglowColor;
			glUniform3f(uniUnderglowColor, underglowColor[0], underglowColor[1], underglowColor[2]);

			// get ground fog variables
			float groundFogStart = environmentManager.currentGroundFogStart;
			glUniform1f(uniGroundFogStart, groundFogStart);
			float groundFogEnd = environmentManager.currentGroundFogEnd;
			glUniform1f(uniGroundFogEnd, groundFogEnd);
			float groundFogOpacity = environmentManager.currentGroundFogOpacity;
			groundFogOpacity = config.groundFog() ? groundFogOpacity : 0;
			glUniform1f(uniGroundFogOpacity, groundFogOpacity);

			// lightning
			glUniform1f(uniLightningBrightness, environmentManager.lightningBrightness);
			glUniform1i(uniPointLightsCount, Math.min(configMaxDynamicLights, lightManager.visibleLightsCount));

			glUniform1f(uniSaturation, config.saturation().getAmount());
			glUniform1f(uniContrast, config.contrast().getAmount());
			glUniform1i(uniUnderwaterEnvironment, environmentManager.isUnderwater() ? 1 : 0);
			glUniform1i(uniUnderwaterCaustics, config.underwaterCaustics() ? 1 : 0);
			glUniform3fv(uniUnderwaterCausticsColor, environmentManager.currentUnderwaterCausticsColor);
			glUniform1f(uniUnderwaterCausticsStrength, environmentManager.currentUnderwaterCausticsStrength);

			double lightPitchRadians = Math.toRadians(lightPitch);
			double lightYawRadians = Math.toRadians(lightYaw);
			glUniform3f(uniLightDirection,
				(float) (Math.cos(lightPitchRadians) * -Math.sin(lightYawRadians)),
				(float) -Math.sin(lightPitchRadians),
				(float) (Math.cos(lightPitchRadians) * -Math.cos(lightYawRadians)));

			// use a curve to calculate max bias value based on the density of the shadow map
			float shadowPixelsPerTile = (float)config.shadowResolution().getValue() / (float)config.shadowDistance().getValue();
			float maxBias = 26f * (float)Math.pow(0.925f, (0.4f * shadowPixelsPerTile + -10f)) + 13f;
			glUniform1f(uniShadowMaxBias, maxBias / 10000f);

			glUniform1i(uniShadowsEnabled, configShadowsEnabled ? 1 : 0);

			// Calculate projection matrix
			float[] projectionMatrix = Mat4.scale(client.getScale(), client.getScale(), 1);
			Mat4.mul(projectionMatrix, Mat4.projection(viewportWidth, viewportHeight, 50));
			Mat4.mul(projectionMatrix, Mat4.rotateX((float) -(Math.PI - pitch * Perspective.UNIT)));
			Mat4.mul(projectionMatrix, Mat4.rotateY((float) (yaw * Perspective.UNIT)));
			Mat4.mul(projectionMatrix, Mat4.translate(-client.getCameraX2(), -client.getCameraY2(), -client.getCameraZ2()));
			glUniformMatrix4fv(uniProjectionMatrix, false, projectionMatrix);

			// Bind directional light projection matrix
			glUniformMatrix4fv(uniLightProjectionMatrix, false, lightProjectionMatrix);

			// Bind uniforms
			glUniformBlockBinding(glProgram, uniBlockMain, 0);
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

			// Draw buffers
			glBindVertexArray(vaoHandle);

			glEnableVertexAttribArray(0);
			glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
			glVertexAttribIPointer(0, 4, GL_INT, 0, 0);

			glEnableVertexAttribArray(1);
			glBindBuffer(GL_ARRAY_BUFFER, uvBuffer);
			glVertexAttribPointer(1, 4, GL_FLOAT, false, 0, 0);

			glEnableVertexAttribArray(2);
			glBindBuffer(GL_ARRAY_BUFFER, normalBuffer);
			glVertexAttribPointer(2, 4, GL_FLOAT, false, 0, 0);

			glDrawArrays(GL_TRIANGLES, 0, renderBufferOffset);

			glDisable(GL_BLEND);
			glDisable(GL_CULL_FACE);

			glUseProgram(0);

			if (aaEnabled)
			{
				glBindFramebuffer(GL_READ_FRAMEBUFFER, fboSceneHandle);
				glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
				glBlitFramebuffer(0, 0, lastStretchedCanvasWidth, lastStretchedCanvasHeight,
					0, 0, lastStretchedCanvasWidth, lastStretchedCanvasHeight,
					GL_COLOR_BUFFER_BIT, GL_NEAREST);

				// Reset
				glBindFramebuffer(GL_READ_FRAMEBUFFER, awtContext.getFramebuffer(false));
			}

			stagingBufferVertices.clear();
			stagingBufferUvs.clear();
			stagingBufferNormals.clear();
			modelBufferUnordered.clear();
			modelBufferSmall.clear();
			modelBufferLarge.clear();
			frameModelInfoMap.clear();
			numModelsUnordered = numModelsSmall = numModelsLarge = 0;
		}

		// Texture on UI
		drawUi(overlayColor, canvasHeight, canvasWidth);

		awtContext.swapBuffers();

		drawManager.processDrawComplete(this::screenshot);

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

		checkGLErrors();
	}

	private void drawUi(final int overlayColor, final int canvasHeight, final int canvasWidth)
	{
		glEnable(GL_BLEND);

		glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);

		// Use the texture bound in the first pass
		glUseProgram(glUiProgram);
		glUniform2i(uniTexSourceDimensions, canvasWidth, canvasHeight);
		glUniform1f(uniUiColorBlindnessIntensity, config.colorBlindnessIntensity() / 100.f);
		glUniform4f(uniUiAlphaOverlay,
			(overlayColor >> 16 & 0xFF) / 255f,
			(overlayColor >> 8 & 0xFF) / 255f,
			(overlayColor & 0xFF) / 255f,
			(overlayColor >>> 24) / 255f
		);

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			glDpiAwareViewport(0, 0, dim.width, dim.height);
			glUniform2i(uniTexTargetDimensions, dim.width, dim.height);
		}
		else
		{
			glDpiAwareViewport(0, 0, canvasWidth, canvasHeight);
			glUniform2i(uniTexTargetDimensions, canvasWidth, canvasHeight);
		}

		// Set the sampling function used when stretching the UI.
		// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
		// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
		if (client.isStretchedEnabled())
		{
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

		stagingBufferVertices.clear();
	}

	/**
	 * Convert the front framebuffer to an Image
	 *
	 * @return
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
			final Graphics2D graphics = (Graphics2D) canvas.getGraphics();
			final AffineTransform t = graphics.getTransform();
			width = getScaledValue(t.getScaleX(), width);
			height = getScaledValue(t.getScaleY(), height);
			graphics.dispose();
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
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		switch (gameStateChanged.getGameState()) {
			case LOADING:
				if (config.loadingClearCache()) {
					modelPusher.clearModelCache();
				}
				break;
			case LOGGED_IN:
				uploadScene();
				checkGLErrors();
				break;
			case LOGIN_SCREEN:
				// Avoid drawing the last frame's buffer during LOADING after LOGIN_SCREEN
				renderBufferOffset = 0;
				hasLoggedIn = false;
				modelPusher.clearModelCache();
				break;
		}
	}

	private void uploadScene()
	{
		lightManager.reset();

		generateHDSceneData();

		stagingBufferVertices.clear();
		stagingBufferUvs.clear();
		stagingBufferNormals.clear();

		sceneUploader.upload(client.getScene(), stagingBufferVertices, stagingBufferUvs, stagingBufferNormals);

		dynamicOffsetVertices = stagingBufferVertices.position() / VERTEX_SIZE;
		dynamicOffsetUvs = stagingBufferUvs.position() / UV_SIZE;

		stagingBufferVertices.flip();
		stagingBufferUvs.flip();
		stagingBufferNormals.flip();

		updateBuffer(hStagingBufferVertices, GL_ARRAY_BUFFER, stagingBufferVertices.getBuffer(), GL_STATIC_DRAW, CL_MEM_READ_ONLY);
		updateBuffer(hStagingBufferUvs, GL_ARRAY_BUFFER, stagingBufferUvs.getBuffer(), GL_STATIC_DRAW, CL_MEM_READ_ONLY);
		updateBuffer(hStagingBufferNormals, GL_ARRAY_BUFFER, stagingBufferNormals.getBuffer(), GL_STATIC_DRAW, CL_MEM_READ_ONLY);

		stagingBufferVertices.clear();
		stagingBufferUvs.clear();
		stagingBufferNormals.clear();
	}

	public void reloadSceneNextGameTick()
	{
		reloadSceneIn(1);
	}

	public void reloadSceneIn(int gameTicks)
	{
		assert gameTicks > 0 : "A value <= 0 will not reload the scene";
		if (gameTicks > gameTicksUntilSceneReload) {
			gameTicksUntilSceneReload = gameTicks;
		}
	}

	void generateHDSceneData()
	{
		environmentManager.loadSceneEnvironments();
		lightManager.loadSceneLights();

		long procGenTimer = System.currentTimeMillis();
		long timerCalculateTerrainNormals, timerGenerateTerrainData, timerGenerateUnderwaterTerrain;

		long startTime = System.currentTimeMillis();
		proceduralGenerator.generateUnderwaterTerrain(client.getScene());
		timerGenerateUnderwaterTerrain = (int)(System.currentTimeMillis() - startTime);
		startTime = System.currentTimeMillis();
		proceduralGenerator.calculateTerrainNormals(client.getScene());
		timerCalculateTerrainNormals = (int)(System.currentTimeMillis() - startTime);
		startTime = System.currentTimeMillis();
		proceduralGenerator.generateTerrainData(client.getScene());
		timerGenerateTerrainData = (int)(System.currentTimeMillis() - startTime);

		log.debug("procedural data generation took {}ms to complete", (System.currentTimeMillis() - procGenTimer));
		log.debug("-- calculateTerrainNormals: {}ms", timerCalculateTerrainNormals);
		log.debug("-- generateTerrainData: {}ms", timerGenerateTerrainData);
		log.debug("-- generateUnderwaterTerrain: {}ms", timerGenerateUnderwaterTerrain);
	}

	private boolean skyboxColorChanged = false;

	@Subscribe(priority = -1)
	public void onBeforeRender(BeforeRender event) {
		// Update sky color after the skybox plugin has had time to update the client's sky color
		if (skyboxColorChanged) {
			skyboxColorChanged = false;
			environmentManager.updateSkyColor();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals("skybox") && config.defaultSkyColor() == DefaultSkyColor.RUNELITE)
		{
			skyboxColorChanged = true;
			return;
		}

		if (!event.getGroup().equals("hd"))
		{
			return;
		}

		String key = event.getKey();

		switch (key)
		{
			case "shadowsEnabled":
				configShadowsEnabled = config.shadowsEnabled();
				clientThread.invoke(() ->
				{
					modelPusher.clearModelCache();
					shutdownShadowMapFbo();
					initShadowMapFbo();
				});
				break;
			case "shadowResolution":
				clientThread.invoke(() ->
				{
					shutdownShadowMapFbo();
					initShadowMapFbo();
				});
				break;
			case "textureResolution":
			case "hdInfernalTexture":
			case KEY_WINTER_THEME:
				configHdInfernalTexture = config.hdInfernalTexture();
				textureManager.freeTextures();
			case "hideBakedEffects":
			case "groundBlending":
			case "groundTextures":
			case "objectTextures":
			case "tzhaarHD":
			case KEY_LEGACY_GREY_COLORS:
				configHideBakedEffects = config.hideBakedEffects();
				configGroundBlending = config.groundBlending();
				configGroundTextures = config.groundTextures();
				configModelTextures = config.objectTextures();
				configTzhaarHD = config.tzhaarHD();
				configWinterTheme = config.winterTheme();
				configReduceOverExposure = config.enableLegacyGreyColors();
				clientThread.invoke(() -> {
					modelPusher.clearModelCache();
					uploadScene();
				});
				break;
			case "projectileLights":
				configProjectileLights = config.projectileLights();
				break;
			case "npcLights":
				configNpcLights = config.npcLights();
				break;
			case "expandShadowDraw":
				configExpandShadowDraw = config.expandShadowDraw();
				break;
			case "maxDynamicLights":
				clientThread.invoke(() -> {
					configMaxDynamicLights = config.maxDynamicLights().getValue();
					recompilePrograms();
				});
				break;
			case "anisotropicFilteringLevel":
				textureManager.freeTextures();
				break;
			case "uiScalingMode":
			case "colorBlindMode":
			case "parallaxMappingMode":
			case "macosIntelWorkaround":
				clientThread.invoke(this::recompilePrograms);
				break;
			case "unlockFps":
			case "vsyncMode":
			case "fpsTarget":
				log.debug("Rebuilding sync mode");
				clientThread.invoke(this::setupSyncMode);
				break;
			case KEY_MODEL_CACHING:
			case KEY_MODEL_CACHE_SIZE:
				configEnableModelCaching = config.enableModelCaching();
				clientThread.invoke(() -> {
					modelPusher.shutDown();
					modelPusher.startUp();
				});
				break;
			case KEY_MODEL_BATCHING:
				configEnableModelBatching = config.enableModelBatching();
				break;
		}
	}

	private void setupSyncMode()
	{
		final boolean unlockFps = config.unlockFps();
		client.setUnlockedFps(unlockFps);

		// Without unlocked fps, the client manages sync on its 20ms timer
		HdPluginConfig.SyncMode syncMode = unlockFps
				? this.config.syncMode()
				: HdPluginConfig.SyncMode.OFF;

		int swapInterval = 0;
		switch (syncMode)
		{
			case ON:
				swapInterval = 1;
				break;
			case OFF:
				swapInterval = 0;
				break;
			case ADAPTIVE:
				swapInterval = -1;
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
	private boolean isVisible(Model model, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z)
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
						return var22 / depth < Rasterizer3D_clipMidY2;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Draw a renderable in the scene
	 *
	 * @param renderable
	 * @param orientation
	 * @param pitchSin
	 * @param pitchCos
	 * @param yawSin
	 * @param yawCos
	 * @param x
	 * @param y
	 * @param z
	 * @param hash
	 */
	@Override
	public void draw(Renderable renderable, int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z, long hash)
	{
		if (modelOverrideManager.shouldHideModel(hash, x, z)) {
			return;
		}

		Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
		if (model == null || model.getFaceCount() == 0) {
			// skip models with zero faces
			// this does seem to happen sometimes (mostly during loading)
			// should save some CPU cycles here and there
			return;
		}

		// Model may be in the scene buffer
		if (model.getSceneId() == sceneUploader.sceneId)
		{
			model.calculateBoundsCylinder();

			if (!isVisible(model, pitchSin, pitchCos, yawSin, yawCos, x, y, z))
			{
				return;
			}

			if ((model.getBufferOffset() & 0b11) == 0b11)
			{
				// this object was marked to be skipped
				return;
			}

			model.calculateExtreme(orientation);
			client.checkClickbox(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash);

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
		}
		else
		{
			// Temporary model (animated or otherwise not a static Model on the scene)
			// Apply height to renderable from the model
			if (model != renderable)
			{
				renderable.setModelHeight(model.getModelHeight());
			}

			model.calculateBoundsCylinder();

			if (!isVisible(model, pitchSin, pitchCos, yawSin, yawCos, x, y, z))
			{
				return;
			}

			if ((model.getBufferOffset() & 0b11) == 0b11)
			{
				// this object was marked to be skipped
				return;
			}

			model.calculateExtreme(orientation);
			client.checkClickbox(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash);

			eightIntWrite[3] = renderBufferOffset;
			eightIntWrite[4] = model.getRadius() << 12 | orientation;
			eightIntWrite[5] = x + client.getCameraX2();
			eightIntWrite[6] = y + client.getCameraY2();
			eightIntWrite[7] = z + client.getCameraZ2();

			TempModelInfo tempModelInfo = null;
			int batchHash = 0;

			if (configEnableModelBatching || configEnableModelCaching) {
				modelHasher.setModel(model);
				if (configEnableModelBatching) {
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
				int vertexOffset = dynamicOffsetVertices + stagingBufferVertices.position() / VERTEX_SIZE;
				int uvOffset = dynamicOffsetUvs + stagingBufferUvs.position() / UV_SIZE;

				ModelOverride modelOverride = modelOverrideManager.getOverride(hash);
				final int[] lengths = modelPusher.pushModel(hash, model, stagingBufferVertices, stagingBufferUvs, stagingBufferNormals,
					0, 0, 0, modelOverride, ObjectType.NONE, true);
				final int faceCount = lengths[0] / 3;
				if (lengths[1] <= 0)
					uvOffset = -1;

				eightIntWrite[0] = vertexOffset;
				eightIntWrite[1] = uvOffset;
				eightIntWrite[2] = faceCount;
				bufferForTriangles(faceCount).ensureCapacity(8).put(eightIntWrite);

				renderBufferOffset += lengths[0];

				// add this temporary model to the map for batching purposes
				if (configEnableModelBatching) {
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
	public boolean drawFace(Model model, int face)
	{
		return false;
	}

	/**
	 * returns the correct buffer based on triangle count and updates model count
	 *
	 * @param triangles
	 * @return
	 */
	private GpuIntBuffer bufferForTriangles(int triangles)
	{
		if (triangles <= SMALL_TRIANGLE_COUNT)
		{
			++numModelsSmall;
			return modelBufferSmall;
		}
		else
		{
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
			final Graphics2D graphics = (Graphics2D) canvas.getGraphics();
			if (graphics == null) return;
			final AffineTransform t = graphics.getTransform();
			glViewport(
				getScaledValue(t.getScaleX(), x),
				getScaledValue(t.getScaleY(), y),
				getScaledValue(t.getScaleX(), width),
				getScaledValue(t.getScaleY(), height));
			graphics.dispose();
		}
	}

	private int getDrawDistance()
	{
		final int limit = MAX_DISTANCE;
		return Ints.constrainToRange(config.drawDistance(), 0, limit);
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
			if (glBuffer.cl_mem != null)
			{
				CL.clReleaseMemObject(glBuffer.cl_mem);
			}

			// allocate new
			if (glBuffer.size == 0)
			{
				// opencl does not allow 0-size gl buffers, it will segfault on macos
				glBuffer.cl_mem = null;
			}
			else
			{
				assert glBuffer.size > 0 : "Size -1 should not reach this point";
				glBuffer.cl_mem = clCreateFromGLBuffer(openCLManager.context, clFlags, glBuffer.glBufferId, null);
			}
		}
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved projectileMoved)
	{
		lightManager.addProjectileLight(projectileMoved.getProjectile());
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned npcSpawned)
	{
		lightManager.addNpcLights(npcSpawned.getNpc());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned npcDespawned)
	{
		lightManager.removeNpcLight(npcDespawned);
	}

	@Subscribe
	public void onNpcChanged(NpcChanged npcChanged)
	{
		lightManager.updateNpcChanged(npcChanged);
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned gameObjectSpawned)
	{
		GameObject gameObject = gameObjectSpawned.getGameObject();
		lightManager.addObjectLight(gameObject, gameObjectSpawned.getTile().getRenderLevel(), gameObject.sizeX(), gameObject.sizeY(), gameObject.getOrientation().getAngle());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned gameObjectDespawned)
	{
		GameObject gameObject = gameObjectDespawned.getGameObject();
		lightManager.removeObjectLight(gameObject);
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned wallObjectSpawned)
	{
		WallObject wallObject = wallObjectSpawned.getWallObject();
		lightManager.addObjectLight(wallObject, wallObjectSpawned.getTile().getRenderLevel(), 1, 1, wallObject.getOrientationA());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned wallObjectDespawned)
	{
		WallObject wallObject = wallObjectDespawned.getWallObject();
		lightManager.removeObjectLight(wallObject);
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned decorativeObjectSpawned)
	{
		DecorativeObject decorativeObject = decorativeObjectSpawned.getDecorativeObject();
		lightManager.addObjectLight(decorativeObject, decorativeObjectSpawned.getTile().getRenderLevel());
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned decorativeObjectDespawned)
	{
		DecorativeObject decorativeObject = decorativeObjectDespawned.getDecorativeObject();
		lightManager.removeObjectLight(decorativeObject);
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned groundObjectSpawned)
	{
		GroundObject groundObject = groundObjectSpawned.getGroundObject();
		lightManager.addObjectLight(groundObject, groundObjectSpawned.getTile().getRenderLevel());
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned groundObjectDespawned)
	{
		GroundObject groundObject = groundObjectDespawned.getGroundObject();
		lightManager.removeObjectLight(groundObject);
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated graphicsObjectCreated)
	{
		GraphicsObject graphicsObject = graphicsObjectCreated.getGraphicsObject();
		lightManager.addGraphicsObjectLight(graphicsObject);
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		if (gameTicksUntilSceneReload > 0 && --gameTicksUntilSceneReload == 0) {
			uploadScene();
		}

		if (!hasLoggedIn && client.getGameState() == GameState.LOGGED_IN)
		{
			hasLoggedIn = true;
		}
	}

	private void checkGLErrors()
	{
		if (!log.isDebugEnabled())
		{
			return;
		}

		for (; ; )
		{
			int err = glGetError();
			if (err == GL_NO_ERROR)
			{
				return;
			}

			String errStr;
			switch (err)
			{
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
					errStr = "" + err;
					break;
			}

			log.debug("glGetError:", new Exception(errStr));
		}
	}

	private void displayUpdateMessage() {
		int messageId = 1;
		if (config.getPluginUpdateMessage() >= messageId) {
			return; // Don't show the same message multiple times
		}

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
