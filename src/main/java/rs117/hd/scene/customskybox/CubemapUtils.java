package rs117.hd.scene.customskybox;

import java.awt.image.BufferedImage;

public class CubemapUtils {
	/**
	 * Slices a standard unwrapped horizontal-cross cubemap layout (4 columns x 3 rows) into
	 * the 6 individual cube faces, in +X,-X,+Y,-Y,+Z,-Z order:
	 * <pre>
	 *        [+Y]
	 * [-X] [+Z] [+X] [-Z]
	 *        [-Y]
	 * </pre>
	 */
	public static BufferedImage[] sliceHorizontalCross(BufferedImage cross) {
		int faceSize = cross.getWidth() / 4;
		if (faceSize <= 0 || cross.getHeight() / 3 != faceSize)
			throw new IllegalArgumentException(
				"Cubemap cross image must have a 4:3 aspect ratio (width / 4 == height / 3)");

		return new BufferedImage[] {
			cross.getSubimage(2 * faceSize, faceSize, faceSize, faceSize), // +X
			cross.getSubimage(0, faceSize, faceSize, faceSize), // -X
			cross.getSubimage(faceSize, 0, faceSize, faceSize), // +Y
			cross.getSubimage(faceSize, 2 * faceSize, faceSize, faceSize), // -Y
			cross.getSubimage(faceSize, faceSize, faceSize, faceSize), // +Z
			cross.getSubimage(3 * faceSize, faceSize, faceSize, faceSize), // -Z
		};
	}
}
