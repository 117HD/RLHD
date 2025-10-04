package rs117.hd.utils.opengl.texture;

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glBindRenderbuffer;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL30.glGenRenderbuffers;
import static org.lwjgl.opengl.GL30.glRenderbufferStorageMultisample;
import static org.lwjgl.opengl.GL32.glFramebufferTexture;
import static rs117.hd.HdPlugin.checkGLErrors;

@Slf4j
public class GLFrameBufferAttachment {
	public GLFrameBuffer frameBuffer;
	public GLAttachmentSlot slot;
	public GLTextureFormat format;
	public GLTexture texture;
	public int renderBuffer;
	public int resolveFboId;

	public GLFrameBufferAttachment(GLFrameBuffer frameBuffer) {
		this.frameBuffer = frameBuffer;
	}

	public GLFrameBufferAttachment create(GLFrameBufferDesc.AttachmentDescriptor attDesc){
		final GLFrameBufferDesc descriptor = frameBuffer.getDescriptor();

		slot = attDesc.slot;
		format = attDesc.format;

		if (descriptor.samples > 0) {
			renderBuffer = glGenRenderbuffers();
			glBindRenderbuffer(GL_RENDERBUFFER, renderBuffer);
			glRenderbufferStorageMultisample(
				GL_RENDERBUFFER,
				descriptor.samples,
				attDesc.format.internalFormat,
				descriptor.width,
				descriptor.height
			);

			if (HdPlugin.GL_CAPS.OpenGL43 && !descriptor.debugName.isEmpty()) {
				GL43C.glObjectLabel(GL_RENDERBUFFER, renderBuffer, descriptor.debugName + " - " + attDesc.slot.toString());
				checkGLErrors();
			}

			glFramebufferRenderbuffer(GL_FRAMEBUFFER, attDesc.slot.glEnum, GL_RENDERBUFFER, renderBuffer);
			texture = new GLTexture(descriptor.width, descriptor.height, descriptor.depth, attDesc.format, attDesc.params);

			resolveFboId = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, resolveFboId);

			switch (attDesc.params.type) {
				case TEXTURE2D:
					glFramebufferTexture2D(GL_FRAMEBUFFER, attDesc.slot.glEnum, GL_TEXTURE_2D, texture.getId(), 0);
					break;
				case TEXTURE2D_ARRAY:
					glFramebufferTexture(GL_FRAMEBUFFER, attDesc.slot.glEnum, texture.getId(), 0);
					break;
				default:
					log.error("Unsupported GLTextureType: {}", attDesc.params.type);
					break;
			}

			if (HdPlugin.GL_CAPS.OpenGL43 && !descriptor.debugName.isEmpty()) {
				texture.setDebugName(descriptor.debugName + " - " + attDesc.slot.toString() + " (MSAA Resolve)");
				GL43C.glObjectLabel(GL_FRAMEBUFFER, resolveFboId, descriptor.debugName + " - " + attDesc.slot.toString() + " (MSAA Resolve)");
				checkGLErrors();
			}
		} else {
			texture = new GLTexture(descriptor.width, descriptor.height, descriptor.depth, attDesc.format, attDesc.params);
			switch (attDesc.params.type) {
				case TEXTURE2D:
					glFramebufferTexture2D(GL_FRAMEBUFFER, attDesc.slot.glEnum, GL_TEXTURE_2D, texture.getId(), 0);
					break;
				case TEXTURE2D_ARRAY:
					glFramebufferTexture(GL_FRAMEBUFFER, attDesc.slot.glEnum, texture.getId(), 0);
					break;
				default:
					log.error("GLFormat doesn't support GLTextureType: {}", attDesc.params.type);
					break;
			}

			if (HdPlugin.GL_CAPS.OpenGL43 && !descriptor.debugName.isEmpty()) {
				texture.setDebugName(descriptor.debugName + " - " + attDesc.slot.toString());
				checkGLErrors();
			}
		}
		checkGLErrors();
		glBindFramebuffer(GL_FRAMEBUFFER, frameBuffer.getFboId());

		return this;
	}

	public void resize() {
		final GLFrameBufferDesc descriptor = frameBuffer.getDescriptor();
		final GLTextureParams params = texture.getTextureParams();

		if (descriptor.samples > 0 && renderBuffer != 0) {
			glBindRenderbuffer(GL_RENDERBUFFER, renderBuffer);
			glRenderbufferStorageMultisample(
				GL_RENDERBUFFER,
				descriptor.samples,
				format.internalFormat,
				descriptor.width,
				descriptor.height
			);
			checkGLErrors();

			glBindFramebuffer(GL_FRAMEBUFFER, frameBuffer.getFboId());
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, slot.glEnum, GL_RENDERBUFFER, renderBuffer);

			// Resize resolve texture
			texture.resize(descriptor.width, descriptor.height, descriptor.depth);

			// Reattach to resolve framebuffer
			if (resolveFboId != 0) {
				glBindFramebuffer(GL_FRAMEBUFFER, resolveFboId);
				switch (params.type) {
					case TEXTURE2D:
						glFramebufferTexture2D(GL_FRAMEBUFFER, slot.glEnum, GL_TEXTURE_2D, texture.getId(), 0);
						break;
					case TEXTURE2D_ARRAY:
						glFramebufferTexture(GL_FRAMEBUFFER, slot.glEnum, texture.getId(), 0);
						break;
					default:
						log.error("Unsupported GLTextureType during MSAA resize: {}", params.type);
						break;
				}
			}
		} else {
			texture.resize(descriptor.width, descriptor.height, descriptor.depth);

			glBindFramebuffer(GL_FRAMEBUFFER, frameBuffer.getFboId());
			switch (params.type) {
				case TEXTURE2D:
					glFramebufferTexture2D(GL_FRAMEBUFFER, slot.glEnum, GL_TEXTURE_2D, texture.getId(), 0);
					break;
				case TEXTURE2D_ARRAY:
					glFramebufferTexture(GL_FRAMEBUFFER, slot.glEnum, texture.getId(), 0);
					break;
				default:
					log.error("Unsupported GLTextureType during non-MSAA resize: {}", params.type);
					break;
			}
		}
	}

	public void delete() {
		if (renderBuffer != 0) {
			glDeleteFramebuffers(renderBuffer);
			renderBuffer = 0;
		}

		if (texture != null) {
			texture.delete();
			texture = null;
		}

		if (resolveFboId != 0) {
			glDeleteFramebuffers(resolveFboId);
			resolveFboId = 0;
		}
	}
}
