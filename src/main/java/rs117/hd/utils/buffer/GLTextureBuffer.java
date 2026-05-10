package rs117.hd.utils.buffer;

import lombok.Getter;
import rs117.hd.HdPlugin;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.GL_CAPS;

public class GLTextureBuffer extends GLBuffer {

	public static boolean isRGBASupported() { return HdPlugin.GL_CAPS.GL_ARB_texture_buffer_object; }

	@Getter
	private int texId;

	private final int texelSize;
	private final int internalFormat;

	public GLTextureBuffer(String name, int usage) {
		this(name, usage, 0);
	}

	public GLTextureBuffer(String name, int usage, int storageFlags) {
		super(name, GL_TEXTURE_BUFFER, usage, storageFlags);

		if(isRGBASupported()) {
			texelSize = 4;
			internalFormat = GL_RGBA32I;
		} else {
			texelSize = 3;
			internalFormat = GL_RGB32I;
		}
	}

	@Override
	public GLTextureBuffer initialize(long initialCapacity) {
		super.initialize(initialCapacity);

		texId = glGenTextures();
		glBindTexture(target, texId);
		glTexBuffer(target, internalFormat, id);

		glBindTexture(target, 0);
		return this;
	}

	@Override
	public boolean ensureCapacity(long byteOffset, long numBytes) {
		// Ensure offset and size are aligned to texels
		byteOffset = alignToTexel(byteOffset);
		numBytes = alignToTexel(numBytes);

		int oldId = id;
		final boolean resized = super.ensureCapacity(byteOffset, numBytes);

		if (oldId != id) {
			glBindTexture(target, texId);
			glTexBuffer(target, internalFormat, id);
			glBindTexture(target, 0);
		}

		return resized;
	}

	private long alignToTexel(long numBytes) {
		final int texelBytes = texelSize * 4;
		final long remainder = numBytes % texelBytes;
		if (remainder == 0)
			return numBytes;

		return numBytes + (texelBytes - remainder);
	}

	@Override
	public void destroy() {
		if (texId != 0)
			glDeleteTextures(texId);
		texId = 0;

		super.destroy();
	}
}
