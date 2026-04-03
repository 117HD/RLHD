package rs117.hd.utils.texture;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.utils.Destructible;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL11.GL_TEXTURE;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_INTERNAL_FORMAT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glGetTexLevelParameteriv;
import static org.lwjgl.opengl.GL13C.GL_MULTISAMPLE;
import static org.lwjgl.opengl.GL21.GL_SRGB;
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
import static org.lwjgl.opengl.GL30.glDrawBuffer;
import static org.lwjgl.opengl.GL30.glDrawBuffers;
import static org.lwjgl.opengl.GL30.glFramebufferTextureLayer;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL30.glGetFramebufferAttachmentParameteriv;
import static org.lwjgl.opengl.GL30.glGetRenderbufferParameteriv;
import static org.lwjgl.opengl.GL30.glReadBuffer;
import static rs117.hd.HdPlugin.checkGLErrors;

@Slf4j
public class GLFrameBuffer implements Destructible {
	@Getter
	private final GLFrameBufferDesc descriptor;

	private GLFrameBufferAttachment[] colorAttachments;
	private GLFrameBufferAttachment depthAttachment;
	private int[] drawBuffers;

	@Getter
	private int fboId = 0;
	@Getter
	private final boolean wrapper;

	private float depthClearValue = 0.0f;
	private float colorClearRed = 0.0f;
	private float colorClearGreen = 0.0f;
	private float colorClearBlue = 0.0f;
	private float colorClearAlpha = 1.0f;

	private final StringBuilder sb = new StringBuilder();

	private GLFrameBuffer() {
		descriptor = new GLFrameBufferDesc();
		wrapper = true;
	}

	public GLFrameBuffer(GLFrameBufferDesc descriptor) {
		this.descriptor = descriptor;
		wrapper = false;
		create();
	}

	public boolean isCreated() {return fboId > 0 || wrapper; }

	public boolean create() {
		if (isCreated())
			return false;

		fboId = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboId);

		if (HdPlugin.GL_CAPS.OpenGL43 && !descriptor.debugName.isEmpty()) {
			GL43C.glObjectLabel(GL_FRAMEBUFFER, fboId, descriptor.debugName);
			checkGLErrors(() -> descriptor.debugName);
		}

		if(!descriptor.colorDescriptors.isEmpty()) {
			descriptor.colorDescriptors.sort(Comparator.comparingInt(a -> a.slot.glEnum));

			colorAttachments = new GLFrameBufferAttachment[descriptor.colorDescriptors.size()];
			drawBuffers = new int[colorAttachments.length];
			for(int i = 0; i < descriptor.colorDescriptors.size(); i++) {
				colorAttachments[i] = new GLFrameBufferAttachment(this).create( descriptor.colorDescriptors.get(i));
				if(colorAttachments[i] != null)
					drawBuffers[i] = colorAttachments[i].slot.glEnum;
				glBindFramebuffer(GL_FRAMEBUFFER, fboId);
			}
			glDrawBuffers(drawBuffers);
		} else {
			// No color attachments; must specify NONE explicitly
			glDrawBuffer(GL_NONE);
			glReadBuffer(GL_NONE);
		}

		if(descriptor.depthDescriptor != null) {
			depthAttachment = new GLFrameBufferAttachment(this).create(descriptor.depthDescriptor);
		}

