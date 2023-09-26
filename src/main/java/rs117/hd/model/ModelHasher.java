package rs117.hd.model;

import javax.inject.Singleton;
import lombok.NonNull;
import net.runelite.api.*;
import rs117.hd.data.materials.UvType;
import rs117.hd.scene.model_overrides.ModelOverride;

@Singleton
public class ModelHasher {
	private Model model;
	private int faceCount;
	private long faceColorsOneHash;
	private long faceColorsTwoHash;
	private long faceColorsThreeHash;
	private long faceTransparenciesHash;
	private long faceTexturesHash;
	private long xVerticesHash;
	private long yVerticesHash;
	private long zVerticesHash;
	private long faceIndicesOneHash;
	private long faceIndicesTwoHash;
	private long faceIndicesThreeHash;
	private long textureTrianglesHash;

	public void setModel(Model model) {
		this.model = model;
		faceCount = model.getFaceCount();
		faceColorsOneHash = fastHash(model.getFaceColors1());
		faceColorsTwoHash = fastHash(model.getFaceColors2());
		faceColorsThreeHash = fastHash(model.getFaceColors3());
		faceTransparenciesHash = fastByteHash(model.getFaceTransparencies());
		faceTexturesHash = fastShortHash(model.getFaceTextures());
		xVerticesHash = fastHash(model.getVerticesX(), model.getVerticesCount());
		yVerticesHash = fastHash(model.getVerticesY(), model.getVerticesCount());
		zVerticesHash = fastHash(model.getVerticesZ(), model.getVerticesCount());
		faceIndicesOneHash = fastHash(model.getFaceIndices1());
		faceIndicesTwoHash = fastHash(model.getFaceIndices2());
		faceIndicesThreeHash = fastHash(model.getFaceIndices3());
		textureTrianglesHash = 0;
		final byte[] textureFaces = model.getTextureFaces();
		if (textureFaces != null) {
			boolean hasVanillaTexturedFaces = false;
			for (int textureId : textureFaces) {
				if (textureId != -1) {
					hasVanillaTexturedFaces = true;
					break;
				}
			}
			if (hasVanillaTexturedFaces) {
				final int[] texIndices1 = model.getTexIndices1();
				final int[] texIndices2 = model.getTexIndices2();
				final int[] texIndices3 = model.getTexIndices3();
				final int[] vertexX = model.getVerticesX();
				final int[] vertexY = model.getVerticesY();
				final int[] vertexZ = model.getVerticesZ();
				long h = 0;
				for (int i = 0; i < model.getFaceCount(); i++) {
					int texFace = textureFaces[i];
					if (texFace == -1)
						continue;
					texFace &= 0xff;
					final int texA = texIndices1[texFace];
					final int texB = texIndices2[texFace];
					final int texC = texIndices3[texFace];
					h = h * 31L + vertexX[texA];
					h = h * 31L + vertexY[texA];
					h = h * 31L + vertexZ[texA];
					h = h * 31L + vertexX[texB];
					h = h * 31L + vertexY[texB];
					h = h * 31L + vertexZ[texB];
					h = h * 31L + vertexX[texC];
					h = h * 31L + vertexY[texC];
					h = h * 31L + vertexZ[texC];
				}
				textureTrianglesHash = h;
			}
		}
	}

	public long calculateVertexCacheHash() {
		long h = faceCount;
		h = h * 31L + faceColorsOneHash;
		h = h * 31L + faceColorsTwoHash;
		h = h * 31L + faceColorsThreeHash;
		h = h * 31L + faceTransparenciesHash;
		h = h * 31L + faceTexturesHash;
		h = h * 31L + xVerticesHash;
		h = h * 31L + yVerticesHash;
		h = h * 31L + zVerticesHash;
		h = h * 31L + faceIndicesOneHash;
		h = h * 31L + faceIndicesTwoHash;
		h = h * 31L + faceIndicesThreeHash;
		h = h * 31L + textureTrianglesHash;
		h = h * 31L + model.getOverrideAmount();
		h = h * 31L + model.getOverrideHue();
		h = h * 31L + model.getOverrideSaturation();
		h = h * 31L + model.getOverrideLuminance();
		return h;
	}

	public long calculateNormalCacheHash() {
		long h = faceCount;
		h = h * 31L + faceIndicesOneHash;
		h = h * 31L + faceIndicesTwoHash;
		h = h * 31L + faceIndicesThreeHash;
		h = h * 31L + fastHash(model.getVertexNormalsX());
		h = h * 31L + fastHash(model.getVertexNormalsY());
		h = h * 31L + fastHash(model.getVertexNormalsZ());
		return h;
	}

