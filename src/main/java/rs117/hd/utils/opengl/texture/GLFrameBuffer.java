package rs117.hd.utils.opengl.texture;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.BufferUtils;

import static org.lwjgl.opengl.GL11.GL_BACK_LEFT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_INTERNAL_FORMAT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glGetTexLevelParameteriv;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL13C.GL_MULTISAMPLE;
import static org.lwjgl.opengl.GL13C.GL_SAMPLES;
import static org.lwjgl.opengl.GL21.GL_SRGB;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL30.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_ATTACHMENT_BLUE_SIZE;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_ATTACHMENT_GREEN_SIZE;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_DEFAULT;
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
import static org.lwjgl.opengl.GL30.GL_RENDERBUFFER_HEIGHT;
import static org.lwjgl.opengl.GL30.GL_RENDERBUFFER_INTERNAL_FORMAT;
import static org.lwjgl.opengl.GL30.GL_RENDERBUFFER_SAMPLES;
import static org.lwjgl.opengl.GL30.GL_RENDERBUFFER_WIDTH;
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
import static org.lwjgl.opengl.GL30.glGetFramebufferAttachmentParameteri;
import static org.lwjgl.opengl.GL30.glGetFramebufferAttachmentParameteriv;
import static org.lwjgl.opengl.GL30.glGetRenderbufferParameteriv;
import static org.lwjgl.opengl.GL30.glReadBuffer;
import static org.lwjgl.opengl.GL30.glRenderbufferStorageMultisample;
import static org.lwjgl.opengl.GL30.glViewport;
import static org.lwjgl.opengl.GL32.glFramebufferTexture;
import static rs117.hd.HdPlugin.checkGLErrors;

@Slf4j
public class GLFrameBuffer {
	private static class FrameBufferAttachment {
		public GLAttachmentSlot slot;
		public GLTextureFormat format;
		public GLTexture texture;
		public int renderBuffer;
		public int resolveFboId;

