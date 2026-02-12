package rs117.hd.utils.buffer;

import lombok.Getter;

import static org.lwjgl.opengl.GL33C.*;

public class GLTextureBuffer extends GLBuffer {
	@Getter
	private int texId;

	public GLTextureBuffer(String name, int usage) {
		super(name, GL_TEXTURE_BUFFER, usage, 0);
	}

	public GLTextureBuffer(String name, int usage, int storageFlags) {
		super(name, GL_TEXTURE_BUFFER, usage, storageFlags);
	}

	@Override
	public GLTextureBuffer initialize(long initialCapacity) {
		super.initialize(initialCapacity);

		// Create texture
		texId = glGenTextures();
		glBindTexture(target, texId);

		// RGB32 signed integer texture buffer
		glTexBuffer(target, GL_RGB32I, id);

		glBindTexture(target, 0);
		return this;
	}

	@Override
	public boolean ensureCapacity(long byteOffset, long numBytes) {
		int oldId = id;
		boolean resized = super.ensureCapacity(byteOffset, numBytes);
		if (oldId != id) {
			glBindTexture(target, texId);
			glTexBuffer(target, GL_RGB32I, id);
			glBindTexture(target, 0);
		}
		return resized;
	}

	@Override
	public void destroy() {
		if (texId != 0) {
			glDeleteTextures(texId);
			texId = 0;
		}

		super.destroy();
	}
}
