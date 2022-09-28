package rs117.hd.model;

import lombok.NonNull;
import net.runelite.api.Model;
import rs117.hd.scene.model_overrides.ModelOverride;

import javax.inject.Singleton;

@Singleton
public class ModelHasher {
    private Model model;
    private int faceColorsOneHash;
    private int faceColorsTwoHash;
    private int faceColorsThreeHash;
    private int faceTransparenciesHash;
    private int faceTexturesHash;
    private int faceTexturesUvHash;
    private int xVerticesHash;
    private int yVerticesHash;
    private int zVerticesHash;
    private int faceIndicesOneHash;
    private int faceIndicesTwoHash;
    private int faceIndicesThreeHash;

    public void setModel(Model model) {
        this.model = model;

        this.faceColorsOneHash = fastIntHash(model.getFaceColors1(), -1);
        this.faceColorsTwoHash = fastIntHash(model.getFaceColors2(), -1);
        this.faceColorsThreeHash = fastIntHash(model.getFaceColors3(), -1);
        this.faceTransparenciesHash = fastByteHash(model.getFaceTransparencies());
        this.faceTexturesHash = fastShortHash(model.getFaceTextures());
        this.faceTexturesUvHash = fastFloatHash(model.getFaceTextureUVCoordinates());
        this.xVerticesHash = fastIntHash(model.getVerticesX(), model.getVerticesCount());
        this.yVerticesHash = fastIntHash(model.getVerticesY(), model.getVerticesCount());
        this.zVerticesHash = fastIntHash(model.getVerticesZ(), model.getVerticesCount());
        this.faceIndicesOneHash = fastIntHash(model.getFaceIndices1(), -1);
        this.faceIndicesTwoHash = fastIntHash(model.getFaceIndices2(), -1);
        this.faceIndicesThreeHash = fastIntHash(model.getFaceIndices3(), -1);
    }

    public int calculateVertexCacheHash() {
        return fastIntHash(new int[]{
                this.faceColorsOneHash,
                this.faceColorsTwoHash,
                this.faceColorsThreeHash,
                this.faceTransparenciesHash,
                this.faceTexturesHash,
                this.faceTexturesUvHash,
                this.model.getOverrideAmount(),
                this.model.getOverrideHue(),
                this.model.getOverrideSaturation(),
                this.model.getOverrideLuminance(),
                faceIndicesOneHash,
                faceIndicesTwoHash,
                faceIndicesThreeHash,
                xVerticesHash,
                yVerticesHash,
                zVerticesHash,
        }, -1);
    }

    public int calculateNormalCacheHash() {
        return fastIntHash(new int[]{
                this.faceIndicesOneHash,
                this.faceIndicesTwoHash,
                this.faceIndicesThreeHash,
                fastIntHash(this.model.getVertexNormalsX(), -1),
                fastIntHash(this.model.getVertexNormalsY(), -1),
                fastIntHash(this.model.getVertexNormalsZ(), -1),
        }, -1);
    }

    public int calculateUvCacheHash(@NonNull ModelOverride modelOverride) {
        return fastIntHash(new int[]{
                this.faceTexturesHash,
                this.faceTexturesUvHash,
                modelOverride.hashCode()
        }, -1);
    }

    public int calculateColorCacheHash() {
        return fastIntHash(new int[]{
                this.faceColorsOneHash,
                this.faceColorsTwoHash,
                this.faceColorsThreeHash,
                this.faceTransparenciesHash,
                this.faceTexturesHash,
                this.faceTexturesUvHash,
                this.model.getOverrideAmount(),
                this.model.getOverrideHue(),
                this.model.getOverrideSaturation(),
                this.model.getOverrideLuminance()
        }, -1);
    }

    public int calculateBatchHash() {
        return calculateVertexCacheHash();
    }

    public static int fastIntHash(int[] a, int actualLength) {
        if (a == null) {
            return 0;
        }

        int i = 0;
        int r = 1;
        int length = a.length;
        if (actualLength != -1) {
            length = actualLength;
        }

        for (; i + 5 < length; i += 6) {
            r = 31 * 31 * 31 * 31 * 31 * 31 * r
                    + 31 * 31 * 31 * 31 * 31 * a[i]
                    + 31 * 31 * 31 * 31 * a[i + 1]
                    + 31 * 31 * 31 * a[i + 2]
                    + 31 * 31 * a[i + 3]
                    + 31 * a[i + 4]
                    + a[i + 5];
        }

        for (; i < length; i++) {
            r = 31 * r + a[i];
        }

        return r;
    }

    public static int fastByteHash(byte[] a) {
        if (a == null) {
            return 0;
        }

        int i = 0;
        int r = 1;

        for (; i + 5 < a.length; i += 6) {
            r = 31 * 31 * 31 * 31 * 31 * 31 * r
                    + 31 * 31 * 31 * 31 * 31 * a[i]
                    + 31 * 31 * 31 * 31 * a[i + 1]
                    + 31 * 31 * 31 * a[i + 2]
                    + 31 * 31 * a[i + 3]
                    + 31 * a[i + 4]
                    + a[i + 5];
        }

        for (; i < a.length; i++) {
            r = 31 * r + a[i];
        }

        return r;
    }

    public static int fastShortHash(short[] a) {
        if (a == null) {
            return 0;
        }

        int i = 0;
        int r = 1;

        for (; i + 5 < a.length; i += 6) {
            r = 31 * 31 * 31 * 31 * 31 * 31 * r
                    + 31 * 31 * 31 * 31 * 31 * a[i]
                    + 31 * 31 * 31 * 31 * a[i + 1]
                    + 31 * 31 * 31 * a[i + 2]
                    + 31 * 31 * a[i + 3]
                    + 31 * a[i + 4]
                    + a[i + 5];
        }

        for (; i < a.length; i++) {
            r = 31 * r + a[i];
        }

        return r;
    }

    public static int fastFloatHash(float[] a) {
        if (a == null) {
            return 0;
        }

        int i = 0;
        int r = 1;

        for (; i + 5 < a.length; i += 6) {
            r = 31 * 31 * 31 * 31 * 31 * 31 * r
                    + 31 * 31 * 31 * 31 * 31 * Float.floatToIntBits(a[i])
                    + 31 * 31 * 31 * 31 * Float.floatToIntBits(a[i + 1])
                    + 31 * 31 * 31 * Float.floatToIntBits(a[i + 2])
                    + 31 * 31 * Float.floatToIntBits(a[i + 3])
                    + 31 * Float.floatToIntBits(a[i + 4])
                    + Float.floatToIntBits(a[i + 5]);
        }

        for (; i < a.length; i++) {
            r = 31 * r + Float.floatToIntBits(a[i]);
        }

        return r;
    }
}