		public void delete() {
			if (renderBuffer != 0) {
				deleteRenderBuffer(renderBuffer);
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

	@Getter
	private final GLFrameBufferDesc descriptor;

	private FrameBufferAttachment[] colorAttachments;
	private FrameBufferAttachment depthAttachment;
	private int[] drawBuffers;

	@Getter
	private int fboId = -1;
	@Getter
	private boolean created = false;
	@Getter
	private boolean wrapper = false;

	private GLFrameBuffer() {
		descriptor = new GLFrameBufferDesc();
	}

	public GLFrameBuffer(GLFrameBufferDesc descriptor) {
		this.descriptor = descriptor;
		create();
	}

	private FrameBufferAttachment createAttachment(GLFrameBufferDesc.AttachmentDescriptor attDesc) {
		if (attDesc == null) return null;

		FrameBufferAttachment attachment = new FrameBufferAttachment();
		attachment.slot = attDesc.slot;
		attachment.format = attDesc.format;

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

			attachment.texture = new GLTexture(descriptor.width, descriptor.height, descriptor.depth, attDesc.format, attDesc.params);

			attachment.resolveFboId = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, attachment.resolveFboId);

			switch (attDesc.params.type) {
				case TEXTURE2D:
					glFramebufferTexture2D(GL_FRAMEBUFFER, attDesc.slot.glEnum, GL_TEXTURE_2D, attachment.texture.getId(), 0);
					break;
				case TEXTURE2D_ARRAY:
					glFramebufferTexture(GL_FRAMEBUFFER, attDesc.slot.glEnum, attachment.texture.getId(), 0);
					break;
				default:
					log.error("Unsupported GLTextureType: {}", attDesc.params.type);
					break;
			}

			glBindFramebuffer(GL_FRAMEBUFFER, fboId);
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

	public boolean create() {
		if (created) {
			return false;
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
			log.error("Framebuffer validation failed: {}", reason);
			delete();
			return false;
		}

		glBindFramebuffer(GL_FRAMEBUFFER, 0);

		return true;
	}

	public void bind() {
		glViewport(0, 0, descriptor.width, descriptor.height);
		if (descriptor.samples > 1) {
			glEnable(GL_MULTISAMPLE);
		} else {
			glDisable(GL_MULTISAMPLE);
		}
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
		if (!created || wrapper) return;

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

	private void resolveMSAA(GLAttachmentSlot slot) {
		if (descriptor.samples <= 1 || colorAttachments == null) return;

		FrameBufferAttachment att = getColorAttachment(slot);
		if(att != null && att.resolveFboId != 0) {
			glBindFramebuffer(GL_READ_FRAMEBUFFER, fboId);
			glReadBuffer(att.slot.glEnum);

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, att.resolveFboId);
			glDrawBuffer(att.slot.glEnum);

			glBlitFramebuffer(
				0, 0, descriptor.width, descriptor.height,
				0, 0, descriptor.width, descriptor.height,
				GL_COLOR_BUFFER_BIT,
				GL_NEAREST
			);

			glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);

			checkGLErrors();
		}
	}

	public void blitTo(GLFrameBuffer target, GLAttachmentSlot srcSlot, GLAttachmentSlot dstSlot) {
		blitTo(target, srcSlot, dstSlot, 0, 0, target.descriptor.width, target.descriptor.height);
	}

	public void blitTo(GLFrameBuffer target, GLAttachmentSlot srcSlot, GLAttachmentSlot dstSlot, int dstX, int dstY, int dstWidth, int dstHeight) {
		blitTo(target, srcSlot, dstSlot, dstX, dstY, dstWidth, dstHeight, GL_NEAREST);
	}

	public void blitTo(GLFrameBuffer target, GLAttachmentSlot srcSlot, GLAttachmentSlot dstSlot, int dstX, int dstY, int dstWidth, int dstHeight, int glFilterMode) {
		if (descriptor.samples > 1) {
			resolveMSAA(srcSlot);
		}

		FrameBufferAttachment srcAtt = getColorAttachment(srcSlot);
		FrameBufferAttachment dstAtt = target.getColorAttachment(dstSlot);

		if(srcAtt != null && dstAtt != null && srcAtt.format == dstAtt.format) {
			int readFbo = (descriptor.samples > 1 && srcAtt.resolveFboId != 0) ? srcAtt.resolveFboId : this.fboId;

			glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
			glReadBuffer(srcAtt.slot.glEnum);

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.getFboId());
			glDrawBuffer(dstAtt.slot.glEnum);

			glBlitFramebuffer(
				0, 0, descriptor.width, descriptor.height,
				dstX, dstY, dstWidth, dstHeight,
				srcAtt.format.isDepth() ? GL_DEPTH_BUFFER_BIT : GL_COLOR_BUFFER_BIT,
				glFilterMode
			);

			glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);

			checkGLErrors();
		}
	}

