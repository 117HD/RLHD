package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import org.lwjgl.BufferUtils;
import rs117.hd.opengl.uniforms.UBOGlobal;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.materials.Material;
import rs117.hd.utils.Camera;

import static net.runelite.api.Perspective.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.renderer.zone.FacePrioritySorter.distanceFaceCount;
import static rs117.hd.renderer.zone.FacePrioritySorter.distanceToFaces;

@Slf4j
@RequiredArgsConstructor
class Zone {
	// Zone vertex format
	// pos short vec3(x, y, z)
	// uvw short vec3(u, v, w)
	// normal short vec3(nx, ny, nz)
	// alphaBiasHsl int
	// materialData int
	// terrainData int
	static final int VERT_SIZE = 32;

	static final int LEVEL_WATER_SURFACE = 4;

	int glVao;
	int bufLen;

	int glVaoA;
	int bufLenA;

	int sizeO, sizeA;
	VBO vboO, vboA;

	boolean initialized; // whether the zone vao and vbos are ready
	boolean cull; // whether the zone is queued for deletion
	boolean dirty; // whether the zone has temporary modifications
	boolean invalidate; // whether the zone needs rebuilding
	boolean hasWater; // whether the zone has any water tiles
	boolean inSceneFrustum;
	boolean inShadowFrustum;

	int[] levelOffsets = new int[5]; // buffer pos in ints for the end of the level

	int[][] rids;
	int[][] roofStart;
	int[][] roofEnd;

	final List<AlphaModel> alphaModels = new ArrayList<>(0);

	void initialize(VBO o, VBO a) {
		assert glVao == 0;
		assert glVaoA == 0;

		if (o != null) {
			vboO = o;
			glVao = glGenVertexArrays();
			setupVao(glVao, o.bufId);
		}

		if (a != null) {
			vboA = a;
			glVaoA = glGenVertexArrays();
			setupVao(glVaoA, a.bufId);
		}
	}

	void free() {
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

		// don't add permanent alphamodels to the cache as permanent alphamodels are always allocated
		// to avoid having to synchronize the cache
		alphaModels.clear();
	}

	void unmap() {
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

	private void setupVao(int vao, int buffer) {
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, buffer);

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

		checkGLErrors();

		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
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

	private void convertForDraw(int vertSize) {
		for (int i = 0; i < drawIdx; ++i) {
			assert drawEnd[i] >= drawOff[i];

			// convert from bytes to verts
			drawOff[i] /= vertSize >> 2;
			drawEnd[i] /= vertSize >> 2;

			drawEnd[i] -= drawOff[i]; // convert from end pos to length
		}

		glDrawOffset = Arrays.copyOfRange(drawOff, 0, drawIdx);
		glDrawLength = Arrays.copyOfRange(drawEnd, 0, drawIdx);
	}

	void renderOpaque(UBOGlobal uboGlobal, int zx, int zz, int minLevel, int currentLevel, int maxLevel, Set<Integer> hiddenRoofIds) {
		drawIdx = 0;

		for (int level = minLevel; level <= maxLevel; ++level) {
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

		convertForDraw(VERT_SIZE);

		if (glDrawLength.length == 0)
			return;

		uboGlobal.sceneBase.set(zx << 10, 0, zz << 10);
		uboGlobal.upload();
		glBindVertexArray(glVao);
		glMultiDrawArrays(GL_TRIANGLES, glDrawOffset, glDrawLength);
	}

	void renderOpaqueLevel(UBOGlobal uboGlobal, int zx, int zz, int level) {
		drawIdx = 0;

		// draw the specific level
		pushRange(this.levelOffsets[level - 1], this.levelOffsets[level]);

		convertForDraw(VERT_SIZE);

		if (glDrawLength.length == 0)
			return;

		uboGlobal.sceneBase.set(zx << 10, 0, zz << 10);
		uboGlobal.upload();
		glBindVertexArray(glVao);
		glMultiDrawArrays(GL_TRIANGLES, glDrawOffset, glDrawLength);
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

		static final int SKIP = 1; // temporary model is in a closer zone
		static final int TEMP = 2; // temporary model added to a closer zone

		boolean isTemp() {
			return packedFaces == null;
		}
	}

	static final Queue<AlphaModel> modelCache = new ArrayDeque<>();

	void addAlphaModel(
		int vao,
		Model model,
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

			boolean alpha =
				transparencies != null && transparencies[f] != 0 ||
				faceTextures != null && Material.hasVanillaTransparency(faceTextures[f]);
			if (!alpha)
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

			boolean alpha =
				transparencies != null && transparencies[f] != 0 ||
				faceTextures != null && Material.hasVanillaTransparency(faceTextures[f]);
			if (!alpha)
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

		m.radius = 2 + (int) Math.sqrt(radius);

		assert packedFaces.length > 0;
		assert bufferIdx == packedFaces.length : String.format("%d != %d", (int) bufferIdx, packedFaces.length);

		alphaModels.add(m);
	}

	void addTempAlphaModel(int vao, int startpos, int endpos, int level, int x, int y, int z) {
		AlphaModel m = modelCache.poll();
		if (m == null) {
			m = new AlphaModel();
		}
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
				modelCache.add(m);
			}
			m.flags &= ~AlphaModel.SKIP;
		}
	}

	// this needs to be larger than the max model alpha face count * 3
	private static final IntBuffer alphaElements = BufferUtils.createIntBuffer(16384);

