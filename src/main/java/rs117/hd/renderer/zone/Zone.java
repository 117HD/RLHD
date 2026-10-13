package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import org.lwjgl.system.MemoryStack;
import rs117.hd.HdPlugin;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.Destructible;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLTextureBuffer;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.collections.Int2IntHashMap;
import rs117.hd.utils.collections.IntHashSet;
import rs117.hd.utils.collections.PooledArrayType;
import rs117.hd.utils.collections.PrimitiveIntArray;

import static net.runelite.api.Constants.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.SUPPORTS_INDIRECT_DRAW;
import static rs117.hd.HdPlugin.SUPPORTS_MULTI_INDIRECT_DRAW;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;
import static rs117.hd.renderer.zone.ZoneRenderer.eboAlpha;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.collections.Util.quickSortByKey;

@Slf4j
public final class Zone implements Destructible {
	private static final ConcurrentPool<AlphaModelInstance> ALPHA_MODEL_POOL = new ConcurrentPool<>(AlphaModelInstance::new);

	@Inject
	private Client client;

	// Zone vertex format
	// pos short vec3(x, y, z)
	// uvw short vec3(u, v, w)
	// normal short vec3(nx, ny, nz)
	// texturedFaceIdx int
	public static final int VERT_SIZE = 28;

	// alphaBiasHsl ivec3
	// materialData ivec3
	// terrainData ivec3
	public static final int TEXTURE_SIZE = 36;

	// Metadata format
	// worldViewIndex int int
	// sceneOffset int vec2(x, y)
	public static final int METADATA_SIZE = 12;

	public static int LEVEL_COUNT = MAX_Z;
	public static final int LEVEL_TERRAIN = LEVEL_COUNT++;
	public static final int LEVEL_WATER_SURFACE = LEVEL_COUNT++;
	public static final int LEVEL_GAP_FILLER = LEVEL_COUNT++;

	public int glVao;
	int modelCount;
	int bufLen;

	byte staticAlphaLevelMask = 0;
	byte alphaLevelMask = 0;

	public int glVaoA;
	public int bufLenA;

	public int sizeO, sizeA, sizeF;
	@Nullable
	public GLBuffer vboO, vboA, vboM;
	public GLTextureBuffer tboF;

	public boolean initialized; // whether the zone vao and vbos are ready
	public boolean cull; // whether the zone is queued for deletion
	public boolean needsRoofUpdate; // whether the zone needs to have its roofs updated during scene swap
	public boolean rebuild; // whether the zone is queued for rebuild
	public boolean dirty; // whether the zone has temporary modifications
	public boolean hasWater; // whether the zone has any water tiles
	public boolean onlyWater; // whether the zone only contains water tiles
	public boolean hasGapFiller; // whether the zone has any gap filler geometry
	public boolean inSceneFrustum = true; // whether the zone is visible to the scene camera
	public boolean inShadowFrustum; // whether the zone casts shadows into the visible scene
	public boolean isFirstLoadingAttempt = true;

	public IntHashSet animatedDynamicObjectIds = new IntHashSet();

	ZoneUploadJob uploadJob;

	// The WorldViewContext which owns this zone. Used to release shared StaticAlphaModels when the zone is destroyed
	@Nullable
	WorldViewContext viewContext;

	void setUploadJob(ZoneUploadJob uploadJob) {
		this.uploadJob = uploadJob;
		if (uploadJob != null)
			uploadJob.zoneToBeReplaced = this;
	}

	int[] levelOffsets = new int[LEVEL_COUNT]; // buffer pos in ints for the end of the level

	int[][] rids;
	int[][] roofStart;
	int[][] roofEnd;

	final List<AlphaModelInstance> alphaModels = new ArrayList<>(0);
	// StaticAlphaModels deduplicated by the WorldViewContext for this zone in unmap(), released when destroyed
	final List<StaticAlphaModel> acquiredStaticModels = new ArrayList<>(0);
	final PrimitiveIntArray visibleAlphaModels = new PrimitiveIntArray();

	// Combined AABB of this zone's own static alpha models, in zone-local coordinates. Maintained by addAlphaModel so
	// the world view can frustum-cull the whole zone without recomputing bounds every frame.
	int alphaMinBoundsX = Integer.MAX_VALUE, alphaMinBoundsY = Integer.MAX_VALUE, alphaMinBoundsZ = Integer.MAX_VALUE;
	int alphaMaxBoundsX = Integer.MIN_VALUE, alphaMaxBoundsY = Integer.MIN_VALUE, alphaMaxBoundsZ = Integer.MIN_VALUE;

	boolean hasAlphaBounds() {
		return alphaMaxBoundsX >= alphaMinBoundsX;
	}

	public boolean doesLevelHaveAlphaModels(int level) {
		return (alphaLevelMask & (1 << level)) != 0;
	}

	public boolean hasVisibleAlphaModels() {
		return !visibleAlphaModels.isEmpty();
	}

	public void initialize(GLBuffer o, GLBuffer a, GLTextureBuffer f) {
		assert glVao == 0;
		assert glVaoA == 0;
		if (o == null && a == null || f == null)
			return;

		vboM = new GLBuffer("ZoneMetadata", GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW);
		vboM.initialize(METADATA_SIZE);

		if (o != null) {
			vboO = o;
			glVao = glGenVertexArrays();
			setupVao(glVao, o.id, vboM.id);
		}

		if (a != null) {
			vboA = a;
			glVaoA = glGenVertexArrays();
			setupVao(glVaoA, a.id, vboM.id);
		}

		tboF = f;
	}

