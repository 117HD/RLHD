package rs117.hd.opengl.uniforms;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.model.ModelHasher;
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
		private long address;
		private int hash;
		private final PropertyType type;
		private final String name;

		public final void set(int value) {
			if (type != PropertyType.Int) {
				log.warn("{} - Incorrect Setter(int) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (address == 0) {
				log.warn("{} - Hasn't been initialized yet!", owner.glBuffer.name);
				return;
			}

			final int newHash = Integer.hashCode(value);
			if(newHash != hash) {
				MemoryUtil.memPutInt(address, value);
				owner.markWaterLine(position, type.size);
				hash = newHash;
			}
		}

		public final void set(int x, int y) {
			if (type != PropertyType.IVec2) {
				log.warn("{} - Incorrect Setter(int, int) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (address == 0) {
				log.warn("{} - Hasn't been initialized yet!", owner.glBuffer.name);
				return;
			}

			final int newHash = ModelHasher.fastIntHash(x, y);
			if(newHash != hash) {
				MemoryUtil.memPutInt(address, x);
				MemoryUtil.memPutInt(address + 4, y);
				owner.markWaterLine(position, type.size);
				hash = newHash;
			}
		}

		public final void set(int x, int y, int z) {
			if (type != PropertyType.IVec3) {
				log.warn("{} - Incorrect Setter(int, int, int) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (address == 0) {
				log.warn("{} - Hasn't been initialized yet!", owner.glBuffer.name);
				return;
			}

			final int newHash = ModelHasher.fastIntHash(x, y, z);
			if(newHash != hash) {
				MemoryUtil.memPutInt(address, x);
				MemoryUtil.memPutInt(address + 4, y);
				MemoryUtil.memPutInt(address + 8, z);
				owner.markWaterLine(position, type.size);
				hash = newHash;
			}
		}

		public final void set(int x, int y, int z, int w) {
			if (type != PropertyType.IVec4) {
				log.warn("{} - Incorrect Setter(int, int, int, int) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (address == 0) {
				log.warn("{} - Hasn't been initialized yet!", owner.glBuffer.name);
				return;
			}

			final int newHash = ModelHasher.fastIntHash(x, y, z, w);
			if(newHash != hash) {
				MemoryUtil.memPutInt(address, x);
				MemoryUtil.memPutInt(address + 4, y);
				MemoryUtil.memPutInt(address + 8, z);
				MemoryUtil.memPutInt(address + 12, w);
				owner.markWaterLine(position, type.size);
				hash = newHash;
			}
		}

		public final void set(int... values) {
			if (values == null) {
				log.warn("{} - Setter(float[]) was provided with null value for Property: {}", owner.glBuffer.name, name);
				return;
			}

			switch (type)
			{
				case IVec2:
					set(values[0], values[1]);
					break;
				case IVec3:
					set(values[0], values[1], values[2]);
					break;
				case IVec4:
					set(values[0], values[1], values[2], values[3]);
					break;
				case IntArray:
				case IVec2Array:
				case IVec3Array:
				case IVec4Array:
					if (address == 0) {
						log.warn("{} - Hasn't been initialized yet!", owner.glBuffer.name);
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

					final int newHash = ModelHasher.fastIntHash(values, values.length);
					if(hash == newHash) {
						return;
					}
					hash = newHash;

					final int elementCount = type.isArray ? values.length : type.elementCount;
					final IntCopyBuffer copyBuffer = owner.intCopyBuffer.ensureCapacity(elementCount);
					copyBuffer.data.put(values, 0, elementCount);
					copyBuffer.copy(address, elementCount);

					owner.markWaterLine(position, type.elementSize * elementCount);
					break;
				default:
					log.warn("{} - Incorrect Setter(float[]) called for Property: {}", owner.glBuffer.name, name);
					break;
			}
		}

		public final void set(float value) {
			if (type != PropertyType.Float) {
				log.warn("{} - Incorrect Setter(float) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (address == 0) {
				log.warn("{} - Hasn't been initialized yet!", owner.glBuffer.name);
				return;
			}

			final int newHash = Float.hashCode(value);
			if(newHash != hash) {
				MemoryUtil.memPutFloat(address, value);
				owner.markWaterLine(position, type.size);
				hash = newHash;
			}
		}

		public final void set(float x, float y) {
			if (type != PropertyType.FVec2) {
				log.warn("{} - Incorrect Setter(float, float) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (address == 0) {
				log.warn("{} - Hasn't been initialized yet!", owner.glBuffer.name);
				return;
			}

			final int newHash = ModelHasher.fastFloatHash(x, y);
			if(newHash != hash) {
				MemoryUtil.memPutFloat(address, x);
				MemoryUtil.memPutFloat(address + 4, y);
				owner.markWaterLine(position, type.size);
				hash = newHash;
			}
		}

		public final void set(float x, float y, float z) {
			if (type != PropertyType.FVec3) {
				log.warn("{} - Incorrect Setter(float, float, float) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (address == 0) {
				log.warn("{} - Hasn't been initialized yet!", owner.glBuffer.name);
				return;
			}

			final int newHash = ModelHasher.fastFloatHash(x, y, z);
			if(newHash != hash) {
				MemoryUtil.memPutFloat(address, x);
				MemoryUtil.memPutFloat(address + 4, y);
				MemoryUtil.memPutFloat(address + 8, z);
				owner.markWaterLine(position, type.size);
				hash = newHash;
			}
		}

		public final void set(float x, float y, float z, float w) {
			if (type != PropertyType.FVec4) {
				log.warn("{} - Incorrect Setter(float, float, float, float) called for Property: {}", owner.glBuffer.name, name);
				return;
			}

			if (address == 0) {
				log.warn("{} - Hasn't been initialized yet!", owner.glBuffer.name);
				return;
			}

			final int newHash = ModelHasher.fastFloatHash(x, y, z, w);
			if(newHash != hash) {
				MemoryUtil.memPutFloat(address, x);
				MemoryUtil.memPutFloat(address + 4, y);
				MemoryUtil.memPutFloat(address + 8, z);
				MemoryUtil.memPutFloat(address + 12, w);
				owner.markWaterLine(position, type.size);
				hash = newHash;
			}
		}

		public final void set(float... values) {
			if (values == null) {
				log.warn("{} - Setter(float[]) was provided with null value for Property: {}", owner.glBuffer.name, name);
				return;
			}

			switch (type)
			{
				case FVec2:
					set(values[0], values[1]);
					break;
				case FVec3:
					set(values[0], values[1], values[2]);
					break;
				case FVec4:
					set(values[0], values[1], values[2], values[3]);
					break;
				case FloatArray:
				case FVec2Array:
				case FVec3Array:
				case FVec4Array:
				case Mat3:
				case Mat4:
					if (address == 0) {
						log.warn("{} - Hasn't been initialized yet!", owner.glBuffer.name);
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

					final int newHash = ModelHasher.fastFloatHash(values);
					if(hash == newHash) {
						return;
					}
					hash = newHash;

					final int elementCount = type.isArray ? values.length : type.elementCount;
					final FloatCopyBuffer copyBuffer = owner.floatCopyBuffer.ensureCapacity(elementCount);
					copyBuffer.data.put(values, 0, elementCount);
					copyBuffer.copy(address, elementCount);

					owner.markWaterLine(position, type.elementSize * elementCount);
					break;
				default:
					log.warn("{} - Incorrect Setter(float[]) called for Property: {}", owner.glBuffer.name, name);
					break;
			}
		}
	}

	abstract static class CopyBuffer<T extends Buffer> {
		public T data;
		protected long address;
		protected long elementSize;

		public abstract CopyBuffer<T> ensureCapacity(int size);

		public final void copy(long dstAddress, int elementCount) {
			MemoryUtil.memCopy(address, dstAddress, elementCount * elementSize);
			data.clear();
		}
	}

	static class IntCopyBuffer extends CopyBuffer<IntBuffer> {
		public IntCopyBuffer ensureCapacity(int size) {
			if(data == null || data.capacity() < size) {
				data = BufferUtils.createIntBuffer(size);
				address = MemoryUtil.memAddress(data);
				elementSize = Integer.BYTES;
			}
			return this;
		}
	}

	static class FloatCopyBuffer extends CopyBuffer<FloatBuffer> {
		public FloatCopyBuffer ensureCapacity(int size) {
			if(data == null || data.capacity() < size) {
				data = BufferUtils.createFloatBuffer(size);
				address = MemoryUtil.memAddress(data);
				elementSize = Float.BYTES;
			}
			return this;
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
	private final IntCopyBuffer intCopyBuffer = new IntCopyBuffer();
	private final FloatCopyBuffer floatCopyBuffer = new FloatCopyBuffer();
	private final List<Property> properties = new ArrayList<>();

	protected UniformBuffer(GLBuffer glBuffer) {
		this.glBuffer = glBuffer;
	}

	public UniformBuffer(String name, int glUsage) {
		this(new GLBuffer("UBO " + name, GL_UNIFORM_BUFFER, glUsage));
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
		properties.add(property);

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

		for(Property prop : properties) {
			prop.address = MemoryUtil.memAddress(data, prop.position);
		}
	}

	public void initialize(int uniformBlockIndex) {
		initialize();
		bind(uniformBlockIndex);
	}

	public void bind(int uniformBlockIndex) {
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

		for(Property prop : properties) {
			prop.address = 0;
		}

		glBuffer.destroy();
		data = null;
	}
}
