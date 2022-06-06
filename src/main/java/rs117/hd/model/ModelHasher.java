package rs117.hd.model;

import net.runelite.api.Model;

import javax.inject.Singleton;

@Singleton
public class ModelHasher {
    private Model model;
    private int faceColors1Hash;
    private int faceColors2Hash;
    private int faceColors3Hash;
    private int faceTransparenciesHash;
    private int faceTexturesHash;
    private int faceTexturesUvHash;

    public static int fastIntHash(int[] a) {
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

    public void setModel(Model model) {
        this.model = model;
        this.faceColors1Hash = fastIntHash(model.getFaceColors1());
        this.faceColors2Hash = fastIntHash(model.getFaceColors2());
        this.faceColors3Hash = fastIntHash(model.getFaceColors3());
        this.faceTransparenciesHash = fastByteHash(model.getFaceTransparencies());
        this.faceTexturesHash = fastShortHash(model.getFaceTextures());
        this.faceTexturesUvHash = fastFloatHash(model.getFaceTextureUVCoordinates());
    }

    public int calculateColorCacheHash() {
        return fastIntHash(new int[]{
                this.faceColors1Hash,
                this.faceColors2Hash,
                this.faceColors3Hash,
                this.faceTransparenciesHash,
                this.faceTexturesHash,
                this.faceTexturesUvHash,
                this.model.getOverrideAmount(),
                this.model.getOverrideHue(),
                this.model.getOverrideSaturation(),
                this.model.getOverrideLuminance()
        });
    }

    public int calculateBatchHash() {
        return fastIntHash(new int[]{
                fastIntHash(this.model.getVerticesX()),
                fastIntHash(this.model.getVerticesY()),
                fastIntHash(this.model.getVerticesZ()),
                this.faceColors1Hash,
                this.faceColors2Hash,
                this.faceColors3Hash,
                this.faceTexturesHash,
                this.faceTexturesUvHash,
        });
    }
}
