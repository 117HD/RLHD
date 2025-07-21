package rs117.hd.opengl.uniforms;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

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
				log.warn("{} - Incorrect Setter(int) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (owner.data.getInt(position) != value) {
				owner.data.putInt(position, value);
				owner.markWaterLine(position, type.size);
			}
		}

		public final void set(int... values) {
			if (type != PropertyType.IVec2 && type != PropertyType.IVec3 && type != PropertyType.IVec4) {
				log.warn("{} - Incorrect Setter(int[]) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (values == null) {
				log.warn("{} - Setter(int[]) was provided with null value for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if ((type.isArray && (values.length % type.elementCount) != 0) || values.length != type.elementCount) {
				log.warn(
					"{} - Setter(int[]) was provided with incorrect number of elements for Property: {}",
					owner.glBuffer.name,
					name
				);
				return;
			}

			if (owner.data == null) {
				log.warn("{} - Hasn't been initialized yet!", owner.glBuffer.name);
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
				log.warn("{} - Incorrect Setter(float) called for Property: {}", owner.glBuffer.name, name);
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
				log.warn("{} - Incorrect Setter(float[]) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (values == null) {
				log.warn("{} - Setter(float[]) was provided with null value for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if ((type.isArray && (values.length % type.elementCount) != 0) || values.length != type.elementCount) {
				log.warn(
					"{} - Setter(float[]) was provided with incorrect number of elements for Property: {}",
					owner.glBuffer.name,
					name
				);
				return;
			}

			if (owner.data == null) {
				log.warn("{} - Hasn't been initialized yet!", owner.glBuffer.name);
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

	public final GLBuffer glBuffer;

	private int size;
	private int dirtyLowTide = Integer.MAX_VALUE;
	private int dirtyHighTide = 0;
	private ByteBuffer data;

	@Getter
	private final String uniformBlockName;
	@Getter
	private int uniformBlockIndex;

	protected UniformBuffer(GLBuffer glBuffer, String uniformBlockName) {
		this.glBuffer = glBuffer;
		this.uniformBlockName = uniformBlockName;
	}

	public UniformBuffer(String name, String uniformBlockName, int glUsage) {
		this(new GLBuffer("UBO " + name, GL_UNIFORM_BUFFER, glUsage), uniformBlockName);
	}

	protected final <T extends StructProperty> T addStruct(T newStructProp) {
		for (Property property : newStructProp.properties)
			appendToBuffer(property);

		// Structs need to align to 16 bytes
		size += (16 - (size % 16)) % 16;

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

		int padding = (property.type.alignment - (size % property.type.alignment)) % property.type.alignment;
		property.position = size + padding;

		size += property.type.size + padding;

		return property;
	}

	private void markWaterLine(int position, int size) {
		dirtyLowTide = Math.min(dirtyLowTide, position);
		dirtyHighTide = Math.max(dirtyHighTide, position + size);
	}

	public void initialize() {
		if (data != null)
			destroy();

		glBuffer.initialize(size);
		data = BufferUtils.createByteBuffer(size);
	}

	public void initialize(int uniformBlockIndex) {
		initialize();
		bind(uniformBlockIndex);
	}

	public void bind(int uniformBlockIndex) {
		this.uniformBlockIndex = uniformBlockIndex;
		glBindBufferBase(GL_UNIFORM_BUFFER, uniformBlockIndex, glBuffer.id);
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

		glBindBuffer(GL_UNIFORM_BUFFER, glBuffer.id);
		glBufferSubData(GL_UNIFORM_BUFFER, dirtyLowTide, data);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);

		data.clear();

		dirtyLowTide = Integer.MAX_VALUE;
		dirtyHighTide = 0;
	}

	public final void destroy() {
		if (data == null)
			return;

		glBuffer.destroy();
		data = null;
	}
}
