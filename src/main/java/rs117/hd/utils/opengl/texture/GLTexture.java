package rs117.hd.utils.opengl.texture;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.GLRenderState;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_BORDER_COLOR;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameterfv;
import static org.lwjgl.opengl.GL11.glTexSubImage2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE;
import static org.lwjgl.opengl.GL11C.glGetFloat;
import static org.lwjgl.opengl.GL11C.glTexParameterf;
import static org.lwjgl.opengl.GL12.glTexImage3D;
import static org.lwjgl.opengl.GL12.glTexSubImage3D;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL15.GL_READ_WRITE;
import static org.lwjgl.opengl.GL15.GL_STREAM_DRAW;
import static org.lwjgl.opengl.GL15.GL_WRITE_ONLY;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL15C.GL_READ_ONLY;
import static org.lwjgl.opengl.GL30.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL30.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindTexture;
import static org.lwjgl.opengl.GL30.glDeleteBuffers;
import static org.lwjgl.opengl.GL30.glDeleteTextures;
import static org.lwjgl.opengl.GL30.glGenTextures;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;
import static org.lwjgl.opengl.GL30.glMapBuffer;
import static org.lwjgl.opengl.GL30.glTexParameteri;
import static org.lwjgl.opengl.GL30.glUnmapBuffer;
import static org.lwjgl.opengl.GL30C.GL_TEXTURE_2D_ARRAY;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;


@Slf4j
public class GLTexture {
	private static float MAX_TEXTURE_MAX_ANISOTROPY = -1.0f;

	@Getter
	protected int id = -1;

	@Getter
	protected int width;

	@Getter
	protected int height;

	@Getter
	protected int depth;

	@Getter
	private boolean mipmapsDirty = false;

	@Getter
	protected final GLTextureFormat textureFormat;

	@Getter
	protected final GLTextureParams textureParams;

	private boolean internallyBinded = false;

	protected ByteBuffer mappedPBO;
	protected int pboId = 0;


	public GLTexture(int width, int height, GLTextureFormat textureFormat, GLTextureParams textureParams) {
		this(width, height, 1, textureFormat, textureParams);
	}

	public GLTexture(int width, int height, int depth, GLTextureFormat textureFormat, GLTextureParams textureParams) {
		this.width = width;
		this.height = height;
		this.depth = depth;
		this.textureFormat = textureFormat;
		this.textureParams = textureParams != null ? textureParams : new GLTextureParams();

		create();
	}

	public boolean isCreated() { return id != -1; }

	private GLTexture create() {
		if (isCreated()) throw new IllegalStateException("Texture already created");

		if(textureParams.textureUnit > 0) {
			assert textureParams.textureUnit >= GL_TEXTURE1;
			glActiveTexture(textureParams.textureUnit);
		}

		id = glGenTextures();

		bind(true);
		allocateTextureStorage();
		setupSamplerFiltering();

		if(textureParams.generateMipmaps && textureParams.anisotropySamples > 0) {
			if(MAX_TEXTURE_MAX_ANISOTROPY < 0) {
				MAX_TEXTURE_MAX_ANISOTROPY = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
			}
			glTexParameterf(GL_TEXTURE_2D_ARRAY, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, clamp(textureParams.anisotropySamples, 1, MAX_TEXTURE_MAX_ANISOTROPY));
		}

		if(textureParams.borderColor != null) {
			glTexParameterfv(textureParams.type.glTarget, GL_TEXTURE_BORDER_COLOR, textureParams.borderColor);
		}

		if (textureParams.generateMipmaps) {
			glGenerateMipmap(textureParams.type.glTarget);
		}

		if(textureParams.imageUnit >= 0 && HdPlugin.GL_CAPS.GL_ARB_shader_image_load_store) {
			boolean layered = textureParams.imageUnitLayer >= 0;
			int layer = layered ? textureParams.imageUnitLayer : 0;
			ARBShaderImageLoadStore.glBindImageTexture(textureParams.imageUnit, id, 0, layered, layer, textureParams.imageUnitWriteMode, textureFormat.internalFormat);
		}

		if(!textureParams.debugName.isEmpty())
			setDebugName(textureParams.debugName);

		unbind(true);

		checkGLErrors(() -> textureParams.debugName);

		return this;
	}

	private void allocateTextureStorage() {
		int mipLevels = textureParams.generateMipmaps ? (1 + (int) Math.floor(Math.log(Math.max(width, height)) / Math.log(2))) : 1;
		boolean supportStorage = depth > 1 ? HdPlugin.GL_CAPS.glTexStorage3D != 0 : HdPlugin.GL_CAPS.glTexStorage2D != 0;
		if (supportStorage && textureParams.immutable) {
			if (depth > 1) {
				ARBTextureStorage.glTexStorage3D(textureParams.type.glTarget, mipLevels, textureFormat.internalFormat, width, height, depth);
			} else {
				ARBTextureStorage.glTexStorage2D(textureParams.type.glTarget, mipLevels, textureFormat.internalFormat, width, height);
			}
		} else {
			for (int mip = 0; mip < mipLevels; mip++) {
				int mipWidth = Math.max(1, width >> mip);
				int mipHeight = Math.max(1, height >> mip);

				if (depth > 1) {
					glTexImage3D(textureParams.type.glTarget, mip, textureFormat.internalFormat, mipWidth, mipHeight, depth, 0, textureFormat.format, textureFormat.type, 0);
				} else {
					glTexImage2D(textureParams.type.glTarget, mip, textureFormat.internalFormat, mipWidth, mipHeight, 0, textureFormat.format, textureFormat.type, 0);
				}
			}
		}
		checkGLErrors(() -> textureParams.debugName);
	}

