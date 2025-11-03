package rs117.hd.opengl.buffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.SharedGLBuffer;

import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferSubData;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class StructuredBuffer<GLBUFFER extends GLBuffer>  {
	@RequiredArgsConstructor
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
		private final int elementCount;
		private final boolean isInt = name().startsWith("I");
	}

	@AllArgsConstructor
	@RequiredArgsConstructor
	public static class Property {
		protected StructuredBuffer<?> owner;
		protected int position;
		protected int offset = -1;
		protected final PropertyType type;
		protected final String name;

		private void log(String message) {
			log.warn("{}.{} - {}", owner.glBuffer.name, name, message);
		}

		private boolean isUninitialized() {
			if (offset >= 0)
				return false;
			log("Hasn't been initialized yet!");
			return true;
		}

		public final void set(int... values) {
			if (isUninitialized())
				return;

			if (!type.isInt) {
				log("Int setter was used with a non-int property type");
				return;
			}

			if (values == null) {
				log("Int setter was provided with null value");
				return;
			}

			if (values.length != type.elementCount) {
				log(String.format("Int setter was provided with incorrect number of elements: %d != %d", values.length, type.elementCount));
				return;
			}

			owner.dataInt.position(offset).put(values);
			owner.markWaterLine(position, type.size);
		}

		public final void set(int x) {
			if (isUninitialized())
				return;

			if (type != PropertyType.Int) {
				log("Int setter was used with the wrong property type: " + type);
				return;
			}

			owner.dataInt.position(offset).put(x);
			owner.markWaterLine(position, type.size);
		}

		public final void set(int x, int y) {
			if (isUninitialized())
				return;

			if (type != PropertyType.IVec2) {
				log("Int setter was used with the wrong property type: " + type);
				return;
			}

			owner.dataInt.position(offset).put(x).put(y);
			owner.markWaterLine(position, type.size);
		}

		public final void set(int x, int y, int z) {
			if (isUninitialized())
				return;

			if (type != PropertyType.IVec3) {
				log("Int setter was used with the wrong property type: " + type);
				return;
			}

			owner.dataInt.position(offset).put(x).put(y).put(z);
			owner.markWaterLine(position, type.size);
		}

		public final void set(int x, int y, int z, int w) {
			if (isUninitialized())
				return;

			if (type != PropertyType.IVec4) {
				log("Int setter was used with the wrong property type: " + type);
				return;
			}

			owner.dataInt.position(offset).put(x).put(y).put(z).put(w);
			owner.markWaterLine(position, type.size);
		}

		public final void set(float... values) {
			if (isUninitialized())
				return;

			if (type.isInt) {
				log("Float setter was used with an int property type");
				return;
			}

			if (values == null) {
				log("Float setter was provided with null value");
				return;
			}

			if (values.length != type.elementCount) {
				log(String.format(
					"Float setter was provided with incorrect number of elements: %d != %d",
					values.length,
					type.elementCount
				));
				return;
			}

			owner.dataFloat.position(offset);
			if (type == PropertyType.Mat3) {
				// Pad each column to a vec4
				for (int i = 0; i < 3; i++)
					owner.dataFloat.put(values, i * 3, 3).put(0);
			} else {
				owner.dataFloat.put(values);
			}
			owner.markWaterLine(position, type.size);
		}

		public final void set(float x) {
			if (isUninitialized())
				return;

			if (type != PropertyType.Float) {
				log("Float setter was used with the wrong property type: " + type);
				return;
			}

			owner.dataFloat.position(offset).put(x);
			owner.markWaterLine(position, type.size);
		}

		public final void set(float x, float y) {
			if (isUninitialized())
				return;

			if (type != PropertyType.FVec2) {
				log("Float setter was used with the wrong property type: " + type);
				return;
			}

			owner.dataFloat.position(offset).put(x).put(y);
			owner.markWaterLine(position, type.size);
		}

		public final void set(float x, float y, float z) {
			if (isUninitialized())
				return;

			if (type != PropertyType.FVec3) {
				log("Float setter was used with the wrong property type: " + type);
				return;
			}

			owner.dataFloat.position(offset).put(x).put(y).put(z);
			owner.markWaterLine(position, type.size);
		}

		public final void set(float x, float y, float z, float w) {
			if (isUninitialized())
				return;

			if (type != PropertyType.FVec4) {
				log("Float setter was used with the wrong property type: " + type);
				return;
			}

			owner.dataFloat.position(offset).put(x).put(y).put(z).put(w);
			owner.markWaterLine(position, type.size);
		}
	}

	public abstract static class StructProperty {
		@Getter
		protected List<Property> properties = new ArrayList<>();

		protected final Property addProperty(PropertyType type, String name) {
			Property property = new Property(type, name);
			properties.add(property);
			return property;
		}
	}

	public final GLBUFFER glBuffer;

	protected int size;
	protected int dirtyLowTide = Integer.MAX_VALUE;
	protected int dirtyHighTide = 0;
	protected ByteBuffer data;
	protected IntBuffer dataInt;
	protected FloatBuffer dataFloat;
	protected final List<Property> properties = new ArrayList<>();

	@Getter
	protected int bindingIndex;

	@SuppressWarnings("unchecked")
	public StructuredBuffer(int glTarget, int glUsage) {
		glBuffer = (GLBUFFER) new GLBuffer(getClass().getSimpleName(), glTarget, glUsage);
	}

	@SuppressWarnings("unchecked")
	public StructuredBuffer(int glTarget, int glUsage, int clUsage) {
		glBuffer = (GLBUFFER) new SharedGLBuffer(getClass().getSimpleName(), glTarget, glUsage, clUsage);
	}

	public boolean isDirty() {
		return dirtyHighTide > 0 && dirtyLowTide < glBuffer.size;
	}

	protected final <T extends StructProperty> T addStruct(T newStructProp) {
		for (Property property : newStructProp.properties)
			appendToBuffer(property);

		// Structs need to align to 16 bytes
		size += (16 - (size % 16)) % 16;

		newStructProp.properties.clear();
		return newStructProp;
	}

	protected final <T extends StructProperty> T[] addStructs(T[] newStructPropArray, Supplier<T> createFunction) {
		for (int i = 0; i < newStructPropArray.length; i++) {
			newStructPropArray[i] = createFunction.get();
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
		dirtyLowTide = min(dirtyLowTide, position);
		dirtyHighTide = max(dirtyHighTide, position + size);
	}

	protected boolean preUpload() { return true; }

	public void initialize() {
		if (data != null)
			destroy();

		glBuffer.initialize(size);
		data = BufferUtils.createByteBuffer(size);
		dataInt = data.asIntBuffer();
		dataFloat = data.asFloatBuffer();

		// Since everything is aligned to a multiple of 4 bytes, we can easily define offsets into dataInt and dataFloat
		for (Property prop : properties)
			prop.offset = prop.position / 4;
	}

	public void bind(int bindingIndex) {
		this.bindingIndex = bindingIndex;
		glBindBufferBase(glBuffer.target, bindingIndex, glBuffer.id);
	}

	public final void upload() {
		if (data == null)
			return;

		if(!preUpload())
			return;

		if (!isDirty())
			return;

		data.position(dirtyLowTide);
		data.limit(dirtyHighTide);

		glBindBuffer(glBuffer.target, glBuffer.id);
		glBufferSubData(glBuffer.target, dirtyLowTide, data);
		glBindBuffer(glBuffer.target, 0);

		data.clear();

		dirtyLowTide = Integer.MAX_VALUE;
		dirtyHighTide = 0;
	}

	public void destroy() {
		if (data == null)
			return;

		for (Property prop : properties)
			prop.offset = -1;

		glBuffer.destroy();
		data = null;
		dataInt = null;
		dataFloat = null;
	}
}
