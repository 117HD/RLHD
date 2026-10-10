package rs117.hd.renderer.zone;

import java.util.ArrayList;
import java.util.Collections;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.config.PositionalShadowMode;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.RectAtlasPacker;
import rs117.hd.utils.RectAtlasPacker.Rect;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.collections.IntHashSet;
import rs117.hd.utils.collections.PrimitiveIntArray;

import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
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
import static org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glFramebufferTextureLayer;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;
import static org.lwjgl.opengl.GL30C.glBlitFramebuffer;
import static org.lwjgl.opengl.GL43.glCopyImageSubData;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_POSITIONAL_SHADOW_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.opengl.uniforms.UBOLights.MAX_LIGHTS;
import static rs117.hd.utils.Mat4.mul;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class PositionalShadowRenderer implements LightManager.Listener {
	private static final int ATLAS_SIZE = 4096;
	private static final int NUM_FACES = 6;

	private static final int MAX_FACE_RESOLUTION = 512;
	private static final int MIN_FACE_RESOLUTION = 32;

	private static final int MIN_SIZE_EXP = Integer.numberOfTrailingZeros(MIN_FACE_RESOLUTION);
	private static final int MAX_SIZE_EXP = Integer.numberOfTrailingZeros(MAX_FACE_RESOLUTION);
	private static final int SIZE_TIER_COUNT = MAX_SIZE_EXP - MIN_SIZE_EXP + 1;
	private static final int SIZE_TIER_BITS = 32 - Integer.numberOfLeadingZeros(Math.max(1, SIZE_TIER_COUNT - 1));
	private static final int GRID_CELLS = ATLAS_SIZE / MIN_FACE_RESOLUTION;
	private static final int GRID_BITS = 32 - Integer.numberOfLeadingZeros(GRID_CELLS - 1);

	private static final int PACKED_NEAR_PLANE_BITS = 14;
	private static final int PACKED_NEAR_PLANE_SHIFT = SIZE_TIER_BITS + 2 * GRID_BITS;
	private static final float NEAR_PLANE_SCALE = 16f;
	private static final float NEAR_PLANE_MAX = ((1 << PACKED_NEAR_PLANE_BITS) - 1) / NEAR_PLANE_SCALE;
	private static final int PACKED_TOTAL_BITS = SIZE_TIER_BITS + 2 * GRID_BITS + PACKED_NEAR_PLANE_BITS;

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

	private final ArrayList<Light> pendingAdd = new ArrayList<>();
	private final ArrayList<Light> pendingRemove = new ArrayList<>();
	private final ArrayList<Light> shadowLights = new ArrayList<>();
	private final PrimitiveIntArray visibleIndices = new PrimitiveIntArray();

	private final int[] packSizes = new int[MAX_LIGHTS];
	private final Rect[] packRects = new Rect[MAX_LIGHTS];

	private final float[][] faceRotation = new float[6][16];
	private final float[] shadowView = new float[16];
	private final float[] viewProjMatrix = new float[16];
	private final float[] shiftedLightPos = new float[3];

	private int fboShadowBakedRead;
	private int fboShadow;
	private int texShadowCubemapArray;

	public PositionalShadowRenderer() {
		for (int i = 0; i < packRects.length; i++)
			packRects[i] = new Rect();

		for (int face = 0; face < 6; face++) {
			final float[] dir = FACE_DIRECTIONS[face];
			final float[] up = FACE_UP_VECTORS[face];
			faceRotation[face] = Mat4.lookAtRotation(dir[0], dir[1], dir[2], up[0], up[1], up[2]);
		}
	}

	public void initialize() {
		lightManager.addListener(this);

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

		fboShadowBakedRead = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboShadowBakedRead);

		glDrawBuffer(GL_NONE);
		glReadBuffer(GL_NONE);

		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	public void destroy() {
		visibleIndices.reset();

		for (Light light : shadowLights)
			if (light.shadowData != null)
				light.shadowData.destroy();
		shadowLights.clear();

		lightManager.removeListener(this);

		if (fboShadow != 0)
			glDeleteFramebuffers(fboShadow);
		fboShadow = 0;

		if (fboShadowBakedRead != 0)
			glDeleteFramebuffers(fboShadowBakedRead);
		fboShadowBakedRead = 0;

		if (texShadowCubemapArray != 0)
			glDeleteTextures(texShadowCubemapArray);
		texShadowCubemapArray = 0;
	}

	public void update() {
		if(!plugin.configPositionalShadows)
			return;

		for(int i = 0; i < pendingAdd.size(); i++) {
			final Light light = pendingAdd.get(i);
			light.shadowData = new ShadowData();
			light.shadowData.dirty = true;

			if(light.shadowMode != PositionalShadowMode.MOVEABLE) {
				light.shadowData.texBakedCubemap = glGenTextures();
				glBindTexture(GL_TEXTURE_2D_ARRAY, light.shadowData.texBakedCubemap);
				for (int level = 0; level <= MAX_SIZE_EXP - MIN_SIZE_EXP; level++) {
					final int resolution = MAX_FACE_RESOLUTION >> level;
					glTexImage3D(GL_TEXTURE_2D_ARRAY, level, GL_DEPTH_COMPONENT16,
						resolution, resolution, NUM_FACES,
						0, GL_DEPTH_COMPONENT, GL_FLOAT, 0);
				}
				glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
			}

			shadowLights.add(light);
		}
		pendingAdd.clear();

		for(int i = 0; i < pendingRemove.size(); i++) {
			final Light light = pendingRemove.get(i);
			if(light.shadowData != null)
				light.shadowData.destroy();
			light.shadowData = null;
			shadowLights.remove(light);
		}
		pendingRemove.clear();

		visibleIndices.reset();
		visibleIndices.ensureCapacity(shadowLights.size());

		for (int i = 0; i < shadowLights.size(); i++) {
			final Light light = shadowLights.get(i);
			light.shadowData.overlappingZones.reset();
			light.shadowData.atlasRect = null;

			if (light.visible && visibleIndices.length < MAX_LIGHTS) {
				visibleIndices.put(i);

				light.shadowData.packedNearPlane = encodeNearPlane(light.shadowNearPlane);
				computeShadowProjection(light.shadowData.lightProjection, decodeNearPlane(light.shadowData.packedNearPlane), light.radius);
			}
		}

		packShadowAtlas();
	}

	private void packShadowAtlas() {
		final int count = visibleIndices.length;
		if (count == 0)
			return;

		for (int i = 0; i < count; i++) {
			final Light light = shadowLights.get(visibleIndices.array[i]);
			packSizes[i] = estimateShadowResolution(light);
		}

		while (RectAtlasPacker.totalArea(packSizes, count) > (long) ATLAS_SIZE * ATLAS_SIZE) {
			int smallestIdx = -1;
			int smallestSize = Integer.MAX_VALUE;

			for (int i = 0; i < count; i++) {
				if (packSizes[i] > MIN_FACE_RESOLUTION && packSizes[i] < smallestSize) {
					smallestSize = packSizes[i];
					smallestIdx = i;
				}
			}

			if (smallestIdx < 0)
				break; // everything is already at the minimum; caller has genuinely too many lights

			packSizes[smallestIdx] >>= 1;
		}

		if(!RectAtlasPacker.pack(ATLAS_SIZE, count, packSizes, packRects)) {
			log.warn("Failed to pack light rects??!");
			return;
		}

		for (int i = 0; i < count; i++) {
			final Light light = shadowLights.get(visibleIndices.array[i]);
			light.shadowData.atlasRect = packRects[i];
		}
	}

	private int estimateShadowResolution(Light light) {
		final Camera sceneCamera = zoneRenderer.sceneCamera;
		final float distance = sceneCamera.distanceTo(light.pos[0], light.pos[1], light.pos[2]);
		if (distance <= light.shadowNearPlane)
			return MAX_FACE_RESOLUTION;

		final float tanHalfFovY = sceneCamera.getViewportHeight() / sceneCamera.getZoom() / 2f;
		final float angularRadius = light.radius / distance;
		final float screenFraction = angularRadius / tanHalfFovY; // ~fraction of viewport height covered by the light's radius
		final float pixels = screenFraction * sceneCamera.getViewportHeight();

		// Quantize to a power of two within [MIN_FACE_RESOLUTION, MAX_FACE_RESOLUTION]
		int size = MIN_FACE_RESOLUTION;
		while (size < MAX_FACE_RESOLUTION && size < pixels)
			size <<= 1;
		return size;
	}

	public void buildDrawLists() {
		if(!plugin.configPositionalShadows)
			return;

		final WorldViewContext root = sceneManager.getRoot();
		final WorldViewContext[] subs = sceneManager.getSubContexts();

		for (int i = 0; i < visibleIndices.length; i++) {
			final int lightIndex = visibleIndices.array[i];
			final Light light = shadowLights.get(lightIndex);
			final ShadowData shadowData = light.shadowData;

			shadowData.drawBuffer.reset();
			if (shadowData.atlasRect == null)
				continue;

			if(light.shadowMode != PositionalShadowMode.MOVEABLE && anyOverlappingZoneUnbaked(shadowData, root))
				shadowData.dirty = true;

			if(light.shadowMode == PositionalShadowMode.DYNAMIC || shadowData.dirty) {
				appendOverlappingZones(light, shadowData, root);

				// TODO: use izZoneVisible for subScenes, to ensure we don't draw zones that are not visible
				if (light.sceneContext != null) {
					for (WorldViewContext sub : subs) {
						if (sub == null || sub.sceneContext == null)
							continue;
						appendAllZones(light, shadowData, sub);
					}
				}
			}
		}
	}

	private boolean anyOverlappingZoneUnbaked(ShadowData shadowData, WorldViewContext ctx) {
		for (int z = 0; z < shadowData.overlappingZones.length; z++) {
			final Zone zone = shadowZone(shadowData, ctx, z);
			if (zone == null || !zone.initialized || zone.sizeO == 0)
				continue;

			if (!shadowData.bakedZoneHashes.contains(zone.hashCode()))
				return true;
		}
		return false;
	}

	private void appendOverlappingZones(Light light, ShadowData shadowData, WorldViewContext ctx) {
		for (int z = 0; z < shadowData.overlappingZones.length; z++) {
			final Zone zone = shadowZone(shadowData, ctx, z);
			if (zone == null || !zone.initialized || zone.sizeO == 0)
				continue;

			zone.renderOpaque(shadowData.drawBuffer, 0, 0, 3, Collections.EMPTY_SET);

			if (light.shadowMode != PositionalShadowMode.MOVEABLE)
				shadowData.bakedZoneHashes.add(zone.hashCode());
		}
	}

	private void appendAllZones(Light light, ShadowData shadowData, WorldViewContext ctx) {
		for (int zx = 0; zx < ctx.sizeX; zx++) {
			for (int zz = 0; zz < ctx.sizeZ; zz++) {
				final Zone zone = ctx.zones[zx][zz];
				if (zone == null || !zone.initialized || zone.sizeO == 0)
					continue;

				zone.renderOpaque(shadowData.drawBuffer, 0, 0, 3, Collections.EMPTY_SET);

				if (light.shadowMode != PositionalShadowMode.MOVEABLE)
					shadowData.bakedZoneHashes.add(zone.hashCode());
			}
		}
	}

	private Zone shadowZone(ShadowData shadowData, WorldViewContext ctx, int z) {
		final int packed = shadowData.overlappingZones.array[z];
		final int zx = packed / ctx.sizeX;
		final int zz = packed % ctx.sizeX;
		if (zx >= ctx.sizeX || zz >= ctx.sizeZ)
			return null;
		return ctx.zones[zx][zz];
	}

	public boolean izZoneVisible(WorldViewContext context, Zone zone, int zx, int zz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		if(!plugin.configPositionalShadows)
			return false;

		// TODO: Support Projection of Zone min/max bounds to improve sub scene light drawing
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

	private static int encodeNearPlane(float nearPlane) {
		return round(clamp(nearPlane, 0, NEAR_PLANE_MAX) * NEAR_PLANE_SCALE);
	}

	private static float decodeNearPlane(int packedNearPlane) {
		return packedNearPlane / NEAR_PLANE_SCALE;
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

		zoneRenderer.depthProgram.use();

		// Keep the baked cubemap FBO bound for reads while RenderState updates only
		// the atlas draw target.
		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, fboShadow);
		renderState.disable.set(GL_CULL_FACE);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(true);
		renderState.depthFunc.set(GL_LEQUAL);
		renderState.ido.set(zoneRenderer.indirectDrawCmds.id);

		glClearDepth(1);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fboShadowBakedRead);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboShadow);

		// Bake Static Lights that are dirty
		for (int i = 0; i < visibleIndices.length; i++) {
			final int lightIndex = visibleIndices.array[i];
			final Light light = shadowLights.get(lightIndex);
			if(light.shadowMode == PositionalShadowMode.MOVEABLE)
				continue;

			final ShadowData shadowData = light.shadowData;
			if(!shadowData.dirty)
				continue;

			renderState.viewport.set(0, 0, MAX_FACE_RESOLUTION, MAX_FACE_RESOLUTION);

			for(int face = 0; face < 6; face++) {
				renderState.framebufferTextureLayer.set(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, shadowData.texBakedCubemap, 0, face);
				renderState.apply();

				glClear(GL_DEPTH_BUFFER_BIT);

				shiftedLightPos[0] = light.pos[0] + plugin.cameraShift[0];
				shiftedLightPos[1] = light.pos[1];
				shiftedLightPos[2] = light.pos[2] + plugin.cameraShift[1];

				buildShadowViewMatrix(shadowView, faceRotation[face], shiftedLightPos);

				copyTo(viewProjMatrix, light.shadowData.lightProjection);
				mul(viewProjMatrix, shadowView);

				zoneRenderer.depthProgram.uniViewProjection.set(viewProjMatrix);

				shadowData.drawBuffer.execute(renderState);
			}
			glBindTexture(GL_TEXTURE_2D_ARRAY, shadowData.texBakedCubemap);
			glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
			glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
			shadowData.dirty = false;
		}
		checkGLErrors();

		final boolean supportsLayeredImageCopy = HdPlugin.GL_CAPS.OpenGL43 || HdPlugin.GL_CAPS.GL_ARB_copy_image;

		// Clear every atlas layer before restoring the cached baked faces.
		for(int face = 0; face < NUM_FACES; face++) {
			renderState.framebufferTextureLayer.set(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, texShadowCubemapArray, 0, face);
			renderState.apply();
			glClear(GL_DEPTH_BUFFER_BIT);
		}

		if (supportsLayeredImageCopy) {
			// The matching mip level has the same dimensions as the atlas rect, so
			// this copies all six faces without a scaled framebuffer blit.
			for (int i = 0; i < visibleIndices.length; i++) {
				final Light light = shadowLights.get(visibleIndices.array[i]);
				if (light.shadowMode == PositionalShadowMode.MOVEABLE)
					continue;

				final ShadowData shadowData = light.shadowData;
				if (shadowData.atlasRect == null)
					continue;

				final Rect rect = shadowData.atlasRect;
				final int sourceMipLevel = MAX_SIZE_EXP - Integer.numberOfTrailingZeros(rect.size);
				glCopyImageSubData(
					shadowData.texBakedCubemap, GL_TEXTURE_2D_ARRAY, sourceMipLevel, 0, 0, 0,
					texShadowCubemapArray, GL_TEXTURE_2D_ARRAY, 0, rect.x, rect.y, 0,
					rect.size, rect.size, NUM_FACES
				);
			}
		}

		for(int face = 0; face < NUM_FACES; face++) {
			renderState.framebufferTextureLayer.set(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, texShadowCubemapArray, 0, face);
			renderState.apply();

			for (int i = 0; i < visibleIndices.length; i++) {
				final int lightIndex = visibleIndices.array[i];
				final Light light = shadowLights.get(lightIndex);
				final ShadowData shadowData = light.shadowData;

				if (shadowData.atlasRect == null)
					continue;

				if(light.shadowMode != PositionalShadowMode.STATIC && !shadowData.drawBuffer.isEmpty()) {
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
				}
				renderState.apply();

				if(light.shadowMode != PositionalShadowMode.MOVEABLE && !supportsLayeredImageCopy) {
					final Rect rect = shadowData.atlasRect;
					final int sourceMipLevel = MAX_SIZE_EXP - Integer.numberOfTrailingZeros(rect.size);
					glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, shadowData.texBakedCubemap, sourceMipLevel, face);
					glBlitFramebuffer(
						0, 0, rect.size, rect.size,
						rect.x, rect.y, rect.x + rect.size, rect.y + rect.size,
						GL_DEPTH_BUFFER_BIT, GL_NEAREST
					);
				}

				if(shadowData.drawBuffer.isEmpty())
					continue;

				if(light.shadowMode != PositionalShadowMode.STATIC)
					shadowData.drawBuffer.execute(renderState);
			}
		}

		checkGLErrors();

		glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);

		renderState.disable.set(GL_DEPTH_TEST);

		frameTimer.end(Timer.RENDER_POSITIONAL_SHADOWS);
	}

	@Override
	public void onLightAdded(Light light) {
		if(light.shadowMode == PositionalShadowMode.DISABLED)
			return;

		// Sub world views can move around within the root scene, so their lights can only be MOVEABLE
		if (light.sceneContext != null)
			light.shadowMode = PositionalShadowMode.MOVEABLE;

		pendingAdd.add(light);
	}

	@Override
	public void onLightRemoved(Light light) {
		if(light.shadowData == null)
			return;

		pendingRemove.add(light);
	}

	public static final class ShadowData {
		private final IntHashSet bakedZoneHashes = new IntHashSet(); // TODO: This isn't needed for Dynamic Lights
		private final PrimitiveIntArray overlappingZones = new PrimitiveIntArray();
		private final CommandBuffer drawBuffer = new CommandBuffer("Shadow::DrawBuffer");
		private final float[] lightProjection = new float[16];
		private int texBakedCubemap;
		private boolean dirty;
		private int packedNearPlane;

		private Rect atlasRect;

		public void destroy() {
			if(texBakedCubemap != 0)
				glDeleteTextures(texBakedCubemap);
			texBakedCubemap = 0;
		}

		public int pack() {
			if(atlasRect == null)
				return -1;

			final int size = atlasRect.size;
			final int exponent = Integer.numberOfTrailingZeros(size);
			final int tier = exponent - MIN_SIZE_EXP;
			final int gridX = atlasRect.x >> exponent;
			final int gridY = atlasRect.y >> exponent;

			return tier |
				   (gridX << SIZE_TIER_BITS) |
				   (gridY << (SIZE_TIER_BITS + GRID_BITS)) |
				   (packedNearPlane << PACKED_NEAR_PLANE_SHIFT);
		}
	}
}