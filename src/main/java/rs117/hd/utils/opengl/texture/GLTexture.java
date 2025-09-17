package rs117.hd.utils.opengl.texture;

import java.nio.ByteBuffer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.HdPlugin;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_BORDER_COLOR;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameterfv;
import static org.lwjgl.opengl.GL11.glTexSubImage2D;
import static org.lwjgl.opengl.GL12.glTexImage3D;
import static org.lwjgl.opengl.GL12.glTexSubImage3D;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL15.GL_STREAM_DRAW;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL30.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glBindTexture;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glDeleteBuffers;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glDeleteTextures;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL30.glGenTextures;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;
import static org.lwjgl.opengl.GL30.glMapBuffer;
import static org.lwjgl.opengl.GL30.glTexParameteri;
import static org.lwjgl.opengl.GL30.glUnmapBuffer;
import static rs117.hd.HdPlugin.checkGLErrors;


@Slf4j
public class GLTexture {
	@Getter
	protected int id = -1; // delayed creation, so init to -1

	@Getter
	protected int width;

	@Getter
	protected int height;

	@Getter
	protected int depth;

	@Getter
	protected final GLTextureFormat textureFormat;

	@Getter
	protected final GLTextureParams textureParams;

	protected boolean created = false;

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

	private GLTexture create() {
		if (created) throw new IllegalStateException("Texture already created");

		if(textureParams.textureUnit > 0) {
			assert textureParams.textureUnit >= GL_TEXTURE0;
			glActiveTexture(textureParams.textureUnit);
		}

		id = glGenTextures();
		glBindTexture(textureParams.type.glTarget, id);

		if(depth > 1) {
			glTexImage3D(textureParams.type.glTarget, 0, textureFormat.internalFormat, width, height, depth, 0, textureFormat.format, textureFormat.type, 0);
		} else {
			glTexImage2D(textureParams.type.glTarget, 0, textureFormat.internalFormat, width, height, 0, textureFormat.format, textureFormat.type, 0);
		}

		glTexParameteri(textureParams.type.glTarget, GL_TEXTURE_MIN_FILTER, textureParams.sampler.minFilter);
		glTexParameteri(textureParams.type.glTarget, GL_TEXTURE_MAG_FILTER, textureParams.sampler.magFilter);
		glTexParameteri(textureParams.type.glTarget, GL_TEXTURE_WRAP_S, textureParams.sampler.wrapS);
		glTexParameteri(textureParams.type.glTarget, GL_TEXTURE_WRAP_T, textureParams.sampler.wrapT);

		if(textureParams.borderColor != null) {
			glTexParameterfv(textureParams.type.glTarget, GL_TEXTURE_BORDER_COLOR, textureParams.borderColor);
		}

		if (textureParams.generateMipmaps) {
			glGenerateMipmap(textureParams.type.glTarget);
		}

		if(textureParams.imageUnit >= 0 && HdPlugin.GL_CAPS.GL_ARB_shader_image_load_store)
			ARBShaderImageLoadStore.glBindImageTexture(textureParams.imageUnit, id, 0, false, 0, textureParams.imageUnitWriteMode, textureFormat.internalFormat);

		checkGLErrors();
		created = true;

		return this;
	}

	public void setSampler(GLSamplerMode sampler) {
		if(textureParams.sampler == sampler) return;

		textureParams.sampler = sampler;

		glTexParameteri(textureParams.type.glTarget, GL_TEXTURE_MIN_FILTER, textureParams.sampler.minFilter);
		glTexParameteri(textureParams.type.glTarget, GL_TEXTURE_MAG_FILTER, textureParams.sampler.magFilter);
		glTexParameteri(textureParams.type.glTarget, GL_TEXTURE_WRAP_S, textureParams.sampler.wrapS);
		glTexParameteri(textureParams.type.glTarget, GL_TEXTURE_WRAP_T, textureParams.sampler.wrapT);
	}

	public void resize(int width, int height) {
		if (!created) throw new IllegalStateException("Texture not created");
		this.width = width;
		this.height = height;

		bind();

		if(depth > 1) {
			glTexImage3D(textureParams.type.glTarget, 0, textureFormat.internalFormat, width, height, depth, 0, textureFormat.format, textureFormat.type, 0);
		} else {
			glTexImage2D(textureParams.type.glTarget, 0, textureFormat.internalFormat, width, height, 0, textureFormat.format, textureFormat.type, 0);
		}

		if (textureParams.generateMipmaps) {
			glGenerateMipmap(textureParams.type.glTarget);
		}
	}

	public void bind() {
		if (!created) throw new IllegalStateException("Texture not created");
		glBindTexture(textureParams.type.glTarget, id);
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
		checkGLErrors();

		return mappedPBO;
	}

	public void unmap(int xOffset, int yOffset, int width, int height) {
		unmap(xOffset, yOffset, 0, width, height, 1);
	}

	public void unmap(int xOffset, int yOffset, int zOffset, int width, int height, int depth) {
		if(pboId != 0) {
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pboId);
			glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);

			bind();

			if(depth > 1) {
				glTexSubImage3D(textureParams.type.glTarget, 0, xOffset, yOffset, zOffset, width, height, depth, textureFormat.internalFormat, textureFormat.type, 0);
			} else {
				glTexSubImage2D(textureParams.type.glTarget, 0, xOffset, yOffset, width, height, textureFormat.internalFormat, textureFormat.type, 0);
			}

			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		}

		if (textureParams.generateMipmaps) {
			glGenerateMipmap(textureParams.type.glTarget);
		}

		checkGLErrors();
	}

	public void delete() {
		if (created) {
			glDeleteTextures(id);
			id = -1;
			created = false;
		}
		if (pboId != 0) {
			glDeleteBuffers(pboId);
			pboId = 0;
		}
	}

	public ByteBuffer readPixels() {
		int fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, textureParams.type.glTarget, id, 0);

		if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
			throw new RuntimeException("FBO incomplete for readPixels");
		}

		ByteBuffer buffer = MemoryUtil.memAlloc(width * height * textureFormat.pixelSize);
		glReadPixels(0, 0, width, height, textureFormat.format, textureFormat.type, buffer);

		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		glDeleteFramebuffers(fbo);

		return buffer;
	}

	public void uploadPixels(ByteBuffer data) {
		bind();
		glTexSubImage2D(textureParams.type.glTarget, 0, 0, 0, width, height, textureFormat.format, textureFormat.type, data);
		if (textureParams.generateMipmaps) {
			glGenerateMipmap(textureParams.type.glTarget);
		}
	}

	public void uploadPixels(int[] pixels, int width, int height) {
		bind();
		glTexSubImage2D(textureParams.type.glTarget, 0, 0, 0, width, height, textureFormat.format, textureFormat.type, pixels);
		if (textureParams.generateMipmaps) {
			glGenerateMipmap(textureParams.type.glTarget);
		}
	}
}