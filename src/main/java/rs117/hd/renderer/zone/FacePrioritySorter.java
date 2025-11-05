/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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
package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.UvType;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.*;

@Singleton
class FacePrioritySorter {
	static final int[] distances;
	static final char[] distanceFaceCount;
	static final char[][] distanceToFaces;

	private static final float[] modelCanvasX;
	private static final float[] modelCanvasY;

	private static final float[] modelLocalX;
	private static final float[] modelLocalY;
	private static final float[] modelLocalZ;

	private static final float[] workingSpace;
	private static final float[] modelUvs;
	private static final int[] modelNormals;

	static final int[] numOfPriority;
	private static final int[] eq10;
	private static final int[] eq11;
	private static final int[] lt10;
	static final int[][] orderedFaces;

	private static int orientSin, orientCos;

	private static final int MAX_VERTEX_COUNT = 6500;
	private static final int MAX_DIAMETER = 6000;
	private static final int ZSORT_GROUP_SIZE = 1024; // was 512
	private static final int MAX_FACES_PER_PRIORITY = 4000; // was 2500

	private static final int[] MAX_BRIGHTNESS_LOOKUP_TABLE = new int[8];
	private static final float[] LIGHT_DIR_MODEL = new float[] { 0.57735026f, 0.57735026f, 0.57735026f };
	// subtracts the X lowest lightness levels from the formula.
	// helps keep darker colors appropriately dark
	private static final int IGNORE_LOW_LIGHTNESS = 3;
	// multiplier applied to vertex' lightness value.
	// results in greater lightening of lighter colors
	private static final float LIGHTNESS_MULTIPLIER = 3;
	// the minimum amount by which each color will be lightened
	private static final int BASE_LIGHTEN = 10;

	static {
		distances = new int[MAX_VERTEX_COUNT];
		distanceFaceCount = new char[MAX_DIAMETER];
		distanceToFaces = new char[MAX_DIAMETER][ZSORT_GROUP_SIZE];

		modelCanvasX = new float[MAX_VERTEX_COUNT];
		modelCanvasY = new float[MAX_VERTEX_COUNT];

		modelLocalX = new float[MAX_VERTEX_COUNT];
		modelLocalY = new float[MAX_VERTEX_COUNT];
		modelLocalZ = new float[MAX_VERTEX_COUNT];

		workingSpace = new float[9];
		modelUvs = new float[12];
		modelNormals = new int[9];

		numOfPriority = new int[12];
		eq10 = new int[MAX_FACES_PER_PRIORITY];
		eq11 = new int[MAX_FACES_PER_PRIORITY];
		lt10 = new int[12];
		orderedFaces = new int[12][MAX_FACES_PER_PRIORITY];

		for (int i = 0; i < 8; i++)
			MAX_BRIGHTNESS_LOOKUP_TABLE[i] = (int) (127 - 72 * Math.pow(i / 7f, .05));
	}

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private MaterialManager materialManager;

