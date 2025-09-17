package rs117.hd.utils.opengl.texture;

import java.util.ArrayList;
import java.util.List;

public class GLFrameBufferDesc {
	public int width   = 1;
	public int height  = 1;
	public int depth   = 1;
	public int samples = 0;
	public boolean shouldConstructionCreate = true;

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

	public GLFrameBufferDesc setShouldConstructionCreate(boolean shouldConstructionCreate) {
		this.shouldConstructionCreate = shouldConstructionCreate;
		return this;
	}

	public GLFrameBufferDesc setColorAttachment(GLAttachmentSlot slot, GLTextureFormat format, GLTextureParams filterParams) {
		assert !slot.isDepth();
		colorDescriptors.add(new AttachmentDescriptor(slot, format, filterParams));
		return this;
	}

	public GLFrameBufferDesc setDepthAttachment(GLTextureFormat format, GLTextureParams filterParams) {
		depthDescriptor = new AttachmentDescriptor(GLAttachmentSlot.DEPTH, format, filterParams);
		return this;
	}

	public static class AttachmentDescriptor {
		public GLAttachmentSlot slot;
		public GLTextureFormat format;
		public GLTextureParams params;

		public AttachmentDescriptor(GLAttachmentSlot slot, GLTextureFormat format, GLTextureParams params) {
			this.slot = slot;
			this.format = format;
			this.params = params;
		}
	}
}
