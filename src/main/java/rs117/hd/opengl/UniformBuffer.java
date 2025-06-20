package rs117.hd.opengl;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import org.lwjgl.BufferUtils;

import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL43C.*;

public abstract class UniformBuffer {
	@Retention(RetentionPolicy.RUNTIME)
	protected @interface UBOProperty {
		UBOEntryType value();
	}

	protected interface UBOWriteFunction<T> {
		void write(ByteBuffer Buffer, T Value);
	}

	private static UBOWriteFunction WriteInt = (Buffer, Value) -> Buffer.putInt((int)Value);
	private static UBOWriteFunction WriteIntArray = (Buffer, Value) -> {
		int[] Array = (int[])Value;
		for (int val : Array) {
			Buffer.putInt(val);
		}
	};

	private static UBOWriteFunction WriteFloat = (Buffer, Value) -> Buffer.putFloat((float)Value);
	private static UBOWriteFunction WriteFloatArray = (Buffer, Value) -> {
		float[] Array = (float[])Value;
		for (float val : Array) {
			Buffer.putFloat(val);
		}
	};


	protected enum UBOEntryType {
		Int(4, 4, WriteInt),
		IVec2(8, 8, WriteIntArray),
		IVec3(12, 16, WriteIntArray),
		IVec4(16, 16, WriteIntArray),

		Float(4, 4, WriteFloat),
		FVec2(8, 8, WriteFloatArray),
		FVec3(12, 16, WriteFloatArray),
		FVec4(16, 16, WriteFloatArray),

		Mat3(36, 16, WriteFloatArray),
		Mat4(64, 16, WriteFloatArray);

		protected int Size;
		protected int Alignment;
		protected UBOWriteFunction WriteFunction;

		UBOEntryType(int Size, int Alignment, UBOWriteFunction WriteFunction) {
			this.Size = Size;
			this.Alignment = Alignment;
			this.WriteFunction = WriteFunction;
		}
	}

	public static class UBOEntry<T> {
		protected T Value;
		protected int Padding;
		protected UBOEntryType Type;
		protected String Name;
		protected boolean Dirty;

		private UBOEntry(UBOEntryType Type) {
			this.Type = Type;
			Dirty = true;
		}

		public void Set(T NewValue) {
			if(Value != NewValue) {
				Value = NewValue;
				Dirty = true;
			}
		}

		public T Get() { return Value; }
	}

	private ByteBuffer CPUBuffer;
	private ArrayList<UBOEntry> Entries = new ArrayList<>();
	private int Size;
	private int glBuffer;

	public UniformBuffer() {
		try {
			Field[] UBOPropertieFields = this.getClass().getFields();
			for(Field PropField : UBOPropertieFields) {
				UBOProperty Prop = PropField.getAnnotation(UBOProperty.class);
				if(Prop != null) {
					Class<?> FieldType = PropField.getType();
					Constructor<?> Constructor = FieldType.getDeclaredConstructor(UBOEntryType.class);

					UBOEntryType Type = Prop.value();
					UBOEntry<?> NewEntry = (UBOEntry<?>) Constructor.newInstance(Type);

					NewEntry.Padding = (Type.Alignment - (Size % Type.Alignment)) % Type.Alignment;
					NewEntry.Name = PropField.getName();

					Entries.add(NewEntry);

					Size += NewEntry.Type.Size + NewEntry.Padding;
					PropField.setAccessible(true);
					PropField.set(this, NewEntry);
				}
			}
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new RuntimeException(e);
		} catch (InvocationTargetException e) {
			throw new RuntimeException(e);
		} catch (InstantiationException e) {
			throw new RuntimeException(e);
		}
	}

	public void Initialise(int UniformBlockIndex) {
		if(CPUBuffer != null) {
			Destroy();
		}

		CPUBuffer = BufferUtils.createByteBuffer(Size);
		glBuffer = glGenBuffers();

		glBindBuffer(GL_UNIFORM_BUFFER, glBuffer);
		glBufferData(GL_UNIFORM_BUFFER, Size, GL_STATIC_DRAW);
		glBufferSubData(GL_UNIFORM_BUFFER, 0, CPUBuffer);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);

		glBindBufferBase(GL_UNIFORM_BUFFER, UniformBlockIndex, glBuffer);
	}

	public void UploadUniforms() {
		if(CPUBuffer == null) {
			return;
		}

		CPUBuffer.clear();
		for(UBOEntry Entry : Entries) {
			try {
				if (Entry.Padding > 0) {
					CPUBuffer.position(CPUBuffer.position() + Entry.Padding);
				}
				if (Entry.Dirty) {
					if(Entry.Value != null) {
						Entry.Type.WriteFunction.write(CPUBuffer, Entry.Value);
					} else {
						// Value is null, meaning not initialized fill in with zeros
						for(int i = 0; i < Entry.Type.Size; i++) {
							CPUBuffer.put((byte)0);
						}
					}
					Entry.Dirty = false;
				} else {
					CPUBuffer.position(CPUBuffer.position() + Entry.Type.Size);
				}
			}catch (Exception Ex) {
				// TODO Print useful info here since we have the prop name
			}
		}
		CPUBuffer.flip();

		glBindBuffer(GL_UNIFORM_BUFFER, glBuffer);
		glBufferSubData(GL_UNIFORM_BUFFER, 0, CPUBuffer);

		glBindBuffer(GL_UNIFORM_BUFFER, 0);
	}

	public void Destroy() {
		if(CPUBuffer != null) {
			glDeleteBuffers(glBuffer);

			CPUBuffer = null;
			glBuffer = -1;
		}
	}
}