	private void setupSamplerFiltering() {
		if(textureParams.generateMipmaps) {
			glTexParameteri(textureParams.type.glTarget, GL_TEXTURE_MIN_FILTER, textureParams.sampler.minFilterMip);
		} else {
			glTexParameteri(textureParams.type.glTarget, GL_TEXTURE_MIN_FILTER, textureParams.sampler.minFilter);
		}
		glTexParameteri(textureParams.type.glTarget, GL_TEXTURE_MAG_FILTER, textureParams.sampler.magFilter);
		glTexParameteri(textureParams.type.glTarget, GL_TEXTURE_WRAP_S, textureParams.sampler.wrapS);
		glTexParameteri(textureParams.type.glTarget, GL_TEXTURE_WRAP_T, textureParams.sampler.wrapT);
	}

	public GLTexture setDebugName(String debugName) {
		if (HdPlugin.GL_CAPS.OpenGL43 && !debugName.isEmpty()) {
			textureParams.debugName = debugName;
			GL43C.glObjectLabel(GL_TEXTURE, id, debugName);
			checkGLErrors();
		}
		return this;
	}

	public void setSampler(GLSamplerMode sampler) {
		if(textureParams.sampler != sampler) {
			textureParams.sampler = sampler;
			bind(true);
			setupSamplerFiltering();
			unbind(true);
		}
	}

	public void resize(int width, int height) {
		if (!isCreated()) throw new IllegalStateException("Texture not created");
		resize(width, height, 1);
	}

	public void resize(int width, int height, int depth) {
		if (!isCreated()) throw new IllegalStateException("Texture not created");

		if(this.width == width && this.width == height && this.depth == depth){
			return;
		}

		this.width = width;
		this.height = height;
		this.depth = depth;

		if(textureParams.immutable) {
			delete();
			create();
			return;
		}

		bind(true);
		allocateTextureStorage();

		if(pboId != 0) {
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pboId);
			glBufferData(GL_PIXEL_UNPACK_BUFFER, (long) width * height * depth * textureFormat.pixelSize, GL_STREAM_DRAW);
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		}

		if (textureParams.generateMipmaps) {
			glGenerateMipmap(textureParams.type.glTarget);
		}

		checkGLErrors(() -> textureParams.debugName);