	public static void freeZones(@Nullable Zone[][] zones) {
		if (zones == null)
			return;

		for (Zone[] column : zones)
			for (Zone zone : column)
				if (zone != null)
					zone.destroy();
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void finalize() {
		if (glVao != 0 || glVaoA != 0)
			DestructibleHandler.queueLeakedDestruction(this);
	}

	@Override
	public void destroy() {
		if (vboO != null) {
			vboO.destroy();
			vboO = null;
		}

		if (vboA != null) {
			vboA.destroy();
			vboA = null;
		}

		if (vboM != null) {
			vboM.destroy();
			vboM = null;
		}

		if (tboF != null) {
			tboF.destroy();
			tboF = null;
		}

		if (glVao != 0) {
			glDeleteVertexArrays(glVao);
			glVao = 0;
		}

		if (glVaoA != 0) {
			glDeleteVertexArrays(glVaoA);
			glVaoA = 0;
		}

		if (uploadJob != null) {
			uploadJob.cancel();
			DestructibleHandler.destroy(uploadJob.zoneBeingUploaded);
			uploadJob = null;
		}

		sizeO = 0;
		sizeA = 0;
		sizeF = 0;
		bufLen = 0;
		bufLenA = 0;

		initialized = false;
		cull = false;
		hasWater = false;
		onlyWater = false;
		hasGapFiller = false;
		inSceneFrustum = false;
		inShadowFrustum = false;

		Arrays.fill(levelOffsets, 0);
		rids = null;
		roofStart = null;
		roofEnd = null;

		if (viewContext != null) {
			for (int i = 0; i < acquiredStaticModels.size(); i++)
				viewContext.releaseStaticAlphaModel(acquiredStaticModels.get(i));
		}
		acquiredStaticModels.clear();

		alphaModels.clear();
		viewContext = null;
	}

	@Override
	public String toString() {
		return String.format(
			"Zone Initialized: %b, culled: %b hasUploadJob: %b opaqueSize: %d alphaSize: %d",
			initialized, cull, uploadJob != null, sizeO, sizeA
		);
	}

	public void unmap(WorldViewContext viewContext) {
		assert client.isClientThread();

		if (vboO != null) {
			vboO.mapped().syncViews();
			this.bufLen = vboO.mapped().byteView().position() / VERT_SIZE;
			vboO.unmap();
		}

		if (vboA != null) {
			vboA.mapped().syncViews();
			this.bufLenA = vboA.mapped().byteView().position() / VERT_SIZE;
			vboA.unmap();
		}

		if (tboF != null)
			tboF.unmap();

		// Deduplicate static alpha models now that upload has completed, sharing identical models across all zones
		for (int i = 0; i < alphaModels.size(); i++) {
			final AlphaModelInstance m = alphaModels.get(i);
			if (m.staticModel != null) {
				m.staticModel = viewContext.dedupeStaticAlphaModel(m.staticModel);
				acquiredStaticModels.add(m.staticModel);
			}
		}
	}

	private void setupVao(int vao, int buffer, int metadata) {
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, buffer);

		// Position
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 4, GL_SHORT, false, VERT_SIZE, 0);