	public long calculateUvCacheHash(int orientation, @NonNull ModelOverride modelOverride) {
		long h = faceCount;
		h = h * 31L + (modelOverride.uvType == UvType.VANILLA ? textureTrianglesHash : 0);
		h = h * 31L + (modelOverride.uvType.orientationDependent ? orientation : 0);
		h = h * 31L + modelOverride.hashCode();
		h = h * 31L + faceTexturesHash;
		return h;
	}

	public static long fastHash(int[] a) {
		if (a == null)
			return 0;

		int i = 0;
		long r = 1;
		int length = a.length;

		for (; i + 5 < length; i += 6)
			r = 31L * 31L * 31L * 31L * 31L * 31L * r +
				31L * 31L * 31L * 31L * 31L * a[i] +
				31L * 31L * 31L * 31L * a[i + 1] +
				31L * 31L * 31L * a[i + 2] +
				31L * 31L * a[i + 3] +
				31L * a[i + 4] +
				a[i + 5];

		for (; i < length; i++)
			r = 31L * r + a[i];

		return r;
	}

	public static long fastHash(int[] a, int length) {
		if (a == null)
			return 0;

		int i = 0;
		long r = 1;

		for (; i + 5 < length; i += 6)
			r = 31L * 31L * 31L * 31L * 31L * 31L * r +
				31L * 31L * 31L * 31L * 31L * a[i] +
				31L * 31L * 31L * 31L * a[i + 1] +
				31L * 31L * 31L * a[i + 2] +
				31L * 31L * a[i + 3] +
				31L * a[i + 4] +
				a[i + 5];

		for (; i < length; i++)
			r = 31L * r + a[i];

		return r;
	}

	public static int fastIntHash(int[] a, int actualLength) {
		if (a == null)
			return 0;

		int i = 0;
		int r = 1;
		int length = a.length;
		if (actualLength != -1)
			length = actualLength;

		for (; i + 5 < length; i += 6)
			r = 31 * 31 * 31 * 31 * 31 * 31 * r +
				31 * 31 * 31 * 31 * 31 * a[i] +
				31 * 31 * 31 * 31 * a[i + 1] +
				31 * 31 * 31 * a[i + 2] +
				31 * 31 * a[i + 3] +
				31 * a[i + 4] +
				a[i + 5];

		for (; i < length; i++)
			r = 31 * r + a[i];

		return r;
	}

	public static int fastByteHash(byte[] a) {
		if (a == null)
			return 0;

		int i = 0;
		int r = 1;

		for (; i + 5 < a.length; i += 6)
			r = 31 * 31 * 31 * 31 * 31 * 31 * r
				+ 31 * 31 * 31 * 31 * 31 * a[i]
				+ 31 * 31 * 31 * 31 * a[i + 1]
				+ 31 * 31 * 31 * a[i + 2]
				+ 31 * 31 * a[i + 3]
				+ 31 * a[i + 4]
				+ a[i + 5];

		for (; i < a.length; i++) {
			r = 31 * r + a[i];
		}

		return r;
	}

    public static int fastShortHash(short[] a) {
		if (a == null)
			return 0;

		int i = 0;
		int r = 1;

		for (; i + 5 < a.length; i += 6)
			r = 31 * 31 * 31 * 31 * 31 * 31 * r +
				31 * 31 * 31 * 31 * 31 * a[i] +
				31 * 31 * 31 * 31 * a[i + 1] +
				31 * 31 * 31 * a[i + 2] +
				31 * 31 * a[i + 3] +
				31 * a[i + 4] +
				a[i + 5];

		for (; i < a.length; i++)
			r = 31 * r + a[i];

		return r;
	}

    public static int fastFloatHash(float[] a) {
		if (a == null)
			return 0;

		int i = 0;
		int r = 1;

		for (; i + 5 < a.length; i += 6)
			r = 31 * 31 * 31 * 31 * 31 * 31 * r +
				31 * 31 * 31 * 31 * 31 * Float.floatToIntBits(a[i]) +
				31 * 31 * 31 * 31 * Float.floatToIntBits(a[i + 1]) +
				31 * 31 * 31 * Float.floatToIntBits(a[i + 2]) +
				31 * 31 * Float.floatToIntBits(a[i + 3]) +
				31 * Float.floatToIntBits(a[i + 4]) +
				Float.floatToIntBits(a[i + 5]);

		for (; i < a.length; i++)
			r = 31 * r + Float.floatToIntBits(a[i]);

		return r;
	}
}
