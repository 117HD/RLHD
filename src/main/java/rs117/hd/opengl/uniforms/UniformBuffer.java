package rs117.hd.opengl.uniforms;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.model.ModelHasher;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.SharedGLBuffer;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public abstract class UniformBuffer<GLBUFFER extends GLBuffer> {
	protected enum PropertyType {
		Int(4, 4, 1),
		IVec2(8, 8, 2),
		IVec3(12, 16, 3),
		IVec4(16, 16, 4),

		Float(4, 4, 1),
		FVec2(8, 8, 2),
		FVec3(12, 16, 3),
		FVec4(16, 16, 4),

		Mat3(48, 16, 9),
		Mat4(64, 16, 16);

		private final int size;
		private final int alignment;
		private final int elementSize;
		private final int elementCount;

		PropertyType(int size, int alignment, int elementCount) {
			this.size = size;
			this.alignment = alignment;
			this.elementSize = size / elementCount;
			this.elementCount = elementCount;
		}
	}

	@AllArgsConstructor
	@RequiredArgsConstructor
	public static class Property {
		private UniformBuffer<?> owner;
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
			if (newHash != hash) {
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
			if (newHash != hash) {
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
			if (newHash != hash) {
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
			if (newHash != hash) {
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

			switch (type) {
				case IVec2:
					set(values[0], values[1]);
					break;
				case IVec3:
					set(values[0], values[1], values[2]);
					break;
				case IVec4:
					set(values[0], values[1], values[2], values[3]);
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
			if (newHash != hash) {
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
			if (newHash != hash) {
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
			if (newHash != hash) {
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
			if (newHash != hash) {
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

			switch (type) {
				case FVec2:
					set(values[0], values[1]);
					break;
				case FVec3:
					set(values[0], values[1], values[2]);
					break;
				case FVec4:
					set(values[0], values[1], values[2], values[3]);
					break;
				case Mat3: {
					if (address == 0) {
						log.warn("{} - Hasn't been initialized yet!", owner.glBuffer.name);
						return;
					}

					if (values.length != type.elementCount) {
						log.warn(
							"{} - Setter(float[]) was provided with incorrect number of elements for Property: {}",
							owner.glBuffer.name,
							name
						);
						return;
					}

					final int newHash = ModelHasher.fastFloatHash(values);
					if (hash == newHash)
						return;
					hash = newHash;

					int elementCount = 12;
					final FloatCopyBuffer copyBuffer = owner.floatCopyBuffer.ensureCapacity(elementCount);
					for (int i = 0; i < 3; i++)
						copyBuffer.data.put(values, i * 3, 3).put(0);
					copyBuffer.copy(address, elementCount);

					owner.markWaterLine(position, type.elementSize * elementCount);
					break;
				}
				case Mat4: {
					if (address == 0) {
						log.warn("{} - Hasn't been initialized yet!", owner.glBuffer.name);
						return;
					}

					if (values.length != type.elementCount) {
						log.warn(
							"{} - Setter(float[]) was provided with incorrect number of elements for Property: {}",
							owner.glBuffer.name,
							name
						);
						return;
					}

					final int newHash = ModelHasher.fastFloatHash(values);
					if (hash == newHash)
						return;
					hash = newHash;

					final FloatCopyBuffer copyBuffer = owner.floatCopyBuffer.ensureCapacity(type.elementCount);
					copyBuffer.data.put(values, 0, type.elementCount);
					copyBuffer.copy(address, type.elementCount);

					owner.markWaterLine(position, type.elementSize * type.elementCount);
					break;
				}
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

		public final void copy(long dstAddress, int elementCount) {
			MemoryUtil.memCopy(address, dstAddress, elementCount * elementSize);
			data.clear();
		}
	}

	static class FloatCopyBuffer extends CopyBuffer<FloatBuffer> {
		private FloatCopyBuffer ensureCapacity(int size) {
			if (data == null || data.capacity() < size) {
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

	public final GLBUFFER glBuffer;

	private int size;
	private int dirtyLowTide = Integer.MAX_VALUE;
	private int dirtyHighTide = 0;
	private ByteBuffer data;
	private final FloatCopyBuffer floatCopyBuffer = new FloatCopyBuffer();
	private final List<Property> properties = new ArrayList<>();

	@Getter
	private int bindingIndex;

	@SuppressWarnings("unchecked")
	public UniformBuffer(int glUsage) {
		glBuffer = (GLBUFFER) new GLBuffer(getClass().getSimpleName(), GL_UNIFORM_BUFFER, glUsage);
	}

	@SuppressWarnings("unchecked")
	public UniformBuffer(int glUsage, int clUsage) {
		glBuffer = (GLBUFFER) new SharedGLBuffer(getClass().getSimpleName(), GL_UNIFORM_BUFFER, glUsage, clUsage);
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

		for (Property prop : properties)
			prop.address = MemoryUtil.memAddress(data, prop.position);
	}

	public void initialize(int bindingIndex) {
		initialize();
		bind(bindingIndex);
	}

	public String getUniformBlockName() {
		return glBuffer.name;
	}

	public void bind(int bindingIndex) {
		this.bindingIndex = bindingIndex;
		glBindBufferBase(GL_UNIFORM_BUFFER, bindingIndex, glBuffer.id);
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

		for (Property prop : properties) {
			prop.address = 0;
			prop.hash = 0;
		}

		glBuffer.destroy();
		data = null;
	}
}