		// UVs
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 4, GL_HALF_FLOAT, false, VERT_SIZE, 8);

		// Normals
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 4, GL_SHORT, false, VERT_SIZE, 16);

		// TextureFaceIdx
		glEnableVertexAttribArray(3);
		glVertexAttribIPointer(3, 1, GL_INT, VERT_SIZE, 24);

		glBindBuffer(GL_ARRAY_BUFFER, metadata);

		// WorldView index (not ID)
		glEnableVertexAttribArray(6);
		glVertexAttribDivisor(6, 1);
		glVertexAttribIPointer(6, 1, GL_INT, METADATA_SIZE, 0);

		// Scene offset
		glEnableVertexAttribArray(7);
		glVertexAttribDivisor(7, 1);
		glVertexAttribIPointer(7, 2, GL_INT, METADATA_SIZE, 4);

		checkGLErrors();

		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	public void setMetadata(WorldViewContext viewContext, SceneContext sceneContext, int mx, int mz) {
		this.viewContext = viewContext;
		if (vboM == null)
			return;

		int baseX = (mx - (sceneContext.sceneOffset >> 3)) << 10;
		int baseZ = (mz - (sceneContext.sceneOffset >> 3)) << 10;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer buf = stack.mallocInt(3)
				.put(viewContext.uboWorldViewStruct != null ? viewContext.uboWorldViewStruct.worldViewIdx + 1 : 0)
				.put(baseX)
				.put(baseZ);
			buf.flip();
			vboM.upload(buf);
		}
	}

	void updateRoofs(Int2IntHashMap updates) {
		for (int level = 0; level < 4; ++level) {
			for (int i = 0; i < rids[level].length; ++i) {
				rids[level][i] = updates.getOrDefault(rids[level][i], rids[level][i]);
			}
		}

		for (AlphaModelInstance m : alphaModels)
			m.rid = (short) updates.getOrDefault(m.rid, m.rid);
	}

	private static final int NUM_DRAW_RANGES = 512;
	private static final int[] drawRanges = new int[NUM_DRAW_RANGES * 2];
	private static final int[] glDrawOffset = new int[NUM_DRAW_RANGES];
	private static final int[] glDrawLength = new int[NUM_DRAW_RANGES];

	private static int drawIdx = 0;

	private void convertForDraw(int vertSize) {
		final int vertSizeInts = vertSize >> 2;
		for (int i = 0, j = 0; i < drawIdx; ++i) {
			int start = drawRanges[j++];
			int end = drawRanges[j++];

			assert end >= start;

			// convert from ints to verts
			start /= vertSizeInts;
			end /= vertSizeInts;

			end -= start; // convert from end pos to length

			glDrawOffset[i] = start;
			glDrawLength[i] = end;
		}
	}

	void renderOpaque(CommandBuffer cmd, WorldViewContext ctx, boolean roofShadows) {
		drawIdx = 0;

		int currentLevel = ctx.level;
		int maxLevel = ctx.maxLevel;
		var hiddenRoofIds = ctx.hideRoofIds;
		if (roofShadows) {
			maxLevel = 3;
			hiddenRoofIds = Collections.emptySet();
		}

		for (int level = ctx.minLevel; level <= maxLevel; ++level) {
			int[] rids = this.rids[level];
			int[] roofStart = this.roofStart[level];
			int[] roofEnd = this.roofEnd[level];

			if (rids.length == 0 || hiddenRoofIds.isEmpty() || level <= currentLevel) {
				// draw the whole level
				int start = level == 0 ? 0 : this.levelOffsets[level - 1];
				int end = this.levelOffsets[level];
				pushRange(start, end);
				continue;
			}

			for (int roofIdx = 0; roofIdx < rids.length; ++roofIdx) {
				int rid = rids[roofIdx];
				if (rid > 0 && !hiddenRoofIds.contains(rid)) {
					// draw the roof
					assert roofEnd[roofIdx] >= roofStart[roofIdx];
					if (roofEnd[roofIdx] > roofStart[roofIdx]) {
						pushRange(roofStart[roofIdx], roofEnd[roofIdx]);
					}
				}
			}

			// push from the end of the last roof to the end of the level
			int endpos = level == 0 ? 0 : this.levelOffsets[level - 1];
			for (int roofIdx = rids.length - 1; roofIdx >= 0; --roofIdx) {
				int rid = rids[roofIdx];
				if (rid > 0) {
					endpos = roofEnd[roofIdx];
					break;
				}
			}
			// draw the non roofs
			pushRange(endpos, this.levelOffsets[level]);
		}

		if (drawIdx == 0)
			return;

		lastDrawMode = STATIC_UNSORTED;
		lastVao = glVao;
		lastTboF = tboF.getTexId();
		flush(cmd);
	}

	void renderOpaqueLevel(CommandBuffer cmd, int level) {
		drawIdx = 0;

		pushRange(level > 0 ? this.levelOffsets[level - 1] : 0, this.levelOffsets[level]);

		if (drawIdx == 0)
			return;

		lastDrawMode = STATIC_UNSORTED;
		lastVao = glVao;
		lastTboF = tboF.getTexId();
		flush(cmd);
	}

	private static void pushRange(int start, int end) {
		assert end >= start;
		if (end <= start)
			return;

		if (drawIdx > 0 && drawRanges[(drawIdx - 1) * 2 + 1] == start) {
			drawRanges[(drawIdx - 1) * 2 + 1] = end;
		} else if (drawIdx >= NUM_DRAW_RANGES) {
			log.debug("draw ranges exhausted");
		} else {
			drawRanges[drawIdx * 2] = start;
			drawRanges[drawIdx * 2 + 1] = end;
			drawIdx++;
		}
	}

	void addAlphaModel(
		HdPlugin plugin,
		MaterialManager materialManager,
		int vao,
		int tboF,
		Model model,
		ModelOverride modelOverride,
		int startpos,
		int endpos,
		int x,
		int y,
		int z,
		int lx,
		int lz,
		int ux,
		int uz,
		int rid,
		int level,
		int id
	) {
		AlphaModelInstance m = new AlphaModelInstance();
		m.id = id;
		m.modelOverride = modelOverride;
		m.startpos = startpos;
		m.endpos = endpos;
		m.x = (short) x;
		m.y = (short) y;
		m.z = (short) z;
		m.vao = vao;
		m.tboF = tboF;
		m.rid = (short) rid;
		m.level = (byte) level;
		staticAlphaLevelMask = (byte) (staticAlphaLevelMask | 1 << level);
		if (lx > -1) {
			m.lx = (byte) lx;
			m.lz = (byte) lz;
			m.ux = (byte) ux;
			m.uz = (byte) uz;
		} else {
			m.lx = m.lz = m.ux = m.uz = -1;
		}

		int faceCount = model.getFaceCount();
		short[] unlitColor = plugin.configUnlitFaceColors ? model.getUnlitFaceColors() : null;
		int[] color1 = model.getFaceColors1();
		int[] color3 = model.getFaceColors3();
		byte[] transparencies = model.getFaceTransparencies();
		short[] faceTextures = model.getFaceTextures();
		float[] vertexX = model.getVerticesX();
		float[] vertexY = model.getVerticesY();
		float[] vertexZ = model.getVerticesZ();
		int[] indices1 = model.getFaceIndices1();
		int[] indices2 = model.getFaceIndices2();
		int[] indices3 = model.getFaceIndices3();

		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

		for (int f = 0; f < faceCount; ++f) {
			if (color3[f] == -2)
				continue;

			boolean hasAlpha = modelOverride.mightHaveTransparency || transparencies != null && transparencies[f] != 0;
			if (!hasAlpha)
				continue;

			int fx = (int) (vertexX[indices1[f]] + vertexX[indices2[f]] + vertexX[indices3[f]]);
			int fy = (int) (vertexY[indices1[f]] + vertexY[indices2[f]] + vertexY[indices3[f]]);
			int fz = (int) (vertexZ[indices1[f]] + vertexZ[indices2[f]] + vertexZ[indices3[f]]);

			minX = Math.min(minX, fx);
			maxX = Math.max(maxX, fx);
			minY = Math.min(minY, fy);
			maxY = Math.max(maxY, fy);
			minZ = Math.min(minZ, fz);
			maxZ = Math.max(maxZ, fz);
		}

		int cx = (minX + maxX) / 6;
		int cy = (minY + maxY) / 6;
		int cz = (minZ + maxZ) / 6;

		int size = Math.max(
			Math.max(
				Math.max(maxX / 3 - cx, minX / -3 - cx),
				Math.max(maxY / 3 - cy, minY / -3 - cy) * 2
			),
			Math.max(maxZ / 3 - cz, minZ / -3 - cz)
		);

		int shift = 0;
		// 10 bits because we need a sign bit
		for (int v = size >> 10; v > 0; v >>= 1) {
			shift++;
		}

		final int intsPerVertex = VERT_SIZE / Integer.BYTES;
		final int writtenAlphaFaceCount = (endpos - startpos) / (3 * intsPerVertex);
		final int bucketCapacity = ceil(writtenAlphaFaceCount / 32.0f);

		final int[] packedFaces = PooledArrayType.INT.borrow(writtenAlphaFaceCount);
		final int[] doubleSidedBitSet = PooledArrayType.INT.borrow(bucketCapacity);

		Arrays.fill(doubleSidedBitSet, 0, bucketCapacity, 0);

		final Material baseMaterial = modelOverride.baseMaterial;
		final Material textureMaterial = modelOverride.textureMaterial;

		int radius = 0;
		char bufferIdx = 0;
		char doubleSidedCount = 0;
		int minBoundX = Integer.MAX_VALUE, minBoundY = Integer.MAX_VALUE, minBoundZ = Integer.MAX_VALUE;
		int maxBoundX = Integer.MIN_VALUE, maxBoundY = Integer.MIN_VALUE, maxBoundZ = Integer.MIN_VALUE;
		for (int f = 0; f < faceCount; ++f) {
			if (color3[f] == -2)
				continue;

			// Hide fake shadows or lighting that is often baked into models by making the fake shadow transparent
			if (plugin.configHideFakeShadows && modelOverride.hideVanillaShadows && HDUtils.isBakedGroundShading(model, f))
				continue;

			Material material = baseMaterial;
			ModelOverride faceOverride = modelOverride;

			int transparency = SceneUploader.readFaceTransparency(model.getTransparency(), transparencies, f);
			if (transparency == 255)
				continue;

			int textureId = faceTextures != null ? faceTextures[f] : -1;
			if (textureId != -1) {
				if (modelOverride.textureMaterial != Material.NONE) {
					material = textureMaterial;
				} else {
					material = materialManager.fromVanillaTexture(textureId);
					if (modelOverride.materialOverrides != null) {
						var override = modelOverride.materialOverrides.get(material);
						if (override != null) {
							faceOverride = override;
							material = override.textureMaterial;
						}
					}
				}
			} else if (modelOverride.colorOverrides != null) {
				final int ahsl = (0xFF - transparency) << 16 | (unlitColor != null ? unlitColor[f] & 0xFFFF : color1[f]);
				final var override = modelOverride.testColorOverrides(ahsl);
				if (override != null) {
					faceOverride = override;
					material = override.baseMaterial;
				}
			}

			if (faceOverride.hide)
				continue;

			if (faceOverride.modifiesAlpha)
				transparency = 255 - faceOverride.modifyAlpha(255 - transparency);

			boolean hasAlpha = material.hasTransparency || transparency != 0;
			if (!hasAlpha)
				continue;

			final int i1 = indices1[f], i2 = indices2[f], i3 = indices3[f];
			final int vx1 = (int) vertexX[i1], vx2 = (int) vertexX[i2], vx3 = (int) vertexX[i3];
			final int vy1 = (int) vertexY[i1], vy2 = (int) vertexY[i2], vy3 = (int) vertexY[i3];
			final int vz1 = (int) vertexZ[i1], vz2 = (int) vertexZ[i2], vz3 = (int) vertexZ[i3];
			minBoundX = min(minBoundX, min(vx1, min(vx2, vx3)));
			maxBoundX = max(maxBoundX, max(vx1, max(vx2, vx3)));
			minBoundY = min(minBoundY, min(vy1, min(vy2, vy3)));
			maxBoundY = max(maxBoundY, max(vy1, max(vy2, vy3)));
			minBoundZ = min(minBoundZ, min(vz1, min(vz2, vz3)));
			maxBoundZ = max(maxBoundZ, max(vz1, max(vz2, vz3)));

			final int fx = (((int) (vertexX[indices1[f]] + vertexX[indices2[f]] + vertexX[indices3[f]]) / 3) - cx) >> shift;
			final int fy = (((int) (vertexY[indices1[f]] + vertexY[indices2[f]] + vertexY[indices3[f]]) / 3) - cy) >> shift;
			final int fz = (((int) (vertexZ[indices1[f]] + vertexZ[indices2[f]] + vertexZ[indices3[f]]) / 3) - cz) >> shift;

			radius = Math.max(radius, fx * fx + fy * fy + fz * fz);
			packedFaces[bufferIdx] = ((fx & ((1 << 11) - 1)) << 21)
			                         | ((fy & ((1 << 10) - 1)) << 11)
			                         | (fz & ((1 << 11) - 1));

			if (faceOverride.doubleSidedFaces || material.doubleSidedFaces) {
				doubleSidedBitSet[bufferIdx >> 5] |= 1 << (bufferIdx & 31);
				doubleSidedCount++;
			}

			bufferIdx++;
		}

		assert packedFaces.length > 0;
		// Normally these will be equal, but transparency is used to hide faces in the TzHaar reskin
		assert bufferIdx <= packedFaces.length : String.format("%d > %d", (int) bufferIdx, packedFaces.length);

		final int staticRadius = 2 + (int) Math.sqrt(radius);
		final int[] finalPackedFaces = Arrays.copyOf(packedFaces, bufferIdx);
		final int[] finalDoubleSidedBitSet = doubleSidedCount > 0 ? Arrays.copyOf(doubleSidedBitSet, ceil(bufferIdx / 32.0f)) : null;
		m.createStaticModel(finalPackedFaces, finalDoubleSidedBitSet, doubleSidedCount, staticRadius);

		if (bufferIdx == 0) {
			m.extentsX = m.extentsY = m.extentsZ = 0;
		} else {
			// Symmetric half-extents around the model origin, which is conservative for off-centre geometry
			m.extentsX = (short) Math.min(Short.MAX_VALUE, Math.max(Math.abs(minBoundX), Math.abs(maxBoundX)));
			m.extentsY = (short) Math.min(Short.MAX_VALUE, Math.max(Math.abs(minBoundY), Math.abs(maxBoundY)));
			m.extentsZ = (short) Math.min(Short.MAX_VALUE, Math.max(Math.abs(minBoundZ), Math.abs(maxBoundZ)));
		}

		alphaMinBoundsX = min(alphaMinBoundsX, m.x - m.extentsX);
		alphaMaxBoundsX = max(alphaMaxBoundsX, m.x + m.extentsX);
		alphaMinBoundsY = min(alphaMinBoundsY, m.y - m.extentsY);
		alphaMaxBoundsY = max(alphaMaxBoundsY, m.y + m.extentsY);
		alphaMinBoundsZ = min(alphaMinBoundsZ, m.z - m.extentsZ);
		alphaMaxBoundsZ = max(alphaMaxBoundsZ, m.z + m.extentsZ);

		alphaModels.add(m);

		PooledArrayType.INT.release(packedFaces);
		PooledArrayType.INT.release(doubleSidedBitSet);
	}

	synchronized AlphaModelInstance requestTempAlphaModel(Camera camera, int zx, int zz, ModelOverride modelOverride, int level, int x, int y, int z) {
		AlphaModelInstance m = ALPHA_MODEL_POOL.acquire();
		m.id = -1;
		m.modelOverride = modelOverride;
		m.x = (short) x;
		m.y = (short) y;
		m.z = (short) z;
		m.level = (byte) level;
		m.vao = m.tboF = m.rid = m.lx = m.lz = m.ux = m.uz = -1;
		m.staticModel = null;
		m.flags = AlphaModelInstance.VISIBLE;
		m.zofx = m.zofz = 0;
		m.calculateDist((int) camera.getPositionX(), (int) camera.getPositionY(), (int) camera.getPositionZ(), zx, zz);
		alphaLevelMask = (byte) (alphaLevelMask | 1 << level);
		alphaModels.add(m);
		return m;
	}

	synchronized void postAlphaPass() {
		if (viewContext != null)
			viewContext.alphaSortingJob.waitForCompletion();
		alphaLevelMask = staticAlphaLevelMask;
		visibleAlphaModels.reset();

		for (int i = alphaModels.size() - 1; i >= 0; --i) {
			AlphaModelInstance m = alphaModels.get(i);
			m.dist = Integer.MAX_VALUE;
			m.flags &= ~AlphaModelInstance.SKIP;
			m.flags |= AlphaModelInstance.VISIBLE;
			assert m.asyncModel == null;

			if (m.isTemp() || (m.flags & AlphaModelInstance.TEMP) != 0) {
				alphaModels.remove(i);
				m.staticModel = null;
				ALPHA_MODEL_POOL.recycle(m);
			}
		}
	}

	private static final int STATIC = 1;
	private static final int TEMP = 2;
	private static final int STATIC_UNSORTED = 3;

	private static int[] alphaModelsKeys = new int[64];
	private static int lastDrawMode;
	private static int lastVao;
	private static int lastTboF;
	private static int lastzx, lastzz;

	// Per-instance draws for the sorted (STATIC) alpha path. Each instance references a shared sorted-index block
	// with its own baseVertex, so we can't collapse them into a single contiguous draw anymore.
	private static int staticDrawCount;
	private static final int[] staticDrawOffset = new int[NUM_DRAW_RANGES];
	private static final int[] staticDrawLength = new int[NUM_DRAW_RANGES];
	private static final int[] staticDrawBaseVertex = new int[NUM_DRAW_RANGES];

	synchronized void alphaSort(Camera camera, int zx, int zz) {
		visibleAlphaModels.reset();

		final int alphaModelCount = alphaModels.size();
		if (alphaModelCount == 0)
			return;

		// The world view's sorting job assigns the shared models their index blocks, and the whole packed block array
		// is uploaded to the EBO as one contiguous copy per world view, so reserve its destination once, before any
		// instance draws reference it. The EBO is already mapped at this point, since this runs during scene draw.
		if (viewContext != null) {
			viewContext.alphaSortingJob.waitForCompletion();
			if (viewContext.eboAlphaBaseOffset < 0 && viewContext.eboAlphaCount > 0)
				viewContext.eboAlphaBaseOffset = viewContext.sortedAlphaFacesUpload.reserve(viewContext.eboAlphaCount);
		}

		if (alphaModelCount == 1) {
			visibleAlphaModels.put(0);
			return;
		}

		if(alphaModelCount > alphaModelsKeys.length)
			alphaModelsKeys = new int[alphaModelCount];

		visibleAlphaModels.ensureCapacity(alphaModelCount);

		final int cx = (int) camera.getPositionX();
		final int cy = (int) camera.getPositionY();
		final int cz = (int) camera.getPositionZ();

		// Instance visibility already resolved for this frame by WorldViewContext.sortStaticAlphaModels(). Just compute
		// distances and order the visible instances back to front.
		for(int i = 0; i < alphaModelCount; i++) {
			final AlphaModelInstance model = alphaModels.get(i);
			if (!model.isVisible())
				continue;

			if ((model.flags & AlphaModelInstance.SKIP) == 0 && !model.isTemp())
				model.calculateDist(cx, cy, cz, zx, zz);

			alphaModelsKeys[visibleAlphaModels.length] = -model.dist;
			visibleAlphaModels.put(i);
		}

		if (visibleAlphaModels.length <= 1)
			return;

		quickSortByKey(alphaModelsKeys, visibleAlphaModels.array, 0, visibleAlphaModels.length - 1);
	}

	/**
	 * Hides this zone's static alpha model instances which are outside the frustum. Called once per frame by
	 * {@link WorldViewContext#sortStaticAlphaModels(Camera)} before the shared models are sorted, so unnecessary models
	 * can be skipped. The zone's combined AABB is maintained during upload by {@link #addAlphaModel}, allowing the
	 * per-instance test to be skipped entirely when the whole zone is visible. Instances start visible to avoid pop-in,
	 * so this only ever hides.
	 */
	void determineAlphaModelVisibility(Camera camera, int zx, int zz, boolean frustumCull) {
		final int alphaModelCount = alphaModels.size();
		if (alphaModelCount <= 1)
			return;

		int boundsClassification = 1;
		if (frustumCull && hasAlphaBounds()) {
			final int shiftX = zx << 10;
			final int shiftZ = zz << 10;
			boundsClassification = camera.classifyAABB(
				alphaMinBoundsX + shiftX, alphaMinBoundsY, alphaMinBoundsZ + shiftZ,
				alphaMaxBoundsX + shiftX, alphaMaxBoundsY, alphaMaxBoundsZ + shiftZ
			);
		}

		final boolean fullyOutside = boundsClassification == -1;
		final boolean partiallyVisible = boundsClassification == 0;
		if (!fullyOutside && !partiallyVisible)
			return;

		for (int i = 0; i < alphaModelCount; i++) {
			final AlphaModelInstance m = alphaModels.get(i);
			if ((m.flags & AlphaModelInstance.SKIP) != 0 || m.isTemp())
				continue;

			// The zone AABB only covers this zone's own instances. Multizone clones are positioned in another zone, so
			// they always fall back to the individual test instead of the zone-wide result.
			if (fullyOutside && (m.flags & AlphaModelInstance.TEMP) == 0) {
				m.setHidden();
				continue;
			}

			final int modelX = m.x + ((zx - m.zofx) << 10);
			final int modelZ = m.z + ((zz - m.zofz) << 10);
			if (!camera.intersectsAABB(
				modelX - m.extentsX, m.y - m.extentsY, modelZ - m.extentsZ,
				modelX + m.extentsX, m.y + m.extentsY, modelZ + m.extentsZ
			)) {
				m.setHidden();
			}
		}
	}

	void renderAlpha(
		CommandBuffer cmd,
		int zx,
		int zz,
		int level,
		WorldViewContext ctx,
		boolean depthOnly,
		boolean includeRoof
	) {
		if (visibleAlphaModels.isEmpty())
			return;

		int minLevel = ctx.minLevel;
		int currentLevel = ctx.level;
		int maxLevel = ctx.maxLevel;
		var hiddenRoofIds = ctx.hideRoofIds;
		if (includeRoof) {
			maxLevel = 3;
			hiddenRoofIds = Collections.emptySet();
		}

		drawIdx = 0;
		staticDrawCount = 0;

		if(!depthOnly)
			ctx.alphaSortingJob.waitForCompletion();

		for (int i = 0; i < visibleAlphaModels.length; i++) {
			final AlphaModelInstance m = alphaModels.get(visibleAlphaModels.array[i]);
			if ((m.flags & AlphaModelInstance.SKIP) != 0 || m.level != level)
				continue;

			final AsyncCachedModel asyncModel = m.asyncModel;
			if(asyncModel != null)
				asyncModel.waitForCompletion();

			if (m.vao == -1 || level < minLevel || level > maxLevel ||
				level > currentLevel && !hiddenRoofIds.isEmpty() && hiddenRoofIds.contains((int) m.rid))
				continue;


			int drawMode = STATIC;
			if (m.isTemp()) {
				// these are already sorted and so just requires a glMultiDrawArrays() from the active vao
				drawMode = TEMP;
			} else if (depthOnly) {
				drawMode = STATIC_UNSORTED;
			}

			if (lastDrawMode != drawMode ||
				lastVao != m.vao ||
				lastTboF != m.tboF ||
				lastzx != (zx - m.zofx) ||
				lastzz != (zz - m.zofz)
			) {
				flush(cmd);
				lastDrawMode = drawMode;
				lastVao = m.vao;
				lastTboF = m.tboF;
				lastzx = zx - m.zofx;
				lastzz = zz - m.zofz;
			}

			if (drawMode != STATIC) {
				pushRange(m.startpos, m.endpos);
				continue;
			}

			final StaticAlphaModel model = m.staticModel;
			if (model == null || model.sortedIndiciesOffset < 0 || model.sortedFaceCount <= 0)
				continue;

			// Each instance's sorted indices live at the world view's EBO base offset + its model's block offset,
			// which was reserved in alphaSort().
			final int dest = ctx.eboAlphaBaseOffset + model.sortedIndiciesOffset;

			if (staticDrawCount >= NUM_DRAW_RANGES)
				flush(cmd);

			staticDrawOffset[staticDrawCount] = dest;
			staticDrawLength[staticDrawCount] = model.sortedFaceCount * 3;
			staticDrawBaseVertex[staticDrawCount] = m.startpos / (VERT_SIZE >> 2);
			staticDrawCount++;
		}
		flush(cmd);
	}

	void renderAlphaShadows(CommandBuffer cmd, int zx, int zz, WorldViewContext ctx, boolean includeRoof) {
		drawIdx = 0;

		if (glVaoA != 0 && bufLenA > 0 && tboF != null) {
			lastDrawMode = STATIC_UNSORTED;
			lastVao = glVaoA;
			lastTboF = tboF.getTexId();
			lastzx = zx;
			lastzz = zz;
			pushRange(0, bufLenA * (VERT_SIZE >> 2)); // pushRange works in ints, flush converts to verts
			flush(cmd);
		}

		if (alphaModels.isEmpty())
			return;

		int minLevel = ctx.minLevel;
		int currentLevel = ctx.level;
		int maxLevel = ctx.maxLevel;
		var hiddenRoofIds = ctx.hideRoofIds;
		if (includeRoof) {
			maxLevel = 3;
			hiddenRoofIds = Collections.emptySet();
		}

		for (int i = 0; i < alphaModels.size(); i++) {
			final AlphaModelInstance m = alphaModels.get(i);

			if ((m.flags & (AlphaModelInstance.SKIP | AlphaModelInstance.TEMP)) != 0 || m.vao == -1 || m.vao == glVaoA)
				continue;

			final AsyncCachedModel asyncModel = m.asyncModel;
			if (asyncModel != null)
				asyncModel.waitForCompletion();

			if (m.level < minLevel || m.level > maxLevel ||
				m.level > currentLevel && !hiddenRoofIds.isEmpty() && hiddenRoofIds.contains((int) m.rid))
				continue;

			int drawMode = m.isTemp() ? TEMP : STATIC_UNSORTED;
			if (lastDrawMode != drawMode || lastVao != m.vao || lastTboF != m.tboF) {
				flush(cmd);
				lastDrawMode = drawMode;
				lastVao = m.vao;
				lastTboF = m.tboF;
			}

			pushRange(m.startpos, m.endpos);
		}
		flush(cmd);
	}

	private void flush(CommandBuffer cmd) {
		if (lastDrawMode == STATIC) {
			if (staticDrawCount > 0 && lastVao != 0) {
				cmd.BindVertexArray(lastVao, eboAlpha);
				cmd.BindTextureUnit(GL_TEXTURE_BUFFER, lastTboF, TEXTURE_UNIT_TEXTURED_FACES);
				// The EBO & IDO are bound by ZoneRenderer
				if (SUPPORTS_MULTI_INDIRECT_DRAW) {
					cmd.MultiDrawElementsIndirect(
						GL_TRIANGLES,
						staticDrawLength, staticDrawOffset, staticDrawBaseVertex,
						staticDrawCount,
						ZoneRenderer.indirectDrawCmdsStaging
					);
				} else if (SUPPORTS_INDIRECT_DRAW) {
					for (int i = 0; i < staticDrawCount; i++)
						cmd.DrawElementsIndirect(
							GL_TRIANGLES, staticDrawLength[i], staticDrawOffset[i], staticDrawBaseVertex[i],
							ZoneRenderer.indirectDrawCmdsStaging
						);
				} else {
					for (int i = 0; i < staticDrawCount; i++)
						cmd.DrawElementsBaseVertex(
							GL_TRIANGLES, staticDrawLength[i], 4L * staticDrawOffset[i], staticDrawBaseVertex[i]
						);
				}
			}
			staticDrawCount = 0;
		} else if (drawIdx != 0) {
			convertForDraw(lastDrawMode == STATIC_UNSORTED ? VERT_SIZE : DynamicModelVAO.VERT_SIZE);
			cmd.BindVertexArray(lastVao);
			cmd.BindTextureUnit(GL_TEXTURE_BUFFER, lastTboF, TEXTURE_UNIT_TEXTURED_FACES);
			if (drawIdx == 1) {
				if (SUPPORTS_INDIRECT_DRAW) {
					cmd.DrawArraysIndirect(GL_TRIANGLES, glDrawOffset[0], glDrawLength[0], ZoneRenderer.indirectDrawCmdsStaging);
				} else {
					cmd.DrawArrays(GL_TRIANGLES, glDrawOffset[0], glDrawLength[0]);
				}
			} else {
				if (SUPPORTS_MULTI_INDIRECT_DRAW) {
					cmd.MultiDrawArraysIndirect(GL_TRIANGLES, glDrawOffset, glDrawLength, drawIdx, ZoneRenderer.indirectDrawCmdsStaging);
				} else {
					cmd.MultiDrawArrays(GL_TRIANGLES, glDrawOffset, glDrawLength, drawIdx);
				}
			}
			drawIdx = 0;
		}
	}

	synchronized void multizoneLocs(SceneContext ctx, int zx, int zz, Camera camera, Zone[][] zones) {
		int offset = ctx.sceneOffset >> 3;
		int cx = (int) camera.getPositionX();
		int cz = (int) camera.getPositionZ();
		for (int i = 0; i < alphaModels.size(); i++) {
			final AlphaModelInstance m = alphaModels.get(i);
			if (m.lx == -1)
				continue;

			// calculate which zone this model should be drawn from
			// TODO fix for boats
			int max = Integer.MAX_VALUE;
			int closestZoneX = -50, closestZoneZ = -50;
			for (int x = m.lx >> 3; x <= m.ux >> 3; ++x) {
				for (int z = m.lz >> 3; z <= m.uz >> 3; ++z) {
					int centerX = (zx - m.zofx + x) * 8 + 4 << 7;
					int centerZ = (zz - m.zofz + z) * 8 + 4 << 7;
					int distance = (centerX - cx) * (centerX - cx) +
								   (centerZ - cz) * (centerZ - cz);
					if (distance < max) {
						int zx2 = (centerX >> 10) + offset;
						int zz2 = (centerZ >> 10) + offset;
						if (zx2 >= 0 && zx2 < zones.length && zz2 >= 0 && zz2 < zones[0].length) {
							if (zones[zx2][zz2].inSceneFrustum && zones[zx2][zz2].initialized) {
								max = distance;
								closestZoneX = centerX >> 10;
								closestZoneZ = centerZ >> 10;
							}
						}
					}
				}
			}
			if (closestZoneX != -50 && (closestZoneX != zx || closestZoneZ != zz)) {
				assert closestZoneX + offset >= 0 : closestZoneX;
				assert closestZoneX + offset < zones.length : closestZoneX;
				assert closestZoneZ + offset >= 0 : closestZoneZ;
				assert closestZoneZ + offset < zones[0].length : closestZoneZ;

				Zone z = zones[closestZoneX + offset][closestZoneZ + offset];
				assert z != null;
				assert z != this;

				AlphaModelInstance m2 = ALPHA_MODEL_POOL.acquire();
				m2.id = m.id;
				m2.modelOverride = m.modelOverride;
				m2.startpos = m.startpos;
				m2.endpos = m.endpos;
				m2.x = m.x;
				m2.y = m.y;
				m2.z = m.z;
				m2.extentsX = m.extentsX;
				m2.extentsY = m.extentsY;
				m2.extentsZ = m.extentsZ;
				m2.vao = m.vao;
				m2.tboF = m.tboF;
				m2.rid = m.rid;
				m2.level = m.level;
				m2.lx = m.lx;
				m2.lz = m.lz;
				m2.ux = m.ux;
				m2.uz = m.uz;
				m2.zofx = (byte) (closestZoneX - zx);
				m2.zofz = (byte) (closestZoneZ - zz);

				// The clone shares the owner's StaticAlphaModel. The owner zone owns the refcount and releases it
				m2.staticModel = m.staticModel;

				m2.flags = AlphaModelInstance.TEMP | AlphaModelInstance.VISIBLE;
				m.flags |= AlphaModelInstance.SKIP;

				z.alphaLevelMask = (byte) (z.alphaLevelMask | 1 << m2.level);
				z.alphaModels.add(m2);
			}
		}
	}
}
