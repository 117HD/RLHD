package rs117.hd.utils;

import java.nio.IntBuffer;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import rs117.hd.opengl.shader.ShaderProgram;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40.glDrawArraysIndirect;
import static org.lwjgl.opengl.GL40.glDrawElementsIndirect;
import static org.lwjgl.opengl.GL43.glMultiDrawArraysIndirect;

@Slf4j
public class CommandBuffer {
	public static boolean SKIP_DEPTH_MASKING;

	private static final int GL_MULTI_DRAW_ARRAYS_TYPE = 0;
	private static final int GL_MULTI_DRAW_ARRAYS_INDIRECT_TYPE = 1;
	private static final int GL_DRAW_ARRAYS_TYPE = 2;
	private static final int GL_DRAW_ARRAYS_INDIRECT_TYPE = 3;
	private static final int GL_DRAW_ELEMENTS_TYPE = 4;
	private static final int GL_DRAW_ELEMENTS_INDIRECT_TYPE = 5;
	private static final int GL_DRAW_CALL_TYPE_COUNT = 6;

	private static final int GL_BIND_VERTEX_ARRAY_TYPE = 6;
	private static final int GL_BIND_ELEMENTS_ARRAY_TYPE = 7;
	private static final int GL_BIND_INDIRECT_ARRAY_TYPE = 8;
	private static final int GL_BIND_TEXTURE_UNIT_TYPE = 9;
	private static final int GL_DEPTH_MASK_TYPE = 10;
	private static final int GL_COLOR_MASK_TYPE = 11;
	private static final int GL_USE_PROGRAM = 12;

	private static final int GL_TOGGLE_TYPE = 13; // Combined glEnable & glDisable

	private static final long INT_MASK = 0xFFFF_FFFFL;
	private static final int DRAW_MODE_MASK = 0xF;

	private final Object[] objects = new Object[10];
	private int objectCount = 0;

	private final RenderState renderState;

	private long[] cmd = new long[1 << 20]; // ~1 million calls
	private int writeHead = 0;

	public CommandBuffer(RenderState renderState) {
		this.renderState = renderState;
	}

	private void ensureCapacity(int numLongs) {
		if (writeHead + numLongs >= cmd.length)
			cmd = Arrays.copyOf(cmd, cmd.length * 2);
	}

	public void BindVertexArray(int vao) {
		ensureCapacity(1);
		cmd[writeHead++] = GL_BIND_VERTEX_ARRAY_TYPE & 0xFF | (long) vao << 8;
	}

	public void BindElementsArray(int ebo) {
		ensureCapacity(1);
		cmd[writeHead++] = GL_BIND_ELEMENTS_ARRAY_TYPE & 0xFF | (long) ebo << 8;
	}

	public void BindIndirectArray(int ido) {
		ensureCapacity(1);
		cmd[writeHead++] = GL_BIND_INDIRECT_ARRAY_TYPE & 0xFF | (long) ido << 8;
	}

	public void BindTextureUnit(int type, int texId, int bindingIndex) {
		ensureCapacity(1);
		cmd[writeHead++] = GL_BIND_TEXTURE_UNIT_TYPE & 0xFF | (long) type << 8;
		cmd[writeHead++] = texId | (long) bindingIndex << 32;
	}

	public void SetShader(ShaderProgram program) {
		ensureCapacity(1);
		int objectIdx = writeObject(program);
		cmd[writeHead++] = GL_USE_PROGRAM & 0xFF | (long) objectIdx << 8;
	}

	public void DepthMask(boolean writeDepth) {
		ensureCapacity(1);
		cmd[writeHead++] = GL_DEPTH_MASK_TYPE & 0xFF | (writeDepth ? 1 : 0) << 8;
	}

	public void ColorMask(boolean writeRed, boolean writeGreen, boolean writeBlue, boolean writeAlpha) {
		ensureCapacity(1);
		cmd[writeHead++] =
			GL_COLOR_MASK_TYPE & 0xFF |
			(writeRed ? 1 : 0) << 8 |
			(writeGreen ? 1 : 0) << 9 |
			(writeBlue ? 1 : 0) << 10 |
			(writeAlpha ? 1 : 0) << 11;
	}

