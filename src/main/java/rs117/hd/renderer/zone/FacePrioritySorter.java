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
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.UvType;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Perspective.*;
import static rs117.hd.renderer.zone.SceneUploader.calculateFaceNormal;
import static rs117.hd.renderer.zone.SceneUploader.interpolateHSL;
import static rs117.hd.renderer.zone.SceneUploader.undoVanillaShading;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
class FacePrioritySorter {
	private static final int[] EMPTY_NORMALS = new int[9];

	static final int[] distances;
	static final char[] distanceFaceCount;
	static final char[][] distanceToFaces;

	private static final float[] modelProjectedX;
	private static final float[] modelProjectedY;

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

	static {
		distances = new int[MAX_VERTEX_COUNT];
		distanceFaceCount = new char[MAX_DIAMETER];
		distanceToFaces = new char[MAX_DIAMETER][ZSORT_GROUP_SIZE];

		modelProjectedX = new float[MAX_VERTEX_COUNT];
		modelProjectedY = new float[MAX_VERTEX_COUNT];

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
	}

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
		IntBuffer alphaBuffer,
		IntBuffer opaqueTexBuffer,
		IntBuffer alphaTexBuffer
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

		orientation = mod(orientation, 2048);
		orientSin = SINE[orientation];
		orientCos = COSINE[orientation];
		float orientSinf = orientSin / 65536f;
		float orientCosf = orientCos / 65536f;

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