		unbind(true);
	}

	public boolean bind() { return bind(false); }

	private boolean bind(boolean internal) {
		if (!isCreated())
			throw new IllegalStateException("Texture not created");
		if(!GLRenderState.texture.isActive(this)) {
			glBindTexture(textureParams.type.glTarget, id);
			internallyBinded = internal;
			GLRenderState.texture.push(this);
			return true;
		}
		return false;
	}

	public void unbind() { unbind(false); }

	public void unbind(boolean internal) {
		if(GLRenderState.texture.isActive(this)) {
			if(internal && !internallyBinded) {
				return; // Texture was bound outside of class, so should be left alone
			}
			internallyBinded = false;

			if(textureParams.textureUnit > 0) {
				glActiveTexture(GL_TEXTURE0); // Switch back to GL_Texture0 before unbinding to avoid clearing a texture Unity accidentally
			}

			glBindTexture(textureParams.type.glTarget, 0);
			GLRenderState.texture.pop();
		}
	}

	public ByteBuffer map(int access) {
		if(pboId == 0) {
			pboId = glGenBuffers();
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pboId);
			glBufferData(GL_PIXEL_UNPACK_BUFFER, (long) width * height * depth * textureFormat.pixelSize, GL_STREAM_DRAW);
		} else {
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pboId);
		}
		mappedPBO = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, access, mappedPBO);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		checkGLErrors(() -> String.format("%s Access: %s", textureParams.debugName,
				access == GL_READ_ONLY ? "GL_READ_ONLY" :
				access == GL_WRITE_ONLY ? "GL_WRITE_ONLY" :
				access == GL_READ_WRITE ? "GL_READ_WRITE" : "UNKNOWN"));

		return mappedPBO;
	}

	public void unmap(int xOffset, int yOffset, int width, int height) {
		unmap(xOffset, yOffset, 0, width, height, 1);
	}

	public void unmap(int xOffset, int yOffset, int zOffset, int width, int height, int depth) {
		if(pboId != 0) {
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pboId);
			glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);

			bind(true);

			if(this.depth > 1) {
				glTexSubImage3D(textureParams.type.glTarget, 0, xOffset, yOffset, zOffset, width, height, depth, textureFormat.format, textureFormat.type, 0);
			} else {
				glTexSubImage2D(textureParams.type.glTarget, 0, xOffset, yOffset, width, height, textureFormat.format, textureFormat.type, 0);
			}

			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

			if (textureParams.generateMipmaps) {
				glGenerateMipmap(textureParams.type.glTarget);
			}

			unbind(true);
			checkGLErrors(() -> String.format("%s (%dx%dx%d %dx%dx%d)", textureParams.debugName, xOffset, yOffset, zOffset, width, height, depth));
		}
	}

	public void delete() {
		if (isCreated()) {
			glDeleteTextures(id);
			id = -1;
		}
		if (pboId != 0) {
			glDeleteBuffers(pboId);
			pboId = 0;
		}
	}

	public void uploadSubPixels2D(int width, int height, Buffer pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(0, 0, 0, width, height, 0, pixelData, pixelFormat);
	}

	public void uploadSubPixels2D(int xOffset, int yOffset, int width, int height, Buffer pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(xOffset, yOffset, 0, width, height, 0, pixelData, pixelFormat);
	}

	public void uploadSubPixels3D(int zOffset, int width, int height, Buffer pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(0, 0, zOffset, width, height, 1, pixelData, pixelFormat);
	}

	public void uploadSubPixels3D(int zOffset, int width, int height, int depth, Buffer pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(0, 0, zOffset, width, height, depth, pixelData, pixelFormat);
	}

	public void uploadSubPixels(int width, int height, int depth, Buffer pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(0, 0, 0, width, height, depth, pixelData, pixelFormat);
	}

	public void uploadSubPixels(int xOffset, int yOffset, int zOffset, int width, int height, int depth, Buffer pixelData, GLTextureFormat pixelFormat) {
		if (xOffset + width > this.width || yOffset + height > this.height || zOffset + depth > this.depth)
			throw new IllegalArgumentException("Upload region exceeds texture dimensions");

		bind(true);

		if (this.depth > 1) {
			if (pixelData instanceof ByteBuffer) {
				glTexSubImage3D(textureParams.type.glTarget, 0,
					xOffset, yOffset, zOffset,
					width, height, depth,
					pixelFormat.internalFormat, pixelFormat.type, (ByteBuffer)pixelData);
			} else if (pixelData instanceof ShortBuffer) {
				glTexSubImage3D(textureParams.type.glTarget, 0,
					xOffset, yOffset, zOffset,
					width, height, depth,
					pixelFormat.internalFormat, pixelFormat.type, (ShortBuffer)pixelData);
			} else if (pixelData instanceof IntBuffer) {
				glTexSubImage3D(textureParams.type.glTarget, 0,
					xOffset, yOffset, zOffset,
					width, height, depth,
					pixelFormat.internalFormat, pixelFormat.type, (IntBuffer)pixelData);
			} else if (pixelData instanceof FloatBuffer) {
				glTexSubImage3D(textureParams.type.glTarget, 0,
					xOffset, yOffset, zOffset,
					width, height, depth,
					pixelFormat.internalFormat, pixelFormat.type, (FloatBuffer)pixelData);
			} else {
				throw new IllegalArgumentException("Unsupported buffer type for glTexSubImage3D: " + pixelData.getClass());
			}
		} else {
			if (pixelData instanceof ByteBuffer) {
				glTexSubImage2D(textureParams.type.glTarget, 0,
					xOffset, yOffset,
					width, height,
					pixelFormat.internalFormat, pixelFormat.type, (ByteBuffer)pixelData);
			} else if (pixelData instanceof ShortBuffer) {
				glTexSubImage2D(textureParams.type.glTarget, 0,
					xOffset, yOffset,
					width, height,
					pixelFormat.internalFormat, pixelFormat.type, (ShortBuffer)pixelData);
			} else if (pixelData instanceof IntBuffer) {
				glTexSubImage2D(textureParams.type.glTarget, 0,
					xOffset, yOffset,
					width, height,
					pixelFormat.internalFormat, pixelFormat.type, (IntBuffer)pixelData);
			} else if (pixelData instanceof FloatBuffer) {
				glTexSubImage2D(textureParams.type.glTarget, 0,
					xOffset, yOffset,
					width, height,
					pixelFormat.internalFormat, pixelFormat.type, (FloatBuffer)pixelData);
			} else {
				throw new IllegalArgumentException("Unsupported buffer type for glTexSubImage2D: " + pixelData.getClass());
			}
		}

		unbind(true);
		checkGLErrors(() -> String.format("%s (%dx%dx%d %dx%dx%d)", textureParams.debugName, xOffset, yOffset, zOffset, width, height, depth));

		mipmapsDirty = true;
	}

	public void generateMipMaps() {
		if (textureParams.generateMipmaps && mipmapsDirty) {
			bind(true);
			glGenerateMipmap(textureParams.type.glTarget);
			unbind(true);
			checkGLErrors(() -> textureParams.debugName);
			mipmapsDirty = false;
		}
	}
}