	public void MultiDrawArrays(int mode, int[] offsets, int[] counts) {
		assert offsets.length == counts.length;
		assert (mode & DRAW_MODE_MASK) == mode;
		if (offsets.length == 0)
			return;

		ensureCapacity(1 + offsets.length);
		cmd[writeHead++] = GL_MULTI_DRAW_ARRAYS_TYPE & 0xFF | mode << 8 | (long) offsets.length << 32;
		for (int i = 0; i < offsets.length; i++)
			cmd[writeHead++] = (long) offsets[i] << 32 | counts[i] & INT_MASK;
	}

	public void DrawElements(int mode, int vertexCount, long offset) {
		ensureCapacity(2);
		cmd[writeHead++] = GL_DRAW_ELEMENTS_TYPE & 0xFF | (mode & DRAW_MODE_MASK) << 8 | (long) vertexCount << 32;
		cmd[writeHead++] = offset;
	}

	public void DrawArrays(int mode, int offset, int vertexCount) {
		ensureCapacity(2);
		cmd[writeHead++] = GL_DRAW_ARRAYS_TYPE & 0xFF | (mode & DRAW_MODE_MASK) << 8;
		cmd[writeHead++] = (long) offset << 32 | vertexCount & INT_MASK;
	}

	public void DrawArraysIndirect(int mode, int vertexOffset, int vertexCount, GpuIntBuffer indirectBuffer) {
		ensureCapacity(2);

		// https://registry.khronos.org/OpenGL-Refpages/gl4/html/glDrawArraysIndirect.xhtml
		int indirectOffset = indirectBuffer.position();
		indirectBuffer.ensureCapacity(4).getBuffer()
			.put(vertexCount)  // count
			.put(1)         // primCount
			.put(vertexOffset) // first
			.put(0);        // baseInstance (reserved 4.1 prior)

		cmd[writeHead++] = GL_DRAW_ARRAYS_INDIRECT_TYPE & 0xFF | (long) mode << 8;
		cmd[writeHead++] = (long) indirectOffset * Integer.BYTES;
	}

	public void DrawElementsIndirect(int mode, int indexCount, int indexOffset, GpuIntBuffer indirectBuffer) {
		ensureCapacity(2);

		// https://registry.khronos.org/OpenGL-Refpages/gl4/html/glDrawElementsIndirect.xhtml
		int indirectOffset = indirectBuffer.position();
		indirectBuffer.ensureCapacity(5).getBuffer()
			.put(indexCount)    // count
			.put(1)          // instanceCount
			.put(indexOffset)   // firstIndex
			.put(0)          // baseVertex
			.put(0);         // baseInstance

		cmd[writeHead++] = GL_DRAW_ELEMENTS_INDIRECT_TYPE & 0xFF | (long) mode << 8;
		cmd[writeHead++] = (long) indirectOffset * Integer.BYTES;
	}

	public void MultiDrawArraysIndirect(int mode, int[] vertexOffsets, int[] vertexCounts, GpuIntBuffer indirectBuffer) {
		assert vertexOffsets.length == vertexCounts.length;
		assert (mode & DRAW_MODE_MASK) == mode;
		int drawCount = vertexOffsets.length;
		if (drawCount == 0)
			return;

		ensureCapacity(2);
		int indirectOffset = indirectBuffer.position();

		// https://registry.khronos.org/OpenGL-Refpages/gl4/html/glMultiDrawArraysIndirect.xhtml
		indirectBuffer.ensureCapacity(drawCount * 4);
		IntBuffer buf = indirectBuffer.getBuffer();
		for (int i = 0; i < drawCount; i++) {
			buf.put(vertexCounts[i]);  // count
			buf.put(1);              // instanceCount
			buf.put(vertexOffsets[i]); // first
			buf.put(0);             // baseInstance
		}

		cmd[writeHead++] = GL_MULTI_DRAW_ARRAYS_INDIRECT_TYPE & 0xFF | (long) mode << 8 | (long) drawCount << 32;
		cmd[writeHead++] = (long) indirectOffset * Integer.BYTES;
	}


	public void Enable(int capability) {
		Toggle(capability, true);
	}

	public void Disable(int capability) {
		Toggle(capability, false);
	}

	public void Toggle(int capability, boolean enabled) {
		ensureCapacity(2);
		cmd[writeHead++] = GL_TOGGLE_TYPE;
		cmd[writeHead++] = (enabled ? 1L : 0) << 32 | capability & INT_MASK;
	}

