package rs117.hd.utils;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ImageUtils {

	private static final String[] SUPPORTED_IMAGE_EXTENSIONS = { "png", "jpg" };

	public static BufferedImage loadTextureImage(ResourcePath path,String textureName) {
		for (String ext : SUPPORTED_IMAGE_EXTENSIONS) {
			try {
				return path.resolve(textureName + "." + ext).loadImage();
			} catch (Exception ex) {
				log.trace("Unable to load texture: {}", path, ex);
			}
		}

		return null;
	}

	public static BufferedImage scaleTexture(
		BufferedImage scaledImage,
		BufferedImage vanillaImage,
		BufferedImage image,
		int scaledSize,
		boolean flipU,
		boolean flipV
	) {
		if(scaledImage == null || scaledImage.getWidth() != scaledSize || scaledImage.getHeight() != scaledSize){
			scaledImage = new BufferedImage(scaledSize, scaledSize, BufferedImage.TYPE_INT_ARGB);
		}

		if(!flipU && !flipV && image.getWidth() == scaledSize && image.getHeight() == scaledSize) {
			return image;
		}

		AffineTransform t = new AffineTransform();
		if (image != vanillaImage) {
			if(flipU) {
				t.translate(scaledSize, 0);
				t.scale(-1, 1);
			}
			if(flipV) {
				t.translate(0, scaledSize);
				t.scale(1, -1);
			}
		}
		t.scale((double) scaledSize / image.getWidth(), (double) scaledSize / image.getHeight());
		AffineTransformOp scaleOp = new AffineTransformOp(t, AffineTransformOp.TYPE_BICUBIC);
		scaleOp.filter(image, scaledImage);

		return scaledImage;
	}

	public static BufferedImage scaleTextureSimple(
		BufferedImage scaledImage,
		BufferedImage image,
		int scaledSize,
		boolean flipU,
		boolean flipV
	) {
		if(scaledImage == null || scaledImage.getWidth() != scaledSize || scaledImage.getHeight() != scaledSize){
			scaledImage = new BufferedImage(scaledSize, scaledSize, BufferedImage.TYPE_INT_ARGB);
		}

		if(!flipU && !flipV && image.getWidth() == scaledSize && image.getHeight() == scaledSize) {
			return image;
		}

		AffineTransform t = new AffineTransform();

		t.scale((double) scaledSize / image.getWidth(), (double) scaledSize / image.getHeight());
		AffineTransformOp scaleOp = new AffineTransformOp(t, AffineTransformOp.TYPE_BICUBIC);
		scaleOp.filter(image, scaledImage);

		return scaledImage;
	}


}
