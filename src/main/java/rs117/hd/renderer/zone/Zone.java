package rs117.hd.renderer.zone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.opengl.buffer.storage.SSBOModelData;
import rs117.hd.opengl.buffer.uniforms.UBOZoneData;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;

import static net.runelite.api.Perspective.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.SUPPORTS_INDIRECT_DRAW;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.renderer.zone.FacePrioritySorter.distanceFaceCount;
import static rs117.hd.renderer.zone.FacePrioritySorter.distanceToFaces;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@RequiredArgsConstructor
public class Zone {
	// Zone vertex format
	// pos short vec3(x, y, z)
	// uvw short vec3(u, v, w)
	// normal short vec3(nx, ny, nz)
	// alphaBiasHsl int
	// materialData int
	// terrainData int
	// packedZoneModelIdx int
	public static final int VERT_SIZE = 36;

	public static final int LEVEL_WATER_SURFACE = 4;

	public int glVao;
	int bufLen;

	public int glVaoA;
	public int bufLenA;

	public int sizeO, sizeA;
	@Nullable
	public VBO vboO, vboA;

	public boolean initialized; // whether the zone vao and vbos are ready
	public boolean cull; // whether the zone is queued for deletion
	public boolean needsRoofUpdate; // whether the zone needs to have its roofs updated during scene swap
	public boolean rebuild; // whether the zone is queued for rebuild
	public boolean dirty; // whether the zone has temporary modifications
	public boolean hasWater; // whether the zone has any water tiles
	public boolean onlyWater; // whether the zone only contains water tiles
	public boolean inSceneFrustum; // whether the zone is visible to the scene camera
	public boolean inShadowFrustum; // whether the zone casts shadows into the visible scene

	ZoneUploadJob uploadJob;

	int[] levelOffsets = new int[5]; // buffer pos in ints for the end of the level

	int[][] rids;
	int[][] roofStart;
	int[][] roofEnd;
	short modelCount;
	float revealTime = 0.25f;
	SSBOModelData.Slice modelDataSlice;
	UBOZoneData.ZoneStruct zoneData;

	final List<AlphaModel> alphaModels = new ArrayList<>(0);