	public void execute() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer offsets = null, counts = null;
			int readHead = 0;
			while (readHead < writeHead) {
				// Casting from long to int keeps the lower 32 bits
				long data = cmd[readHead++];
				int type = (int) data & 0xFF;
				if (type < GL_DRAW_CALL_TYPE_COUNT)
					renderState.apply();

				switch (type) {
					case GL_DEPTH_MASK_TYPE: {
						int state = (int) (data >> 8) & 1;
						if (SKIP_DEPTH_MASKING)
							continue;
						renderState.depthMask.set(state == 1);
						break;
					}
					case GL_COLOR_MASK_TYPE: {
						boolean red = ((data >> 8) & 1) == 1;
						boolean green = ((data >> 9) & 1) == 1;
						boolean blue = ((data >> 10) & 1) == 1;
						boolean alpha = ((data >> 11) & 1) == 1;
						renderState.colorMask.set(red, green, blue, alpha);
						break;
					}
					case GL_BIND_VERTEX_ARRAY_TYPE: {
						renderState.vao.set((int) (data >> 8));
						break;
					}
					case GL_BIND_ELEMENTS_ARRAY_TYPE: {
						renderState.ebo.set((int) (data >> 8));
						break;
					}
					case GL_BIND_INDIRECT_ARRAY_TYPE: {
						renderState.ido.set((int) (data >> 8));
						break;
					}
					case GL_BIND_TEXTURE_UNIT_TYPE: {
						long packed = cmd[readHead++];
						int texType = (int) (data >> 8);
						int texUnit = (int) (packed >> 32);
						int texId = (int) packed;

						glActiveTexture(texUnit);
						glBindTexture(texType, texId);
						break;
					}
					case GL_USE_PROGRAM: {
						int objectIdx = (int) (data >> 8);
						renderState.program.set((ShaderProgram) objects[objectIdx]);
						break;
					}
					case GL_TOGGLE_TYPE: {
						long packed = cmd[readHead++];
						int capability = (int) (packed & INT_MASK);
						if ((packed >> 32) != 0) {
							renderState.enable.set(capability);
						} else {
							renderState.disable.set(capability);
						}
						break;
					}
					case GL_DRAW_ARRAYS_TYPE: {
						long packed = cmd[readHead++];
						int mode = (int) data >> 8;
						int offset = (int) (packed >> 32);
						int count = (int) packed;

						glDrawArrays(mode, offset, count);
						break;
					}
					case GL_DRAW_ELEMENTS_TYPE: {
						int mode = (int) data >> 8;
						int vertexCount = (int) (data >> 32);
						long byteOffset = cmd[readHead++];

						glDrawElements(mode, vertexCount, GL_UNSIGNED_INT, byteOffset);
						break;
					}
					case GL_MULTI_DRAW_ARRAYS_TYPE: {
						int mode = (int) data >> 8;
						int drawCount = (int) (data >> 32);

						if (offsets == null || offsets.capacity() < drawCount) {
							offsets = stack.callocInt(drawCount);
							counts = stack.callocInt(drawCount);
						}

						for (int i = 0; i < drawCount; i++) {
							long packed = cmd[readHead++];
							offsets.put((int) (packed >> 32));
							counts.put((int) packed);
						}

						offsets.flip();
						counts.flip();

						glMultiDrawArrays(mode, offsets, counts);

						offsets.clear();
						counts.clear();
						break;
					}
					case GL_DRAW_ARRAYS_INDIRECT_TYPE: {
						int mode = (int) data >> 8;
						glDrawArraysIndirect(mode, cmd[readHead++]);
						break;
					}
					case GL_DRAW_ELEMENTS_INDIRECT_TYPE: {
						int mode = (int) data >> 8;
						glDrawElementsIndirect(mode, GL_UNSIGNED_INT, cmd[readHead++]);
						break;
					}
					case GL_MULTI_DRAW_ARRAYS_INDIRECT_TYPE: {
						int mode = (int) data >> 8;
						int drawCount = (int) (data >> 32);
						long offset = cmd[readHead++];
						glMultiDrawArraysIndirect(mode, offset, drawCount, 0);
						break;
					}
					default:
						throw new IllegalArgumentException("Encountered an unknown DrawCall type: " + type);
				}
			}
			renderState.apply();
		}
	}

	private int writeObject(Object obj) {
		for (int i = 0; i < objectCount; i++) {
			if (objects[i] == obj) {
				return i;
			}
		}
		objects[objectCount] = obj;
		return objectCount++;
	}

	public void reset() {
		writeHead = 0;
		renderState.reset();
	}
}
