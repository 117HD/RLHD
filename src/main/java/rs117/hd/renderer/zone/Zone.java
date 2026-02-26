package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.ToIntFunction;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import org.lwjgl.system.MemoryStack;
import rs117.hd.HdPlugin;
import rs117.hd.renderer.zone.OcclusionManager.OcclusionQuery;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLTextureBuffer;
import rs117.hd.utils.jobs.GenericJob;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.SUPPORTS_INDIRECT_DRAW;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class Zone {
	@Inject
	private Client client;

	// Zone vertex format
	// pos short vec3(x, y, z)
	// uvw short vec3(u, v, w)
	// normal short vec3(nx, ny, nz)
	// texturedFaceIdx int
	public static final int VERT_SIZE = 24;

	// alphaBiasHsl ivec3
	// materialData ivec3
	// terrainData ivec3
	public static final int TEXTURE_SIZE = 36;

	// Metadata format
	// worldViewIndex int int
	// sceneOffset int vec2(x, y)
	public static final int METADATA_SIZE = 12;

	public static final int LEVEL_WATER_SURFACE = 4;

	public static final BlockingDeque<GLBuffer> VBO_PENDING_DELETION = new LinkedBlockingDeque<>();
	public static final BlockingDeque<Integer> VAO_PENDING_DELETION = new LinkedBlockingDeque<>();

	public int glVao;
	int bufLen;
	int dist;

	public int glVaoA;
	public int bufLenA;
	public int sortedFacesLen;

	public int sizeO, sizeA, sizeF;
	@Nullable
	public GLBuffer vboO, vboA, vboM;
	public GLTextureBuffer tboF;
	public OcclusionQuery occlusionQuery;
	public ConcurrentLinkedQueue<OcclusionQuery> additionalOcclusionQueries = new ConcurrentLinkedQueue<>();
	public boolean isFullyOccluded;

	public boolean initialized; // whether the zone vao and vbos are ready
	public boolean cull; // whether the zone is queued for deletion
	public boolean needsRoofUpdate; // whether the zone needs to have its roofs updated during scene swap
	public boolean rebuild; // whether the zone is queued for rebuild
	public boolean dirty; // whether the zone has temporary modifications
	public boolean hasWater; // whether the zone has any water tiles
	public boolean onlyWater; // whether the zone only contains water tiles
	public boolean inSceneFrustum; // whether the zone is visible to the scene camera
	public boolean inShadowFrustum; // whether the zone casts shadows into the visible scene
	public boolean isFirstLoadingAttempt = true;

	final StaticAlphaSortingJob alphaSortingJob = new StaticAlphaSortingJob();
	ZoneUploadJob uploadJob;

	int[] levelOffsets = new int[5]; // buffer pos in ints for the end of the level

	int[][] rids;
	int[][] roofStart;
	int[][] roofEnd;

	final List<AlphaModel> alphaModels = new ArrayList<>(0);
	final List<AlphaModel> playerModels = new ArrayList<>(0);
	final ConcurrentLinkedQueue<AsyncCachedModel> pendingModelJobs = new ConcurrentLinkedQueue<>();

	public void initialize(GLBuffer o, GLBuffer a, GLTextureBuffer f, int eboShared) {
		assert glVao == 0;
		assert glVaoA == 0;
		if (o == null && a == null || f == null)
			return;

		vboM = new GLBuffer("ZoneMetadata", GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW, 0);
		vboM.initialize(METADATA_SIZE);

		if (o != null) {
			vboO = o;
			glVao = glGenVertexArrays();
			setupVao(glVao, o.id, vboM.id, eboShared);
		}

		if (a != null) {
			vboA = a;
			glVaoA = glGenVertexArrays();
			setupVao(glVaoA, a.id, vboM.id, eboShared);
		}

		tboF = f;
		occlusionQuery = OcclusionManager.getInstance().obtainQuery();
	}

	public static void freeZones(@Nullable Zone[][] zones) {
		if (zones == null)
			return;

		for (Zone[] column : zones)
			for (Zone zone : column)
				if (zone != null)
					zone.free();
	}

	public void free() {
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
			uploadJob = null;
		}

		if(occlusionQuery != null) {
			occlusionQuery.free();
			occlusionQuery = null;
		}

		for(AlphaModel m : alphaModels) {
			if(m.occlusionQuery != null)
				m.occlusionQuery.free();
			m.occlusionQuery = null;
		}

		sortedAlphaFacesUpload.release();

		sizeO = 0;
		sizeA = 0;
		sizeF = 0;
		bufLen = 0;
		bufLenA = 0;

		initialized = false;
		cull = false;
		hasWater = false;
		onlyWater = false;
		inSceneFrustum = false;
		inShadowFrustum = false;

		Arrays.fill(levelOffsets, 0);
		rids = null;
		roofStart = null;
		roofEnd = null;

		// don't add permanent alphamodels to the cache as permanent alphamodels are always allocated
		// to avoid having to synchronize the cache
		alphaModels.clear();
	}

	public void evaluateOcclusion(){
		if(occlusionQuery != null) {
			isFullyOccluded = occlusionQuery.isOccluded();
			if(isFullyOccluded) {
				// Check if any of the dynamic occlusion queries are not occluded
				for(OcclusionQuery dynamicQuery : additionalOcclusionQueries) {
					if(dynamicQuery.isVisible()) {
						isFullyOccluded = false;
						break;
					}
				}

				if(isFullyOccluded) {
					// Zone is fully occluded, we need to requeue all dynamic queries since they are revelvant to if the zone is fully occluded
					for(OcclusionQuery dynamicQuery : additionalOcclusionQueries)
						dynamicQuery.queue();
				}
			}
			occlusionQuery.queue();
		}
		if(!isFullyOccluded) // Dynamics will reappend when they are processed
			additionalOcclusionQueries.clear();
	}

	public static void processPendingDeletions() {
		int leakCount = 0;
		GLBuffer vbo;
		while ((vbo = VBO_PENDING_DELETION.poll()) != null) {
			vbo.destroy();
			leakCount++;
		}

		Integer vao;
		while ((vao = VAO_PENDING_DELETION.poll()) != null) {
			glDeleteVertexArrays(vao);
			leakCount++;
		}

		if (leakCount > 0)
			log.warn("Destroyed {} leaked VBOs", leakCount);
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void finalize() {
		// Just in case the zone instance is no longer valid,
		// copy everything which needs to be cleaned up here
		if (vboO != null) {
			VBO_PENDING_DELETION.add(vboO);
			vboO = null;
		}

		if (vboA != null) {
			VBO_PENDING_DELETION.add(vboA);
			vboA = null;
		}

		if (vboM != null) {
			VBO_PENDING_DELETION.add(vboM);
			vboM = null;
		}

		if (glVao != 0) {
			VAO_PENDING_DELETION.add(glVao);
			glVao = 0;
		}

		if (glVaoA != 0) {
			VAO_PENDING_DELETION.add(glVaoA);
			glVaoA = 0;
		}
	}

	public void unmap() {
		assert client.isClientThread();

		if (vboO != null)
			vboO.unmap();
		if (vboA != null)
			vboA.unmap();
		if (tboF != null)
			tboF.unmap();

		if (vboO != null) {
			this.bufLen = vboO.mapped().byteView().position() / VERT_SIZE;
		}

		if (vboA != null) {
			this.bufLenA = vboA.mapped().byteView().position() / VERT_SIZE;
		}
	}

	private void setupVao(int vao, int buffer, int metadata, int ebo) {
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, buffer);

		// The element buffer is part of VAO state
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);

		// Position
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_SHORT, false, VERT_SIZE, 0);

		// UVs
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 3, GL_HALF_FLOAT, false, VERT_SIZE, 6);

		// Normals
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 3, GL_SHORT, false, VERT_SIZE, 12);

		// TextureFaceIdx
		glEnableVertexAttribArray(3);
		glVertexAttribIPointer(3, 1, GL_INT, VERT_SIZE, 20);

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

	public void setAlphaModelsOffset(WorldViewContext viewContext, SceneContext sceneContext, int mx, int mz) {
		int baseX = (mx - (sceneContext.sceneOffset >> 3)) << 10;
		int baseZ = (mz - (sceneContext.sceneOffset >> 3)) << 10;

		for(AlphaModel m : alphaModels) {
			if(m.occlusionQuery != null) {
				m.occlusionQuery.setOffset(baseX, 0, baseZ);
				m.occlusionQuery.setWorldView(viewContext.uboWorldViewStruct);
			}
		}
	}

	public void setMetadata(WorldViewContext viewContext, SceneContext sceneContext, int mx, int mz) {
		if (vboM == null)
			return;

		int baseX = (mx - (sceneContext.sceneOffset >> 3)) << 10;
		int baseZ = (mz - (sceneContext.sceneOffset >> 3)) << 10;

		if(occlusionQuery != null) {
			occlusionQuery.setOffset(baseX, 0, baseZ);
			occlusionQuery.setWorldView(viewContext.uboWorldViewStruct);
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer buf = stack.mallocInt(3)
				.put(viewContext.uboWorldViewStruct != null ? viewContext.uboWorldViewStruct.worldViewIdx + 1 : 0)
				.put(baseX)
				.put(baseZ);
			buf.flip();
			vboM.upload(buf);
		}
	}

	void updateRoofs(Map<Integer, Integer> updates) {
		for (int level = 0; level < 4; ++level) {
			for (int i = 0; i < rids[level].length; ++i) {
				rids[level][i] = updates.getOrDefault(rids[level][i], rids[level][i]);
			}
		}

		for (AlphaModel m : alphaModels) {
			m.rid = (short) (int) updates.getOrDefault((int) m.rid, (int) m.rid);
		}
	}

	private static final int NUM_DRAW_RANGES = 512;
	private static final int[] drawOff = new int[NUM_DRAW_RANGES];
	private static final int[] drawEnd = new int[NUM_DRAW_RANGES];

	private static final int[] glDrawOffset = new int[NUM_DRAW_RANGES];
	private static final int[] glDrawLength = new int[NUM_DRAW_RANGES];
	private static int drawIdx = 0;

	private void convertForDraw(int vertSize) {
		for (int i = 0; i < drawIdx; ++i) {
			assert drawEnd[i] >= drawOff[i];

			// convert from bytes to verts
			drawOff[i] /= vertSize >> 2;
			drawEnd[i] /= vertSize >> 2;

			drawEnd[i] -= drawOff[i]; // convert from end pos to length
		}

		copyTo(glDrawOffset, drawOff, 0, drawIdx);
		copyTo(glDrawLength, drawEnd, 0, drawIdx);
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

		pushRange(this.levelOffsets[level - 1], this.levelOffsets[level]);

		if (drawIdx == 0)
			return;

		lastDrawMode = STATIC_UNSORTED;
		lastVao = glVao;
		lastTboF = tboF.getTexId();
		flush(cmd);
	}

	private static void pushRange(int start, int end) {
		assert end >= start;

		if (drawIdx > 0 && drawEnd[drawIdx - 1] == start) {
			drawEnd[drawIdx - 1] = end;
		} else if (drawIdx >= NUM_DRAW_RANGES) {
			log.debug("draw ranges exhausted");
		} else {
			drawOff[drawIdx] = start;
			drawEnd[drawIdx] = end;
			drawIdx++;
		}
	}

	public static class AlphaModel {
		int id;
		ModelOverride modelOverride;
		int startpos, endpos;
		short x, y, z; // local position
		short rid;
		int vao;
		int tboF;
		byte level;
		byte lx, lz, ux, uz; // lower/upper zone coords
		byte zofx, zofz; // for temp alpha models, offset of source zone from target zone
		byte flags;

		// only set for static geometry as they require sorting
		int radius;
		int[] packedFaces;
		int[] sortedFaces;
		int sortedFacesLen;
		OcclusionQuery occlusionQuery;

		int dist;
		int asyncSortIdx = -1;
		int eboOffset = -1;

		static final int SKIP = 1; // temporary model is in a closer zone
		static final int TEMP = 2; // temporary model added to a closer zone
		static final int SORT_COMPLETED = 4;

		void setSorted() {
			flags |= SORT_COMPLETED;
		}

		boolean needsSorting() {
			return (flags & SORT_COMPLETED) == 0;
		}

		boolean isTemp() {
			return packedFaces == null || sortedFaces == null;
		}
	}

	static final ConcurrentLinkedQueue<AlphaModel> modelCache = new ConcurrentLinkedQueue<>();

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
		AlphaModel m = new AlphaModel();
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

		int minX = Integer.MAX_VALUE, minY = minX, minZ = minY;
		int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxY;

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

		int[] packedFaces = m.packedFaces = new int[(endpos - startpos) / ((3 * VERT_SIZE) >> 2)];
		int radius = 0;
		char bufferIdx = 0;
		for (int f = 0; f < faceCount; ++f) {
			if (color3[f] == -2)
				continue;

			// Hide fake shadows or lighting that is often baked into models by making the fake shadow transparent
			if (plugin.configHideFakeShadows && modelOverride.hideVanillaShadows && HDUtils.isBakedGroundShading(model, f))
				continue;

			int transparency = transparencies != null ? transparencies[f] & 0xFF : 0;
			int textureId = faceTextures != null ? faceTextures[f] : -1;

			Material material = Material.NONE;
			if (textureId != -1) {
				if (modelOverride.textureMaterial != Material.NONE) {
					material = modelOverride.textureMaterial;
				} else {
					material = materialManager.fromVanillaTexture(textureId);
					if (modelOverride.materialOverrides != null) {
						var override = modelOverride.materialOverrides.get(material);
						if (override != null) {
							material = override.textureMaterial;
						}
					}
				}
			} else if (modelOverride.colorOverrides != null) {
				int ahsl = (0xFF - transparency) << 16 | (unlitColor != null ? unlitColor[f] & 0xFFFF : color1[f]);
				for (var override : modelOverride.colorOverrides) {
					if (override.ahslCondition.test(ahsl)) {
						material = override.baseMaterial;
						break;
					}
				}
			}

			boolean hasAlpha = material.hasTransparency || transparency != 0;
			if (!hasAlpha)
				continue;

			int fx = (((int) (vertexX[indices1[f]] + vertexX[indices2[f]] + vertexX[indices3[f]]) / 3) - cx) >> shift;
			int fy = (((int) (vertexY[indices1[f]] + vertexY[indices2[f]] + vertexY[indices3[f]]) / 3) - cy) >> shift;
			int fz = (((int) (vertexZ[indices1[f]] + vertexZ[indices2[f]] + vertexZ[indices3[f]]) / 3) - cz) >> shift;

			radius = Math.max(radius, fx * fx + fy * fy + fz * fz);

			packedFaces[bufferIdx] = ((fx & ((1 << 11) - 1)) << 21)
									 | ((fy & ((1 << 10) - 1)) << 11)
									 | (fz & ((1 << 11) - 1));
			bufferIdx++;
		}

		m.radius = 2 + (int) Math.sqrt(radius);
		m.sortedFaces = new int[bufferIdx * 3];

		if(bufferIdx >= 32) {
			m.occlusionQuery = OcclusionManager.getInstance().obtainQuery();
			m.occlusionQuery.addSphere(x + cx, y + cy, z + cz, m.radius);
		}

		assert packedFaces.length > 0;
		// Normally these will be equal, but transparency is used to hide faces in the TzHaar reskin
		assert bufferIdx <= packedFaces.length : String.format("%d > %d", (int) bufferIdx, packedFaces.length);

		sortedFacesLen += m.sortedFaces.length;
		alphaModels.add(m);
	}

	synchronized void addTempAlphaModel(ModelOverride modelOverride, VAO.VAOView view, int level, int x, int y, int z) {
		AlphaModel m = modelCache.poll();
		if (m == null)
			m = new AlphaModel();
		m.id = -1;
		m.modelOverride = modelOverride;
		m.startpos = view.getStartOffset();
		m.endpos = view.getEndOffset();
		m.x = (short) x;
		m.y = (short) y;
		m.z = (short) z;
		m.vao = view.vao;
		m.tboF = view.tboTexId;
		m.rid = -1;
		m.level = (byte) level;
		m.lx = m.lz = m.ux = m.uz = -1;
		m.flags = 0;
		m.zofx = m.zofz = 0;
		alphaModels.add(m);
	}

	synchronized void addPlayerModel(VAO.VAOView view, int level, int x, int y, int z) {
		AlphaModel m = modelCache.poll();
		if (m == null)
			m = new AlphaModel();
		m.id = -1;
		m.modelOverride = null;
		m.startpos = view.getStartOffset();
		m.endpos = view.getEndOffset();
		m.x = (short) x;
		m.y = (short) y;
		m.z = (short) z;
		m.vao = view.vao;
		m.tboF = view.tboTexId;
		m.rid = -1;
		m.level = (byte) level;
		m.lx = m.lz = m.ux = m.uz = -1;
		m.zofx = m.zofz = 0;
		playerModels.add(m);
	}

	synchronized void postAlphaPass() {
		lastSortedAlphaFacesUpload = null;
		sortedAlphaFacesUpload.waitForCompletion();
		alphaSortingJob.waitForCompletion();

		cleanAlphaModels(alphaModels);
		cleanAlphaModels(playerModels);
	}

	private void cleanAlphaModels(List<AlphaModel> alphaModels) {
		for (int i = alphaModels.size() - 1; i >= 0; --i) {
			AlphaModel m = alphaModels.get(i);
			if (m.isTemp() || (m.flags & AlphaModel.TEMP) != 0) {
				alphaModels.remove(i);
				m.packedFaces = null;
				m.sortedFaces = null;
				m.occlusionQuery = null;
				modelCache.add(m);
			}
			m.asyncSortIdx = -1;
			m.eboOffset = -1;
			m.flags &= ~(AlphaModel.SKIP | AlphaModel.SORT_COMPLETED);
		}
	}

	private static final int STATIC = 1;
	private static final int TEMP = 2;
	private static final int STATIC_UNSORTED = 3;

	private static int alphaFaceCount;
	private static int lastDrawMode;
	private static int lastVao;
	private static int lastTboF;
	private static int lastzx, lastzz;

	private static final class AlphaSortPredicate implements ToIntFunction<AlphaModel> {
		int cx, cy, cz;
		int zx, zz;

		@Override
		public int applyAsInt(AlphaModel m) {
			final int mx = m.x + ((zx - m.zofx) << 10);
			final int mz = m.z + ((zz - m.zofz) << 10);
			final int my = m.y;
			return (mx - cx) * (mx - cx) + (my - cy) * (my - cy) + (mz - cz) * (mz - cz);
		}
	}

	private final AlphaSortPredicate alphaSortPred = new AlphaSortPredicate();
	private final Comparator<AlphaModel> alphaSortComparator = Comparator.comparingInt(alphaSortPred).reversed();

	private static GenericJob lastSortedAlphaFacesUpload;
	private final GenericJob sortedAlphaFacesUpload = GenericJob.build("sortedAlphaFacesUpload", this::alphaFacesUpload);

	void alphaFacesUpload(GenericJob job) {
		final IntBuffer eboAlphaBuffer = ZoneRenderer.eboAlphaMapped.intView();
		for (int i = 0; i < alphaModels.size(); ++i) {
			AlphaModel m = alphaModels.get(i);
			if (m.eboOffset < 0 || m.sortedFacesLen <= 0 || m.sortedFaces == null)
				continue;

			eboAlphaBuffer.position(m.eboOffset).put(m.sortedFaces, 0, m.sortedFacesLen);
		}
	}

	synchronized void alphaSort(int zx, int zz, Camera camera) {
		alphaSortPred.cx = (int) camera.getPositionX();
		alphaSortPred.cy = (int) camera.getPositionY();
		alphaSortPred.cz = (int) camera.getPositionZ();
		alphaSortPred.zx = zx;
		alphaSortPred.zz = zz;
		alphaModels.sort(alphaSortComparator);
	}

	synchronized void playerSort(int zx, int zz, Camera camera) {
		alphaSortPred.cx = (int) camera.getPositionX();
		alphaSortPred.cy = (int) camera.getPositionY();
		alphaSortPred.cz = (int) camera.getPositionZ();
		alphaSortPred.zx = zx;
		alphaSortPred.zz = zz;
		playerModels.sort(alphaSortComparator);
	}

	void alphaStaticModelSort(Camera camera) {
		alphaSortingJob.reset();
		for (AlphaModel m : alphaModels) {
			if ((m.flags & AlphaModel.SKIP) != 0 || m.isTemp())
				continue;

			if(m.occlusionQuery != null) {
				m.occlusionQuery.queue();
				if(m.occlusionQuery.isOccluded())
					continue;
			}

			m.dist = dist;
			alphaSortingJob.addAlphaModel(m);
		}
		alphaSortingJob.queue(camera);
	}

	void renderPlayers(
		CommandBuffer cmd,
		int zx,
		int zz
	) {
		if (playerModels.isEmpty())
			return;

		drawIdx = 0;

		for (int i = 0; i < playerModels.size(); i++) {
			final AlphaModel m = playerModels.get(i);

			if (lastVao != m.vao || lastTboF != m.tboF || lastzx != (zx - m.zofx) || lastzz != (zz - m.zofz))
				flush(cmd);

			lastVao = m.vao;
			lastTboF = m.tboF;
			lastzx = zx - m.zofx;
			lastzz = zz - m.zofz;
			lastDrawMode = TEMP;

			pushRange(m.startpos, m.endpos);
		}

		flush(cmd);
	}

	void renderAlpha(
		CommandBuffer cmd,
		int zx,
		int zz,
		int level,
		WorldViewContext ctx,
		boolean isScenePass,
		boolean includeRoof
	) {
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

		drawIdx = 0;

		boolean shouldQueueUpload = false;
		for (int i = 0; i < alphaModels.size(); i++) {
			final AlphaModel m = alphaModels.get(i);
			if ((m.flags & AlphaModel.SKIP) != 0 || m.level != level || (m.occlusionQuery != null && m.occlusionQuery.isOccluded()))
				continue;

			if (level < minLevel || level > maxLevel ||
				level > currentLevel && !hiddenRoofIds.isEmpty() && hiddenRoofIds.contains((int) m.rid))
				continue;

			if (lastVao != m.vao || lastTboF != m.tboF || lastzx != (zx - m.zofx) || lastzz != (zz - m.zofz))
				flush(cmd);

			lastVao = m.vao;
			lastTboF = m.tboF;
			lastzx = zx - m.zofx;
			lastzz = zz - m.zofz;

			if (m.isTemp()) {
				// these are already sorted and so just requires a glMultiDrawArrays() from the active vao
				lastDrawMode = TEMP;
				pushRange(m.startpos, m.endpos);
				continue;
			}

			if (!isScenePass || m.asyncSortIdx < 0) {
				lastDrawMode = STATIC_UNSORTED;
				pushRange(m.startpos, m.endpos);
				continue;
			}

			// Check if we the faces have already been sorted, if not then the client will steal the work,
			// if the model is already being processed then we'll have to wait for the result to finish
			if (m.needsSorting() && !alphaSortingJob.forceProcessModelClient(m)) {
				while (m.needsSorting() && !alphaSortingJob.isDone())
					alphaSortingJob.waitForCompletion(10);
			}

			if (m.sortedFaces == null || m.sortedFacesLen <= 0 || !ZoneRenderer.eboAlphaMapped.isMapped())
				continue;

			if ((long) (ZoneRenderer.eboAlphaOffset + m.sortedFacesLen) * Integer.BYTES < ZoneRenderer.eboAlpha.size) {
				lastDrawMode = STATIC;
				m.eboOffset = ZoneRenderer.eboAlphaOffset - ZoneRenderer.eboAlphaPrevOffset;
				alphaFaceCount += m.sortedFacesLen / 3;
				ZoneRenderer.eboAlphaOffset += m.sortedFacesLen;
				shouldQueueUpload = true;
			}
		}

		if (shouldQueueUpload) {
			GenericJob prevJob = lastSortedAlphaFacesUpload != sortedAlphaFacesUpload ? lastSortedAlphaFacesUpload : null;
			lastSortedAlphaFacesUpload = sortedAlphaFacesUpload.queue(prevJob);
		}

		flush(cmd);
	}

	private void flush(CommandBuffer cmd) {
		if (lastDrawMode == STATIC) {
			if (alphaFaceCount > 0) {
				int vertexCount = alphaFaceCount * 3;
				long byteOffset = 4L * (ZoneRenderer.eboAlphaOffset - vertexCount);
				cmd.BindVertexArray(lastVao);
				cmd.BindTextureUnit(GL_TEXTURE_BUFFER, lastTboF, TEXTURE_UNIT_TEXTURED_FACES);
				// The EBO & IDO is bound by in ZoneRenderer
				if (GL_CAPS.OpenGL40 && SUPPORTS_INDIRECT_DRAW) {
					cmd.DrawElementsIndirect(GL_TRIANGLES, vertexCount, (int) (byteOffset / 4L), ZoneRenderer.indirectDrawCmdsStaging);
				} else {
					cmd.DrawElements(GL_TRIANGLES, vertexCount, byteOffset);
				}
			}
			alphaFaceCount = 0;
		} else if (drawIdx != 0) {
			convertForDraw(lastDrawMode == STATIC_UNSORTED ? VERT_SIZE : VAO.VERT_SIZE);
			cmd.BindVertexArray(lastVao);
			cmd.BindTextureUnit(GL_TEXTURE_BUFFER, lastTboF, TEXTURE_UNIT_TEXTURED_FACES);
			if (drawIdx == 1) {
				if (GL_CAPS.OpenGL40 && SUPPORTS_INDIRECT_DRAW) {
					cmd.DrawArraysIndirect(GL_TRIANGLES, drawOff[0], drawEnd[0], ZoneRenderer.indirectDrawCmdsStaging);
				} else {
					cmd.DrawArrays(GL_TRIANGLES, drawOff[0], drawEnd[0]);
				}
			} else {
				if (GL_CAPS.OpenGL43 && SUPPORTS_INDIRECT_DRAW) {
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
			final AlphaModel m = alphaModels.get(i);
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
							Zone z2 = zones[zx2][zz2];
							if(z2.inSceneFrustum && z2.initialized && (z2.occlusionQuery == null || z2.occlusionQuery.isVisible())) {
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

				AlphaModel m2 = modelCache.poll();
				if (m2 == null)
					m2 = new AlphaModel();
				m2.id = m.id;
				m2.modelOverride = m.modelOverride;
				m2.startpos = m.startpos;
				m2.endpos = m.endpos;
				m2.x = m.x;
				m2.y = m.y;
				m2.z = m.z;
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

				m2.occlusionQuery = m.occlusionQuery;
				m2.packedFaces = m.packedFaces;
				m2.radius = m.radius;
				m2.asyncSortIdx = m.asyncSortIdx;
				m2.eboOffset = m.eboOffset;
				m2.sortedFaces = m.sortedFaces;
				m2.sortedFacesLen = m.sortedFacesLen;

				m2.flags = AlphaModel.TEMP;
				m.flags |= AlphaModel.SKIP;

				z.alphaModels.add(m2);
			}
		}
	}
}