	public static GLFrameBuffer wrap(int fboId) {
		GLFrameBuffer wrapper = new GLFrameBuffer();
		wrapper.fboId = fboId;
		wrapper.created = true;

		glBindFramebuffer(GL_FRAMEBUFFER, fboId);

		IntBuffer param = BufferUtils.createIntBuffer(1);
		List<FrameBufferAttachment> colorAttachments = new ArrayList<>();

		boolean isDefaultFrameBuffer = fboId == 0;
		for (GLAttachmentSlot slot : GLAttachmentSlot.values()) {
			if (slot.isDepth() || slot.defaultFrameBufferSupport != isDefaultFrameBuffer) {
				continue;
			}

			int glSlot = slot.glEnum;
			glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, glSlot, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, param);
			int objType = param.get(0);
			param.rewind();

			if(objType == GL_RENDERBUFFER || objType == GL_TEXTURE) {
				glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, glSlot, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, param);
				int objectName = param.get(0);
				param.rewind();

				if (objType == GL_RENDERBUFFER) {
					glBindRenderbuffer(GL_RENDERBUFFER, objectName);
					glGetRenderbufferParameteriv(GL_RENDERBUFFER, GL_RENDERBUFFER_WIDTH, param);
					wrapper.descriptor.width = param.get(0);
					param.rewind();
					glGetRenderbufferParameteriv(GL_RENDERBUFFER, GL_RENDERBUFFER_HEIGHT, param);
					wrapper.descriptor.height = param.get(0);
					param.rewind();

					glGetRenderbufferParameteriv(GL_RENDERBUFFER, GL_RENDERBUFFER_SAMPLES, param);
					wrapper.descriptor.samples = param.get(0);
					param.rewind();

					glGetRenderbufferParameteriv(GL_RENDERBUFFER, GL_RENDERBUFFER_INTERNAL_FORMAT, param);
					int internalFormat = param.get(0);
					param.rewind();

					GLFrameBufferDesc.AttachmentDescriptor attDesc = new GLFrameBufferDesc.AttachmentDescriptor();
					attDesc.slot = slot;
					attDesc.format = GLTextureFormat.fromInternalFormat(internalFormat);

					wrapper.descriptor.colorDescriptors.add(attDesc);

					FrameBufferAttachment att = new FrameBufferAttachment();
					att.slot = slot;
					att.format = attDesc.format;
					att.renderBuffer = objectName;
					colorAttachments.add(att);

				} else {
					glBindTexture(GL_TEXTURE_2D, objectName);
					glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH, param);
					wrapper.descriptor.width = param.get(0);
					param.rewind();
					glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT, param);
					wrapper.descriptor.height = param.get(0);
					param.rewind();

					glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT, param);
					int internalFormat = param.get(0);
					param.rewind();

					GLFrameBufferDesc.AttachmentDescriptor attDesc = new GLFrameBufferDesc.AttachmentDescriptor();
					attDesc.slot = slot;
					attDesc.format = GLTextureFormat.fromInternalFormat(internalFormat);

					wrapper.descriptor.colorDescriptors.add(attDesc);

					FrameBufferAttachment att = new FrameBufferAttachment();
					att.slot = slot;
					att.format = attDesc.format;
					colorAttachments.add(att);
				}
			} else if(objType == GL_FRAMEBUFFER_DEFAULT) {
				glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, glSlot, GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE, param);
				int rsize = param.get(0);
				param.rewind();
				glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, glSlot, GL_FRAMEBUFFER_ATTACHMENT_GREEN_SIZE, param);
				int gsize = param.get(0);
				param.rewind();
				glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, glSlot, GL_FRAMEBUFFER_ATTACHMENT_BLUE_SIZE, param);
				int bsize = param.get(0);
				param.rewind();
				glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, glSlot, GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE, param);
				int asize = param.get(0);
				param.rewind();
				glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER, glSlot, GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING, param);
				boolean isSRGB = (param.get(0) == GL_SRGB);
				param.rewind();

				GLFrameBufferDesc.AttachmentDescriptor attDesc = new GLFrameBufferDesc.AttachmentDescriptor();
				attDesc.slot = slot;
				attDesc.format = GLTextureFormat.fromComponentSizes(rsize, gsize, bsize, asize, isSRGB);
				wrapper.descriptor.colorDescriptors.add(attDesc);

				FrameBufferAttachment att = new FrameBufferAttachment();
				att.slot = slot;
				att.format = attDesc.format;
				att.renderBuffer = 0;
				att.texture = null;

				colorAttachments.add(att);
			}
			checkGLErrors();
		}

		wrapper.colorAttachments = new FrameBufferAttachment[colorAttachments.size()];
		colorAttachments.toArray(wrapper.colorAttachments);

		glBindFramebuffer(GL_FRAMEBUFFER, 0);

		return wrapper;
	}
}