	private static final int STATIC = 1;
	private static final int TEMP = 2;
	private static final int STATIC_UNSORTED = 3;

	private static int lastDrawMode;
	private static int lastVao;
	private static int lastzx, lastzz;

	void alphaSort(int zx, int zz, Camera camera) {
		int cx = (int) camera.getPositionX();
		int cy = (int) camera.getPositionY();
		int cz = (int) camera.getPositionZ();
		alphaModels.sort(Comparator
			.comparingInt((AlphaModel m) -> {
				final int mx = m.x + ((zx - m.zofx) << 10);
				final int mz = m.z + ((zz - m.zofz) << 10);
				return (mx - cx) * (mx - cx) + (m.y - cy) * (m.y - cy) + (mz - cz) * (mz - cz);
			})
			.reversed()
		);
	}

	void renderAlpha(
		UBOGlobal uboGlobal,
		int zx,
		int zz,
		int minLevel,
		int currentLevel,
		int maxLevel,
		int level,
		Camera camera,
		Set<Integer> hiddenRoofIds
	) {
		drawIdx = 0;
		alphaElements.clear();
		lastDrawMode = lastVao = 0;
		lastzx = zx;
		lastzz = zz;

		int yawsin = Perspective.COSINE[camera.getFixedYaw()];
		int yawcos = SINE[camera.getFixedYaw()];
		int pitchsin = COSINE[camera.getFixedPitch()];
		int pitchcos = SINE[camera.getFixedPitch()];
		for (AlphaModel m : alphaModels) {
			if ((m.flags & AlphaModel.SKIP) != 0) continue;
			if (m.level != level) continue;

			boolean ok = false;
			if (level >= minLevel && level <= maxLevel) {
				if (level <= currentLevel || !hiddenRoofIds.contains((int) m.rid)) {
					ok = true;
				}
			}
			if (!ok) {
				continue;
			}

			if (lastVao != m.vao
				|| lastzx != (zx - m.zofx) || lastzz != (zz - m.zofz)
			) {
				flush(uboGlobal);
			}

			lastVao = m.vao;
			lastzx = zx - m.zofx;
			lastzz = zz - m.zofz;

			if (m.isTemp()) {
				// these are already sorted and so just requires a glMultiDrawArrays() from the active vao
				lastDrawMode = TEMP;
				pushRange(m.startpos, m.endpos);
				continue;
			}

			if (false) {
				lastDrawMode = STATIC_UNSORTED;
				pushRange(m.startpos, m.endpos);
				continue;
			}

			lastDrawMode = STATIC;

			final int radius = m.radius;
			int diameter = 1 + radius * 2;
			final int[] packedFaces = m.packedFaces;
			if (diameter >= 6000) {
				continue;
			}

			Arrays.fill(distanceFaceCount, 0, diameter, (char) 0);

			char bufferIdx = 0;
			for (int i = 0; i < packedFaces.length; ++i) {
				int pack = packedFaces[i];

				int x = pack >> 21;
				int y = (pack << 11) >> 22;
				int z = (pack << 21) >> 21;

				int t = z * yawcos - x * yawsin >> 16;
				int fz = y * pitchsin + t * pitchcos >> 16;
				fz += radius;

				assert fz >= 0 && fz < diameter : fz;
				distanceToFaces[fz][distanceFaceCount[fz]++] = bufferIdx++;
			}

			if (bufferIdx * 3 > alphaElements.remaining()) {
				flush(uboGlobal);
			}

			int start = m.startpos / (VERT_SIZE >> 2); // ints to verts
			for (int i = diameter - 1; i >= 0; --i) {
				final int cnt = distanceFaceCount[i];
				if (cnt > 0) {
					final char[] faces = distanceToFaces[i];

					for (int faceIdx = 0; faceIdx < cnt; ++faceIdx) {
						int face = faces[faceIdx];
						face *= 3;
						face += start;
						alphaElements.put(face++);
						alphaElements.put(face++);
						alphaElements.put(face++);
					}
				}
			}
		}

		flush(uboGlobal);
	}

	private void flush(UBOGlobal uboGlobal) {
		if (lastDrawMode == TEMP) {
			uboGlobal.sceneBase.set(0, 0, 0);
		} else {
			uboGlobal.sceneBase.set(lastzx << 10, 0, lastzz << 10);
		}
		uboGlobal.upload();

		if (lastDrawMode == STATIC) {
			alphaElements.flip();
			if (alphaElements.limit() > 0) {
				glBindVertexArray(lastVao);
				glDrawElements(GL_TRIANGLES, alphaElements);
			}
			alphaElements.clear();
		} else {
			convertForDraw(VAO.VERT_SIZE);
			if (glDrawLength.length > 0) {
				glBindVertexArray(lastVao);
				glMultiDrawArrays(GL_TRIANGLES, glDrawOffset, glDrawLength);
			}
			drawIdx = 0;
		}
	}

	void multizoneLocs(SceneContext ctx, int zx, int zz, Camera camera, Zone[][] zones) {
		int offset = ctx.sceneOffset >> 3;
		int cx = (int) camera.getPositionX();
		int cz = (int) camera.getPositionZ();
		for (AlphaModel m : alphaModels) {
			if (m.lx == -1) {
				continue;
			}

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
				if (m2 == null) {
					m2 = new AlphaModel();
				}
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
				m2.radius = m.radius;

				m2.flags = AlphaModel.TEMP;
				m.flags |= AlphaModel.SKIP;

				z.alphaModels.add(m2);
			}
		}
	}
}
