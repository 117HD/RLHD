package rs117.hd.utils.texture;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.utils.Destructible;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLMappedBuffer;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.jobs.Job;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_BORDER_COLOR;
import static org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameterfv;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE;
import static org.lwjgl.opengl.GL11C.glGetFloat;
import static org.lwjgl.opengl.GL11C.glTexParameterf;
import static org.lwjgl.opengl.GL11C.glTexSubImage2D;
import static org.lwjgl.opengl.GL12.glTexImage3D;
import static org.lwjgl.opengl.GL12.glTexSubImage3D;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL30.glBindTexture;
import static org.lwjgl.opengl.GL30.glDeleteTextures;
import static org.lwjgl.opengl.GL30.glGenTextures;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;
import static org.lwjgl.opengl.GL30.glTexParameteri;
import static org.lwjgl.opengl.GL30C.GL_TEXTURE_2D_ARRAY;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_IMMUTABLE;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_PERSISTENT;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_WRITE;


@Slf4j
public class GLTexture implements Destructible {
	private static int UNPACK_ALIGNMENT = -1;
	private static float MAX_TEXTURE_MAX_ANISOTROPY = -1.0f;

	@Getter
	protected int id = 0;

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

	private PixelUploadJob uploadJob;
	private int pboIdx = 0;
	private GLBuffer[] unpackBuffers;

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

	public boolean isCreated() { return id != 0; }

