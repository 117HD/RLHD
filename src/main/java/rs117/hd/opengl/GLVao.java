package rs117.hd.opengl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL20C.glDisableVertexAttribArray;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;
import static org.lwjgl.opengl.GL30C.glVertexAttribIPointer;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.checkGLErrors;

@RequiredArgsConstructor
@Slf4j
public class GLVao {
	private final String name;
	private final GLVertexLayout layout;
	private final GLBuffer[] buffers = new GLBuffer[GLVertexLayout.MAX_ATTRIBUTES];
	private final boolean[] ownership = new boolean[GLVertexLayout.MAX_ATTRIBUTES];

	private int glVAO, glBoundEBO;
	private int layoutVersion;

	public void setBuffer(GLBuffer buffer, boolean takeOwnership, GLVertexLayout.ArrayField field) {
		buffers[field.ordinal()] = buffer;
		ownership[field.ordinal()] = takeOwnership;
		layoutVersion = -1;
	}

	public void setBufferRange(GLBuffer buffer, boolean takeOwnership, GLVertexLayout.ArrayField start, GLVertexLayout.ArrayField end) {
		for(int i = start.ordinal(); i <= end.ordinal(); i++) {
			buffers[i] = buffer;
			ownership[i] = takeOwnership;
		}
		layoutVersion = -1;
	}

	public void setBuffers(GLBuffer buffer, boolean takeOwnership, GLVertexLayout.ArrayField... fields) {
		for(int i = 0; i < fields.length; i++) {
			buffers[fields[i].ordinal()] = buffer;
			ownership[fields[i].ordinal()] = takeOwnership;
		}
		layoutVersion = -1;
	}

	public void associateBuffer(GLBuffer buffer, GLVertexLayout.ArrayField field) { setBuffer(buffer, false, field); }

	public void associateBuffers(GLBuffer buffer, GLVertexLayout.ArrayField... fields) { setBuffers(buffer, false, fields); }

	public void associateBufferRange(GLBuffer buffer, GLVertexLayout.ArrayField start, GLVertexLayout.ArrayField end) { setBufferRange(buffer, false, start, end); }

	public void remove(GLBuffer buffer) {
		boolean found = false;
		for(int i = 0; i < buffers.length; i++) {
			GLBuffer b = buffers[i];
			if(b == buffer) {
				buffers[i] = null;
				ownership[i] = false;
				found = true;
			}
		}
		if(found)
			layoutVersion = -1;
	}

	public void bind() {
		if(glVAO == 0)
			glVAO = glGenVertexArrays();

		glBindVertexArray(glVAO);

		if(layoutVersion != layout.getVersion()) {

			GLVertexLayout.Attribute[] attributes = layout.getAttributes();
			for(int i = 0; i < attributes.length; i++) {
				final GLVertexLayout.Attribute attrib = attributes[i];
				if(!attrib.isEnabled) {
					glDisableVertexAttribArray(i);
					continue;
				}

				final GLBuffer arrayBuffer = buffers[i + 1];
				if(arrayBuffer == null) {
					log.warn(
						"ArrayField: {} is enabled but no buffer is associated, expect erroneous behaviour",
						GLVertexLayout.ARRAY_FIELD_NAMES[i + 1]
					);
				} else {
					glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer.id);
				}

				glEnableVertexAttribArray(i);

				if(attrib.divisor > 0)
					glVertexAttribDivisor(i, attrib.divisor);

				if(attrib.isInteger) {
					assert attrib.format.ordinal() >= GLVertexLayout.FormatType.INT.ordinal();
					glVertexAttribIPointer(i, attrib.component.size, attrib.format.glFormatType, attrib.stride, attrib.offset);
				} else {
					glVertexAttribPointer(i, attrib.component.size, attrib.format.glFormatType, attrib.isNormalized, attrib.stride, attrib.offset);
				}

				glBindBuffer(GL_ARRAY_BUFFER, 0);
			}

			checkGLErrors(() -> "Building VAO: " + name + " with layout: " + layout);
			layoutVersion = layout.getVersion();
		}

		final GLBuffer eboBuffer = buffers[GLVertexLayout.ArrayField.ELEMENT_BUFFER.ordinal()];
		if(eboBuffer != null && glBoundEBO != eboBuffer.id) {
			// Element buffer id has changed, rebind it to the VAO State
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, glBoundEBO = eboBuffer.id);

			// Rebind the VAO State, so that its safe to unbind the EBO without clearing it off the state
			glBindVertexArray(0);
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
			glBindVertexArray(glVAO);
		} else if(glBoundEBO != 0) {
			// Element buffer was previously bound, but is now null clear it off the VAO State
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
			glBoundEBO = 0;
		}
	}

	public void unbind() {
		// TODO: Verify that we've bound & unbound in the correct order
		glBindVertexArray(0);
	}

	public void destroy() {
		if(glVAO != 0) {
			glDeleteVertexArrays(glVAO);
			glVAO = 0;
		}

		for(int i = 0; i < buffers.length; i++) {
			if(buffers[i] == null)
				continue;

			if(ownership[i])
				buffers[i].destroy();
			buffers[i] = null;
		}
	}
}
