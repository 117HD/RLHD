package rs117.hd.scene;

import java.util.ArrayList;
import java.util.Collections;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.config.PositionalShadowMode;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.StaticShadowCache;
import rs117.hd.utils.TextureAtlasPacker;
import rs117.hd.utils.TextureAtlasPacker.Rect;
import rs117.hd.utils.collections.PrimitiveIntArray;

import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NONE;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDrawBuffer;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11C.GL_LEQUAL;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.glTexImage3D;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT16;
import static org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_FUNC;
import static org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL30.GL_COMPARE_REF_TO_TEXTURE;
import static org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_POSITIONAL_SHADOW_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.opengl.uniforms.UBOLights.MAX_LIGHTS;
import static rs117.hd.utils.Mat4.mul;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.TextureAtlasPacker.computeFillScale;

@Slf4j
@Singleton
public class ShadowManager implements LightManager.Listener {

	private static final int ATLAS_SIZE = 4096;
	private static final int NUM_FACES = 6;

	private static final int MAX_FACE_RESOLUTION = 512;
	private static final int MIN_FACE_RESOLUTION = 32;

	private static final float ATLAS_FILL_TARGET = 0.85f;
	private static final float CULL_THRESHOLD_PIXELS = MIN_FACE_RESOLUTION * 0.5f;

	private static final int MIN_SIZE_EXP = Integer.numberOfTrailingZeros(MIN_FACE_RESOLUTION);
	private static final int MAX_SIZE_EXP = Integer.numberOfTrailingZeros(MAX_FACE_RESOLUTION);

	private static final int SIZE_TIER_COUNT = MAX_SIZE_EXP - MIN_SIZE_EXP + 1;
	private static final int SIZE_TIER_BITS = 32 - Integer.numberOfLeadingZeros(Math.max(1, SIZE_TIER_COUNT - 1));

	private static final int GRID_CELLS = ATLAS_SIZE / MIN_FACE_RESOLUTION;
	private static final int GRID_BITS = 32 - Integer.numberOfLeadingZeros(GRID_CELLS - 1);

	private static final float[][] FACE_DIRECTIONS = {
		{  1,  0,  0 }, { -1,  0,  0 },
		{  0,  1,  0 }, {  0, -1,  0 },
		{  0,  0,  1 }, {  0,  0, -1 },
	};

	private static final float[][] FACE_UP_VECTORS = {
		{ 0, -1,  0 }, { 0, -1,  0 },
		{ 0,  0,  1 }, { 0,  0, -1 },
		{ 0, -1,  0 }, { 0, -1,  0 },
	};

	private static final float[][] faceRotation = new float[6][16];

	static {
		for (int face = 0; face < 6; face++) {
			final float[] dir = FACE_DIRECTIONS[face];
			final float[] up = FACE_UP_VECTORS[face];
			faceRotation[face] = Mat4.lookAtRotation(dir[0], dir[1], dir[2], up[0], up[1], up[2]);
		}
	}

	@Inject
	private HdPlugin plugin;

	@Inject
	private LightManager lightManager;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private ZoneRenderer zoneRenderer;

	@Inject
	private FrameTimer frameTimer;

	private final StaticShadowCache staticCache = new StaticShadowCache(MAX_LIGHTS, MAX_FACE_RESOLUTION, ATLAS_SIZE);

	private final ArrayList<Light> shadowLights = new ArrayList<>();
	private final PrimitiveIntArray visibleIndices = new PrimitiveIntArray();

	private final int[] packSizes = new int[MAX_LIGHTS];
	private final Rect[] packRects = new Rect[MAX_LIGHTS];
	private final int[] packSlots = new int[MAX_LIGHTS]; // cacheSlot for each packed light, parallel to packRects

	private final float[] rawSizes = new float[MAX_LIGHTS];
	private final int[] packLightIndices = new int[MAX_LIGHTS];

	private final float[] shadowView = new float[16];
	private final float[] viewProjMatrix = new float[16];
	private final float[] shiftedLightPos = new float[3];

	private int fboShadow;
	private int texShadowCubemapArray;

	public ShadowManager() {
		for (int i = 0; i < packRects.length; i++)
			packRects[i] = new Rect();
	}

