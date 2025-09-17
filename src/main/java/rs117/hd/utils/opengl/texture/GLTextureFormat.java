package rs117.hd.utils.opengl.texture;

import lombok.RequiredArgsConstructor;

import static org.lwjgl.opengl.GL11.GL_INT;
import static org.lwjgl.opengl.GL11.GL_SHORT;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL12C.GL_BGRA;
import static org.lwjgl.opengl.GL21.GL_SRGB8;
import static org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8;
import static org.lwjgl.opengl.GL30.GL_R16F;
import static org.lwjgl.opengl.GL30.GL_R16I;
import static org.lwjgl.opengl.GL30.GL_R32I;
import static org.lwjgl.opengl.GL30.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL30.GL_RGBA16I;
import static org.lwjgl.opengl.GL30.GL_RGBA_INTEGER;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_COMPONENT32F;
import static org.lwjgl.opengl.GL30C.GL_FLOAT;
import static org.lwjgl.opengl.GL30C.GL_HALF_FLOAT;
import static org.lwjgl.opengl.GL30C.GL_R32F;
import static org.lwjgl.opengl.GL30C.GL_R8;
import static org.lwjgl.opengl.GL30C.GL_RED;
import static org.lwjgl.opengl.GL30C.GL_RGB;
import static org.lwjgl.opengl.GL30C.GL_RGB8;
import static org.lwjgl.opengl.GL30C.GL_RGBA;
import static org.lwjgl.opengl.GL30C.GL_RGBA16F;
import static org.lwjgl.opengl.GL30C.GL_RGBA32F;
import static org.lwjgl.opengl.GL30C.GL_RGBA32I;
import static org.lwjgl.opengl.GL30C.GL_RGBA8;
import static org.lwjgl.opengl.GL30C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL30C.GL_UNSIGNED_INT;

@RequiredArgsConstructor
public enum GLTextureFormat {
	RGBA8(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, 4, 8, 8, 8, 8),
	RGBA16F(GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT, 8, 16, 16, 16, 16),
	RGBA16I(GL_RGBA16I, GL_RGBA_INTEGER, GL_SHORT, 8, 16, 16, 16, 16),
	RGBA32F(GL_RGBA32F, GL_RGBA, GL_FLOAT, 16, 32, 32, 32, 32),
	RGBA32I(GL_RGBA32I, GL_RGBA_INTEGER, GL_INT, 16, 32, 32, 32, 32),

	RGB8(GL_RGB8, GL_RGB, GL_UNSIGNED_BYTE, 3, 8, 8, 8, 0),
	RGB(GL_RGB, GL_RGB, GL_UNSIGNED_BYTE, 3, 8, 8, 8, 0),
	SRGB8(GL_SRGB8, GL_RGB, GL_UNSIGNED_BYTE, 3, 8, 8, 8, 0),

	SRGB8_ALPHA8(GL_SRGB8_ALPHA8, GL_RGBA, GL_UNSIGNED_BYTE, 4, 8, 8, 8, 8),
	RGBA(GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE, 4, 8, 8, 8, 8),

	R8(GL_R8, GL_RED, GL_UNSIGNED_BYTE, 1, 8, 0, 0, 0),
	R16F(GL_R16F, GL_RED, GL_HALF_FLOAT, 2, 16, 0, 0, 0),
	R16I(GL_R16I, GL_RED_INTEGER, GL_SHORT, 2, 16, 0, 0, 0),
	R32F(GL_R32F, GL_RED, GL_FLOAT, 4, 32, 0, 0, 0),
	R32I(GL_R32I, GL_RED_INTEGER, GL_INT, 4, 32, 0, 0, 0),

	BGRA(GL_BGRA, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 4, 8, 8, 8, 8),

	DEPTH24(GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, 4, 0, 0, 0, 0),
	DEPTH32F(GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT, GL_FLOAT, 4, 0, 0, 0, 0);

	public final int internalFormat;
	public final int format;
	public final int type;
	public final int pixelSize;
	public final int redSize;
	public final int greenSize;
	public final int blueSize;
	public final int alphaSize;

	public boolean isDepth() {
		switch (internalFormat) {
			case GL_DEPTH_COMPONENT24:
			case GL_DEPTH_COMPONENT32F:
				return true;
			default:
				return false;
		}
	}

	public boolean isSRGB() {
		switch (internalFormat) {
			case GL_SRGB8:
			case GL_SRGB8_ALPHA8:
				return true;
			default:
				return false;
		}
	}

	public boolean hasAlpha() {
		switch (internalFormat) {
			case GL_RGBA8:
			case GL_RGBA16F:
			case GL_RGBA16I:
			case GL_RGBA32F:
			case GL_RGBA32I:
			case GL_BGRA:
			case GL_SRGB8_ALPHA8:
			case GL_RGBA:
				return true;
			default:
				return false;
		}
	}

	public static GLTextureFormat fromInternalFormat(int internalFormat) {
		for (GLTextureFormat fmt : values()) {
			if (fmt.internalFormat == internalFormat) {
				return fmt;
			}
		}
		return null;
	}

	public static GLTextureFormat fromComponentSizes(int r, int g, int b, int a, boolean isSRGB) {
		for (GLTextureFormat fmt : values()) {
			if (fmt.isSRGB() == isSRGB &&
				fmt.redSize == r &&
				fmt.greenSize == g &&
				fmt.blueSize == b &&
				fmt.alphaSize == a) {
				return fmt;
			}
		}
		return null;
	}
}
