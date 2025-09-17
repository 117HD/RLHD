package rs117.hd.utils.opengl.texture;

import lombok.RequiredArgsConstructor;

import static org.lwjgl.opengl.GL11.GL_INT;
import static org.lwjgl.opengl.GL11.GL_SHORT;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL12C.GL_BGRA;
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
	RGBA8(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, 4),
	RGBA16F(GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT, 8),
	RGBA16I(GL_RGBA16I, GL_RGBA_INTEGER, GL_SHORT, 8),
	RGBA32F(GL_RGBA32F, GL_RGBA, GL_FLOAT, 16),
	RGBA32I(GL_RGBA32I, GL_RGBA_INTEGER, GL_INT, 16),
	RGB8(GL_RGB8, GL_RGB, GL_UNSIGNED_BYTE, 2),
	R8(GL_R8, GL_RED, GL_UNSIGNED_BYTE, 1),
	R16F(GL_R16F, GL_RED, GL_HALF_FLOAT, 2),
	R16I(GL_R16I, GL_RED_INTEGER, GL_SHORT, 2),
	R32F(GL_R32F, GL_RED, GL_FLOAT, 4),
	R32I(GL_R32I, GL_RED_INTEGER, GL_INT, 4),

	BGRA(GL_BGRA, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 4),

	DEPTH24(GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, 4),
	DEPTH32F(GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT, GL_FLOAT, 4);

	public final int internalFormat;
	public final int format;
	public final int type;
	public final int pixelSize;
}
