package rs117.hd.utils.opengl.texture;

import java.util.Comparator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static org.lwjgl.opengl.GL30.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL30.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_UNDEFINED;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_UNSUPPORTED;
import static org.lwjgl.opengl.GL30.GL_NEAREST;
import static org.lwjgl.opengl.GL30.GL_NONE;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glBindRenderbuffer;
import static org.lwjgl.opengl.GL30.glBlitFramebuffer;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glClear;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glDeleteRenderbuffers;
import static org.lwjgl.opengl.GL30.glDrawBuffer;
import static org.lwjgl.opengl.GL30.glDrawBuffers;
import static org.lwjgl.opengl.GL30.glFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glFramebufferTextureLayer;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL30.glGenRenderbuffers;
import static org.lwjgl.opengl.GL30.glReadBuffer;
import static org.lwjgl.opengl.GL30.glRenderbufferStorageMultisample;
import static org.lwjgl.opengl.GL30.glViewport;
import static org.lwjgl.opengl.GL32.glFramebufferTexture;

@Slf4j
public class GLFrameBuffer {
	private static class FrameBufferAttachment {
		public GLAttachmentSlot slot;
		public GLTexture texture;
		public int renderBuffer;

		public void delete() {
			if (renderBuffer != 0) {
				deleteRenderBuffer(renderBuffer);
				renderBuffer = 0;
			}

			if (texture != null) {
				texture.delete();
				texture = null;
			}
		}
	}

	@Getter
	private final GLFrameBufferDesc descriptor;

	private FrameBufferAttachment[] colorAttachments;
	private FrameBufferAttachment depthAttachment;
	private int[] drawBuffers;

	@Getter
	private int fboId = -1;
	@Getter
	private boolean created = false;

	public GLFrameBuffer(GLFrameBufferDesc descriptor) {
		this.descriptor = descriptor;
		create();
	}

	private FrameBufferAttachment createAttachment(GLFrameBufferDesc.AttachmentDescriptor attDesc) {
		if (attDesc == null) return null;

		FrameBufferAttachment attachment = new FrameBufferAttachment();
		attachment.slot = attDesc.slot;

		if (descriptor.samples > 0) {
			attachment.renderBuffer = glGenRenderbuffers();
			glBindRenderbuffer(GL_RENDERBUFFER, attachment.renderBuffer);
			glRenderbufferStorageMultisample(
				GL_RENDERBUFFER,
				descriptor.samples,
				attDesc.format.internalFormat,
				descriptor.width,
				descriptor.height
			);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, attDesc.slot.glEnum, GL_RENDERBUFFER, attachment.renderBuffer);
		} else {
			attachment.texture = new GLTexture(descriptor.width, descriptor.height, descriptor.depth, attDesc.format, attDesc.params);
			switch (attDesc.params.type) {
				case TEXTURE2D:
					glFramebufferTexture2D(GL_FRAMEBUFFER, attDesc.slot.glEnum, GL_TEXTURE_2D, attachment.texture.getId(), 0);
					break;
				case TEXTURE2D_ARRAY:
					glFramebufferTexture(GL_FRAMEBUFFER, attDesc.slot.glEnum, attachment.texture.getId(), 0);
					break;
				default:
					log.error("GLFormat doesn't support GLTextureType: {}", attDesc.params.type);
					break;
			}
		}