			modelProjectedX[v] = p[0] / p[2];
			modelProjectedY[v] = p[1] / p[2];
			distances[v] = (int) p[2] - zero;
		}

		model.calculateBoundsCylinder();
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
				aX = modelProjectedX[v1],
				aY = modelProjectedY[v1],
				bX = modelProjectedX[v2],
				bY = modelProjectedY[v2],
				cX = modelProjectedX[v3],
				cY = modelProjectedY[v3];
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
						len += pushFace(model, modelOverride, preOrientation, face, opaqueBuffer, alphaBuffer, opaqueTexBuffer, alphaTexBuffer);
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

			int currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;

			for (int pri = 0; pri < 10; ++pri) {
				while (pri == 0 && currFaceDistance > avg12) {
					final int face = dynFaces[drawnFaces++];
					len += pushFace(model, modelOverride, preOrientation, face, opaqueBuffer, alphaBuffer, opaqueTexBuffer, alphaTexBuffer);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11]) {
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;
				}

				while (pri == 3 && currFaceDistance > avg34) {
					final int face = dynFaces[drawnFaces++];
					len += pushFace(model, modelOverride, preOrientation, face, opaqueBuffer, alphaBuffer, opaqueTexBuffer, alphaTexBuffer);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11]) {
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;
				}

				while (pri == 5 && currFaceDistance > avg68) {
					final int face = dynFaces[drawnFaces++];
					len += pushFace(model, modelOverride, preOrientation, face, opaqueBuffer, alphaBuffer, opaqueTexBuffer, alphaTexBuffer);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11]) {
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;
				}

				final int priNum = numOfPriority[pri];
				final int[] priFaces = orderedFaces[pri];

				for (int faceIdx = 0; faceIdx < priNum; ++faceIdx) {
					final int face = priFaces[faceIdx];
					len += pushFace(model, modelOverride, preOrientation, face, opaqueBuffer, alphaBuffer, opaqueTexBuffer, alphaTexBuffer);
				}
			}

			while (currFaceDistance != -1000) {
				final int face = dynFaces[drawnFaces++];
				len += pushFace(model, modelOverride, preOrientation, face, opaqueBuffer, alphaBuffer, opaqueTexBuffer, alphaTexBuffer);

				if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11]) {
					drawnFaces = 0;
					dynFaces = orderedFaces[11];
					numDynFaces = numOfPriority[11];
					dynFaceDistances = eq11;
				}

				currFaceDistance = drawnFaces < numDynFaces ? dynFaceDistances[drawnFaces] : -1000;
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
		IntBuffer alphaBuffer,
		IntBuffer opaqueTexBuffer,
		IntBuffer alphaTexBuffer
	) {
		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final short[] unlitFaceColors = plugin.configUnlitFaceColors ? model.getUnlitFaceColors() : null;
		final int[] faceColors1 = model.getFaceColors1();
		final int[] faceColors2 = model.getFaceColors2();
		final int[] faceColors3 = model.getFaceColors3();

		final int[] xVertexNormals = model.getVertexNormalsX();
		final int[] yVertexNormals = model.getVertexNormalsY();
		final int[] zVertexNormals = model.getVertexNormalsZ();
		final boolean hasVertexNormals = xVertexNormals != null && yVertexNormals != null && zVertexNormals != null;

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
		int textureId = isVanillaTextured ? faceTextures[face] : -1;

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

		// Hide fake shadows or lighting that is often baked into models by making the fake shadow transparent
		if (plugin.configHideFakeShadows && modelOverride.hideVanillaShadows && HDUtils.isBakedGroundShading(model, face))
			return 0;

		if (unlitFaceColors != null)
			color1 = color2 = color3 = unlitFaceColors[face] & 0xFFFF;

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

		int transparency = transparencies != null ? transparencies[face] & 0xFF : 0;

		UvType uvType = UvType.GEOMETRY;
		Material material = baseMaterial;
		ModelOverride faceOverride = modelOverride;

		if (textureId != -1) {
			color1 = color2 = color3 = 90;
			uvType = UvType.VANILLA;
			if (textureMaterial != Material.NONE) {
				material = textureMaterial;
			} else {
				material = materialManager.fromVanillaTexture(textureId);
				if (modelOverride.materialOverrides != null) {
					var override = modelOverride.materialOverrides.get(material);
					if (override != null) {
						faceOverride = override;
						material = faceOverride.textureMaterial;
					}
				}
			}
		} else if (modelOverride.colorOverrides != null) {
			int ahsl = (0xFF - transparency) << 16 | color1;
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

		boolean keepShading = true; // Skip vanilla shading reversal in the shader, since we do it on the CPU
		int materialData = material.packMaterialData(faceOverride, uvType, false, keepShading);

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

		final boolean shouldCalculateFaceNormal;
		if (!hasVertexNormals || faceOverride.flatNormals || (!plugin.configPreserveVanillaNormals && faceColors3[face] == -1)) {
			shouldCalculateFaceNormal = true;
		} else {
			modelNormals[0] = xVertexNormals[triangleA];
			modelNormals[1] = yVertexNormals[triangleA];
			modelNormals[2] = zVertexNormals[triangleA];
			modelNormals[3] = xVertexNormals[triangleB];
			modelNormals[4] = yVertexNormals[triangleB];
			modelNormals[5] = zVertexNormals[triangleB];
			modelNormals[6] = xVertexNormals[triangleC];
			modelNormals[7] = yVertexNormals[triangleC];
			modelNormals[8] = zVertexNormals[triangleC];

			// TODO: check if this is actually necessary
			shouldCalculateFaceNormal =
				modelNormals[0] == 0 && modelNormals[1] == 0 && modelNormals[2] == 0 &&
				modelNormals[3] == 0 && modelNormals[4] == 0 && modelNormals[5] == 0 &&
				modelNormals[6] == 0 && modelNormals[7] == 0 && modelNormals[8] == 0;
		}

		if (shouldCalculateFaceNormal) {
			calculateFaceNormal(
				modelNormals,
				vx1, vy1, vz1,
				vx2, vy2, vz2,
				vx3, vy3, vz3
			);
		}

		if (plugin.configUndoVanillaShading) {
			// TODO: Decide whether this should be applied when normals are null
			color1 = undoVanillaShading(color1, plugin.configLegacyGreyColors, modelNormals[0], modelNormals[1], modelNormals[2]);
			color2 = undoVanillaShading(color2, plugin.configLegacyGreyColors, modelNormals[3], modelNormals[4], modelNormals[5]);
			color3 = undoVanillaShading(color3, plugin.configLegacyGreyColors, modelNormals[6], modelNormals[7], modelNormals[8]);
		}

		// HSL override is not applied to textured faces
		if (overrideAmount > 0 && textureId == -1) {
			color1 = interpolateHSL(color1, overrideHue, overrideSat, overrideLum, overrideAmount);
			color2 = interpolateHSL(color2, overrideHue, overrideSat, overrideLum, overrideAmount);
			color3 = interpolateHSL(color3, overrideHue, overrideSat, overrideLum, overrideAmount);
		}

		// Rotate normals
		if (!shouldCalculateFaceNormal) {
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
		var tb = hasAlpha ? alphaTexBuffer : opaqueTexBuffer;

		color1 |= packedAlphaBiasHsl;
		color2 |= packedAlphaBiasHsl;
		color3 |= packedAlphaBiasHsl;

		final int texturedFaceIdx = GpuIntBuffer.putFace(
			tb,
			color1, color2, color3,
			materialData, materialData, materialData,
			0, 0, 0
		);

		GpuIntBuffer.putFloatVertex(
			vb,
			vx1, vy1, vz1,
			modelUvs[0], modelUvs[1], 0,
			modelNormals[0], modelNormals[1], modelNormals[2],
			texturedFaceIdx
		);
		GpuIntBuffer.putFloatVertex(
			vb,
			vx2, vy2, vz2,
			modelUvs[4], modelUvs[5], 0,
			modelNormals[3], modelNormals[4], modelNormals[5],
			texturedFaceIdx
		);
		GpuIntBuffer.putFloatVertex(
			vb,
			vx3, vy3, vz3,
			modelUvs[8], modelUvs[9], 0,
			modelNormals[6], modelNormals[7], modelNormals[8],
			texturedFaceIdx
		);
		return 3;
	}
}
