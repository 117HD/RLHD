package rs117.hd.opengl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.checkGLErrors;

@RequiredArgsConstructor
@Slf4j
public class GLVao {
	private static GLVao previousVao;

	private final String name;
	private final GLVertexLayout layout;
	private final GLBuffer[] buffers = new GLBuffer[GLVertexLayout.MAX_ATTRIBUTES];
	private final long[] builtData = new long[GLVertexLayout.MAX_ATTRIBUTES];

	private int glVAO;
	private int layoutVersion;

	private static long packBufferData(int bufferId, boolean owned) {
		return ((long) bufferId << 1) | (owned ? 1L : 0L);
	}

	private static int unpackBufferId(long data) {
		return (int) (data >>> 1);
	}

	private static boolean unpackOwnership(long data) {
		return (data & 1L) != 0;
	}

	public void setBuffer(GLBuffer buffer, boolean takeOwnership, GLVertexLayout.ArrayField field) {
		buffers[field.ordinal()] = buffer;
		// Note: ownership will be stored in builtData during bind/ensureBuilt
		layoutVersion = -1;
	}

	public void setBufferRange(GLBuffer buffer, boolean takeOwnership, GLVertexLayout.ArrayField start, GLVertexLayout.ArrayField end) {
		for (int i = start.ordinal(); i <= end.ordinal(); i++) {
			buffers[i] = buffer;
		}
		layoutVersion = -1;
	}

	public void setBuffers(GLBuffer buffer, boolean takeOwnership, GLVertexLayout.ArrayField... fields) {
		for (int i = 0; i < fields.length; i++) {
			buffers[fields[i].ordinal()] = buffer;
		}
		layoutVersion = -1;
	}

	public void associateBuffer(GLBuffer buffer, GLVertexLayout.ArrayField field) {
		setBuffer(buffer, false, field);
	}

	public void associateBuffers(GLBuffer buffer, GLVertexLayout.ArrayField... fields) {
		setBuffers(buffer, false, fields);
	}

	public void associateBufferRange(GLBuffer buffer, GLVertexLayout.ArrayField start, GLVertexLayout.ArrayField end) {
		setBufferRange(buffer, false, start, end);
	}

	public void remove(GLBuffer buffer) {
		boolean found = false;
		for (int i = 0; i < buffers.length; i++) {
			GLBuffer b = buffers[i];
			if (b == buffer) {
				buffers[i] = null;
				found = true;
			}
		}
		if (found)
			layoutVersion = -1;
	}

	public void bind() {
		if (previousVao != null && previousVao != this)
			log.warn("Binding VAO: {} when it was already bound by: {}", name, previousVao.name);

		if (glVAO == 0)
			glVAO = glGenVertexArrays();

		glBindVertexArray(glVAO);
		previousVao = this;

		ensureBuilt();

		final GLBuffer eboBuffer = buffers[GLVertexLayout.ArrayField.ELEMENT_BUFFER.ordinal()];
		final int newEboValue = eboBuffer != null ? eboBuffer.id : 0;
		final int boundEboId = unpackBufferId(builtData[0]);
		if (boundEboId != newEboValue) {
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, newEboValue);
			builtData[0] = packBufferData(newEboValue, false);
		}
	}

	private void ensureBuilt() {
		boolean hasArrayBuffersChanged = false;
		for (int i = 1; i < buffers.length; i++) {
			GLBuffer buffer = buffers[i];
			if (buffer != null && buffer.id != unpackBufferId(builtData[i])) {
				hasArrayBuffersChanged = true;
				break;
			}
		}

		if (layoutVersion == layout.getVersion() && !hasArrayBuffersChanged)
			return;

		GLVertexLayout.Attribute[] attributes = layout.getAttributes();
		int prevBufferId = 0;
		for (int i = 0; i < attributes.length; i++) {
			final GLVertexLayout.Attribute attrib = attributes[i];
			if (!attrib.isEnabled()) {
				glDisableVertexAttribArray(i);
				continue;
			}

			final GLBuffer arrayBuffer = buffers[i + 1];
			final int arrayBufferId = arrayBuffer != null ? arrayBuffer.id : 0;
			if (arrayBuffer == null) {
				log.warn(
					"ArrayField: {} is enabled but no buffer is associated, expect erroneous behaviour",
					GLVertexLayout.ARRAY_FIELD_NAMES[i + 1]
				);
			} else if (arrayBuffer.target != GL_ARRAY_BUFFER) {
				log.warn(
					"ArrayField: {} is enabled but buffer is not an array buffer, expect erroneous behaviour",
					GLVertexLayout.ARRAY_FIELD_NAMES[i + 1]
				);
			}

			if (prevBufferId != arrayBufferId) {
				glBindBuffer(GL_ARRAY_BUFFER, arrayBufferId);
				prevBufferId = arrayBufferId;
			}

			glEnableVertexAttribArray(i);
			if (attrib.divisor > 0)
				glVertexAttribDivisor(i, attrib.divisor);

			if (attrib.isInteger()) {
				assert attrib.format.ordinal() >= GLVertexLayout.FormatType.INT.ordinal();
				glVertexAttribIPointer(i, attrib.component.size, attrib.format.glFormatType, attrib.stride, attrib.offset);
			} else {
				glVertexAttribPointer(
					i,
					attrib.component.size,
					attrib.format.glFormatType,
					attrib.isNormalized(),
					attrib.stride,
					attrib.offset
				);
			}
			builtData[i + 1] = packBufferData(arrayBufferId, false);
		}

		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);

		glBindVertexArray(glVAO);

		checkGLErrors(() -> "Building VAO: " + name + " with layout: " + layout);
		layoutVersion = layout.getVersion();
	}

	public void unbind() {
		if (previousVao != null && previousVao != this)
			log.warn("Unbinding VAO: {} when it was bound by: {}", name, previousVao.name);
		glBindVertexArray(0);
		if (unpackBufferId(builtData[0]) != 0)
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
		previousVao = null;
	}

	public void destroy() {
		if (glVAO != 0) {
			glDeleteVertexArrays(glVAO);
			glVAO = 0;
		}

		for (int i = 0; i < buffers.length; i++) {
			if (buffers[i] == null)
				continue;

			if (unpackOwnership(builtData[i]))
				buffers[i].destroy();
			buffers[i] = null;
		}
	}
}