	private GLTexture create() {
		if (isCreated()) throw new IllegalStateException("Texture already created");

		if(textureParams.textureUnit > 0) {
			assert textureParams.textureUnit >= GL_TEXTURE1;
			glActiveTexture(textureParams.textureUnit);
		}

		id = glGenTextures();

		bind();
		allocateTextureStorage();
		setupSamplerFiltering();

		if(textureParams.borderColor != null)
			glTexParameterfv(textureParams.type.glTarget, GL_TEXTURE_BORDER_COLOR, textureParams.borderColor);

		if (textureParams.generateMipmaps)
			glGenerateMipmap(textureParams.type.glTarget);

		if(textureParams.imageUnit >= 0 && HdPlugin.GL_CAPS.GL_ARB_shader_image_load_store) {
			boolean layered = textureParams.imageUnitLayer >= 0;
			int layer = layered ? textureParams.imageUnitLayer : 0;
			ARBShaderImageLoadStore.glBindImageTexture(textureParams.imageUnit, id, 0, layered, layer, textureParams.imageUnitWriteMode, textureFormat.internalFormat);
		}

		if(!textureParams.debugName.isEmpty())
			setDebugName(textureParams.debugName);

		if(textureParams.textureUnit > 0)
			glActiveTexture(GL_TEXTURE0);

		unbind();
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
		if (textureParams.generateMipmaps && textureParams.anisotropySamples > 0) {
			if (MAX_TEXTURE_MAX_ANISOTROPY < 0) {
				MAX_TEXTURE_MAX_ANISOTROPY = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
			}
			glTexParameterf(
				GL_TEXTURE_2D_ARRAY,
				EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT,
				clamp(textureParams.anisotropySamples, 1, MAX_TEXTURE_MAX_ANISOTROPY)
			);
		}

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
			bind();
			setupSamplerFiltering();
			unbind();
		}
	}

	public void setAnisotropySamples(int samples) {
		if(textureParams.anisotropySamples != samples) {
			textureParams.anisotropySamples = samples;
			bind();
			setupSamplerFiltering();
			unbind();
		}
	}

	public int[] getSize() {
		if(depth > 1)
			return new int[] { width, height, depth };
		return new int[] { width, height };
	}

	public void resize(int width, int height) {
		if (!isCreated()) throw new IllegalStateException("Texture not created");
		resize(width, height, 1);
	}

	public boolean resize(int width, int height, int depth) {
		if (!isCreated()) throw new IllegalStateException("Texture not created");

		if(this.width == width && this.height == height && this.depth == depth)
			return false;

		this.width = width;
		this.height = height;
		this.depth = depth;

		if(textureParams.immutable) {
			destroy();
			create();
			return true;
		}

		bind();
		allocateTextureStorage();

		if(unpackBuffers != null) {
			for(int i = 0; i < unpackBuffers.length; i++)
				unpackBuffers[i].ensureCapacity((long) width * height * depth * textureFormat.channels);
		}

		if (textureParams.generateMipmaps)
			glGenerateMipmap(textureParams.type.glTarget);

		checkGLErrors(() -> textureParams.debugName);

		unbind();
		return true;
	}

	public boolean bind() {
		if (!isCreated())
			throw new IllegalStateException("Texture not created");
		glBindTexture(textureParams.type.glTarget, id);
		return false;
	}

	public void unbind() { glBindTexture(textureParams.type.glTarget, 0); }

	public GLMappedBuffer map(int flags) {
		if(unpackBuffers == null) {
			int pboCount = textureParams.pboCount > 0 ? textureParams.pboCount : 1;
			unpackBuffers = new GLBuffer[pboCount];
			for (int i = 0; i < pboCount; i++){
				unpackBuffers[i] = new GLBuffer(textureParams.debugName + "::PBO",
					GL21C.GL_PIXEL_UNPACK_BUFFER,
					GL15C.GL_STREAM_DRAW,
					STORAGE_PERSISTENT | STORAGE_IMMUTABLE | STORAGE_WRITE);
				unpackBuffers[i].initialize((long) width * height * depth * textureFormat.channels);
			}

			if(UNPACK_ALIGNMENT == -1)
				UNPACK_ALIGNMENT = glGetInteger(GL_UNPACK_ALIGNMENT);
		}

		pboIdx++;
		if(pboIdx >= unpackBuffers.length)
			pboIdx = 0;

		bind();
		unpackBuffers[pboIdx].map(flags);
		unbind();

		return unpackBuffers[pboIdx].mapped();
	}

	public void unmap() {
		unmap(width, height);
	}

	public void unmap(int width, int height) {
		unmap(0, 0, 0, width, height, 1);
	}

	public void unmap(int xOffset, int yOffset, int width, int height) {
		unmap(xOffset, yOffset, 0, width, height, 1);
	}

	public void unmap(int xOffset, int yOffset, int zOffset, int width, int height, int depth) { unmap(xOffset, yOffset, zOffset, width, height, depth, textureFormat); }

	public void unmap(int xOffset, int yOffset, int zOffset, int width, int height, int depth, GLTextureFormat pixelFormat) {
		if(unpackBuffers == null)
			return;

		final GLBuffer unpackBuffer = unpackBuffers[pboIdx];
		if(!unpackBuffer.isMapped())
			return;

		bind();
		unpackBuffer.unmap();
		unpackBuffer.bind();

		if(this.depth > 1) {
			glTexSubImage3D(textureParams.type.glTarget, 0, xOffset, yOffset, zOffset, width, height, depth, pixelFormat.format, pixelFormat.type, 0);
		} else {
			glTexSubImage2D(textureParams.type.glTarget, 0, xOffset, yOffset, width, height, pixelFormat.format, pixelFormat.type, 0);
		}

		unpackBuffer.unbind();
		if (textureParams.generateMipmaps)
			glGenerateMipmap(textureParams.type.glTarget);

		unbind();
		checkGLErrors(() -> String.format("%s (%dx%dx%d %dx%dx%d)", textureParams.debugName, xOffset, yOffset, zOffset, width, height, depth));
	}

	@Override
	protected void finalize()  {
		if(id == 0)
			return;

		DestructibleHandler.queueLeakedDestruction(this);
	}

	@Override
	public void destroy() {
		if(uploadJob != null)
			uploadJob.waitForCompletion();
		uploadJob = null;

		if (isCreated())
			glDeleteTextures(id);
		id = 0;

		if (unpackBuffers != null) {
			for(int i = 0; i < unpackBuffers.length; i++)
				unpackBuffers[i].destroy();
		}
		unpackBuffers = null;
	}

	public Job uploadSubPixelsAsync2D(int width, int height, byte[] pixelData, GLTextureFormat pixelFormat) {
		return uploadSubPixelsAsync(0, 0, 0, width, height, 0, pixelFormat).setPixelsDataBytes(pixelData).queue();
	}

	public Job uploadSubPixelsAsync2D(int xOffset, int yOffset, int width, int height, byte[] pixelData, GLTextureFormat pixelFormat) {
		return uploadSubPixelsAsync(xOffset, yOffset, 0, width, height, 0, pixelFormat).setPixelsDataBytes(pixelData).queue();
	}

	public Job uploadSubPixelsAsync3D(int xOffset, int yOffset, int zOffset, int width, int height, int depth, byte[] pixelData, GLTextureFormat pixelFormat) {
		return uploadSubPixelsAsync(xOffset, yOffset, zOffset, width, height, depth, pixelFormat).setPixelsDataBytes(pixelData).queue();
	}

	public Job uploadSubPixelsAsync2D(int width, int height, int[] pixelData, GLTextureFormat pixelFormat) {
		return uploadSubPixelsAsync(0, 0, 0, width, height, 0, pixelFormat).setPixelsDataInts(pixelData).queue();
	}

	public Job uploadSubPixelsAsync2D(int xOffset, int yOffset, int width, int height, int[] pixelData, GLTextureFormat pixelFormat) {
		return uploadSubPixelsAsync(xOffset, yOffset, 0, width, height, 0, pixelFormat).setPixelsDataInts(pixelData).queue();
	}

	public Job uploadSubPixelsAsync3D(int xOffset, int yOffset, int zOffset, int width, int height, int depth, int[] pixelData, GLTextureFormat pixelFormat) {
		return uploadSubPixelsAsync(xOffset, yOffset, zOffset, width, height, depth, pixelFormat).setPixelsDataInts(pixelData).queue();
	}

	public Job uploadSubPixelsAsync2D(int width, int height, float[] pixelData, GLTextureFormat pixelFormat) {
		return uploadSubPixelsAsync(0, 0, 0, width, height, 1, pixelFormat).setPixelsDataFloats(pixelData).queue();
	}

	public Job uploadSubPixelsAsync2D(int xOffset, int yOffset, int width, int height, float[] pixelData, GLTextureFormat pixelFormat) {
		return uploadSubPixelsAsync(xOffset, yOffset, 0, width, height, 1, pixelFormat).setPixelsDataFloats(pixelData).queue();
	}

	public Job uploadSubPixelsAsync3D(int xOffset, int yOffset, int zOffset, int width, int height, int depth, float[] pixelData, GLTextureFormat pixelFormat) {
		return uploadSubPixelsAsync(xOffset, yOffset, zOffset, width, height, depth, pixelFormat).setPixelsDataFloats(pixelData).queue();
	}

	public Job uploadSubPixelsAsync2D(int width, int height, Buffer pixelData, GLTextureFormat pixelFormat) {
		return uploadSubPixelsAsync(0, 0, 0, width, height, 1, pixelFormat).setPixelData(pixelData).queue();
	}

	public Job uploadSubPixelsAsync3D(int xOffset, int yOffset, int width, int height, Buffer pixelData, GLTextureFormat pixelFormat) {
		return uploadSubPixelsAsync(xOffset, yOffset, 0, width, height, 1, pixelFormat).setPixelData(pixelData).queue();
	}

	private PixelUploadJob uploadSubPixelsAsync(int xOffset, int yOffset, int zOffset, int width, int height, int depth, GLTextureFormat pixelFormat) {
		map(GLBuffer.MAP_WRITE);
		uploadJob = PixelUploadJob.POOL.acquire();
		uploadJob.texture = this;
		uploadJob.xOffset = xOffset;
		uploadJob.yOffset = yOffset;
		uploadJob.zOffset = zOffset;
		uploadJob.width = width;
		uploadJob.height = height;
		uploadJob.depth = depth;
		uploadJob.pixelFormat = pixelFormat;
		uploadJob.pendingSubTextureUpload = true;
		return uploadJob;
	}

	public void completeUploadSubPixelsAsync() {
		if(uploadJob == null || !uploadJob.pendingSubTextureUpload)
			return;

		uploadJob.waitForCompletion();
		uploadJob.pendingSubTextureUpload = false;

		unmap(uploadJob.xOffset, uploadJob.yOffset, uploadJob.zOffset, uploadJob.width, uploadJob.height, uploadJob.depth, uploadJob.pixelFormat);

		PixelUploadJob.POOL.recycle(uploadJob);
		uploadJob = null;
	}

	public void uploadSubPixels2D(int width, int height, int[] pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(0, 0, 0, width, height, 0, pixelData, pixelFormat);
	}

	public void uploadSubPixels2D(int width, int height, float[] pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(0, 0, 0, width, height, 0, pixelData, pixelFormat);
	}

	public void uploadSubPixels2D(int width, int height, Buffer pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(0, 0, 0, width, height, 0, pixelData, pixelFormat);
	}

	public void uploadSubPixels2D(int xOffset, int yOffset, int width, int height, int[] pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(xOffset, yOffset, 0, width, height, 0, pixelData, pixelFormat);
	}

	public void uploadSubPixels2D(int xOffset, int yOffset, int width, int height, float[] pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(xOffset, yOffset, 0, width, height, 0, pixelData, pixelFormat);
	}

	public void uploadSubPixels2D(int xOffset, int yOffset, int width, int height, Buffer pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(xOffset, yOffset, 0, width, height, 0, pixelData, pixelFormat);
	}

	public void uploadSubPixels3D(int zOffset, int width, int height, int[] pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(0, 0, zOffset, width, height, 1, pixelData, pixelFormat);
	}

	public void uploadSubPixels3D(int zOffset, int width, int height, float[] pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(0, 0, zOffset, width, height, 1, pixelData, pixelFormat);
	}

	public void uploadSubPixels3D(int zOffset, int width, int height, Buffer pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(0, 0, zOffset, width, height, 1, pixelData, pixelFormat);
	}

	public void uploadSubPixels3D(int zOffset, int width, int height, int depth, int[] pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(0, 0, zOffset, width, height, depth, pixelData, pixelFormat);
	}

	public void uploadSubPixels3D(int zOffset, int width, int height, int depth, float[] pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(0, 0, zOffset, width, height, depth, pixelData, pixelFormat);
	}

	public void uploadSubPixels3D(int zOffset, int width, int height, int depth, Buffer pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(0, 0, zOffset, width, height, depth, pixelData, pixelFormat);
	}

	public void uploadSubPixels(int width, int height, int depth, Buffer pixelData, GLTextureFormat pixelFormat) {
		uploadSubPixels(0, 0, 0, width, height, depth, pixelData, pixelFormat);
	}

	public void uploadSubPixels(int xOffset, int yOffset, int zOffset, int width, int height, int depth, Object pixelData, GLTextureFormat pixelFormat) {
		if (xOffset + width > this.width || yOffset + height > this.height || zOffset + depth > this.depth)
			throw new IllegalArgumentException("Upload region exceeds texture dimensions");

		bind();

		if (this.depth > 1) {
			if (pixelData instanceof ByteBuffer) {
				glTexSubImage3D(textureParams.type.glTarget, 0,
					xOffset, yOffset, zOffset,
					width, height, depth,
					pixelFormat.format, pixelFormat.type, (ByteBuffer)pixelData);
			} else if (pixelData instanceof ShortBuffer) {
				glTexSubImage3D(textureParams.type.glTarget, 0,
					xOffset, yOffset, zOffset,
					width, height, depth,
					pixelFormat.format, pixelFormat.type, (ShortBuffer)pixelData);
			} else if (pixelData instanceof IntBuffer) {
				glTexSubImage3D(textureParams.type.glTarget, 0,
					xOffset, yOffset, zOffset,
					width, height, depth,
					pixelFormat.format, pixelFormat.type, (IntBuffer)pixelData);
			} else if (pixelData instanceof FloatBuffer) {
				glTexSubImage3D(textureParams.type.glTarget, 0,
					xOffset, yOffset, zOffset,
					width, height, depth,
					pixelFormat.format, pixelFormat.type, (FloatBuffer)pixelData);
			} else if (pixelData instanceof int[]) {
				glTexSubImage3D(textureParams.type.glTarget, 0,
					xOffset, yOffset, zOffset,
					width, height, depth,
					pixelFormat.format, pixelFormat.type, (int[])pixelData);

			} else if (pixelData instanceof float[]) {
				glTexSubImage3D(textureParams.type.glTarget, 0,
					xOffset, yOffset, zOffset,
					width, height, depth,
					pixelFormat.format, pixelFormat.type, (float[])pixelData);
			} else {
				throw new IllegalArgumentException("Unsupported pixel data type for glTexSubImage3D: " + pixelData.getClass());
			}
		} else {
			if (pixelData instanceof ByteBuffer) {
				glTexSubImage2D(textureParams.type.glTarget, 0,
					xOffset, yOffset,
					width, height,
					pixelFormat.format, pixelFormat.type, (ByteBuffer)pixelData);
			} else if (pixelData instanceof ShortBuffer) {
				glTexSubImage2D(textureParams.type.glTarget, 0,
					xOffset, yOffset,
					width, height,
					pixelFormat.format, pixelFormat.type, (ShortBuffer)pixelData);
			} else if (pixelData instanceof IntBuffer) {
				glTexSubImage2D(textureParams.type.glTarget, 0,
					xOffset, yOffset,
					width, height,
					pixelFormat.format, pixelFormat.type, (IntBuffer)pixelData);
			} else if (pixelData instanceof FloatBuffer) {
				glTexSubImage2D(textureParams.type.glTarget, 0,
					xOffset, yOffset,
					width, height,
					pixelFormat.format, pixelFormat.type, (FloatBuffer)pixelData);
			} else if(pixelData instanceof int[]) {
				glTexSubImage2D(textureParams.type.glTarget, 0,
					xOffset, yOffset,
					width, height,
					pixelFormat.format, pixelFormat.type, (int[])pixelData);
			} else if(pixelData instanceof float[]) {
				glTexSubImage2D(textureParams.type.glTarget, 0,
					xOffset, yOffset,
					width, height,
					pixelFormat.format, pixelFormat.type, (float[])pixelData);
			} else {
				throw new IllegalArgumentException("Unsupported buffer type for glTexSubImage2D: " + pixelData.getClass());
			}
		}

		unbind();
		checkGLErrors(() -> String.format("%s (%dx%dx%d %dx%dx%d)", textureParams.debugName, xOffset, yOffset, zOffset, width, height, depth));

		mipmapsDirty = true;
	}

	public void generateMipMaps() {
		if (textureParams.generateMipmaps && mipmapsDirty) {
			bind();
			glGenerateMipmap(textureParams.type.glTarget);
			unbind();
			checkGLErrors(() -> textureParams.debugName);
			mipmapsDirty = false;
		}
	}

	private static class PixelUploadJob extends Job {
		private static final ConcurrentPool<PixelUploadJob> POOL = new ConcurrentPool<>(PixelUploadJob::new);

		private GLTexture texture;
		private int xOffset, yOffset, zOffset, width, height, depth;
		private GLTextureFormat pixelFormat;

		@Setter
		private byte[] pixelsDataBytes;
		@Setter
		private int[] pixelsDataInts;
		@Setter
		private float[] pixelsDataFloats;
		@Setter
		private Buffer pixelData;

		private boolean pendingSubTextureUpload = false;

		@Override
		protected void onRun() {
			int bpp = pixelFormat.bytesPerPixel();
			int unalignedRowSize = texture.getWidth() * bpp;
			int rowStride = ((unalignedRowSize + UNPACK_ALIGNMENT - 1) / UNPACK_ALIGNMENT) * UNPACK_ALIGNMENT;
			int sliceStride = rowStride * texture.getHeight();

			final GLMappedBuffer mappedBuffer = texture.unpackBuffers[texture.pboIdx].mapped();
			mappedBuffer.setPositionBytes(
				(zOffset * sliceStride) +
				(yOffset * rowStride) +
				(xOffset * bpp));

			if(pixelData != null) {
				if (pixelData instanceof ByteBuffer) {
					mappedBuffer.byteView().put((ByteBuffer) pixelData);
				} else if (pixelData instanceof IntBuffer) {
					mappedBuffer.intView().put((IntBuffer) pixelData);
				} else if (pixelData instanceof FloatBuffer) {
					mappedBuffer.floatView().put((FloatBuffer) pixelData);
				}
			} else {
				if(pixelsDataBytes != null) {
					mappedBuffer.byteView().put(pixelsDataBytes);
				} else if(pixelsDataInts != null) {
					mappedBuffer.intView().put(pixelsDataInts);
				} else if(pixelsDataFloats != null) {
					mappedBuffer.floatView().put(pixelsDataFloats);
				}
			}

			mappedBuffer.syncViews();

			texture = null;
			pixelData = null;
			pixelsDataBytes = null;
			pixelsDataInts = null;
			pixelsDataFloats = null;
		}
	}
}