		return attachment;
	}

	public GLFrameBuffer create() {
		if (created) {
			throw new IllegalStateException("Framebuffer already created.");
		}

		created = true;
		fboId = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboId);

		if(!descriptor.colorDescriptors.isEmpty()) {
			descriptor.colorDescriptors.sort(Comparator.comparingInt(a -> a.slot.glEnum));

			colorAttachments = new FrameBufferAttachment[descriptor.colorDescriptors.size()];
			drawBuffers = new int[colorAttachments.length];
			for(int i = 0; i < descriptor.colorDescriptors.size(); i++) {
				colorAttachments[i] = createAttachment(descriptor.colorDescriptors.get(i));
				if(colorAttachments[i] != null) {
					drawBuffers[i] = colorAttachments[i].slot.glEnum;
				}
			}
			glDrawBuffers(drawBuffers);
		} else {
			// No color attachments; must specify NONE explicitly
			glDrawBuffer(GL_NONE);
			glReadBuffer(GL_NONE);
		}

		depthAttachment = createAttachment(descriptor.depthDescriptor);

		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE) {
			String reason;
			switch (status) {
				case GL_FRAMEBUFFER_UNDEFINED: reason = "GL_FRAMEBUFFER_UNDEFINED"; break;
				case GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT: reason = "GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT"; break;
				case GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT: reason = "GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT"; break;
				case GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER: reason = "GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER"; break;
				case GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER: reason = "GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER"; break;
				case GL_FRAMEBUFFER_UNSUPPORTED: reason = "GL_FRAMEBUFFER_UNSUPPORTED"; break;
				case GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE: reason = "GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE"; break;
				default: reason = "UNKNOWN_ERROR_" + status; break;
			};
			throw new IllegalStateException("Framebuffer validation failed: " + reason);
		}
		glBindFramebuffer(GL_FRAMEBUFFER, 0);

		return this;
	}

	public void bind() {
		glViewport(0, 0, descriptor.width, descriptor.height);
		glBindFramebuffer(GL_FRAMEBUFFER, fboId);
	}

	public void bindLayer(GLAttachmentSlot slot, int layer) {
		FrameBufferAttachment att = getColorAttachment(slot);
		if(att != null && layer >= 0 && att.texture.depth < layer) {
			glFramebufferTextureLayer(GL_FRAMEBUFFER, att.slot.glEnum, att.texture.getId(), 0, layer);
		}
	}

	public void unbind() {
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	public boolean resize(int newWidth, int newHeight) {
		if (created && newWidth == descriptor.width && newHeight == descriptor.height)
			return false;

		descriptor.width = newWidth;
		descriptor.height = newHeight;

		delete();
		create();
		return true;
	}

	public int getWidth() {
		return descriptor.width;
	}

	public int getHeight() {
		return descriptor.height;
	}

	public int getDepth() {
		return descriptor.depth;
	}

	public void delete() {
		if (!created) return;

		if (fboId != 0) {
			glDeleteFramebuffers(fboId);
			fboId = 0;
		}

		// Delete color renderBuffers
		if(colorAttachments != null) {
			for(FrameBufferAttachment att : colorAttachments) {
				att.delete();
			}
			colorAttachments = null;
		}

		if(depthAttachment != null) {
			depthAttachment.delete();
			depthAttachment = null;
		}

		drawBuffers = null;
		created = false;
	}

	private FrameBufferAttachment getColorAttachment(GLAttachmentSlot slot) {
		for(FrameBufferAttachment att : colorAttachments) {
			if(att.slot == slot) {
				return att;
			}
		}
		return null;
	}

	public GLTexture getColorTexture(GLAttachmentSlot slot) {
		FrameBufferAttachment colorAtt = getColorAttachment(slot);
		return colorAtt != null ? colorAtt.texture : null;
	}

	public GLTexture getDepthTexture() {
		return depthAttachment.texture;
	}

	private void checkCreated() {
		if (!created) {
			throw new IllegalStateException("Framebuffer not yet created.");
		}
	}

	private void checkNotCreated() {
		if (created) {
			throw new IllegalStateException("Cannot modify framebuffer after creation.");
		}
	}

	private static void deleteRenderBuffer(int rbo) {
		if (rbo != 0) {
			glDeleteRenderbuffers(rbo);
		}
	}

	private void clearInternal(boolean clearColor, boolean clearDepth) {
		checkCreated();

		if (!clearColor && !clearDepth) return;
		if (clearColor && colorAttachments == null) clearColor = false;
		if (clearDepth && depthAttachment == null) clearDepth = false;
		if (!clearColor && !clearDepth) return;

		bind();

		int clearMask = 0;

		if (clearColor) {
			glDrawBuffers(drawBuffers);
			clearMask |= GL_COLOR_BUFFER_BIT;
		}

		if (clearDepth) {
			clearMask |= GL_DEPTH_BUFFER_BIT;
		}

		glClear(clearMask);

		unbind();
	}

	public void clearColor() {
		clearInternal(true, false);
	}

	public void clearDepth() {
		clearInternal(false, true);
	}

	public void clear() {
		clearInternal(true, true);
	}

	public void blitTo(GLFrameBuffer target, boolean blitDepth) {
		if (descriptor.samples <= 0) {
			throw new IllegalStateException("resolveTo called on a non-multisampled framebuffer");
		}
		if (target.descriptor.samples > 0) {
			throw new IllegalArgumentException("Target framebuffer must not be multisampled");
		}

		glBindFramebuffer(GL_READ_FRAMEBUFFER, this.fboId);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.getFboId());

		for (FrameBufferAttachment colorAtt : colorAttachments) {
			FrameBufferAttachment targetColorAtt = target.getColorAttachment(colorAtt.slot);
			if (targetColorAtt == null || colorAtt.texture.textureFormat != targetColorAtt.texture.textureFormat) continue;

			glReadBuffer(colorAtt.slot.glEnum);
			glDrawBuffer(colorAtt.slot.glEnum);

			glBlitFramebuffer(
				0, 0, descriptor.width, descriptor.height,
				0, 0, target.descriptor.width, target.descriptor.height,
				GL_COLOR_BUFFER_BIT,
				GL_NEAREST
			);
		}

		if (blitDepth && depthAttachment != null) {
			// Blit depth buffer
			glBlitFramebuffer(
				0, 0, descriptor.width, descriptor.height,
				0, 0, target.descriptor.width, target.descriptor.height,
				GL_DEPTH_BUFFER_BIT,
				GL_NEAREST
			);
		}

		glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
	}
}
