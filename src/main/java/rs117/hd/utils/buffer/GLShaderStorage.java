package rs117.hd.utils.buffer;

import lombok.Getter;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.SUPPORTS_SHADER_STORAGE;

public class GLShaderStorage extends GLBuffer {
	public static boolean isRGBASupported() {
		return GL_CAPS.GL_ARB_texture_buffer_object;
	}

	private final boolean shaderStorage = SUPPORTS_SHADER_STORAGE;
	private final int internalFormat;

	@Getter
	private int texId;

	@Getter
	private int bindingIndex;
	@Getter
	private int textureUnit;

	public GLShaderStorage(String name, int usage) {
		this(name, usage, 0);
	}

	public GLShaderStorage(String name, int usage, int storageFlags) {
		super(name, SUPPORTS_SHADER_STORAGE ? GL_SHADER_STORAGE_BUFFER : GL_TEXTURE_BUFFER, usage, storageFlags);

		internalFormat = isRGBASupported() ? GL_RGBA32I : GL_RGB32I;
	}

	/** Associates the binding point & texture unit shaders read this buffer through, whether storage buffer or texture buffer. */
	public GLShaderStorage initialize(long initialCapacity, int bindingIndex, int textureUnit) {
		this.bindingIndex = bindingIndex;
		this.textureUnit = textureUnit;

		return initialize(initialCapacity);
	}

	@Override
	public GLShaderStorage initialize(long initialCapacity) {
		super.initialize(initialCapacity);

		if (shaderStorage)
			return this;

		texId = glGenTextures();
		glBindTexture(target, texId);
		glTexBuffer(target, internalFormat, id);
		glBindTexture(target, 0);

		return this;
	}

	@Override
	public boolean ensureCapacity(long byteOffset, long numBytes) {
		int oldId = id;
		final boolean resized = super.ensureCapacity(byteOffset, numBytes);

		if (!shaderStorage && oldId != id) {
			glBindTexture(target, texId);
			glTexBuffer(target, internalFormat, id);
			glBindTexture(target, 0);
		}

		return resized;
	}

	@Override
	public void destroy() {
		if (texId != 0)
			glDeleteTextures(texId);
		texId = 0;

		super.destroy();
	}
}
