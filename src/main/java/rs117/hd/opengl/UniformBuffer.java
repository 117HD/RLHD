package rs117.hd.opengl;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.compute.OpenCLManager;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL31C.*;
import static org.lwjgl.opengl.GL43C.*;

@Slf4j
public abstract class UniformBuffer {
	protected enum PropertyType {
		Int(4, 4, 1),
		IVec2(8, 8, 2),
		IVec3(12, 16, 3),
		IVec4(16, 16, 4),

		IntArray(4, 16, 1, true),
		IVec2Array(8, 16, 2, true),
		IVec3Array(12, 16, 3, true),
		IVec4Array(16, 16, 4, true),

		Float(4, 4, 1),
		FVec2(8, 8, 2),
		FVec3(12, 16, 3),
		FVec4(16, 16, 4),

		FloatArray(4, 16, 1, true),
		FVec2Array(8, 16, 2, true),
		FVec3Array(12, 16, 3, true),
		FVec4Array(16, 16, 4, true),

		Mat3(36, 16, 9),
		Mat4(64, 16, 16);

		private final int size;
		private final int alignment;
		private final int elementSize;
		private final int elementCount;
		private final boolean isArray;

		PropertyType(int size, int alignment, int elementCount) {
			this.size = size;
			this.alignment = alignment;
			this.elementSize = size / elementCount;
			this.elementCount = elementCount;
			this.isArray = false;
		}

		PropertyType(int size, int alignment, int elementCount, boolean isArray) {
			this.size = size;
			this.alignment = alignment;
			this.elementSize = size / elementCount;
			this.elementCount = elementCount;
			this.isArray = isArray;
		}
	}

	@AllArgsConstructor
	@RequiredArgsConstructor
	public static class Property {
		private UniformBuffer owner;
		private int position;
		private final PropertyType type;
		private final String name;