	int uploadSortedModel(
		Projection proj,
		Model model,
		ModelOverride modelOverride,
		int preOrientation,
		int orientation,
		int x,
		int y,
		int z,
		IntBuffer opaqueBuffer,
		IntBuffer alphaBuffer
	) {
		final int vertexCount = model.getVerticesCount();
		final float[] verticesX = model.getVerticesX();
		final float[] verticesY = model.getVerticesY();
		final float[] verticesZ = model.getVerticesZ();

		final int faceCount = model.getFaceCount();
		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final int[] faceColors3 = model.getFaceColors3();
		final byte[] faceRenderPriorities = model.getFaceRenderPriorities();

		final int centerX = client.getCenterX();
		final int centerY = client.getCenterY();
		final int zoom = client.get3dZoom();

		float orientSinf = 0;
		float orientCosf = 0;
		if (orientation != 0) {
			orientation = mod(orientation, 2048);
			orientSin = SINE[orientation];
			orientCos = COSINE[orientation];
			orientSinf = orientSin / 65536f;
			orientCosf = orientCos / 65536f;
		} else {
			orientSin = orientCos = 0;
		}

		float[] p = proj.project(x, y, z);
		int zero = (int) p[2];

		for (int v = 0; v < vertexCount; ++v) {
			float vertexX = verticesX[v];
			float vertexY = verticesY[v];
			float vertexZ = verticesZ[v];

			if (orientation != 0) {
				float x0 = vertexX;
				vertexX = vertexZ * orientSinf + x0 * orientCosf;
				vertexZ = vertexZ * orientCosf - x0 * orientSinf;
			}

			// move to local position
			vertexX += x;
			vertexY += y;
			vertexZ += z;

			modelLocalX[v] = vertexX;
			modelLocalY[v] = vertexY;
			modelLocalZ[v] = vertexZ;

			p = proj.project(vertexX, vertexY, vertexZ);
			if (p[2] < 50) {
				return 0;
			}

			modelCanvasX[v] = centerX + p[0] * zoom / p[2];
			modelCanvasY[v] = centerY + p[1] * zoom / p[2];
			distances[v] = (int) p[2] - zero;
		}

		final int diameter = model.getDiameter();
		final int radius = model.getRadius();
		if (diameter >= 6000) {
			return 0;
		}

		Arrays.fill(distanceFaceCount, 0, diameter, (char) 0);

		for (char i = 0; i < faceCount; ++i) {
			if (faceColors3[i] == -2)
				continue;

			final int v1 = indices1[i];
			final int v2 = indices2[i];
			final int v3 = indices3[i];

			final float
				aX = modelCanvasX[v1],
				aY = modelCanvasY[v1],
				bX = modelCanvasX[v2],
				bY = modelCanvasY[v2],
				cX = modelCanvasX[v3],
				cY = modelCanvasY[v3];
			// Back-face culling
			if ((aX - bX) * (cY - bY) - (cX - bX) * (aY - bY) <= 0)
				continue;

			int distance = radius + (distances[v1] + distances[v2] + distances[v3]) / 3;
			assert distance >= 0 && distance < diameter;
			distanceToFaces[distance][distanceFaceCount[distance]++] = i;
		}

		int len = 0;
		if (faceRenderPriorities == null) {
			for (int i = diameter - 1; i >= 0; --i) {
				final int cnt = distanceFaceCount[i];
				if (cnt > 0) {
					final char[] faces = distanceToFaces[i];
					for (int faceIdx = 0; faceIdx < cnt; ++faceIdx) {
						final int face = faces[faceIdx];
						len += pushFace(model, modelOverride, preOrientation, face, opaqueBuffer, alphaBuffer);
					}
				}
			}
		} else {
			Arrays.fill(numOfPriority, 0);
			Arrays.fill(lt10, 0);

			for (int i = diameter - 1; i >= 0; --i) {
				final int cnt = distanceFaceCount[i];
				if (cnt > 0) {
					final char[] faces = distanceToFaces[i];
					for (int faceIdx = 0; faceIdx < cnt; ++faceIdx) {
						final int face = faces[faceIdx];
						final byte pri = faceRenderPriorities[face];
						final int distIdx = numOfPriority[pri]++;

						orderedFaces[pri][distIdx] = face;
						if (pri < 10) {
							lt10[pri] += i;
						} else if (pri == 10) {
							eq10[distIdx] = i;
						} else {
							eq11[distIdx] = i;
						}
					}
				}
			}

			int avg12 = 0;
			if (numOfPriority[1] > 0 || numOfPriority[2] > 0)
				avg12 = (lt10[1] + lt10[2]) / (numOfPriority[1] + numOfPriority[2]);

			int avg34 = 0;
			if (numOfPriority[3] > 0 || numOfPriority[4] > 0)
				avg34 = (lt10[3] + lt10[4]) / (numOfPriority[3] + numOfPriority[4]);

			int avg68 = 0;
			if (numOfPriority[6] > 0 || numOfPriority[8] > 0)
				avg68 = (lt10[8] + lt10[6]) / (numOfPriority[8] + numOfPriority[6]);

			int drawnFaces = 0;
			int numDynFaces = numOfPriority[10];
			int[] dynFaces = orderedFaces[10];
			int[] dynFaceDistances = eq10;
			if (drawnFaces == numDynFaces) {
				numDynFaces = numOfPriority[11];
				dynFaces = orderedFaces[11];
				dynFaceDistances = eq11;
			}

			int currFaceDistance;
			if (drawnFaces < numDynFaces) {
				currFaceDistance = dynFaceDistances[drawnFaces];
			} else {
				currFaceDistance = -1000;
			}

			for (int pri = 0; pri < 10; ++pri) {
				while (pri == 0 && currFaceDistance > avg12) {
					final int face = dynFaces[drawnFaces++];
					len += pushFace(model, modelOverride, preOrientation, face, opaqueBuffer, alphaBuffer);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11]) {
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					if (drawnFaces < numDynFaces) {
						currFaceDistance = dynFaceDistances[drawnFaces];
					} else {
						currFaceDistance = -1000;
					}
				}

				while (pri == 3 && currFaceDistance > avg34) {
					final int face = dynFaces[drawnFaces++];
					len += pushFace(model, modelOverride, preOrientation, face, opaqueBuffer, alphaBuffer);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11]) {
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					if (drawnFaces < numDynFaces) {
						currFaceDistance = dynFaceDistances[drawnFaces];
					} else {
						currFaceDistance = -1000;
					}
				}

				while (pri == 5 && currFaceDistance > avg68) {
					final int face = dynFaces[drawnFaces++];
					len += pushFace(model, modelOverride, preOrientation, face, opaqueBuffer, alphaBuffer);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11]) {
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					if (drawnFaces < numDynFaces) {
						currFaceDistance = dynFaceDistances[drawnFaces];
					} else {
						currFaceDistance = -1000;
					}
				}

