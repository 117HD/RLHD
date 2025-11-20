package rs117.hd.renderer.legacy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.hooks.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.DrawManager;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.ColorFilter;
import rs117.hd.config.DynamicLights;
import rs117.hd.model.ModelHasher;
import rs117.hd.model.ModelOffsets;
import rs117.hd.opengl.compute.ComputeMode;
import rs117.hd.opengl.compute.OpenCLManager;
import rs117.hd.opengl.shader.ModelPassthroughComputeProgram;
import rs117.hd.opengl.shader.ModelSortingComputeProgram;
import rs117.hd.opengl.shader.SceneShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.shader.ShadowShaderProgram;
import rs117.hd.opengl.uniforms.UBOCompute;
import rs117.hd.opengl.uniforms.UBOLights;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.Renderer;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.FishingSpotReplacer;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.GroundMaterialManager;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.TextureManager;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.WaterTypeManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.NpcDisplacementCache;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.buffer.SharedGLBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.COLOR_FILTER_FADE_DURATION;
import static rs117.hd.HdPlugin.MAX_FACE_COUNT;
import static rs117.hd.HdPlugin.NEAR_PLANE;
import static rs117.hd.HdPlugin.ORTHOGRAPHIC_ZOOM;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_TILE_HEIGHT_MAP;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class LegacyRenderer implements Renderer {
	public static final int GROUND_MIN_Y = 350; // how far below the ground models extend
	public static final int VERTEX_SIZE = 4; // 4 ints per vertex
	public static final int UV_SIZE = 4; // 4 floats per vertex
	public static final int NORMAL_SIZE = 4; // 4 floats per vertex

	private static int UNIFORM_BLOCK_COUNT = HdPlugin.UNIFORM_BLOCK_COUNT;
	public static final int UNIFORM_BLOCK_COMPUTE = UNIFORM_BLOCK_COUNT++;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private HdPlugin plugin;

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
	private LegacySceneUploader sceneUploader;

	@Inject
	private LegacyModelPusher modelPusher;

	@Inject
	private ModelHasher modelHasher;

	@Inject
	private FishingSpotReplacer fishingSpotReplacer;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	public SceneShaderProgram sceneProgram;

	@Inject
	public ModelPassthroughComputeProgram modelPassthroughComputeProgram;

	@Inject
	public ShadowShaderProgram shadowProgram;

	private final ComputeMode computeMode = HdPlugin.APPLE ? ComputeMode.OPENCL : ComputeMode.OPENGL;
	private final List<ModelSortingComputeProgram> modelSortingComputePrograms = new ArrayList<>();

	public int vaoScene;
	public int texTileHeightMap;
	public SharedGLBuffer hStagingBufferVertices;
	public SharedGLBuffer hStagingBufferUvs;
	public SharedGLBuffer hStagingBufferNormals;
	public SharedGLBuffer hRenderBufferVertices;
	public SharedGLBuffer hRenderBufferUvs;
	public SharedGLBuffer hRenderBufferNormals;
	public int numPassthroughModels;
	public GpuIntBuffer modelPassthroughBuffer;
	public SharedGLBuffer hModelPassthroughBuffer;
	// ordered by face count from small to large
	public int numSortingBins;
	public int[] modelSortingBinFaceCounts; // facesPerThread * threadCount
	public int[] modelSortingBinThreadCounts;
	public int[] numModelsToSort;
	public GpuIntBuffer[] modelSortingBuffers;
	public SharedGLBuffer[] hModelSortingBuffers;
	public int dynamicOffsetVertices;
	public int dynamicOffsetUvs;
	public int renderBufferOffset;
	public final Map<Long, ModelOffsets> frameModelInfoMap = new HashMap<Long, ModelOffsets>();
	// Camera position and orientation may be reused from the old scene while hopping, prior to drawScene being called
	public int visibilityCheckZoom;
	public boolean tileVisibilityCached;
	public final boolean[][][] tileIsVisible = new boolean[Constants.MAX_Z][Constants.EXTENDED_SCENE_SIZE][Constants.EXTENDED_SCENE_SIZE];

	private final int[] eightIntWrite = new int[8];

	@Getter
	@Nullable
	private LegacySceneContext sceneContext;
	private LegacySceneContext nextSceneContext;
	private int gameTicksUntilSceneReload;

	private UBOCompute uboCompute;

	@Override
	public boolean supportsGpu(GLCapabilities glCaps) {
		return computeMode == ComputeMode.OPENGL ? glCaps.OpenGL43 : glCaps.OpenGL31;
	}

	@Override
	public int gpuFlags() {
		return
			DrawCallbacks.NORMALS |
			DrawCallbacks.HILLSKEW;
	}

	@Override
	public void initialize() {
		modelPusher.startUp();

		renderBufferOffset = 0;
		numPassthroughModels = 0;
		numModelsToSort = null;

		// Create scene VAO
		vaoScene = glGenVertexArrays();

		int maxComputeThreadCount;
		if (computeMode == ComputeMode.OPENCL) {
			clManager.startUp(this, plugin.awtContext);
			maxComputeThreadCount = clManager.getMaxWorkGroupSize();
		} else {
			maxComputeThreadCount = glGetInteger(GL43C.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS);
		}
		initializeModelSortingBins(maxComputeThreadCount);

		initializeBuffers();
	}

	@Override
	public synchronized void destroy() {
		modelPusher.shutDown();

		if (vaoScene != 0)
			glDeleteVertexArrays(vaoScene);
		vaoScene = 0;

		destroyBuffers();
		destroyTileHeightMap();
		destroyModelSortingBins();
		if (modelPassthroughBuffer != null)
			modelPassthroughBuffer.destroy();
		modelPassthroughBuffer = null;

		if (sceneContext != null)
			sceneContext.destroy();
		sceneContext = null;

		if (nextSceneContext != null)
			nextSceneContext.destroy();
		nextSceneContext = null;

		clManager.shutDown();
	}

	@Override
	public void waitUntilIdle() {
		if (computeMode == ComputeMode.OPENCL)
			clManager.finish();
		glFinish();
	}

	@Subscribe
	public void onGameTick(GameTick gameTick) {
		if (gameTicksUntilSceneReload > 0) {
			if (gameTicksUntilSceneReload == 1)
				reloadScene();
			--gameTicksUntilSceneReload;
		}
	}

	@Override
	public void processConfigChanges(Set<String> keys) {
		if (keys.contains(KEY_MODEL_CACHING) || keys.contains(KEY_MODEL_CACHE_SIZE)) {
			modelPusher.shutDown();
			modelPusher.startUp();
		}
	}

	@Override
	public void clearCaches() {
		modelPusher.clearModelCache();
	}

	@Override
	public void addShaderIncludes(ShaderIncludes includes) {
		includes.addUniformBuffer(uboCompute);
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		sceneProgram.compile(includes);

		shadowProgram.setMode(plugin.configShadowMode);
		shadowProgram.compile(includes);

		if (computeMode == ComputeMode.OPENCL) {
			clManager.initializePrograms();
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
	}

	@Override
	public void destroyShaders() {
		sceneProgram.destroy();
		shadowProgram.destroy();

		if (computeMode == ComputeMode.OPENGL) {
			modelPassthroughComputeProgram.destroy();
			for (var program : modelSortingComputePrograms)
				program.destroy();
			modelSortingComputePrograms.clear();
		} else {
			clManager.destroyPrograms();
		}
	}

	public void initializeModelSortingBins(int maxThreadCount) {
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

	public void destroyModelSortingBins() {
		// Don't allow redrawing the previous frame if the model sorting buffers are no longer valid
		plugin.redrawPreviousFrame = false;

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

	public void updateSceneVao(GLBuffer vertexBuffer, GLBuffer uvBuffer, GLBuffer normalBuffer) {
		glBindVertexArray(vaoScene);

		// Position
		glEnableVertexAttribArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer.id);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0);

		// UVs
		glEnableVertexAttribArray(1);
		glBindBuffer(GL_ARRAY_BUFFER, uvBuffer.id);
		glVertexAttribPointer(1, 3, GL_FLOAT, false, 16, 0);

		// Normals
		glEnableVertexAttribArray(2);
		glBindBuffer(GL_ARRAY_BUFFER, normalBuffer.id);
		glVertexAttribPointer(2, 3, GL_FLOAT, false, 16, 0);

		// Alpha, HSL
		glEnableVertexAttribArray(3);
		glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer.id);
		glVertexAttribIPointer(3, 1, GL_INT, 16, 12);

		// Material data
		glEnableVertexAttribArray(4);
		glBindBuffer(GL_ARRAY_BUFFER, uvBuffer.id);
		glVertexAttribIPointer(4, 1, GL_INT, 16, 12);

		// Terrain data
		glEnableVertexAttribArray(5);
		glBindBuffer(GL_ARRAY_BUFFER, normalBuffer.id);
		glVertexAttribIPointer(5, 1, GL_INT, 16, 12);
	}

	private void initializeBuffers() {
		hStagingBufferVertices = new SharedGLBuffer("Staging Vertices", GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW, CL_MEM_READ_ONLY);
		hStagingBufferUvs = new SharedGLBuffer("Staging UVs", GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW, CL_MEM_READ_ONLY);
		hStagingBufferNormals = new SharedGLBuffer("Staging Normals", GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW, CL_MEM_READ_ONLY);
		hRenderBufferVertices = new SharedGLBuffer("Render Vertices", GL_ARRAY_BUFFER, GL_STREAM_COPY, CL_MEM_WRITE_ONLY);
		hRenderBufferUvs = new SharedGLBuffer("Render UVs", GL_ARRAY_BUFFER, GL_STREAM_COPY, CL_MEM_WRITE_ONLY);
		hRenderBufferNormals = new SharedGLBuffer("Render Normals", GL_ARRAY_BUFFER, GL_STREAM_COPY, CL_MEM_WRITE_ONLY);
		hModelPassthroughBuffer = new SharedGLBuffer("Model Passthrough", GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);

		uboCompute = new UBOCompute();
		uboCompute.initialize(UNIFORM_BLOCK_COMPUTE);

		modelPassthroughBuffer = new GpuIntBuffer();

		hStagingBufferVertices.initialize();
		hStagingBufferUvs.initialize();
		hStagingBufferNormals.initialize();

		hRenderBufferVertices.initialize();
		hRenderBufferUvs.initialize();
		hRenderBufferNormals.initialize();

		hModelPassthroughBuffer.initialize();
	}

	private void destroyBuffers() {
		uboCompute.destroy();
		uboCompute = null;

		hStagingBufferVertices.destroy();
		hStagingBufferUvs.destroy();
		hStagingBufferNormals.destroy();

		hRenderBufferVertices.destroy();
		hRenderBufferUvs.destroy();
		hRenderBufferNormals.destroy();

		hModelPassthroughBuffer.destroy();

		hStagingBufferVertices = null;
		hStagingBufferUvs = null;
		hStagingBufferNormals = null;
		hRenderBufferVertices = null;
		hRenderBufferUvs = null;
		hRenderBufferNormals = null;
		hModelPassthroughBuffer = null;

		clManager.shutDown();
	}

	public void initializeTileHeightMap(Scene scene) {
		final int TILE_HEIGHT_BUFFER_SIZE = Constants.MAX_Z * Constants.EXTENDED_SCENE_SIZE * Constants.EXTENDED_SCENE_SIZE * Short.BYTES;
		ShortBuffer tileBuffer = ByteBuffer
			.allocateDirect(TILE_HEIGHT_BUFFER_SIZE)
			.order(ByteOrder.nativeOrder())
			.asShortBuffer();

		int[][][] tileHeights = scene.getTileHeights();
		for (int z = 0; z < Constants.MAX_Z; ++z) {
			for (int y = 0; y < Constants.EXTENDED_SCENE_SIZE; ++y) {
				for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE; ++x) {
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
		glTexImage3D(
			GL_TEXTURE_3D, 0, GL_R16I,
			Constants.EXTENDED_SCENE_SIZE, Constants.EXTENDED_SCENE_SIZE, Constants.MAX_Z,
			0, GL_RED_INTEGER, GL_SHORT, tileBuffer
		);
	}

	public void destroyTileHeightMap() {
		if (texTileHeightMap != 0)
			glDeleteTextures(texTileHeightMap);
		texTileHeightMap = 0;
	}

	@Override
	public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane) {
		plugin.updateSceneFbo();

		if (sceneContext == null || plugin.sceneViewport == null)
			return;

		frameTimer.begin(Timer.DRAW_FRAME);
		frameTimer.begin(Timer.DRAW_SCENE);

		final Scene scene = client.getTopLevelWorldView().getScene();
		int drawDistance = plugin.getDrawDistance();
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
				sceneContext.sceneBase[2] + client.getTopLevelWorldView().getPlane()
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
				if (plugin.justChangedArea) {
					// Prevent getting stuck in a scene reloading loop if this breaks for any reason
					sceneContext.forceDisableAreaHiding = true;
					log.error(
						"Force disabling area hiding after moving from {} to {} at {}",
						sceneContext.currentArea,
						newArea,
						worldPos
					);
				} else {
					plugin.justChangedArea = true;
				}
				// Reload the scene to reapply area hiding
				client.setGameState(GameState.LOADING);
				updateUniforms = false;
				plugin.redrawPreviousFrame = true;
			} else {
				plugin.justChangedArea = false;
			}
		} else {
			plugin.justChangedArea = false;
		}

		if (!plugin.enableFreezeFrame) {
			if (!plugin.redrawPreviousFrame) {
				// Only reset the target buffer offset right before drawing the scene. That way if there are frames
				// after this that don't involve a scene draw, like during LOADING/HOPPING/CONNECTION_LOST, we can
				// still redraw the previous frame's scene to emulate the client behavior of not painting over the
				// viewport buffer.
				renderBufferOffset = sceneContext.staticVertexCount;

				plugin.drawnTileCount = 0;
				plugin.drawnStaticRenderableCount = 0;
				plugin.drawnDynamicRenderableCount = 0;

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
				int newZoom = plugin.configShadowsEnabled && plugin.configExpandShadowDraw ?
					client.get3dZoom() / 2 :
					client.get3dZoom();
				if (!Arrays.equals(plugin.cameraPosition, newCameraPosition) ||
					!Arrays.equals(plugin.cameraOrientation, newCameraOrientation) ||
					visibilityCheckZoom != newZoom ||
					drawDistanceChanged
				) {
					copyTo(plugin.cameraPosition, newCameraPosition);
					copyTo(plugin.cameraOrientation, newCameraOrientation);
					visibilityCheckZoom = newZoom;
					tileVisibilityCached = false;
				}

				if (sceneContext.scene == scene) {
					plugin.cameraFocalPoint[0] = (int) client.getCameraFocalPointX();
					plugin.cameraFocalPoint[1] = (int) client.getCameraFocalPointZ();
					Arrays.fill(plugin.cameraShift, 0);
				} else {
					plugin.cameraShift[0] = plugin.cameraFocalPoint[0] - (int) client.getCameraFocalPointX();
					plugin.cameraShift[1] = plugin.cameraFocalPoint[1] - (int) client.getCameraFocalPointZ();
					plugin.cameraPosition[0] += plugin.cameraShift[0];
					plugin.cameraPosition[2] += plugin.cameraShift[1];
				}

				uboCompute.yaw.set(plugin.cameraOrientation[0]);
				uboCompute.pitch.set(plugin.cameraOrientation[1]);
				uboCompute.centerX.set(client.getCenterX());
				uboCompute.centerY.set(client.getCenterY());
				uboCompute.zoom.set(client.getScale());
				uboCompute.cameraX.set(plugin.cameraPosition[0]);
				uboCompute.cameraY.set(plugin.cameraPosition[1]);
				uboCompute.cameraZ.set(plugin.cameraPosition[2]);

				uboCompute.windDirectionX.set(cos(environmentManager.currentWindAngle));
				uboCompute.windDirectionZ.set(sin(environmentManager.currentWindAngle));
				uboCompute.windStrength.set(environmentManager.currentWindStrength);
				uboCompute.windCeiling.set(environmentManager.currentWindCeiling);
				uboCompute.windOffset.set(plugin.windOffset);

				if (plugin.configCharacterDisplacement) {
					// The local player needs to be added first for distance culling
					Model playerModel = localPlayer.getModel();
					if (playerModel != null)
						uboCompute.addCharacterPosition(lp.getX(), lp.getY(), (int) (Perspective.LOCAL_TILE_SIZE * 1.33f));
				}

				// Calculate the viewport dimensions before scaling in order to include the extra padding
				int viewportWidth = (int) (plugin.sceneViewport[2] / plugin.sceneViewportScale[0]);
				int viewportHeight = (int) (plugin.sceneViewport[3] / plugin.sceneViewportScale[1]);

				// Calculate projection matrix
				float[] projectionMatrix = Mat4.scale(client.getScale(), client.getScale(), 1);
				if (plugin.orthographicProjection) {
					Mat4.mul(projectionMatrix, Mat4.scale(ORTHOGRAPHIC_ZOOM, ORTHOGRAPHIC_ZOOM, -1));
					Mat4.mul(projectionMatrix, Mat4.orthographic(viewportWidth, viewportHeight, 40000));
				} else {
					Mat4.mul(projectionMatrix, Mat4.perspective(viewportWidth, viewportHeight, NEAR_PLANE));
				}


				// Calculate view matrix
				plugin.viewMatrix = Mat4.rotateX(plugin.cameraOrientation[1]);
				Mat4.mul(plugin.viewMatrix, Mat4.rotateY(plugin.cameraOrientation[0]));
				Mat4.mul(
					plugin.viewMatrix,
					Mat4.translate(-plugin.cameraPosition[0], -plugin.cameraPosition[1], -plugin.cameraPosition[2])
				);

				// Calculate view proj & inv matrix
				plugin.viewProjMatrix = Mat4.identity();
				Mat4.mul(plugin.viewProjMatrix, projectionMatrix);
				Mat4.mul(plugin.viewProjMatrix, plugin.viewMatrix);
				Mat4.extractPlanes(plugin.viewProjMatrix, plugin.cameraFrustum);
				plugin.invViewProjMatrix = Mat4.inverse(plugin.viewProjMatrix);

				if (sceneContext.scene == scene) {
					try {
						frameTimer.begin(Timer.UPDATE_ENVIRONMENT);
						environmentManager.update(sceneContext);
						frameTimer.end(Timer.UPDATE_ENVIRONMENT);

						frameTimer.begin(Timer.UPDATE_LIGHTS);
						lightManager.update(sceneContext, plugin.cameraShift, plugin.cameraFrustum);
						frameTimer.end(Timer.UPDATE_LIGHTS);
					} catch (Exception ex) {
						log.error("Error while updating environment or lights:", ex);
						plugin.stopPlugin();
						return;
					}
				}

				plugin.uboGlobal.cameraPos.set(plugin.cameraPosition);
				plugin.uboGlobal.viewMatrix.set(plugin.viewMatrix);
				plugin.uboGlobal.projectionMatrix.set(plugin.viewProjMatrix);
				plugin.uboGlobal.invProjectionMatrix.set(plugin.invViewProjMatrix);
				plugin.uboGlobal.pointLightsCount.set(sceneContext.numVisibleLights);
				plugin.uboGlobal.upload();
			}
		}

		if (plugin.configDynamicLights != DynamicLights.NONE && sceneContext.scene == scene && updateUniforms) {
			// Update lights UBO
			assert sceneContext.numVisibleLights <= UBOLights.MAX_LIGHTS;

			frameTimer.begin(Timer.UPDATE_LIGHTS);
			final float[] lightPosition = new float[4];
			final float[] lightColor = new float[4];
			for (int i = 0; i < sceneContext.numVisibleLights; i++) {
				final Light light = sceneContext.lights.get(i);
				final float lightRadiusSq = light.radius * light.radius;
				lightPosition[0] = light.pos[0] + plugin.cameraShift[0];
				lightPosition[1] = light.pos[1];
				lightPosition[2] = light.pos[2] + plugin.cameraShift[1];
				lightPosition[3] = lightRadiusSq;

				lightColor[0] = light.color[0] * light.strength;
				lightColor[1] = light.color[1] * light.strength;
				lightColor[2] = light.color[2] * light.strength;
				lightColor[3] = 0.0f;

				plugin.uboLights.setLight(i, lightPosition, lightColor);

				if (plugin.configTiledLighting) {
					// Pre-calculate the view space position of the light, to save having to do the multiplication in the culling shader
					lightPosition[3] = 1.0f;
					Mat4.mulVec(lightPosition, plugin.viewMatrix, lightPosition);
					lightPosition[3] = lightRadiusSq; // Restore lightRadiusSq
					plugin.uboLightsCulling.setLight(i, lightPosition, lightColor);
				}
			}

			plugin.uboLights.upload();
			plugin.uboLightsCulling.upload();
			frameTimer.end(Timer.UPDATE_LIGHTS);

			// Perform tiled lighting culling before the compute memory barrier, so it's performed asynchronously
			if (plugin.configTiledLighting) {
				plugin.updateTiledLightingFbo();
				assert plugin.fboTiledLighting != 0;

				frameTimer.begin(Timer.DRAW_TILED_LIGHTING);
				frameTimer.begin(Timer.RENDER_TILED_LIGHTING);

				glViewport(0, 0, plugin.tiledLightingResolution[0], plugin.tiledLightingResolution[1]);
				glBindFramebuffer(GL_FRAMEBUFFER, plugin.fboTiledLighting);

				glBindVertexArray(plugin.vaoTri);

				if (plugin.tiledLightingImageStoreProgram.isValid()) {
					plugin.tiledLightingImageStoreProgram.use();
					glDrawBuffer(GL_NONE);
					glDrawArrays(GL_TRIANGLES, 0, 3);
				} else {
					glDrawBuffer(GL_COLOR_ATTACHMENT0);
					int layerCount = plugin.configDynamicLights.getTiledLightingLayers();
					for (int layer = 0; layer < layerCount; layer++) {
						plugin.tiledLightingShaderPrograms.get(layer).use();
						glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, plugin.texTiledLighting, 0, layer);
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
		if (!plugin.redrawPreviousFrame) {
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

		if (!plugin.redrawPreviousFrame) {
			numPassthroughModels = 0;
			Arrays.fill(numModelsToSort, 0);
		}
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileY) {
		if (plugin.redrawPreviousFrame || paint.getBufferLen() <= 0)
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
			.put(tileX * Perspective.LOCAL_TILE_SIZE)
			.put(0)
			.put(tileY * Perspective.LOCAL_TILE_SIZE);

		renderBufferOffset += vertexCount;
		plugin.drawnTileCount++;
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileY) {
		if (plugin.redrawPreviousFrame || model.getBufferLen() <= 0)
			return;

		final int localX = tileX * Perspective.LOCAL_TILE_SIZE;
		final int localY = 0;
		final int localZ = tileY * Perspective.LOCAL_TILE_SIZE;

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
			plugin.drawnTileCount++;
		}

		++numPassthroughModels;

		buffer.put(model.getBufferOffset());
		buffer.put(model.getUvBufferOffset());
		buffer.put(bufferLength / 3);
		buffer.put(renderBufferOffset);
		buffer.put(0);
		buffer.put(localX).put(localY).put(localZ);

		renderBufferOffset += bufferLength;
		plugin.drawnTileCount++;
	}

	@Override
	public void draw(int overlayColor) {
		final GameState gameState = client.getGameState();
		if (gameState == GameState.STARTING) {
			frameTimer.end(Timer.DRAW_FRAME);
			return;
		}

		if (plugin.lastFrameTimeMillis > 0) {
			plugin.deltaTime = (float) ((System.currentTimeMillis() - plugin.lastFrameTimeMillis) / 1000.);

			// Restart the plugin to avoid potential buffer corruption if the computer has likely resumed from suspension
			if (plugin.deltaTime > 300) {
				log.debug("Restarting the plugin after probable OS suspend ({} second delta)", plugin.deltaTime);
				plugin.restartPlugin();
				return;
			}

			// If system time changes between frames, clamp the delta to a more sensible value
			if (abs(plugin.deltaTime) > 10)
				plugin.deltaTime = 1 / 60.f;
			plugin.elapsedTime += plugin.deltaTime;
			plugin.windOffset += plugin.deltaTime * environmentManager.currentWindSpeed;

			// The client delta doesn't need clamping
			plugin.deltaClientTime = (float) (plugin.elapsedClientTime - plugin.lastFrameClientTime);
		}
		plugin.lastFrameTimeMillis = System.currentTimeMillis();
		plugin.lastFrameClientTime = plugin.elapsedClientTime;

		try {
			plugin.prepareInterfaceTexture();
		} catch (Exception ex) {
			// Fixes: https://github.com/runelite/runelite/issues/12930
			// Gracefully Handle loss of opengl buffers and context
			log.warn("prepareInterfaceTexture exception", ex);
			plugin.restartPlugin();
			return;
		}

		// Upon logging in, the client will draw some frames with zero geometry before it hides the login screen
		if (gameState.getState() >= GameState.LOGGED_IN.getState() && renderBufferOffset > 0)
			plugin.hasLoggedIn = true;

		// Draw 3d scene
		if (plugin.hasLoggedIn && sceneContext != null && plugin.sceneViewport != null) {
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
			fogDepth *= min(plugin.getDrawDistance(), 90) / 10.f;
			plugin.uboGlobal.useFog.set(fogDepth > 0 ? 1 : 0);
			plugin.uboGlobal.fogDepth.set(fogDepth);
			plugin.uboGlobal.fogColor.set(fogColor);

			plugin.uboGlobal.drawDistance.set((float) plugin.getDrawDistance());
			plugin.uboGlobal.expandedMapLoadingChunks.set(sceneContext.expandedMapLoadingChunks);
			plugin.uboGlobal.colorBlindnessIntensity.set(config.colorBlindnessIntensity() / 100.f);

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
			plugin.uboGlobal.waterColorLight.set(waterColorLight);
			plugin.uboGlobal.waterColorMid.set(waterColorMid);
			plugin.uboGlobal.waterColorDark.set(waterColorDark);

			plugin.uboGlobal.gammaCorrection.set(plugin.getGammaCorrection());
			float ambientStrength = environmentManager.currentAmbientStrength;
			float directionalStrength = environmentManager.currentDirectionalStrength;
			if (config.useLegacyBrightness()) {
				float factor = config.legacyBrightness() / 20f;
				ambientStrength *= factor;
				directionalStrength *= factor;
			}
			plugin.uboGlobal.ambientStrength.set(ambientStrength);
			plugin.uboGlobal.ambientColor.set(environmentManager.currentAmbientColor);
			plugin.uboGlobal.lightStrength.set(directionalStrength);
			plugin.uboGlobal.lightColor.set(environmentManager.currentDirectionalColor);

			plugin.uboGlobal.underglowStrength.set(environmentManager.currentUnderglowStrength);
			plugin.uboGlobal.underglowColor.set(environmentManager.currentUnderglowColor);

			plugin.uboGlobal.groundFogStart.set(environmentManager.currentGroundFogStart);
			plugin.uboGlobal.groundFogEnd.set(environmentManager.currentGroundFogEnd);
			plugin.uboGlobal.groundFogOpacity.set(config.groundFog() ?
				environmentManager.currentGroundFogOpacity :
				0);

			// Lights & lightning
			plugin.uboGlobal.pointLightsCount.set(sceneContext.numVisibleLights);
			plugin.uboGlobal.lightningBrightness.set(environmentManager.getLightningBrightness());

			plugin.uboGlobal.saturation.set(config.saturation() / 100f);
			plugin.uboGlobal.contrast.set(config.contrast() / 100f);
			plugin.uboGlobal.underwaterEnvironment.set(environmentManager.isUnderwater() ? 1 : 0);
			plugin.uboGlobal.underwaterCaustics.set(config.underwaterCaustics() ? 1 : 0);
			plugin.uboGlobal.underwaterCausticsColor.set(environmentManager.currentUnderwaterCausticsColor);
			plugin.uboGlobal.underwaterCausticsStrength.set(environmentManager.currentUnderwaterCausticsStrength);
			plugin.uboGlobal.elapsedTime.set((float) (plugin.elapsedTime % MAX_FLOAT_WITH_128TH_PRECISION));

			float[] lightViewMatrix = Mat4.rotateX(environmentManager.currentSunAngles[0]);
			Mat4.mul(lightViewMatrix, Mat4.rotateY(PI - environmentManager.currentSunAngles[1]));
			// Extract the 3rd column from the light view matrix (the float array is column-major).
			// This produces the light's direction vector in world space, which we negate in order to
			// get the light's direction vector pointing away from each fragment
			plugin.uboGlobal.lightDir.set(-lightViewMatrix[2], -lightViewMatrix[6], -lightViewMatrix[10]);

			if (plugin.configColorFilter != ColorFilter.NONE) {
				plugin.uboGlobal.colorFilter.set(plugin.configColorFilter.ordinal());
				plugin.uboGlobal.colorFilterPrevious.set(plugin.configColorFilterPrevious.ordinal());
				long timeSinceChange = System.currentTimeMillis() - plugin.colorFilterChangedAt;
				plugin.uboGlobal.colorFilterFade.set(clamp(timeSinceChange / COLOR_FILTER_FADE_DURATION, 0, 1));
			}

			if (plugin.configShadowsEnabled && plugin.fboShadowMap != 0
				&& environmentManager.currentDirectionalStrength > 0) {
				frameTimer.begin(Timer.RENDER_SHADOWS);

				// Render to the shadow depth map
				glViewport(0, 0, plugin.shadowMapResolution, plugin.shadowMapResolution);
				glBindFramebuffer(GL_FRAMEBUFFER, plugin.fboShadowMap);
				glClearDepth(1);
				glClear(GL_DEPTH_BUFFER_BIT);
				glDepthFunc(GL_LEQUAL);

				shadowProgram.use();

				final int camX = plugin.cameraFocalPoint[0];
				final int camY = plugin.cameraFocalPoint[1];

				final int drawDistanceSceneUnits =
					min(config.shadowDistance().getValue(), plugin.getDrawDistance())
					* Perspective.LOCAL_TILE_SIZE / 2;
				final int east = min(camX + drawDistanceSceneUnits, Perspective.LOCAL_TILE_SIZE * Constants.SCENE_SIZE);
				final int west = max(camX - drawDistanceSceneUnits, 0);
				final int north = min(camY + drawDistanceSceneUnits, Perspective.LOCAL_TILE_SIZE * Constants.SCENE_SIZE);
				final int south = max(camY - drawDistanceSceneUnits, 0);
				final int width = east - west;
				final int height = north - south;
				final int depthScale = 10000;

				final int maxDrawDistance = 90;
				final float maxScale = 0.7f;
				final float minScale = 0.4f;
				final float scaleMultiplier = 1.0f - (plugin.getDrawDistance() / (maxDrawDistance * maxScale));
				float scale = mix(maxScale, minScale, scaleMultiplier);
				float[] lightProjectionMatrix = Mat4.identity();
				Mat4.mul(lightProjectionMatrix, Mat4.scale(scale, scale, scale));
				Mat4.mul(lightProjectionMatrix, Mat4.orthographic(width, height, depthScale));
				Mat4.mul(lightProjectionMatrix, lightViewMatrix);
				Mat4.mul(lightProjectionMatrix, Mat4.translate(-(width / 2f + west), 0, -(height / 2f + south)));

				plugin.uboGlobal.lightProjectionMatrix.set(lightProjectionMatrix);
				plugin.uboGlobal.upload();

				glEnable(GL_CULL_FACE);
				glEnable(GL_DEPTH_TEST);

				glBindVertexArray(vaoScene);
				glDrawArrays(GL_TRIANGLES, 0, renderBufferOffset);

				glDisable(GL_CULL_FACE);
				glDisable(GL_DEPTH_TEST);

				frameTimer.end(Timer.RENDER_SHADOWS);
			}

			plugin.uboGlobal.upload();
			sceneProgram.use();

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, plugin.fboScene);
			if (plugin.msaaSamples > 1) {
				glEnable(GL_MULTISAMPLE);
			} else {
				glDisable(GL_MULTISAMPLE);
			}
			glViewport(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);

			// Clear scene
			frameTimer.begin(Timer.CLEAR_SCENE);

			float[] gammaCorrectedFogColor = pow(fogColor, plugin.getGammaCorrection());
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

			glBindFramebuffer(GL_READ_FRAMEBUFFER, plugin.fboScene);
			if (plugin.fboSceneResolve != 0) {
				// Blit from the scene FBO to the multisample resolve FBO
				glBindFramebuffer(GL_DRAW_FRAMEBUFFER, plugin.fboSceneResolve);
				glBlitFramebuffer(
					0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1],
					0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1],
					GL_COLOR_BUFFER_BIT, GL_NEAREST
				);
				glBindFramebuffer(GL_READ_FRAMEBUFFER, plugin.fboSceneResolve);
			}

			// Blit from the resolved FBO to the default FBO
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, plugin.awtContext.getFramebuffer(false));
			glBlitFramebuffer(
				0,
				0,
				plugin.sceneResolution[0],
				plugin.sceneResolution[1],
				plugin.sceneViewport[0],
				plugin.sceneViewport[1],
				plugin.sceneViewport[0] + plugin.sceneViewport[2],
				plugin.sceneViewport[1] + plugin.sceneViewport[3],
				GL_COLOR_BUFFER_BIT,
				config.sceneScalingMode().glFilter
			);
		} else {
			glClearColor(0, 0, 0, 1f);
			glClear(GL_COLOR_BUFFER_BIT);
		}

		plugin.drawUi(overlayColor);

		try {
			frameTimer.begin(Timer.SWAP_BUFFERS);
			plugin.awtContext.swapBuffers();
			frameTimer.end(Timer.SWAP_BUFFERS);
			drawManager.processDrawComplete(plugin::screenshot);
		} catch (RuntimeException ex) {
			// this is always fatal
			if (!plugin.canvas.isValid()) {
				// this might be AWT shutting down on VM shutdown, ignore it
				return;
			}

			log.error("Unable to swap buffers:", ex);
		}

		glBindFramebuffer(GL_FRAMEBUFFER, plugin.awtContext.getFramebuffer(false));

		frameTimer.end(Timer.DRAW_FRAME);
		frameTimer.end(Timer.RENDER_FRAME);
		frameTimer.endFrameAndReset();
		frameModelInfoMap.clear();
		checkGLErrors();
	}

	@Override
	public void reloadScene() {
		assert client.isClientThread() : "Loading a scene is unsafe while the client can modify it";
		if (client.getGameState().getState() < GameState.LOGGED_IN.getState())
			return;

		Scene scene = client.getTopLevelWorldView().getScene();
		loadScene(scene);
		if (plugin.skipScene == scene)
			plugin.skipScene = null;
		swapScene(scene);
	}

	@Override
	public void loadScene(Scene scene) {
		if (!plugin.isActive())
			return;

		int expandedChunks = plugin.getExpandedMapLoadingChunks();
		if (HDUtils.sceneIntersects(scene, expandedChunks, areaManager.getArea("PLAYER_OWNED_HOUSE"))) {
			// Reload once the POH is done loading
			if (!plugin.isInHouse)
				reloadSceneIn(2);
		} else if (plugin.skipScene != scene && HDUtils.sceneIntersects(
			scene,
			expandedChunks,
			areaManager.getArea("THE_GAUNTLET")
		)) {
			// Some game objects in The Gauntlet are spawned in too late for the initial scene load,
			// so we skip the first scene load and trigger another scene load the next game tick
			reloadSceneNextGameTick();
			plugin.skipScene = scene;
			return;
		}

		if (plugin.useLowMemoryMode)
			return; // Force scene loading to happen on the client thread

		loadSceneInternal(scene);
	}

	@Override
	public boolean isLoadingScene() {
		return nextSceneContext != null;
	}

	public synchronized void loadSceneInternal(Scene scene) {
		if (nextSceneContext != null)
			nextSceneContext.destroy();
		nextSceneContext = null;

		try {
			nextSceneContext = new LegacySceneContext(
				client,
				scene,
				plugin.getExpandedMapLoadingChunks(),
				sceneContext
			);
			// If area hiding was determined to be incorrect previously, keep it disabled
			nextSceneContext.forceDisableAreaHiding = sceneContext != null && sceneContext.forceDisableAreaHiding;

			environmentManager.loadSceneEnvironments(nextSceneContext);
			plugin.minimapRenderer.prepareScene(nextSceneContext);
			sceneUploader.upload(nextSceneContext);
		} catch (OutOfMemoryError oom) {
			log.error(
				"Ran out of memory while loading scene (32-bit: {}, low memory mode: {}, cache size: {})",
				HDUtils.is32Bit(), plugin.useLowMemoryMode, config.modelCacheSizeMiB(), oom
			);
			plugin.displayOutOfMemoryMessage();
			plugin.stopPlugin();
		} catch (Throwable ex) {
			log.error("Error while loading scene:", ex);
			plugin.stopPlugin();
		}
	}

	@Override
	public synchronized void swapScene(Scene scene) {
		if (!plugin.isActive() || plugin.skipScene == scene) {
			plugin.redrawPreviousFrame = true;
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
			initializeTileHeightMap(scene);
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
			plugin.isInHouse = true;
			plugin.isInChambersOfXeric = false;
		} else {
			plugin.isInHouse = false;
			plugin.isInChambersOfXeric = sceneContext.intersects(areaManager.getArea("CHAMBERS_OF_XERIC"));
		}
	}

	private void reloadSceneNextGameTick() {
		reloadSceneIn(1);
	}

	private void reloadSceneIn(int gameTicks) {
		assert gameTicks > 0 : "A value <= 0 will not reload the scene";
		if (gameTicks > gameTicksUntilSceneReload)
			gameTicksUntilSceneReload = gameTicks;
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

		if (plugin.orthographicProjection)
			return true;

		if (tileVisibilityCached)
			return tileIsVisible[plane][tileExX][tileExY];

		int[][][] tileHeights = scene.getTileHeights();
		int x = ((tileExX - sceneContext.sceneOffset) << Perspective.LOCAL_COORD_BITS) + 64;
		int z = ((tileExY - sceneContext.sceneOffset) << Perspective.LOCAL_COORD_BITS) + 64;
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

		x -= (int) plugin.cameraPosition[0];
		y -= (int) plugin.cameraPosition[1];
		z -= (int) plugin.cameraPosition[2];

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
	public boolean isOutsideViewport(
		Model model,
		int modelRadius,
		float pitchSin,
		float pitchCos,
		float yawSin,
		float yawCos,
		int x,
		int y,
		int z
	) {
		if (sceneContext == null)
			return true;

		if (plugin.orthographicProjection)
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
	 * @param projection  Unused
	 * @param scene      Unused
	 * @param renderable  Can be an Actor (Player or NPC), DynamicObject, GraphicsObject, TileItem, Projectile or a raw Model.
	 * @param orientation Rotation around the up-axis, from 0 to 2048 exclusive, 2048 indicating a complete rotation.
	 * @param x           The Renderable's X offset relative to {@link Client#getCameraX()}.
	 * @param y           The Renderable's Y offset relative to {@link Client#getCameraZ()}.
	 * @param z           The Renderable's Z offset relative to {@link Client#getCameraY()}.
	 * @param hash        A unique hash of the renderable consisting of some useful information. See {@link ModelHash} for more details.
	 */
	@Override
	public void draw(Projection projection, @Nullable Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash) {
		if (sceneContext == null)
			return;

		// Hide everything outside the current area if area hiding is enabled
		if (sceneContext.currentArea != null) {
			assert sceneContext.sceneBase != null;
			boolean inArea = sceneContext.currentArea.containsPoint(
				sceneContext.sceneBase[0] + (x >> Perspective.LOCAL_COORD_BITS),
				sceneContext.sceneBase[1] + (z >> Perspective.LOCAL_COORD_BITS),
				sceneContext.sceneBase[2] + client.getTopLevelWorldView().getPlane()
			);
			if (!inArea)
				return;
		}

		if (plugin.enableDetailedTimers)
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
			if (plugin.enableDetailedTimers)
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

		if (plugin.redrawPreviousFrame)
			return;

		if (plugin.enableDetailedTimers)
			frameTimer.begin(Timer.DRAW_RENDERABLE);

		eightIntWrite[3] = renderBufferOffset;
		eightIntWrite[4] = orientation;
		eightIntWrite[5] = x;
		eightIntWrite[6] = y << 16 | height & 0xFFFF; // Pack Y into the upper bits to easily preserve the sign
		eightIntWrite[7] = z;

		int plane = ModelHash.getPlane(hash);
		int faceCount;
		if (sceneContext.id == (offsetModel.getSceneId() & LegacySceneUploader.SCENE_ID_MASK)) {
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

			plugin.drawnStaticRenderableCount = plugin.drawnStaticRenderableCount + 1;
		} else {
			int uuid = ModelHash.generateUuid(client, hash, renderable);
			int[] worldPos = sceneContext.localToWorld(x, z, plane);
			ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
			if (modelOverride.hide)
				return;

			// Disable color overrides when caching is disabled, since they are expensive on dynamic models
			if (!plugin.configModelCaching && modelOverride.colorOverrides != null)
				modelOverride = ModelOverride.NONE;

			int preOrientation = 0;
			if (ModelHash.getType(hash) == ModelHash.TYPE_OBJECT) {
				int tileExX = (x >> Perspective.LOCAL_COORD_BITS) + sceneContext.sceneOffset;
				int tileExY = (z >> Perspective.LOCAL_COORD_BITS) + sceneContext.sceneOffset;
				if (0 <= tileExX && tileExX < Constants.EXTENDED_SCENE_SIZE && 0 <= tileExY && tileExY < Constants.EXTENDED_SCENE_SIZE) {
					Tile tile = sceneContext.scene.getExtendedTiles()[plane][tileExX][tileExY];
					int config;
					if (tile != null && (config = HDUtils.getObjectConfig(tile, hash)) != -1) {
						preOrientation = HDUtils.getModelPreOrientation(config);
					} else if (plane > 0) {
						// Might be on a bridge tile
						tile = sceneContext.scene.getExtendedTiles()[plane - 1][tileExX][tileExY];
						if (tile != null && tile.getBridge() != null
							&& (config = HDUtils.getObjectConfig(tile, hash)) != -1)
							preOrientation = HDUtils.getModelPreOrientation(config);
					}
				}
			}

			// Temporary model (animated or otherwise not a static Model already in the scene buffer)
			if (plugin.enableDetailedTimers)
				frameTimer.begin(Timer.MODEL_BATCHING);
			ModelOffsets modelOffsets = null;
			if (plugin.configModelBatching || plugin.configModelCaching) {
				modelHasher.setModel(model, modelOverride, preOrientation);
				// Disable model batching for models which have been excluded from the scene buffer,
				// because we want to avoid having to fetch the model override
				if (plugin.configModelBatching && offsetModel.getSceneId() != LegacySceneUploader.EXCLUDED_FROM_SCENE_BUFFER) {
					modelOffsets = frameModelInfoMap.get(modelHasher.batchHash);
					if (modelOffsets != null && modelOffsets.faceCount != model.getFaceCount())
						modelOffsets = null; // Assume there's been a hash collision
				}
			}
			if (plugin.enableDetailedTimers)
				frameTimer.end(Timer.MODEL_BATCHING);

			if (modelOffsets != null && modelOffsets.faceCount == model.getFaceCount()) {
				faceCount = modelOffsets.faceCount;
				eightIntWrite[0] = modelOffsets.vertexOffset;
				eightIntWrite[1] = modelOffsets.uvOffset;
				eightIntWrite[2] = modelOffsets.faceCount;
			} else {
				if (plugin.enableDetailedTimers)
					frameTimer.begin(Timer.MODEL_PUSHING);

				int vertexOffset = dynamicOffsetVertices + sceneContext.getVertexOffset();
				int uvOffset = dynamicOffsetUvs + sceneContext.getUvOffset();

				modelPusher.pushModel(sceneContext, null, uuid, model, modelOverride, preOrientation, true);

				faceCount = sceneContext.modelPusherResults[0];
				if (sceneContext.modelPusherResults[1] == 0)
					uvOffset = -1;

				if (plugin.enableDetailedTimers)
					frameTimer.end(Timer.MODEL_PUSHING);

				eightIntWrite[0] = vertexOffset;
				eightIntWrite[1] = uvOffset;
				eightIntWrite[2] = faceCount;

				// add this temporary model to the map for batching purposes
				if (plugin.configModelBatching && modelOffsets == null)
					frameModelInfoMap.put(modelHasher.batchHash, new ModelOffsets(faceCount, vertexOffset, uvOffset));
			}

			if (eightIntWrite[0] != -1)
				plugin.drawnDynamicRenderableCount = plugin.drawnDynamicRenderableCount + 1;

			if (plugin.configCharacterDisplacement && renderable instanceof Actor) {
				if (plugin.enableDetailedTimers)
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
					uboCompute.addCharacterPosition(x, z, (int) (Perspective.LOCAL_TILE_SIZE * 1.33f));
				}
				if (plugin.enableDetailedTimers)
					frameTimer.end(Timer.CHARACTER_DISPLACEMENT);
			}
		}

		if (plugin.enableDetailedTimers)
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
	public GpuIntBuffer bufferForTriangles(int triangles) {
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
}
