package rs117.hd.opengl.buffer;

import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public abstract class UniformStructuredBuffer<GLBUFFER extends GLBuffer> extends StructuredBuffer<GLBUFFER>  {
	@SuppressWarnings("unchecked")
	public UniformStructuredBuffer(int glUsage) { super(GL_UNIFORM_BUFFER, glUsage); }

	@SuppressWarnings("unchecked")
	public UniformStructuredBuffer(int glUsage, int clUsage) { super(GL_UNIFORM_BUFFER, glUsage, clUsage);}

	public void initialize(int bindingIndex) {
		initialize();
		this.bindingIndex = bindingIndex;
		glBindBufferBase(glBuffer.target, bindingIndex, glBuffer.id);
	}

	@Override
	protected void onAppendToBuffer(Property property) {
		if (size > 65536)
			log.warn("Uniform buffer {} is too large when adding property: {}! ({} bytes)", glBuffer.name, property.name, size);
	}

	public String getUniformBlockName() {
		return glBuffer.name;
	}
}