	public void initialize() {
		lightManager.addListener(this);
		staticCache.initialize(plugin.staticShadowBlitProgram);

		texShadowCubemapArray = glGenTextures();

		glActiveTexture(TEXTURE_UNIT_POSITIONAL_SHADOW_MAP);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texShadowCubemapArray);

		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_DEPTH_COMPONENT16,
			ATLAS_SIZE, ATLAS_SIZE, NUM_FACES,
			0, GL_DEPTH_COMPONENT, GL_FLOAT, 0);

		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);

		glActiveTexture(TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);

		fboShadow = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboShadow);

		glDrawBuffer(GL_NONE);
		glReadBuffer(GL_NONE);

		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	public void destroy() {
		visibleIndices.reset();
		shadowLights.clear();

		lightManager.removeListener(this);

		staticCache.destroy();

		if (fboShadow != 0)
			glDeleteFramebuffers(fboShadow);
		fboShadow = 0;

		if (texShadowCubemapArray != 0)
			glDeleteTextures(texShadowCubemapArray);
		texShadowCubemapArray = 0;
	}

	public void update() {
		if(!plugin.configPositionalShadows)
			return;

		visibleIndices.reset();
		visibleIndices.ensureCapacity(shadowLights.size());

		final WorldViewContext ctx = sceneManager.getRoot();
		for (int i = shadowLights.size() - 1; i >= 0; i--) {
			if(ctx.sceneContext.lights.contains(shadowLights.get(i)))
				continue;

			shadowLights.remove(i);
		}

		for (int i = 0; i < shadowLights.size(); i++) {
			final Light light = shadowLights.get(i);
			light.shadowData.overlappingZones.reset();
			light.shadowData.atlasRect = null;

			if (light.visible && visibleIndices.length < MAX_LIGHTS) {
				visibleIndices.put(i);

				computeShadowProjection(light.shadowData.lightProjection, light.shadowNearPlane, light.radius);
			}
		}

		packShadowAtlas();
	}

	private void packShadowAtlas() {
		final int visibleCount = visibleIndices.length;
		if (visibleCount == 0)
			return;

		int count = 0;
		for (int i = 0; i < visibleCount; i++) {
			final Light light = shadowLights.get(visibleIndices.array[i]);
			final float raw = estimateRawShadowSize(light);

			if (raw < CULL_THRESHOLD_PIXELS)
				continue;

			rawSizes[count] = raw;
			packLightIndices[count] = i;
			count++;
		}

		if (count == 0)
			return;

		final float scale = computeFillScale(
			rawSizes, count,
			MIN_FACE_RESOLUTION, MAX_FACE_RESOLUTION,
			ATLAS_SIZE * ATLAS_SIZE * ATLAS_FILL_TARGET,
			64f, 20
		);

		for (int i = 0; i < count; i++) {
			packSizes[i] = quantizePow2(clamp(rawSizes[i] * scale, MIN_FACE_RESOLUTION, MAX_FACE_RESOLUTION),
				MIN_FACE_RESOLUTION,
				MAX_FACE_RESOLUTION
			);
		}

		while (TextureAtlasPacker.totalArea(packSizes, count) > (long) ATLAS_SIZE * ATLAS_SIZE) {
			int smallestIdx = -1;
			int smallestSize = Integer.MAX_VALUE;

			for (int i = 0; i < count; i++) {
				if (packSizes[i] > MIN_FACE_RESOLUTION && packSizes[i] < smallestSize) {
					smallestSize = packSizes[i];
					smallestIdx = i;
				}
			}

			if (smallestIdx < 0)
				break;

			packSizes[smallestIdx] >>= 1;
		}

		if(!TextureAtlasPacker.pack(ATLAS_SIZE, count, packSizes, packRects)) {
			log.warn("Failed to pack light rects??!");
			return;
		}

		for (int i = 0; i < count; i++) {
			final Light light = shadowLights.get(visibleIndices.array[packLightIndices[i]]);
			light.shadowData.atlasRect = packRects[i];
			packSlots[i] = light.shadowData.cacheSlot;
		}

		staticCache.updateGrid(packSlots, packRects, count);
	}

	private float estimateRawShadowSize(Light light) {
		final Camera sceneCamera = zoneRenderer.sceneCamera;
		final float distance = sceneCamera.distanceTo(
			light.pos[0] + plugin.cameraShift[0],
			light.pos[1],
			light.pos[2] + plugin.cameraShift[1]
		);

		if (distance <= light.shadowNearPlane)
			return MAX_FACE_RESOLUTION;

		final float tanHalfFovY = sceneCamera.getViewportHeight() / sceneCamera.getZoom() / 2f;
		final float angularRadius = light.radius / distance;
		final float screenFraction = angularRadius / tanHalfFovY;
		final float pixels = screenFraction * sceneCamera.getViewportHeight();

		return clamp(pixels, MIN_FACE_RESOLUTION, MAX_FACE_RESOLUTION);
	}

	public void buildDrawLists() {
		if(!plugin.configPositionalShadows)
			return;

		final WorldViewContext ctx = sceneManager.getRoot();

		for (int i = 0; i < visibleIndices.length; i++) {
			final int lightIndex = visibleIndices.array[i];
			final Light light = shadowLights.get(lightIndex);
			final ShadowData shadowData = light.shadowData;

			shadowData.dynamicDrawBuffer.reset();

			if (shadowData.atlasRect == null)
				continue;

			final int zoneHash = shadowData.computeZoneHash(ctx);
			shadowData.staticDirty = light.shadowMode != PositionalShadowMode.MOVEABLE && (!shadowData.staticEverBaked || zoneHash != shadowData.bakedZoneHash);

			if (shadowData.staticDirty) {
				shadowData.staticDrawBuffer.reset();

				for (int z = 0; z < shadowData.overlappingZones.length; z++) {
					final int packed = shadowData.overlappingZones.array[z];
					final int zx = packed / ctx.sizeX;
					final int zz = packed % ctx.sizeX;

					final Zone zone = ctx.zones[zx][zz];
					if (!zone.initialized || zone.sizeO == 0)
						continue;

					zone.renderOpaque(shadowData.staticDrawBuffer, 0, 0, 3, Collections.EMPTY_SET);
				}

				shadowData.bakedZoneHash = zoneHash;
				shadowData.staticEverBaked = true;
			}

			if (light.shadowMode != PositionalShadowMode.STATIC) {
				// TODO: This is too expensive at the moment, we need to append specific model draws which is tech that the ModelData branch has
				shadowData.dynamicDrawBuffer.ExecuteSubCommandBuffer(ctx.vaoSceneCmd);
				shadowData.dynamicDrawBuffer.ExecuteSubCommandBuffer(ctx.vaoDirectionalCmd);
			}
		}
	}

	public boolean izZoneVisible(WorldViewContext context, Zone zone, int zx, int zz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		if(!plugin.configPositionalShadows)
			return false;

		boolean isVisible = false;
		for(int i = 0; i < visibleIndices.length; i++) {
			final Light light = shadowLights.get(visibleIndices.array[i]);
			final boolean intersectsZone = HDUtils.SphereAABBIntersects(
				light.pos[0], light.pos[1], light.pos[2], light.radius,
				minX, minY, minZ,
				maxX, maxY, maxZ
			);

			if(!intersectsZone)
				continue;

			light.shadowData.overlappingZones.put(zx * context.sizeX + zz);
			isVisible = true;
		}

		return isVisible;
	}

	private static void computeShadowProjection(float[] out, float near, float far) {
		final float nf = near / far;
		final float a = (1f + nf) / (nf - 1f);
		final float b = a * near - near;
		out[0] = 1f;
		out[5] = 1f;
		out[11] = -1f;
		out[10] = a;
		out[14] = b;
	}

	private static void buildShadowViewMatrix(float[] out, float[] rotation, float[] lightPos) {
		copyTo(out, rotation);

		final float px = lightPos[0], py = lightPos[1], pz = lightPos[2];
		out[12] = -(rotation[0] * px + rotation[4] * py + rotation[8]  * pz);
		out[13] = -(rotation[1] * px + rotation[5] * py + rotation[9]  * pz);
		out[14] = -(rotation[2] * px + rotation[6] * py + rotation[10] * pz);
		out[15] = 1f;
	}

	public void renderShadows(RenderState renderState) {
		if(!plugin.configPositionalShadows)
			return;

		frameTimer.begin(Timer.RENDER_POSITIONAL_SHADOWS);

		renderState.disable.set(GL_CULL_FACE);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(true);
		renderState.ido.set(zoneRenderer.indirectDrawCmds.id);

		glClearDepth(1);

		// rebake dirty lights' static geometry, all faces, before entering the per-face loop
		zoneRenderer.depthProgram.use();
		renderState.depthFunc.set(GL_LEQUAL);

		for (int i = 0; i < visibleIndices.length; i++) {
			final int lightIndex = visibleIndices.array[i];
			final Light light = shadowLights.get(lightIndex);
			final ShadowData shadowData = light.shadowData;

			if (shadowData.atlasRect == null || !shadowData.staticDirty || shadowData.cacheSlot < 0)
				continue;

			shiftedLightPos[0] = light.pos[0] + plugin.cameraShift[0];
			shiftedLightPos[1] = light.pos[1];
			shiftedLightPos[2] = light.pos[2] + plugin.cameraShift[1];

			for (int face = 0; face < NUM_FACES; face++) {
				staticCache.beginBakeLayer(renderState, shadowData.cacheSlot, face);
				renderState.apply();

				glClear(GL_DEPTH_BUFFER_BIT);
				if (shadowData.staticDrawBuffer.isEmpty())
					continue;

				buildShadowViewMatrix(shadowView, faceRotation[face], shiftedLightPos);
				copyTo(viewProjMatrix, light.shadowData.lightProjection);
				mul(viewProjMatrix, shadowView);
				zoneRenderer.depthProgram.uniViewProjection.set(viewProjMatrix);

				shadowData.staticDrawBuffer.execute(renderState);
			}

			shadowData.staticDirty = false;
		}

		for(int face = 0; face < NUM_FACES; face++) {
			// refresh the whole live-atlas face from the static cache in one draw

			renderState.framebuffer.set(GL_FRAMEBUFFER, fboShadow);
			renderState.framebufferTextureLayer.set(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, texShadowCubemapArray, 0, face);
			renderState.viewport.set(0, 0, ATLAS_SIZE, ATLAS_SIZE);
			renderState.apply();

			glClear(GL_DEPTH_BUFFER_BIT);

			staticCache.blitFace(renderState, face);

			// draw dynamic actors on top, only where a light actually has any
			zoneRenderer.depthProgram.use();
			renderState.depthFunc.set(GL_LEQUAL);

			for (int i = 0; i < visibleIndices.length; i++) {
				final int lightIndex = visibleIndices.array[i];
				final Light light = shadowLights.get(lightIndex);
				final ShadowData shadowData = light.shadowData;

				if (shadowData.atlasRect == null || shadowData.dynamicDrawBuffer.isEmpty())
					continue;

				shiftedLightPos[0] = light.pos[0] + plugin.cameraShift[0];
				shiftedLightPos[1] = light.pos[1];
				shiftedLightPos[2] = light.pos[2] + plugin.cameraShift[1];

				buildShadowViewMatrix(shadowView, faceRotation[face], shiftedLightPos);

				copyTo(viewProjMatrix, light.shadowData.lightProjection);
				mul(viewProjMatrix, shadowView);

				zoneRenderer.depthProgram.uniViewProjection.set(viewProjMatrix);

				renderState.viewport.set(
					shadowData.atlasRect.x,
					shadowData.atlasRect.y,
					shadowData.atlasRect.size,
					shadowData.atlasRect.size
				);
				renderState.apply();

				shadowData.dynamicDrawBuffer.execute(renderState);
			}
		}

		checkGLErrors();

		renderState.disable.set(GL_DEPTH_TEST);

		frameTimer.end(Timer.RENDER_POSITIONAL_SHADOWS);
	}

	@Override
	public void onLightAdded(Light light) {
		if(light.shadowMode == PositionalShadowMode.DISABLED)
			return;

		light.shadowData = new ShadowData();
		light.shadowData.cacheSlot = staticCache.acquireSlot();
		shadowLights.add(light);
	}

	@Override
	public void onLightRemoved(Light light) {
		if(light.shadowData == null)
			return;

		staticCache.releaseSlot(light.shadowData.cacheSlot);
		light.shadowData = null;
		shadowLights.remove(light);
	}

	public static final class ShadowData {
		private final PrimitiveIntArray overlappingZones = new PrimitiveIntArray();
		private final CommandBuffer staticDrawBuffer = new CommandBuffer("Shadow::StaticDrawBuffer");
		private final CommandBuffer dynamicDrawBuffer = new CommandBuffer("Shadow::DynamicDrawBuffer");
		private final float[] lightProjection = new float[16];

		private Rect atlasRect;

		private int cacheSlot = -1;
		private boolean staticDirty;
		private boolean staticEverBaked = false;
		private int bakedZoneHash;

		public int computeZoneHash(WorldViewContext ctx) {
			int zoneHash = 0;
			for (int z = 0; z < overlappingZones.length; z++) {
				final int packed = overlappingZones.array[z];
				final int zx = packed / ctx.sizeX;
				final int zz = packed % ctx.sizeX;
				zoneHash += packed * 486187739 + System.identityHashCode(ctx.zones[zx][zz]) * 51;
			}
			return zoneHash * 31 + overlappingZones.length;
		}

		public int pack() {
			if(atlasRect == null)
				return -1;

			final int size = atlasRect.size;
			final int exponent = Integer.numberOfTrailingZeros(size);
			final int tier = exponent - MIN_SIZE_EXP;
			final int gridX = atlasRect.x >> exponent;
			final int gridY = atlasRect.y >> exponent;

			return tier | (gridX << SIZE_TIER_BITS) | (gridY << (SIZE_TIER_BITS + GRID_BITS));
		}
	}
}