		public final void set(int value) {
			if (type != PropertyType.Int) {
				log.warn("UBO {} - Incorrect Setter(int) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (owner.data.getInt(position) != value) {
				owner.data.putInt(position, value);
				owner.markWaterLine(position, type.size);
			}
		}

		public final void set(int... values) {
			if (type != PropertyType.IVec2 && type != PropertyType.IVec3 && type != PropertyType.IVec4) {
				log.warn("UBO {} - Incorrect Setter(int[]) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (values == null) {
				log.warn("UBO {} - Setter(int[]) was provided with null value for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if ((type.isArray && (values.length % type.elementCount) != 0) || values.length != type.elementCount) {
				log.warn(
					"UBO {} - Setter(int[]) was provided with incorrect number of elements for Property: {}",
					owner.glBuffer.name,
					name
				);
				return;
			}

			if (owner.data == null) {
				log.warn("UBO {} - Hasn't been initialized yet!", owner.glBuffer.name);
				return;
			}

			int elementCount = type.isArray ? values.length : type.elementCount;
			for (int elementIdx = 0, offset = position; elementIdx < elementCount; elementIdx++, offset += type.elementSize) {
				if (owner.data.getInt(offset) != values[elementIdx]) {
					owner.data.putInt(offset, values[elementIdx]);
					owner.markWaterLine(offset, type.elementSize);
				}
			}
		}

		public final void set(float value) {
			if (type != PropertyType.Float) {
				log.warn("UBO {} - Incorrect Setter(float) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (owner.data.getFloat(position) != value) {
				owner.data.putFloat(position, value);
				owner.markWaterLine(position, type.size);
			}
		}

		public final void set(float... values) {
			if (type != PropertyType.FVec2 &&
				type != PropertyType.FVec3 &&
				type != PropertyType.FVec4 &&
				type != PropertyType.Mat3 &&
				type != PropertyType.Mat4
			) {
				log.warn("UBO {} - Incorrect Setter(float[]) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (values == null) {
				log.warn("UBO {} - Setter(float[]) was provided with null value for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if ((type.isArray && (values.length % type.elementCount) != 0) || values.length != type.elementCount) {
				log.warn(
					"UBO {} - Setter(float[]) was provided with incorrect number of elements for Property: {}",
					owner.glBuffer.name,
					name
				);
				return;
			}

			if (owner.data == null) {
				log.warn("UBO {} - Hasn't been initialized yet!", owner.glBuffer.name);
				return;
			}

			int elementCount = type.isArray ? values.length : type.elementCount;
			for (int elementIdx = 0, offset = position; elementIdx < elementCount; elementIdx++, offset += type.elementSize) {
				if (owner.data.getFloat(offset) != values[elementIdx]) {
					owner.data.putFloat(offset, values[elementIdx]);
					owner.markWaterLine(offset, type.elementSize);
				}
			}
		}
	}

	public interface CreateStructProperty<T extends StructProperty> {
		T create();
	}

	public abstract static class StructProperty {
		protected List<Property> properties = new ArrayList<>();

		protected final Property addProperty(PropertyType type, String name) {
			Property property = new Property(type, name);
			properties.add(property);
			return property;
		}
	}

	@Getter
	private final GLBuffer glBuffer;
	private final int glDrawType;
	private ByteBuffer data;

	private int dirtyLowTide = Integer.MAX_VALUE;
	private int dirtyHighTide = Integer.MIN_VALUE;

	public UniformBuffer(String name) {
		glBuffer = new GLBuffer("UBO " + name);
		glDrawType = GL_STATIC_DRAW;
	}

	public UniformBuffer(String name, int glDrawType) {
		this.glDrawType = glDrawType;
		glBuffer = new GLBuffer("UBO " + name);
	}

	protected final <T extends StructProperty> T addStruct(T newStructProp) {
		for (Property property : newStructProp.properties)
			appendToBuffer(property);

		// Structs need to align to 16 bytes
		glBuffer.size += (int) (16 - (glBuffer.size % 16)) % 16;

		newStructProp.properties.clear();
		return newStructProp;
	}

	protected final <T extends StructProperty> T[] addStructs(T[] newStructPropArray, CreateStructProperty<T> createFunction) {
		for (int i = 0; i < newStructPropArray.length; i++) {
			newStructPropArray[i] = createFunction.create();
			addStruct(newStructPropArray[i]);
		}

		return newStructPropArray;
	}

	protected Property addProperty(PropertyType type, String name) {
		return appendToBuffer(new Property(type, name));
	}

	protected Property[] addPropertyArray(PropertyType type, String name, int size) {
		Property[] result = new Property[size];
		for (int i = 0; i < size; i++)
			result[i] = addProperty(type, name);
		return result;
	}

	private Property appendToBuffer(Property property) {
		property.owner = this;

		int Padding = (int) (property.type.alignment - (glBuffer.size % property.type.alignment)) % property.type.alignment;
		property.position = (int) glBuffer.size + Padding;

		glBuffer.size += property.type.size + Padding;

		return property;
	}

	private void markWaterLine(int position, int size) {
		dirtyLowTide = Math.min(dirtyLowTide, position);
		dirtyHighTide = Math.max(dirtyHighTide, position + size);
	}

	public final void initialize(int uniformBlockIndex, OpenCLManager openCLManager) {
		initialize(uniformBlockIndex);

		if (openCLManager != null)
			openCLManager.recreateCLBuffer(glBuffer, CL_MEM_READ_ONLY);
	}

	public final void initialize(int UniformBlockIndex) {
		if (data != null)
			destroy();

		glBuffer.size = HDUtils.ceilPow2(glBuffer.size);
		data = BufferUtils.createByteBuffer((int) glBuffer.size);
		glBuffer.glBufferId = glGenBuffers();

		glBindBuffer(GL_UNIFORM_BUFFER, glBuffer.glBufferId);
		glBufferData(GL_UNIFORM_BUFFER, glBuffer.size, glDrawType);
		glBufferSubData(GL_UNIFORM_BUFFER, 0, data);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);

		glBindBufferBase(GL_UNIFORM_BUFFER, UniformBlockIndex, glBuffer.glBufferId);

		if (HdPlugin.glCaps.OpenGL43 && log.isDebugEnabled())
			glObjectLabel(GL_BUFFER, glBuffer.glBufferId, glBuffer.name);
	}

	protected void preUpload() {}

	public final void upload() {
		if (data == null)
			return;

		preUpload();

		if (dirtyHighTide <= 0 || dirtyLowTide >= glBuffer.size)
			return;

		data.position(dirtyLowTide);
		data.limit(dirtyHighTide);

		glBindBuffer(GL_UNIFORM_BUFFER, glBuffer.glBufferId);
		glBufferSubData(GL_UNIFORM_BUFFER, dirtyLowTide, data);

		glBindBuffer(GL_UNIFORM_BUFFER, 0);

		data.position(0);
		data.limit((int) glBuffer.size);

		dirtyLowTide = Integer.MAX_VALUE;
		dirtyHighTide = Integer.MIN_VALUE;
	}

	public final void destroy() {
		if (data == null)
			return;

		glDeleteBuffers(glBuffer.glBufferId);

		data = null;
		glBuffer.glBufferId = -1;

		if (glBuffer.clBuffer != 0) {
			clReleaseMemObject(glBuffer.clBuffer);
			glBuffer.clBuffer = 0;
		}
	}
}