		glBindFramebuffer(GL_FRAMEBUFFER, fboId);

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
			destroy();
			return false;
		}

		return true;
	}

	public boolean bind(RenderState renderState, int glFramebufferEnum, GLAttachmentSlot slot, int layer) {
		renderState.framebuffer.set(glFramebufferEnum, fboId);
		renderState.viewport.set(0, 0, descriptor.width, descriptor.height);

		if (descriptor.samples > 1) {
			renderState.enable.set(GL_MULTISAMPLE);
		} else {
			renderState.disable.set(GL_MULTISAMPLE);
		}

		if(slot != null)
			glDrawBuffer(slot.glEnum);

		if(layer >= 0) {
			GLFrameBufferAttachment att = getColorAttachment(slot);
			if(att != null&& att.texture.depth < layer)
				glFramebufferTextureLayer(GL_FRAMEBUFFER, att.slot.glEnum, att.texture.getId(), 0, layer);
		}

		return false;
	}

	public void bind(RenderState renderState, int glFramebufferEnum, GLAttachmentSlot slot) {
		bind(renderState, glFramebufferEnum, slot, -1);
	}

	public void bind(RenderState renderState, int glFramebufferEnum) {
		bind(renderState, glFramebufferEnum, null, -1);
	}

	public boolean resize(int width, int height) {
		return resize(width, height, descriptor.samples, false);
	}

	public boolean resize(int width, int height, int samples) {
		return resize(width, height, samples, false);
	}

	public boolean resize(int width, int height, int samples, boolean recreate) {
		if (isCreated() && width == descriptor.width && height == descriptor.height && samples == descriptor.samples)
			return false;

		descriptor.width = width;
		descriptor.height = height;
		descriptor.samples = samples;

		if(recreate) {
			destroy();
			create();
		} else {
			if (colorAttachments != null) {
				for (GLFrameBufferAttachment att : colorAttachments)
					att.resize();
			}

			if (depthAttachment != null)
				depthAttachment.resize();
		}

		return true;
	}

	public int getWidth() {
		return descriptor.width;
	}

	public int getHeight() { return descriptor.height; }

	public int getDepth() {
		return descriptor.depth;
	}

	public int getSamples() { return descriptor.samples; }

	@Override
	protected void finalize()  {
		if(fboId == 0)
			return;

		DestructibleHandler.queueLeakedDestruction(this);
	}

	@Override
	public void destroy() {
		if (!isCreated() || wrapper) return;

		if (fboId != 0)
			glDeleteFramebuffers(fboId);
		fboId = 0;

		// Delete color renderBuffers
		if(colorAttachments != null) {
			for(GLFrameBufferAttachment att : colorAttachments)
				att.delete();
			colorAttachments = null;
		}

		if(depthAttachment != null)
			depthAttachment.delete();
		depthAttachment = null;
		drawBuffers = null;
	}

	private GLFrameBufferAttachment getColorAttachment(GLAttachmentSlot slot) {
		for(GLFrameBufferAttachment att : colorAttachments) {
			if(att.slot == slot) {
				return att;
			}
		}
		return null;
	}

	public GLTexture getColorTexture(GLAttachmentSlot slot) {
		GLFrameBufferAttachment colorAtt = getColorAttachment(slot);
		return colorAtt != null ? colorAtt.texture : null;
	}

	public GLTexture getDepthTexture() {
		return depthAttachment.texture;
	}

	private void clearInternal(boolean clearColor, boolean clearDepth) {
		if (!isCreated()) return;
		if (!clearColor && !clearDepth) return;
		if (clearColor && colorAttachments == null) clearColor = false;
		if (clearDepth && depthAttachment == null) clearDepth = false;
		if (!clearColor && !clearDepth) return;

		try {
			if (log.isDebugEnabled() && HdPlugin.GL_CAPS.OpenGL43 && !descriptor.debugName.isEmpty()) {
				sb.setLength(0);
				sb.append(clearColor && clearDepth ? "Clear(Color, Depth) - " : clearColor ? "Clear(Color) - " : "Clear(Depth) - ");
				sb.append(descriptor.debugName);
				GL43C.glPushDebugGroup(GL43C.GL_DEBUG_SOURCE_APPLICATION, -1, sb);
			}

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboId);
			int clearMask = 0;

			if (clearColor) {
				glDrawBuffers(drawBuffers);
				glClearColor(colorClearRed, colorClearGreen, colorClearBlue, colorClearAlpha);
				clearMask |= GL_COLOR_BUFFER_BIT;
			}

			if (clearDepth) {
				glClearDepth(depthClearValue);
				clearMask |= GL_DEPTH_BUFFER_BIT;
			}

			glClear(clearMask);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
		} finally {
			if (log.isDebugEnabled() && HdPlugin.GL_CAPS.OpenGL43 && !descriptor.debugName.isEmpty()) {
				GL43C.glPopDebugGroup();
			}
		}
	}

	public void clearColor() {
		clearColor(0.0f, 0.0f, 0.0f, 0.0f);
	}

	public void clearColor(float r, float g, float b, float a) {
		colorClearRed = r;
		colorClearGreen = g;
		colorClearBlue = b;
		colorClearAlpha = a;
		clearInternal(true, false);
	}

	public void clearDepth(float depth) {
		depthClearValue = depth;
		clearInternal(false, true);
	}

	public void clear(float r, float g, float b, float a, float depth) {
		colorClearRed = r;
		colorClearGreen = g;
		colorClearBlue = b;
		colorClearAlpha = a;
		depthClearValue = depth;
		clearInternal(true, true);
	}

	private void resolveMSAA(GLAttachmentSlot slot) {
		if (descriptor.samples <= 1 || colorAttachments == null) return;

		GLFrameBufferAttachment att = getColorAttachment(slot);
		if(att != null && att.resolveFboId != 0) {
			try {
				if (log.isDebugEnabled() && HdPlugin.GL_CAPS.OpenGL43 && !descriptor.debugName.isEmpty()) {
					GL43C.glPushDebugGroup(GL43C.GL_DEBUG_SOURCE_APPLICATION, -1, "MSAA Resolve");
				}

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

				checkGLErrors(() -> descriptor.debugName);
			} finally {
				if (log.isDebugEnabled() && HdPlugin.GL_CAPS.OpenGL43 && !descriptor.debugName.isEmpty()) {
					GL43C.glPopDebugGroup();
				}
			}
		}
	}

	public void blitTo(GLFrameBuffer target, GLAttachmentSlot srcSlot, GLAttachmentSlot dstSlot) {
		blitTo(target, srcSlot, dstSlot, 0, 0, target.descriptor.width, target.descriptor.height);
	}

	public void blitTo(GLFrameBuffer target, GLAttachmentSlot srcSlot, GLAttachmentSlot dstSlot, int dstX, int dstY, int dstWidth, int dstHeight) {
		blitTo(target, srcSlot, dstSlot, dstX, dstY, dstWidth, dstHeight, GL_NEAREST);
	}

	public void blitTo(GLFrameBuffer target, GLAttachmentSlot srcSlot, GLAttachmentSlot dstSlot, int dstX, int dstY, int dstWidth, int dstHeight, int glFilterMode) {
		GLFrameBufferAttachment srcAtt = getColorAttachment(srcSlot);
		GLFrameBufferAttachment dstAtt = target.getColorAttachment(dstSlot);

		if(srcAtt != null && dstAtt != null && srcAtt.format == dstAtt.format) {
			try {
				if (log.isDebugEnabled() && HdPlugin.GL_CAPS.OpenGL43 && !descriptor.debugName.isEmpty()) {
					sb.setLength(0);
					sb.append("Blit ");
					sb.append(descriptor.debugName);
					if(target.descriptor.debugName.isEmpty()) {
						GL43C.glPushDebugGroup(GL43C.GL_DEBUG_SOURCE_APPLICATION, -1, sb);
					} else {
						sb.append(" -> ");
						sb.append(target.descriptor.debugName);
						GL43C.glPushDebugGroup(GL43C.GL_DEBUG_SOURCE_APPLICATION, -1, sb);
					}
				}

				int readFbo = (descriptor.samples > 1 && srcAtt.resolveFboId != 0) ? srcAtt.resolveFboId : this.fboId;
				if (readFbo == srcAtt.resolveFboId)
					resolveMSAA(srcSlot);

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

				checkGLErrors(() -> String.format("Blit %s (%s) -> %s (%s)", descriptor.debugName, srcSlot.toString(), target.descriptor.debugName, dstSlot.toString()));
			} finally {
				if (log.isDebugEnabled() && HdPlugin.GL_CAPS.OpenGL43 && !descriptor.debugName.isEmpty()) {
					GL43C.glPopDebugGroup();
				}
			}
		}
	}

	public static GLFrameBuffer wrap(int fboId, String debugName) {
		GLFrameBuffer wrapper = new GLFrameBuffer();
		wrapper.fboId = fboId;
		wrapper.descriptor.debugName = debugName;

		glBindFramebuffer(GL_FRAMEBUFFER, fboId);

		IntBuffer param = BufferUtils.createIntBuffer(1);
		List<GLFrameBufferAttachment> colorAttachments = new ArrayList<>();

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

					GLFrameBufferAttachment att = new GLFrameBufferAttachment(wrapper);
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

					GLFrameBufferAttachment att = new GLFrameBufferAttachment(wrapper);
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

				GLFrameBufferAttachment att = new GLFrameBufferAttachment(wrapper);
				att.slot = slot;
				att.format = attDesc.format;
				att.renderBuffer = 0;
				att.texture = null;

				colorAttachments.add(att);
			}
			checkGLErrors(() -> debugName);
		}

		wrapper.colorAttachments = new GLFrameBufferAttachment[colorAttachments.size()];
		colorAttachments.toArray(wrapper.colorAttachments);

		wrapper.drawBuffers = new int[wrapper.colorAttachments.length];
		for(int i = 0; i < wrapper.colorAttachments.length; i++) {
			wrapper.drawBuffers[i] = wrapper.colorAttachments[i].slot.glEnum;
		}
		glBindFramebuffer(GL_FRAMEBUFFER, 0);

		return wrapper;
	}
}
