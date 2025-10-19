package rs117.hd.utils;

import java.nio.IntBuffer;
import java.util.Arrays;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import rs117.hd.opengl.uniforms.UBOCommandBuffer;

import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glColorMask;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL14C.glMultiDrawArrays;
import static org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

@Slf4j
public class CommandBuffer {
	private static final int GL_BIND_VERTEX_ARRAY_TYPE = 0;
	private static final int GL_BIND_ELEMENTS_ARRAY_TYPE = 1;
	private static final int GL_MULTI_DRAW_ARRAYS_TYPE = 2;
	private static final int GL_DRAW_ARRAYS_TYPE = 3;
	private static final int GL_DRAW_ELEMENTS_TYPE = 4;

	private static final int GL_DEPTH_MASK_TYPE = 5;
	private static final int GL_COLOR_MASK_TYPE = 6;

	private static final int UNIFORM_BASE_OFFSET = 7;
	private static final int UNIFORM_WORLD_VIEW_ID = 8;

	private static final int GL_TOGGLE_TYPE = 9; // Combined glEnable & glDisable

	private static final long INT_MASK = 0xFFFF_FFFFL;

	@Setter
	private UBOCommandBuffer uboCommandBuffer;

	private long[] cmd = new long[1 << 20]; // ~1 million calls
	private int writeHead = 0;
	private int readHead = 0;

	private void ensureCapacity(int numLongs) {
		if (writeHead + numLongs >= cmd.length)
			cmd = Arrays.copyOf(cmd, cmd.length * 2);
	}

	public void SetBaseOffset(int x, int y, int z) {
		ensureCapacity(4);
		cmd[writeHead++] = UNIFORM_BASE_OFFSET;
		cmd[writeHead++] = x;
		cmd[writeHead++] = y;
		cmd[writeHead++] = z;
	}

	public void SetWorldViewIndex(int index) {
		ensureCapacity(2);
		cmd[writeHead++] = UNIFORM_WORLD_VIEW_ID;
		cmd[writeHead++] = index;
	}

	public void BindVertexArray(int vao) {
		ensureCapacity(2);
		cmd[writeHead++] = GL_BIND_VERTEX_ARRAY_TYPE;
		cmd[writeHead++] = vao;
	}

	public void BindElementsArray(int ebo) {
		ensureCapacity(2);
		cmd[writeHead++] = GL_BIND_ELEMENTS_ARRAY_TYPE;
		cmd[writeHead++] = ebo;
	}

	public void DepthMask(boolean state) {
		ensureCapacity(2);
		cmd[writeHead++] = GL_DEPTH_MASK_TYPE;
		cmd[writeHead++] = state ? 1 : 0;
	}

	public void ColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
		ensureCapacity(5);
		cmd[writeHead++] = GL_COLOR_MASK_TYPE;
		cmd[writeHead++] = red ? 1 : 0;
		cmd[writeHead++] = green ? 1 : 0;
		cmd[writeHead++] = blue ? 1 : 0;
		cmd[writeHead++] = alpha ? 1 : 0;
	}

	public void MultiDrawArrays(int mode, int[] offsets, int[] counts) {
		assert offsets.length == counts.length;

		ensureCapacity(3 + (offsets.length * 2));
		cmd[writeHead++] = GL_MULTI_DRAW_ARRAYS_TYPE;
		cmd[writeHead++] = mode;
		cmd[writeHead++] = offsets.length;
		for (int i = 0; i < offsets.length; i++) {
			cmd[writeHead++] = offsets[i];
			cmd[writeHead++] = counts[i];
		}
	}

	public void DrawElements(int mode, int count) {
		ensureCapacity(3);
		cmd[writeHead++] = GL_DRAW_ELEMENTS_TYPE;
		cmd[writeHead++] = mode;
		cmd[writeHead++] = count;
	}

	public void DrawArrays(int mode, int offset, int count) {
		ensureCapacity(4);
		cmd[writeHead++] = GL_DRAW_ARRAYS_TYPE;
		cmd[writeHead++] = mode;
		cmd[writeHead++] = offset;
		cmd[writeHead++] = count;
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
			readHead = 0;
			while (readHead < writeHead) {
				int type = (int) cmd[readHead++];
				switch (type) {
					case UNIFORM_BASE_OFFSET: {
						int x = (int) cmd[readHead++];
						int y = (int) cmd[readHead++];
						int z = (int) cmd[readHead++];
						if (uboCommandBuffer != null)
							uboCommandBuffer.sceneBase.set(x, y, z);
						break;
					}
					case UNIFORM_WORLD_VIEW_ID: {
						int id = (int) cmd[readHead++];
						if (uboCommandBuffer != null)
							uboCommandBuffer.worldViewIndex.set(id);
						break;
					}
					case GL_DEPTH_MASK_TYPE: {
						int state = (int) cmd[readHead++];
						glDepthMask(state == 1);
						break;
					}
					case GL_COLOR_MASK_TYPE: {
						int red = (int) cmd[readHead++];
						int green = (int) cmd[readHead++];
						int blue = (int) cmd[readHead++];
						int alpha = (int) cmd[readHead++];
						glColorMask(red == 1, green == 1, blue == 1, alpha == 1);
						break;
					}
					case GL_BIND_VERTEX_ARRAY_TYPE: {
						glBindVertexArray((int) cmd[readHead++]);
						break;
					}
					case GL_BIND_ELEMENTS_ARRAY_TYPE: {
						glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, (int) cmd[readHead++]);
						break;
					}
					case GL_DRAW_ARRAYS_TYPE: {
						int mode = (int) cmd[readHead++];
						int offset = (int) cmd[readHead++];
						int count = (int) cmd[readHead++];

						if (uboCommandBuffer != null && uboCommandBuffer.isDirty())
							uboCommandBuffer.upload();

						glDrawArrays(mode, offset, count);
						break;
					}
					case GL_DRAW_ELEMENTS_TYPE: {
						int mode = (int) cmd[readHead++];
						int elementCount = (int) cmd[readHead++];

						if (uboCommandBuffer != null && uboCommandBuffer.isDirty())
							uboCommandBuffer.upload();

						glDrawElements(mode, elementCount, GL_UNSIGNED_INT, 0L);
						break;
					}
					case GL_MULTI_DRAW_ARRAYS_TYPE: {
						int mode = (int) cmd[readHead++];
						int drawCount = (int) cmd[readHead++];

						if (offsets == null || offsets.capacity() < drawCount) {
							offsets = stack.callocInt(drawCount);
							counts = stack.callocInt(drawCount);
						}

						for (int i = 0; i < drawCount; i++) {
							offsets.put((int) cmd[readHead++]);
							counts.put((int) cmd[readHead++]);
						}

						offsets.flip();
						counts.flip();

						if (uboCommandBuffer != null && uboCommandBuffer.isDirty())
							uboCommandBuffer.upload();

						glMultiDrawArrays(mode, offsets, counts);

						offsets.clear();
						counts.clear();
						break;
					}
					case GL_TOGGLE_TYPE: {
						long packed = cmd[readHead++];
						int capability = (int) (packed & INT_MASK);
						if ((packed >> 32) != 0) {
							glEnable(capability);
						} else {
							glDisable(capability);
						}
						break;
					}
					default:
						throw new IllegalArgumentException("Encountered an unknown DrawCall type: " + type);
				}
			}
		}
	}

	public void reset() {
		writeHead = 0;
	}
}
