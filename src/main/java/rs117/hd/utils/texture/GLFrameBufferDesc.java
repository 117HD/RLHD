package rs117.hd.utils.texture;

import java.util.ArrayList;
import java.util.List;

public class GLFrameBufferDesc {
	public int width   = 1;
	public int height  = 1;
	public int depth   = 1;
	public int samples = 0;
	public boolean shouldConstructionCreate = true;
	public String debugName = "";

	public final List<AttachmentDescriptor> colorDescriptors = new ArrayList<>();
	public AttachmentDescriptor depthDescriptor;

	public GLFrameBufferDesc setWidth(int width) {
		this.width = width;
		return this;
	}

	public GLFrameBufferDesc setHeight(int height) {
		this.height = height;
		return this;
	}

	public GLFrameBufferDesc setDepth(int depth) {
		this.depth = depth;
		return this;
	}

	public GLFrameBufferDesc setMSAASamples(int samples) {
		this.samples = samples;
		return this;
	}

	public GLFrameBufferDesc setDebugName(String debugName) {
		this.debugName = debugName;
		return this;
	}

	public GLFrameBufferDesc setShouldConstructionCreate(boolean shouldConstructionCreate) {
		this.shouldConstructionCreate = shouldConstructionCreate;
		return this;
	}

	public GLFrameBufferDesc setColorAttachment(GLAttachmentSlot slot, GLTextureFormat format) { return setColorAttachment(slot, format, null); }

	public GLFrameBufferDesc setColorAttachment(GLAttachmentSlot slot, GLTextureFormat format, Builder paramBuilder) {
		assert !slot.isDepth();
		GLTextureParams params = paramBuilder != null ? paramBuilder.apply(GLTextureParams.DEFAULT()) : GLTextureParams.DEFAULT();
		colorDescriptors.add(new AttachmentDescriptor(slot, format, params));
		return this;
	}

	public GLFrameBufferDesc setDepthAttachment(GLTextureFormat format) { return setDepthAttachment(format, null); }

	public GLFrameBufferDesc setDepthAttachment(GLTextureFormat format, Builder paramBuilder) {
		GLTextureParams params = paramBuilder != null ? paramBuilder.apply(GLTextureParams.DEFAULT()) : GLTextureParams.DEFAULT();
		depthDescriptor = new AttachmentDescriptor(GLAttachmentSlot.DEPTH, format, params);
		return this;
	}

	@FunctionalInterface
	public interface Builder {
		GLTextureParams apply(GLTextureParams params);
	}

	public static class AttachmentDescriptor {
		public GLAttachmentSlot slot;
		public GLTextureFormat format;
		public GLTextureParams params;

		public AttachmentDescriptor() {}

		public AttachmentDescriptor(GLAttachmentSlot slot, GLTextureFormat format, GLTextureParams params) {
			this.slot = slot;
			this.format = format;
			this.params = params;
		}
	}
}