	void initialize(VBO o, VBO a, int eboShared) {
		assert glVao == 0;
		assert glVaoA == 0;

		if (o != null) {
			vboO = o;
			glVao = glGenVertexArrays();
			setupVao(glVao, o.bufId, eboShared);
		}

		if (a != null) {
			vboA = a;
			glVaoA = glGenVertexArrays();
			setupVao(glVaoA, a.bufId, eboShared);
		}
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

		if (glVao != 0) {
			glDeleteVertexArrays(glVao);
			glVao = 0;
		}

		if (glVaoA != 0) {
			glDeleteVertexArrays(glVaoA);
			glVaoA = 0;
		}

		if(zoneData != null) {
			zoneData.free();
			zoneData = null;
		}

		if (modelDataSlice != null) {
			modelDataSlice.free();
			modelDataSlice = null;
		}
		modelCount = 0;

		if (uploadJob != null) {
			uploadJob.release();
			uploadJob = null;
		}

		sizeO = 0;
		sizeA = 0;
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

	public void unmap() {
		if (vboO != null) {
			vboO.unmap();
		}
		if (vboA != null) {
			vboA.unmap();
		}

		if (vboO != null) {
			this.bufLen = vboO.len / (VERT_SIZE / 4);
		}

		if (vboA != null) {
			this.bufLenA = vboA.len / (VERT_SIZE / 4);
		}
	}

	private void setupVao(int vao, int buffer, int ebo) {
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

		// Alpha, bias & HSL
		glEnableVertexAttribArray(3);
		glVertexAttribIPointer(3, 1, GL_INT, VERT_SIZE, 20);

		// Material data
		glEnableVertexAttribArray(4);
		glVertexAttribIPointer(4, 1, GL_INT, VERT_SIZE, 24);

		// Terrain data
		glEnableVertexAttribArray(5);
		glVertexAttribIPointer(5, 1, GL_INT, VERT_SIZE, 28);

		// packedZoneModelIdx
		glEnableVertexAttribArray(6);
		glVertexAttribIPointer(6, 1, GL_INT, VERT_SIZE, 32);

		checkGLErrors();

		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	public void setWorldViewAndOffset(WorldViewContext viewContext, SceneContext sceneContext, int mx, int mz) {
		if (zoneData == null)
			return;

		zoneData.worldViewIdx.set(viewContext.uboWorldViewStruct != null ? viewContext.uboWorldViewStruct.worldViewIdx + 1 : 0);
		zoneData.offsetX.set((mx - (sceneContext.sceneOffset >> 3)) << 10);
		zoneData.offsetZ.set((mz - (sceneContext.sceneOffset >> 3)) << 10);
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
	private static int drawIdx = 0;
	private static int[] glDrawOffset, glDrawLength;

	private static final int[][] glDrawOffsetPreAlloc = new int[15][];
	private static final int[][] glDrawLengthPreAlloc = new int[15][];

	static {
		for (int i = 0; i < glDrawOffsetPreAlloc.length; ++i) {
			glDrawOffsetPreAlloc[i] = new int[i + 1];
			glDrawLengthPreAlloc[i] = new int[i + 1];
		}
	}

	private void convertForDraw(int vertSize) {
		for (int i = 0; i < drawIdx; ++i) {
			assert drawEnd[i] >= drawOff[i];

			// convert from bytes to verts
			drawOff[i] /= vertSize >> 2;
			drawEnd[i] /= vertSize >> 2;

			drawEnd[i] -= drawOff[i]; // convert from end pos to length
		}

		if (drawIdx < glDrawOffsetPreAlloc.length) {
			glDrawOffset = copyTo(glDrawOffsetPreAlloc[drawIdx], drawOff, 0, drawIdx);
			glDrawLength = copyTo(glDrawLengthPreAlloc[drawIdx], drawEnd, 0, drawIdx);
		} else {
			glDrawOffset = Arrays.copyOfRange(drawOff, 0, drawIdx);
			glDrawLength = Arrays.copyOfRange(drawEnd, 0, drawIdx);
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
		flush(cmd);
	}

	void renderOpaqueLevel(CommandBuffer cmd, int level) {
		drawIdx = 0;

		pushRange(this.levelOffsets[level - 1], this.levelOffsets[level]);

		if (drawIdx == 0)
			return;

		lastDrawMode = STATIC_UNSORTED;
		lastVao = glVao;
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

	static class AlphaModel {
		int id;
		int startpos, endpos;
		short x, y, z; // local position
		short rid;
		int vao;
		byte level;
		byte lx, lz, ux, uz; // lower/upper zone coords
		byte zofx, zofz; // for temp alpha models, offset of source zone from target zone
		byte flags;

		// only set for static geometry as they require sorting
		int radius;
		int[] packedFaces;
		byte[] renderPriorities;

		static final int SKIP = 1; // temporary model is in a closer zone
		static final int TEMP = 2; // temporary model added to a closer zone

		boolean isTemp() {
			return packedFaces == null;
		}
	}

	static final Queue<AlphaModel> modelCache = new ArrayDeque<>();

	void addAlphaModel(
		MaterialManager materialManager,
		int vao,
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
		m.startpos = startpos;
		m.endpos = endpos;
		m.x = (short) x;
		m.y = (short) y;
		m.z = (short) z;
		m.vao = vao;
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
				int ahsl = (0xFF - transparency) << 16 | color1[f];
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

		assert radius >= 0;

		m.renderPriorities = model.getFaceRenderPriorities();
		m.radius = 2 + (int) Math.sqrt(radius);

		assert packedFaces.length > 0;
		// Normally these will be equal, but transparency is used to hide faces in the TzHaar reskin
		assert bufferIdx <= packedFaces.length : String.format("%d > %d", (int) bufferIdx, packedFaces.length);

		alphaModels.add(m);
	}

	void addTempAlphaModel(int vao, int startpos, int endpos, int level, int x, int y, int z) {
		AlphaModel m = modelCache.poll();
		if (m == null)
			m = new AlphaModel();
		m.id = -1;
		m.startpos = startpos;
		m.endpos = endpos;
		m.x = (short) x;
		m.y = (short) y;
		m.z = (short) z;
		m.vao = vao;
		m.rid = -1;
		m.level = (byte) level;
		m.lx = m.lz = m.ux = m.uz = -1;
		m.flags = 0;
		m.zofx = m.zofz = 0;
		alphaModels.add(m);
	}

	void removeTemp() {
		for (int i = alphaModels.size() - 1; i >= 0; --i) {
			AlphaModel m = alphaModels.get(i);
			if (m.isTemp() || (m.flags & AlphaModel.TEMP) != 0) {
				alphaModels.remove(i);
				m.packedFaces = null;
				m.renderPriorities = null;
				modelCache.add(m);
			}
			m.flags &= ~AlphaModel.SKIP;
		}
	}

	private static final int STATIC = 1;
	private static final int TEMP = 2;
	private static final int STATIC_UNSORTED = 3;

	private static int lastDrawMode;
	private static int lastVao;
	private static int lastzx, lastzz;

	private static final int[] numOfPriority = FacePrioritySorter.numOfPriority;
	private static final int[][] orderedFaces = FacePrioritySorter.orderedFaces;

	private Camera alphaSort_Camera;
	private int alphaSort_zx, alphaSort_zz;
	private final Comparator<AlphaModel> alphaSortComparator = Comparator.comparingInt((AlphaModel m) -> {
		final int cx = (int) alphaSort_Camera.getPositionX();
		final int cy = (int) alphaSort_Camera.getPositionY();
		final int cz = (int) alphaSort_Camera.getPositionZ();
		final int mx = m.x + ((alphaSort_zx - m.zofx) << 10);
		final int mz = m.z + ((alphaSort_zz - m.zofz) << 10);
		return (mx - cx) * (mx - cx) + (m.y - cy) * (m.y - cy) + (mz - cz) * (mz - cz);
	}).reversed();

	void alphaSort(int zx, int zz, Camera camera) {
		alphaSort_Camera = camera;
		alphaSort_zx = zx;
		alphaSort_zz = zz;
		alphaModels.sort(alphaSortComparator);
	}

	void renderAlpha(
		CommandBuffer cmd,
		int zx,
		int zz,
		int level,
		WorldViewContext ctx,
		Camera camera,
		boolean roofShadows,
		boolean useStaticUnsorted
	) {
		if (alphaModels.isEmpty())
			return;

		int currentLevel = ctx.level;
		int maxLevel = ctx.maxLevel;
		var hiddenRoofIds = ctx.hideRoofIds;
		if (roofShadows) {
			maxLevel = 3;
			hiddenRoofIds = Collections.emptySet();
		}

		cmd.DepthMask(false);

		drawIdx = 0;

		int yawSin = SINE[camera.getFixedYaw()];
		int yawCos = COSINE[camera.getFixedYaw()];
		int pitchSin = SINE[camera.getFixedPitch()];
		int pitchCos = COSINE[camera.getFixedPitch()];
		for (AlphaModel m : alphaModels) {
			if ((m.flags & AlphaModel.SKIP) != 0 || m.level != level)
				continue;

			if (level < ctx.minLevel || level > maxLevel || level > currentLevel && hiddenRoofIds.contains((int) m.rid))
				continue;

			if (lastVao != m.vao || lastzx != (zx - m.zofx) || lastzz != (zz - m.zofz))
				flush(cmd);

			lastVao = m.vao;
			lastzx = zx - m.zofx;
			lastzz = zz - m.zofz;

			if (m.isTemp()) {
				// these are already sorted and so just requires a glMultiDrawArrays() from the active vao
				lastDrawMode = TEMP;
				pushRange(m.startpos, m.endpos);
				continue;
			}

			if (useStaticUnsorted) {
				lastDrawMode = STATIC_UNSORTED;
				pushRange(m.startpos, m.endpos);
				continue;
			}

			lastDrawMode = STATIC;

			final int radius = m.radius;
			int diameter = 1 + radius * 2;
			final int[] packedFaces = m.packedFaces;
			if (diameter >= 6000)
				continue;

			Arrays.fill(distanceFaceCount, 0, diameter, (char) 0);

			for (int i = 0; i < packedFaces.length; ++i) {
				int packed = packedFaces[i];
				int x = packed >> 21;
				int y = (packed << 11) >> 22;
				int z = (packed << 21) >> 21;

				int t = z * yawCos - x * yawSin >> 16;
				int fz = y * pitchSin + t * pitchCos >> 16;
				fz += radius;

				assert fz >= 0 && fz < diameter : fz;
				distanceToFaces[fz][distanceFaceCount[fz]++] = (char) i;
			}

			ZoneRenderer.eboAlphaStaging.ensureCapacity(packedFaces.length * 3);

			byte[] faceRenderPriorities = m.renderPriorities;
			final int start = m.startpos / (VERT_SIZE >> 2); // ints to verts
			if (faceRenderPriorities == null) {
				for (int i = diameter - 1; i >= 0; --i) {
					final int cnt = distanceFaceCount[i];
					if (cnt > 0) {
						final char[] faces = distanceToFaces[i];

						ZoneRenderer.alphaFaceCount += cnt;
						for (int faceIdx = 0; faceIdx < cnt; ++faceIdx) {
							final int face = faces[faceIdx];
							int idx = face * 3 + start;
							ZoneRenderer.eboAlphaStaging.put(idx, idx + 1, idx + 2);
						}
					}
				}
			} else {
				// Vanilla uses priority draw order for alpha faces and not depth draw order
				// And since we don't have the full model here, only the alpha faces, we can't compute the
				// 10/11 insertion points either. Just ignore those since I think they are mostly for players,
				// which are rendered differently anyway.
				Arrays.fill(numOfPriority, 0);

				for (int i = diameter - 1; i >= 0; --i) {
					final int cnt = distanceFaceCount[i];
					if (cnt > 0) {
						final char[] faces = distanceToFaces[i];

						for (int faceIdx = 0; faceIdx < cnt; ++faceIdx) {
							final int face = faces[faceIdx];
							final byte pri = faceRenderPriorities[face];
							final int distIdx = numOfPriority[pri]++;

							orderedFaces[pri][distIdx] = face;
						}
					}
				}

				for (int pri = 0; pri < 12; ++pri) {
					final int priNum = numOfPriority[pri];
					final int[] priFaces = orderedFaces[pri];

					ZoneRenderer.alphaFaceCount += priNum;
					for (int faceIdx = 0; faceIdx < priNum; ++faceIdx) {
						final int face = priFaces[faceIdx];
						int idx = face * 3 + start;
						ZoneRenderer.eboAlphaStaging.put(idx, idx + 1, idx + 2);
					}
				}
			}
		}

		flush(cmd);
		cmd.DepthMask(true);
	}

	private void flush(CommandBuffer cmd) {
		if (lastDrawMode == STATIC) {
			if (ZoneRenderer.alphaFaceCount > 0) {
				int vertexCount = ZoneRenderer.alphaFaceCount * 3;
				long byteOffset = 4L * (ZoneRenderer.eboAlphaStaging.position() - vertexCount);
				cmd.BindVertexArray(lastVao);
				// The EBO & IDO is bound by in ZoneRenderer
				if (GL_CAPS.OpenGL40 && SUPPORTS_INDIRECT_DRAW) {
					cmd.DrawElementsIndirect(GL_TRIANGLES, vertexCount, (int) (byteOffset / 4L), ZoneRenderer.indirectDrawCmdsStaging);
				} else {
					cmd.DrawElements(GL_TRIANGLES, vertexCount, byteOffset);
				}
				ZoneRenderer.alphaFaceCount = 0;
			}
		} else if (drawIdx != 0) {
			convertForDraw(lastDrawMode == STATIC_UNSORTED ? VERT_SIZE : VAO.VERT_SIZE);
			cmd.BindVertexArray(lastVao);
			if (glDrawOffset.length == 1) {
				if (GL_CAPS.OpenGL40 && SUPPORTS_INDIRECT_DRAW) {
					cmd.DrawArraysIndirect(GL_TRIANGLES, glDrawOffset[0], glDrawLength[0], ZoneRenderer.indirectDrawCmdsStaging);
				} else {
					cmd.DrawArrays(GL_TRIANGLES, glDrawOffset[0], glDrawLength[0]);
				}
			} else {
				if (GL_CAPS.OpenGL43 && SUPPORTS_INDIRECT_DRAW) {
					cmd.MultiDrawArraysIndirect(GL_TRIANGLES, glDrawOffset, glDrawLength, ZoneRenderer.indirectDrawCmdsStaging);
				} else {
					cmd.MultiDrawArrays(GL_TRIANGLES, glDrawOffset, glDrawLength);
				}
			}
			drawIdx = 0;
		}
	}

	void multizoneLocs(SceneContext ctx, int zx, int zz, Camera camera, Zone[][] zones) {
		int offset = ctx.sceneOffset >> 3;
		int cx = (int) camera.getPositionX();
		int cz = (int) camera.getPositionZ();
		for (AlphaModel m : alphaModels) {
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
						max = distance;
						closestZoneX = centerX >> 10;
						closestZoneZ = centerZ >> 10;
					}
				}
			}
			assert closestZoneX != -50;
			if (closestZoneX != zx || closestZoneZ != zz) {
				assert (m.flags & AlphaModel.TEMP) == 0;

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
				m2.startpos = m.startpos;
				m2.endpos = m.endpos;
				m2.x = m.x;
				m2.y = m.y;
				m2.z = m.z;
				m2.vao = m.vao;
				m2.rid = m.rid;
				m2.level = m.level;
				m2.lx = m.lx;
				m2.lz = m.lz;
				m2.ux = m.ux;
				m2.uz = m.uz;
				m2.zofx = (byte) (closestZoneX - zx);
				m2.zofz = (byte) (closestZoneZ - zz);

				m2.packedFaces = m.packedFaces;
				m2.renderPriorities = m.renderPriorities;
				m2.radius = m.radius;

				m2.flags = AlphaModel.TEMP;
				m.flags |= AlphaModel.SKIP;

				z.alphaModels.add(m2);
			}
		}
	}
}