				final int priNum = numOfPriority[pri];
				final int[] priFaces = orderedFaces[pri];

				for (int faceIdx = 0; faceIdx < priNum; ++faceIdx) {
					final int face = priFaces[faceIdx];
					len += pushFace(model, modelOverride, preOrientation, face, opaqueBuffer, alphaBuffer);
				}
			}

			while (currFaceDistance != -1000) {
				final int face = dynFaces[drawnFaces++];
				len += pushFace(model, modelOverride, preOrientation, face, opaqueBuffer, alphaBuffer);

				if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11]) {
					drawnFaces = 0;
					dynFaces = orderedFaces[11];
					numDynFaces = numOfPriority[11];
					dynFaceDistances = eq11;
				}

				if (drawnFaces < numDynFaces) {
					currFaceDistance = dynFaceDistances[drawnFaces];
				} else {
					currFaceDistance = -1000;
				}
			}
		}

		return len;
	}

	private int pushFace(
		Model model,
		ModelOverride modelOverride,
		int preOrientation,
		int face,
		IntBuffer opaqueBuffer,
		IntBuffer alphaBuffer
	) {
		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final int[] faceColors1 = model.getFaceColors1();
		final int[] faceColors2 = model.getFaceColors2();
		final int[] faceColors3 = model.getFaceColors3();

		final int[] xVertexNormals = model.getVertexNormalsX();
		final int[] yVertexNormals = model.getVertexNormalsY();
		final int[] zVertexNormals = model.getVertexNormalsZ();

		final byte overrideAmount = model.getOverrideAmount();
		final byte overrideHue = model.getOverrideHue();
		final byte overrideSat = model.getOverrideSaturation();
		final byte overrideLum = model.getOverrideLuminance();

		final short[] faceTextures = model.getFaceTextures();
		final byte[] textureFaces = model.getTextureFaces();
		final int[] texIndices1 = model.getTexIndices1();
		final int[] texIndices2 = model.getTexIndices2();
		final int[] texIndices3 = model.getTexIndices3();

		final byte[] transparencies = model.getFaceTransparencies();
		final byte[] bias = model.getFaceBias();


		boolean isVanillaTextured = faceTextures != null;
		boolean isVanillaUVMapped =
			isVanillaTextured && // Vanilla UV mapped models don't always have sensible UVs for untextured faces
			model.getTextureFaces() != null;
		boolean isTextured = isVanillaTextured && faceTextures[face] != -1;

		Material baseMaterial = modelOverride.baseMaterial;
		Material textureMaterial = modelOverride.textureMaterial;

		final int triangleA = indices1[face];
		final int triangleB = indices2[face];
		final int triangleC = indices3[face];

		float vx1 = modelLocalX[triangleA];
		float vy1 = modelLocalY[triangleA];
		float vz1 = modelLocalZ[triangleA];

		float vx2 = modelLocalX[triangleB];
		float vy2 = modelLocalY[triangleB];
		float vz2 = modelLocalZ[triangleB];

		float vx3 = modelLocalX[triangleC];
		float vy3 = modelLocalY[triangleC];
		float vz3 = modelLocalZ[triangleC];

		int color1 = faceColors1[face];
		int color2 = faceColors2[face];
		int color3 = faceColors3[face];

		if (color3 == -1)
			color2 = color3 = color1;

		if (plugin.configUndoVanillaShading) {
			int color1H = color1 >> 10 & 0x3F;
			int color1S = color1 >> 7 & 0x7;
			int color1L = color1 & 0x7F;
			int color2H = color2 >> 10 & 0x3F;
			int color2S = color2 >> 7 & 0x7;
			int color2L = color2 & 0x7F;
			int color3H = color3 >> 10 & 0x3F;
			int color3S = color3 >> 7 & 0x7;
			int color3L = color3 & 0x7F;

			// Approximately invert vanilla shading by brightening vertices that were likely darkened by vanilla based on
			// vertex normals. This process is error-prone, as not all models are lit by vanilla with the same light
			// direction, and some models even have baked lighting built into the model itself. In some cases, increasing
			// brightness in this way leads to overly bright colors, so we are forced to cap brightness at a relatively
			// low value for it to look acceptable in most cases.
			float[] L = LIGHT_DIR_MODEL;
			float color1Adjust =
				BASE_LIGHTEN - color1L + (color1L < IGNORE_LOW_LIGHTNESS ? 0 : (color1L - IGNORE_LOW_LIGHTNESS) * LIGHTNESS_MULTIPLIER);
			float color2Adjust =
				BASE_LIGHTEN - color2L + (color2L < IGNORE_LOW_LIGHTNESS ? 0 : (color2L - IGNORE_LOW_LIGHTNESS) * LIGHTNESS_MULTIPLIER);
			float color3Adjust =
				BASE_LIGHTEN - color3L + (color3L < IGNORE_LOW_LIGHTNESS ? 0 : (color3L - IGNORE_LOW_LIGHTNESS) * LIGHTNESS_MULTIPLIER);

			// Normals are currently unrotated, so we don't need to do any rotation for this
			float nx, ny, nz, lightDotNormal;
			nx = xVertexNormals[triangleA];
			ny = yVertexNormals[triangleA];
			nz = zVertexNormals[triangleA];
			lightDotNormal = nx * L[0] + ny * L[1] + nz * L[2];
			if (lightDotNormal > 0) {
				lightDotNormal /= sqrt(nx * nx + ny * ny + nz * nz);
				color1L += (int) (lightDotNormal * color1Adjust);
			}

			nx = xVertexNormals[triangleB];
			ny = yVertexNormals[triangleB];
			nz = zVertexNormals[triangleB];
			lightDotNormal = nx * L[0] + ny * L[1] + nz * L[2];
			if (lightDotNormal > 0) {
				lightDotNormal /= sqrt(nx * nx + ny * ny + nz * nz);
				color2L += (int) (lightDotNormal * color2Adjust);
			}

			nx = xVertexNormals[triangleC];
			ny = yVertexNormals[triangleC];
			nz = zVertexNormals[triangleC];
			lightDotNormal = nx * L[0] + ny * L[1] + nz * L[2];
			if (lightDotNormal > 0) {
				lightDotNormal /= sqrt(nx * nx + ny * ny + nz * nz);
				color3L += (int) (lightDotNormal * color3Adjust);
			}

			int maxBrightness1 = 55;
			int maxBrightness2 = 55;
			int maxBrightness3 = 55;
			if (!plugin.configLegacyGreyColors) {
				maxBrightness1 = MAX_BRIGHTNESS_LOOKUP_TABLE[color1S];
				maxBrightness2 = MAX_BRIGHTNESS_LOOKUP_TABLE[color2S];
				maxBrightness3 = MAX_BRIGHTNESS_LOOKUP_TABLE[color3S];
			}

			// Clamp brightness as detailed above
			color1L = min(color1L, maxBrightness1);
			color2L = min(color2L, maxBrightness2);
			color3L = min(color3L, maxBrightness3);

			color1 = color1H << 10 | color1S << 7 | color1L;
			color2 = color2H << 10 | color2S << 7 | color2L;
			color3 = color3H << 10 | color3S << 7 | color3L;
		}

		// HSL override is not applied to textured faces
		if (overrideAmount > 0 && !isTextured) {
			color1 = SceneUploader.interpolateHSL(color1, overrideHue, overrideSat, overrideLum, overrideAmount);
			color2 = SceneUploader.interpolateHSL(color2, overrideHue, overrideSat, overrideLum, overrideAmount);
			color3 = SceneUploader.interpolateHSL(color3, overrideHue, overrideSat, overrideLum, overrideAmount);
		}

		int texA, texB, texC;

		if (isVanillaUVMapped && textureFaces[face] != -1) {
			int tface = textureFaces[face] & 0xff;
			texA = texIndices1[tface];
			texB = texIndices2[tface];
			texC = texIndices3[tface];
		} else {
			texA = triangleA;
			texB = triangleB;
			texC = triangleC;
		}

		UvType uvType = UvType.GEOMETRY;
		Material material = baseMaterial;

		int transparency = transparencies != null ? transparencies[face] & 0xFF : 0;
		int textureId = isVanillaTextured ? faceTextures[face] : -1;
		if (textureId != -1) {
			uvType = UvType.VANILLA;
			material = textureMaterial;
			if (material == Material.NONE)
				material = materialManager.fromVanillaTexture(textureId);

			color1 = color2 = color3 = 90;
		}

		ModelOverride faceOverride = modelOverride;
		if (modelOverride.materialOverrides != null) {
			var override = modelOverride.materialOverrides.get(material);
			if (override != null) {
				faceOverride = override;
				material = faceOverride.textureMaterial;
			}
		}
		if (modelOverride.colorOverrides != null) {
			int ahsl = (0xFF - transparency) << 16 | faceColors1[face];
			for (var override : modelOverride.colorOverrides) {
				if (override.ahslCondition.test(ahsl)) {
					faceOverride = override;
					material = faceOverride.baseMaterial;
					break;
				}
			}
		}

		if (material != Material.NONE) {
			uvType = faceOverride.uvType;
			if (uvType == UvType.VANILLA || (textureId != -1 && faceOverride.retainVanillaUvs))
				uvType = isVanillaUVMapped && textureFaces[face] != -1 ? UvType.VANILLA : UvType.GEOMETRY;
		}

		isTextured = true; // Skip vanilla shading reversal in the shader, since we do it on the CPU
		int materialData = material.packMaterialData(faceOverride, uvType, false, isTextured);

		if (uvType == UvType.VANILLA) {
			modelUvs[0] = modelLocalX[texA] - vx1;
			modelUvs[1] = modelLocalY[texA] - vy1;
			modelUvs[2] = modelLocalZ[texA] - vz1;
			modelUvs[4] = modelLocalX[texB] - vx2;
			modelUvs[5] = modelLocalY[texB] - vy2;
			modelUvs[6] = modelLocalZ[texB] - vz2;
			modelUvs[8] = modelLocalX[texC] - vx3;
			modelUvs[9] = modelLocalY[texC] - vy3;
			modelUvs[10] = modelLocalZ[texC] - vz3;
		} else {
			faceOverride.fillUvsForFace(modelUvs, model, preOrientation, uvType, face, workingSpace);
		}

		if (modelOverride.flatNormals || (!plugin.configPreserveVanillaNormals && model.getFaceColors3()[face] == -1)) {
			Arrays.fill(modelNormals, 0);
		} else if (xVertexNormals != null && yVertexNormals != null && zVertexNormals != null) {
			modelNormals[0] = xVertexNormals[triangleA];
			modelNormals[1] = yVertexNormals[triangleA];
			modelNormals[2] = zVertexNormals[triangleA];
			modelNormals[3] = xVertexNormals[triangleB];
			modelNormals[4] = yVertexNormals[triangleB];
			modelNormals[5] = zVertexNormals[triangleB];
			modelNormals[6] = xVertexNormals[triangleC];
			modelNormals[7] = yVertexNormals[triangleC];
			modelNormals[8] = zVertexNormals[triangleC];

			// Rotate normals
			for (int i = 0; i < 9; i += 3) {
				int x = modelNormals[i];
				int z = modelNormals[i + 2];
				modelNormals[i] = z * orientSin + x * orientCos >> 16;
				modelNormals[i + 2] = z * orientCos - x * orientSin >> 16;
			}
		}

		int depthBias = faceOverride.depthBias != -1 ? faceOverride.depthBias :
			bias == null ? 0 : bias[face] & 0xFF;
		int packedAlphaBiasHsl = transparency << 24 | depthBias << 16;
		boolean hasAlpha = material.hasTransparency || transparency != 0;
		var vb = hasAlpha ? alphaBuffer : opaqueBuffer;
		GpuIntBuffer.putFloatVertex(
			vb,
			vx1, vy1, vz1, packedAlphaBiasHsl | color1,
			modelUvs[0], modelUvs[1], modelUvs[2], materialData,
			modelNormals[0], modelNormals[1], modelNormals[2], 0
		);
		GpuIntBuffer.putFloatVertex(
			vb,
			vx2, vy2, vz2, packedAlphaBiasHsl | color2,
			modelUvs[4], modelUvs[5], modelUvs[6], materialData,
			modelNormals[3], modelNormals[4], modelNormals[5], 0
		);
		GpuIntBuffer.putFloatVertex(
			vb,
			vx3, vy3, vz3, packedAlphaBiasHsl | color3,
			modelUvs[8], modelUvs[9], modelUvs[10], materialData,
			modelNormals[6], modelNormals[7], modelNormals[8], 0
		);
		return 3;
	}
}
