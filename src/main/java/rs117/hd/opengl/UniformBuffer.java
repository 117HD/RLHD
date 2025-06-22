package rs117.hd.opengl;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.compute.OpenCLManager;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL15C.glBufferData;
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

		private final int Size;
		private final int Alignment;
		private final int ElementSize;
		private final int ElementCount;
		private final boolean IsArray;

		PropertyType(int Size, int Alignment, int ElementCount) {
			this.Size = Size;
			this.Alignment = Alignment;
			this.ElementSize = Size / ElementCount;
			this.ElementCount = ElementCount;
			this.IsArray = false;
		}

		PropertyType(int Size, int Alignment, int ElementCount, boolean IsArray) {
			this.Size = Size;
			this.Alignment = Alignment;
			this.ElementSize = Size / ElementCount;
			this.ElementCount = ElementCount;
			this.IsArray = IsArray;
		}
	}

	public static class Property {
		private UniformBuffer Owner;
		private int Position;
		private final PropertyType Type;
		private final String Name;

		private Property(PropertyType Type, String Name) {
			this.Type = Type;
			this.Name = Name;
		}

		public final void Set(int NewValue) {
			if(Type != PropertyType.Int) {
				log.warn("UBO {} - Incorrect Setter(int) called for Property: {}", Owner.Buffer.name, Name);
				return;
			}

			if(Owner.Data.getInt(Position) != NewValue) {
				Owner.Data.putInt(Position, NewValue);
				Owner.MarkWaterLine(Position, Type.Size);
			}
		}

		public final void SetV(int... NewValues) { Set(NewValues); }
		public final void Set(int[] NewValues) {
			if(Type != PropertyType.IVec2 && Type != PropertyType.IVec3 && Type != PropertyType.IVec4) {
				log.warn("UBO {} - Incorrect Setter(int[]) called for Property: {}", Owner.Buffer.name, Name);
				return;
			}

			if(NewValues == null) {
				log.warn("UBO {} - Setter(int[]) was provided with null value for Property: {}", Owner.Buffer.name, Name);
				return;
			}

			if ((Type.IsArray && (NewValues.length % Type.ElementCount) != 0) || NewValues.length != Type.ElementCount) {
				log.warn("UBO {} - Setter(int[]) was provided with incorrect number of elements for Property: {}", Owner.Buffer.name, Name);
				return;
			}

			if(Owner.Data == null) {
				log.warn("UBO {} - Hasn't been initialized yet!", Owner.Buffer.name);
				return;
			}

			int ElementCount = !Type.IsArray ? Type.ElementCount : NewValues.length;
			for(int ElementIdx = 0, Offset = Position; ElementIdx < ElementCount; ElementIdx++, Offset += Type.ElementSize) {
				if(Owner.Data.getInt(Offset) != NewValues[ElementIdx]) {
					Owner.Data.putInt(Offset, NewValues[ElementIdx]);
					Owner.MarkWaterLine(Offset, Type.ElementSize);
				}
			}
		}

		public final void Set(float NewValue) {
			if(Type != PropertyType.Float) {
				log.warn("UBO {} - Incorrect Setter(float) called for Property: {}", Owner.Buffer.name, Name);
				return;
			}

			if(Owner.Data.getFloat(Position) != NewValue) {
				Owner.Data.putFloat(Position, NewValue);
				Owner.MarkWaterLine(Position, Type.Size);
			}
		}

		public final void SetV(float... NewValues) { Set(NewValues); }
		public final void Set(float[] NewValues) {
			if(Type != PropertyType.FVec2 && Type != PropertyType.FVec3 && Type != PropertyType.FVec4 && Type != PropertyType.Mat3 && Type != PropertyType.Mat4) {
				log.warn("UBO {} - Incorrect Setter(float[]) called for Property: {}", Owner.Buffer.name, Name);
				return;
			}

			if(NewValues == null) {
				log.warn("UBO {} - Setter(float[]) was provided with null value for Property: {}", Owner.Buffer.name, Name);
				return;
			}

			if ((Type.IsArray && (NewValues.length % Type.ElementCount) != 0) || NewValues.length != Type.ElementCount) {
				log.warn("UBO {} - Setter(float[]) was provided with incorrect number of elements for Property: {}", Owner.Buffer.name, Name);
				return;
			}

			if(Owner.Data == null) {
				log.warn("UBO {} - Hasn't been initialized yet!", Owner.Buffer.name);
				return;
			}

			int ElementCount = !Type.IsArray ? Type.ElementCount : NewValues.length;
			for(int ElementIdx = 0, Offset = Position; ElementIdx < ElementCount; ElementIdx++, Offset += Type.ElementSize) {
				if(Owner.Data.getFloat(Offset) != NewValues[ElementIdx]) {
					Owner.Data.putFloat(Offset, NewValues[ElementIdx]);
					Owner.MarkWaterLine(Offset, Type.ElementSize);
				}
			}
		}
	}

	public interface CreateStructProperty<T extends StructProperty> {
		T create();
	}

	public abstract static class StructProperty {
		protected List<Property> Properties = new ArrayList<>();

		protected final Property AddProperty(PropertyType Type, String Name) {
			Property NewProperty = new Property(Type, Name);
			Properties.add(NewProperty);
			return NewProperty;
		}
	}

	private final GLBuffer Buffer;
	private ByteBuffer Data;

	private int DirtyLowTide = Integer.MAX_VALUE;
	private int DirtyHighTide = Integer.MIN_VALUE;

	public UniformBuffer(String Name) {
		Buffer = new GLBuffer(Name);
	}

	protected final <T extends StructProperty> T AddStruct(T NewStructProp) {
		for(Property Prop : NewStructProp.Properties) {
			AppendToBuffer(Prop);
		}

		// Structs need to align to 16 bytes
		Buffer.size += (int)(16 - (Buffer.size % 16)) % 16;

		NewStructProp.Properties.clear();
		return NewStructProp;
	}

	protected final <T extends StructProperty> T[] AddStructs(T[] NewStructPropArray, CreateStructProperty<T> CreateFunction) {
		for(int i = 0; i < NewStructPropArray.length; i++) {
			NewStructPropArray[i] = CreateFunction.create();
			AddStruct(NewStructPropArray[i]);
		};
		return NewStructPropArray;
	}

	protected Property AddProperty(PropertyType Type, String Name) {
		return AppendToBuffer(new Property(Type, Name));
	}

	private Property AppendToBuffer(Property NewProperty) {
		NewProperty.Owner = this;

		int Padding = (int)(NewProperty.Type.Alignment - (Buffer.size % NewProperty.Type.Alignment)) % NewProperty.Type.Alignment;
		NewProperty.Position = (int)Buffer.size + Padding;

		Buffer.size += NewProperty.Type.Size + Padding;

		return NewProperty;
	}

	private void MarkWaterLine(int Position, int Size) {
		DirtyLowTide = Math.min(DirtyLowTide, Position);
		DirtyHighTide = Math.max(DirtyHighTide, Position + Size);
	}

	public final GLBuffer GetGLBuffer() {
		return Buffer;
	}

	public final void Initialise(int UniformBlockIndex, OpenCLManager openCLManager) {
		Initialise(UniformBlockIndex);

		if(openCLManager != null) {
			openCLManager.recreateCLBuffer(Buffer, CL_MEM_READ_ONLY);
		}
	}

	public final void Initialise(int UniformBlockIndex) {
		if(Data != null) {
			Destroy();
		}

		Buffer.size = HDUtils.ceilPow2(Buffer.size);
		Data = BufferUtils.createByteBuffer((int)Buffer.size);
		Buffer.glBufferId = glGenBuffers();

		glBindBuffer(GL_UNIFORM_BUFFER, Buffer.glBufferId);
		glBufferData(GL_UNIFORM_BUFFER, Buffer.size, GL_STATIC_DRAW);
		glBufferSubData(GL_UNIFORM_BUFFER, 0, Data);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);

		glBindBufferBase(GL_UNIFORM_BUFFER, UniformBlockIndex, Buffer.glBufferId);

		if (HdPlugin.glCaps.OpenGL43 && log.isDebugEnabled()) {
			glObjectLabel(GL_BUFFER, Buffer.glBufferId, Buffer.name);
		}
	}

	public final void Upload() {
		if(Data != null && DirtyLowTide < Buffer.size && DirtyHighTide > 0) {
			Data.position(DirtyLowTide);
			Data.limit(DirtyHighTide);

			glBindBuffer(GL_UNIFORM_BUFFER, Buffer.glBufferId);
			glBufferSubData(GL_UNIFORM_BUFFER, DirtyLowTide, Data);

			glBindBuffer(GL_UNIFORM_BUFFER, 0);

			Data.position(0);
			Data.limit((int)Buffer.size);

			DirtyLowTide = Integer.MAX_VALUE;
			DirtyHighTide = Integer.MIN_VALUE;
		}
	}

	public final void Destroy() {
		if(Data != null) {
			glDeleteBuffers(Buffer.glBufferId);

			Data = null;
			Buffer.glBufferId = -1;

			if (Buffer.clBuffer != 0) {
				clReleaseMemObject(Buffer.clBuffer);
				Buffer.clBuffer = 0;
			}
		}
	}
}
