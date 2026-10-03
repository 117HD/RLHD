package rs117.hd.utils;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import rs117.hd.opengl.GLFence;
import rs117.hd.opengl.shader.ShaderProgram;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40.glDrawArraysIndirect;
import static org.lwjgl.opengl.GL40.glDrawElementsIndirect;
import static org.lwjgl.opengl.GL43.glMultiDrawArraysIndirect;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class CommandBuffer {
	private enum CommandType {
		MULTI_DRAW_ARRAYS,
		MULTI_DRAW_ARRAYS_INDIRECT,
		DRAW_ARRAYS,
		DRAW_ARRAYS_INDIRECT,
		DRAW_ELEMENTS,
		DRAW_ELEMENTS_INDIRECT,
		BIND_VERTEX_ARRAY,
		BIND_INDIRECT_ARRAY,
		BIND_TEXTURE_UNIT,
		DEPTH_MASK,
		COLOR_MASK,
		BLEND_FUNC,
		USE_PROGRAM,
		TIMER,
		TOGGLE,
		FENCE_SYNC,
		EXECUTE_SUB_COMMAND_BUFFER;

		private boolean isDrawCall() {
			return ordinal() < BIND_VERTEX_ARRAY.ordinal();
		}
	}

	private static final CommandType[] COMMAND_TYPES = CommandType.values();

	private static final long INT_MASK = 0xFFFF_FFFFL;
	private static final int DRAW_MODE_MASK = 0xF;

	private static final ThreadLocal<ArrayDeque<CommandBuffer>> CALL_STACK = ThreadLocal.withInitial(ArrayDeque::new);

	private final int[] scratch = new int[16];
	private Object[] objects = new Object[8];
	private int objectCount = 0;
	private int lastObjectIdx = -1;

	public final String name;

	@Setter
	private FrameTimer frameTimer;

	private long[] cmd = new long[(int) KiB];
	private int writeHead = 0;

	public CommandBuffer(String name) {
		this.name = name;
	}

	private void ensureCapacity(int numLongs) {
		if (writeHead + numLongs >= cmd.length)
			cmd = Arrays.copyOf(cmd, cmd.length * 2);
	}

	private boolean includes(CommandBuffer subCommandBuffer) {
		if (this == subCommandBuffer)
			return true;
		for (int i = 0; i < objectCount; i++)
			if (objects[i] instanceof CommandBuffer && ((CommandBuffer) objects[i]).includes(this))
				return true;
		return false;
	}

	@Override
	public String toString() {
		return String.format("%s (size: %d)", name, writeHead);
	}

	public boolean isEmpty() {
		return writeHead == 0;
	}

	public void BindVertexArray(int vao, GLBuffer ebo) {
		ensureCapacity(2);
		cmd[writeHead++] = CommandType.BIND_VERTEX_ARRAY.ordinal() & 0xFF;
		cmd[writeHead++] = (long) writeObject(ebo) << 32 | vao & INT_MASK;
	}

	public void BindVertexArray(int vao) {
		BindVertexArray(vao, null);
	}

	public void FenceSync(GLFence fence, int condition) {
		ensureCapacity(2);
		cmd[writeHead++] = CommandType.FENCE_SYNC.ordinal() & 0xFF | (long) condition << 8;
		cmd[writeHead++] = writeObject(fence);
	}

	public void BindIndirectArray(int ido) {
		ensureCapacity(1);
		cmd[writeHead++] = CommandType.BIND_INDIRECT_ARRAY.ordinal() & 0xFF | (long) ido << 8;
	}

	public void BindTextureUnit(int type, int texId, int bindingIndex) {
		ensureCapacity(2);
		cmd[writeHead++] = CommandType.BIND_TEXTURE_UNIT.ordinal() & 0xFF | (long) type << 8;
		cmd[writeHead++] = texId | (long) bindingIndex << 32;
	}

	public void SetShader(ShaderProgram program) {
		ensureCapacity(1);
		int objectIdx = writeObject(program);
		cmd[writeHead++] = CommandType.USE_PROGRAM.ordinal() & 0xFF | (long) objectIdx << 8;
	}

	public void PushTimer(Timer timer) {
		ensureCapacity(1);
		cmd[writeHead++] = CommandType.TIMER.ordinal() & 0xFF | 1 << 8 | (long) timer.ordinal() << 9;
	}

	public void PopTimer(Timer timer) {
		ensureCapacity(1);
		cmd[writeHead++] = CommandType.TIMER.ordinal() & 0xFF | (long) timer.ordinal() << 9;
	}

	public void ExecuteSubCommandBuffer(CommandBuffer subCommandBuffer) {
		ensureCapacity(1);
		assert !subCommandBuffer.includes(this);
		int objectIdx = writeObject(subCommandBuffer);
		cmd[writeHead++] = CommandType.EXECUTE_SUB_COMMAND_BUFFER.ordinal() & 0xFF | (long) objectIdx << 8;
	}

	public void DepthMask(boolean writeDepth) {
		ensureCapacity(1);
		cmd[writeHead++] = CommandType.DEPTH_MASK.ordinal() & 0xFF | (writeDepth ? 1 : 0) << 8;
	}

	public void ColorMask(boolean writeRed, boolean writeGreen, boolean writeBlue, boolean writeAlpha) {
		ensureCapacity(1);
		cmd[writeHead++] =
			CommandType.COLOR_MASK.ordinal() & 0xFF |
			(writeRed ? 1 : 0) << 8 |
			(writeGreen ? 1 : 0) << 9 |
			(writeBlue ? 1 : 0) << 10 |
			(writeAlpha ? 1 : 0) << 11;
	}

	public void BlendFunc(int sfactorRGB, int dfactorRGB, int sfactorAlpha, int dfactorAlpha) {
		ensureCapacity(3);

		cmd[writeHead++] = CommandType.BLEND_FUNC.ordinal() & 0xFF;
		cmd[writeHead++] = ((long) sfactorRGB & INT_MASK) | ((long) dfactorRGB & INT_MASK) << 32;
		cmd[writeHead++] = ((long) sfactorAlpha & INT_MASK) | ((long) dfactorAlpha & INT_MASK) << 32;
	}

	public void MultiDrawArrays(int mode, int[] offsets, int[] counts) {
		MultiDrawArrays(mode, offsets, counts, counts.length);
	}

	public void MultiDrawArrays(int mode, int[] offsets, int[] counts, int drawCount) {
		assert offsets.length == counts.length;
		assert counts.length >= drawCount;
		assert (mode & DRAW_MODE_MASK) == mode;
		if (drawCount == 0)
			return;

		ensureCapacity(1 + drawCount);
		cmd[writeHead++] = CommandType.MULTI_DRAW_ARRAYS.ordinal() & 0xFF | mode << 8 | (long) drawCount << 32;
		for (int i = 0; i < drawCount; i++)
			cmd[writeHead++] = (long) offsets[i] << 32 | counts[i] & INT_MASK;
	}

	public void DrawElements(int mode, int vertexCount, long offset) {
		ensureCapacity(2);
		cmd[writeHead++] = CommandType.DRAW_ELEMENTS.ordinal() & 0xFF | (mode & DRAW_MODE_MASK) << 8 | (long) vertexCount << 32;
		cmd[writeHead++] = offset;
	}

	public void DrawArrays(int mode, int offset, int vertexCount) {
		ensureCapacity(2);
		cmd[writeHead++] = CommandType.DRAW_ARRAYS.ordinal() & 0xFF | (mode & DRAW_MODE_MASK) << 8;
		cmd[writeHead++] = (long) offset << 32 | vertexCount & INT_MASK;
	}

	public void DrawArraysIndirect(int mode, int vertexOffset, int vertexCount, GpuIntBuffer indirectBuffer) {
		ensureCapacity(2);

		// https://registry.khronos.org/OpenGL-Refpages/gl4/html/glDrawArraysIndirect.xhtml
		int indirectOffset = indirectBuffer.position();
		try {
			scratch[0] = vertexCount;  // count
			scratch[1] = 1;            // primCount
			scratch[2] = vertexOffset; // first
			scratch[3] = 0;            // baseInstance (reserved 4.1 prior)
			indirectBuffer.ensureCapacity(4).getBuffer().put(scratch, 0, 4);
		} catch (Exception e) {
			log.debug(
				"Failed to write DrawArraysIndirect buffer position={} remaining={} capacity={}",
				indirectBuffer.getBuffer().position(),
				indirectBuffer.getBuffer().remaining(),
				indirectBuffer.getBuffer().capacity(),
				e
			);
		}

		cmd[writeHead++] = CommandType.DRAW_ARRAYS_INDIRECT.ordinal() & 0xFF | (long) mode << 8;
		cmd[writeHead++] = (long) indirectOffset * Integer.BYTES;
	}

	public void DrawElementsIndirect(int mode, int indexCount, int indexOffset, GpuIntBuffer indirectBuffer) {
		ensureCapacity(2);

		// https://registry.khronos.org/OpenGL-Refpages/gl4/html/glDrawElementsIndirect.xhtml
		int indirectOffset = indirectBuffer.position();
		try {
			scratch[0] = indexCount;  // count
			scratch[1] = 1;           // instanceCount
			scratch[2] = indexOffset; // firstIndex
			scratch[3] = 0;           // baseVertex
			scratch[4] = 0;           // baseInstance

			indirectBuffer.ensureCapacity(5).getBuffer().put(scratch, 0, 5);
		} catch (Exception e) {
			log.debug(
				"Failed to write DrawArraysIndirect buffer position={} remaining={} capacity={}",
				indirectBuffer.getBuffer().position(),
				indirectBuffer.getBuffer().remaining(),
				indirectBuffer.getBuffer().capacity(),
				e
			);
		}

		cmd[writeHead++] = CommandType.DRAW_ELEMENTS_INDIRECT.ordinal() & 0xFF | (long) mode << 8;
		cmd[writeHead++] = (long) indirectOffset * Integer.BYTES;
	}

	public void MultiDrawArraysIndirect(int mode, int[] vertexOffsets, int[] vertexCounts, GpuIntBuffer indirectBuffer) {
		MultiDrawArraysIndirect(mode, vertexOffsets, vertexCounts, vertexCounts.length, indirectBuffer);
	}

	public void MultiDrawArraysIndirect(int mode, int[] vertexOffsets, int[] vertexCounts, int drawCount, GpuIntBuffer indirectBuffer) {
		assert vertexOffsets.length == vertexCounts.length;
		assert vertexCounts.length >= drawCount;
		assert (mode & DRAW_MODE_MASK) == mode;
		if (drawCount == 0)
			return;

		ensureCapacity(2);
		final int indirectOffset = indirectBuffer.position();

		// https://registry.khronos.org/OpenGL-Refpages/gl4/html/glMultiDrawArraysIndirect.xhtml
		indirectBuffer.ensureCapacity(drawCount * 4);
		try {
			final IntBuffer buf = indirectBuffer.getBuffer();
			int pos = 0;
			for (int i = 0; i < drawCount; i++) {
				scratch[pos++] = vertexCounts[i];  // count
				scratch[pos++] = 1;                // instanceCount
				scratch[pos++] = vertexOffsets[i]; // first
				scratch[pos++] = 0;                // baseInstance
				if(pos >= scratch.length) {
					buf.put(scratch);
					pos = 0;
				}
			}
			if(pos > 0) buf.put(scratch, 0, pos);
		} catch (Exception e) {
			log.debug(
				"Failed to write DrawArraysIndirect buffer drawCount={} position={} remaining={} capacity={}",
				drawCount,
				indirectBuffer.getBuffer().position(),
				indirectBuffer.getBuffer().remaining(),
				indirectBuffer.getBuffer().capacity(),
				e
			);
		}

		cmd[writeHead++] = CommandType.MULTI_DRAW_ARRAYS_INDIRECT.ordinal() & 0xFF | (long) mode << 8 | (long) drawCount << 32;
		cmd[writeHead++] = (long) indirectOffset * Integer.BYTES;
	}

	public void Enable(int capability) {
		Toggle(capability, true);
	}

	public void Disable(int capability) {
		Toggle(capability, false);
	}

	public void Toggle(int capability, boolean enabled) {
		ensureCapacity(1);
		cmd[writeHead++] = CommandType.TOGGLE.ordinal() | (enabled ? 1L : 0L) << 8 | (capability & INT_MASK) << 9;
	}

	public void execute(RenderState renderState) {
		var callStack = CALL_STACK.get();
		if (callStack.contains(this))
			throw new IllegalStateException(String.format(
				"Command buffer recursion error: [%s, %s]",
				callStack.stream().map(Object::toString).collect(Collectors.joining(", ")),
				this
			));

		if (frameTimer != null)
			frameTimer.begin(Timer.EXECUTE_COMMAND_BUFFER);
		callStack.push(this);

		// Force VAO state to reapply to ensure it is in sync with the render state
		renderState.vao.invalidate();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer offsets = null, counts = null;
			long data;
			int readHead = 0, type;
			int lastType = -1;
			while (readHead < writeHead) {
				// Casting from long to int keeps the lower 32 bits
				data = cmd[readHead++];
				type = (int) data & 0xFF;
				if (type >= COMMAND_TYPES.length)
					throw new IllegalArgumentException("Encountered an unknown DrawCall type: " + type);
				final CommandType commandType = COMMAND_TYPES[type];
				if (commandType.isDrawCall() && (lastType == -1 || !COMMAND_TYPES[lastType].isDrawCall()))
					renderState.apply();
				lastType = type;

				switch (commandType) {
					case DEPTH_MASK: {
						final int state = (int) (data >> 8) & 1;
						renderState.depthMask.set(state == 1);
						break;
					}
					case COLOR_MASK: {
						final boolean red = ((data >> 8) & 1) == 1;
						final boolean green = ((data >> 9) & 1) == 1;
						final boolean blue = ((data >> 10) & 1) == 1;
						final boolean alpha = ((data >> 11) & 1) == 1;
						renderState.colorMask.set(red, green, blue, alpha);
						break;
					}
					case BLEND_FUNC: {
						data = cmd[readHead++];
						final int sfactorRGB = (int) data;
						final int dfactorRGB = (int) (data >>> 32);

						data = cmd[readHead++];
						final int sfactorAlpha = (int) data;
						final int dfactorAlpha = (int) (data >>> 32);
						renderState.blendFunc.set(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha);
						break;
					}
					case BIND_VERTEX_ARRAY: {
						data = cmd[readHead++];
						final int eboIdx = (int) (data >> 32);
						final int vao = (int) data;
						final int ebo = eboIdx >= 0 ? ((GLBuffer) objects[eboIdx]).id : 0;
						renderState.vao.setVaoAndEbo(vao, ebo);
						break;
					}
					case BIND_INDIRECT_ARRAY: {
						renderState.ido.set((int) (data >> 8));
						break;
					}
					case BIND_TEXTURE_UNIT: {
						final int texType = (int) (data >> 8);
						data = cmd[readHead++];
						final int texUnit = (int) (data >> 32);
						final int texId = (int) data;

						renderState.texture.set(texType, texUnit, texId);
						break;
					}
					case USE_PROGRAM: {
						final int objectIdx = (int) (data >> 8);
						renderState.program.set((ShaderProgram) objects[objectIdx]);
						break;
					}
					case TIMER: {
						if (frameTimer != null) {
							final int timerOrdinal = (int) (data >> 9);
							assert timerOrdinal >= 0 && timerOrdinal < Timer.TIMERS.length;
							final var timer = Timer.TIMERS[timerOrdinal];
							if (((data >> 8) & 1) == 1) {
								frameTimer.begin(timer);
							} else {
								frameTimer.end(timer);
							}
						}
						break;
					}
					case TOGGLE: {
						final int capability = (int) (data >>> 9);
						if (((data >> 8) & 1) != 0) {
							renderState.enable.set(capability);
						} else {
							renderState.disable.set(capability);
						}
						break;
					}
					case FENCE_SYNC: {
						final int condition = (int) (data >> 8);
						GLFence fence = (GLFence) objects[(int) cmd[readHead++]];
						fence.handle = glFenceSync(condition, 0);
						break;
					}
					case DRAW_ARRAYS: {
						final int mode = (int) data >> 8;
						data = cmd[readHead++];
						final int offset = (int) (data >> 32);
						final int count = (int) data;

						glDrawArrays(mode, offset, count);
						break;
					}
					case DRAW_ELEMENTS: {
						final int mode = (int) data >> 8;
						final int vertexCount = (int) (data >> 32);
						glDrawElements(mode, vertexCount, GL_UNSIGNED_INT, cmd[readHead++]);
						break;
					}
					case MULTI_DRAW_ARRAYS: {
						final int mode = (int) data >> 8;
						final int drawCount = (int) (data >> 32);

						if (offsets == null || offsets.capacity() < drawCount) {
							offsets = stack.callocInt(drawCount);
							counts = stack.callocInt(drawCount);
						}

						for (int i = 0; i < drawCount; i++) {
							data = cmd[readHead++];
							offsets.put((int) (data >> 32));
							counts.put((int) data);
						}

						offsets.flip();
						counts.flip();

						glMultiDrawArrays(mode, offsets, counts);

						offsets.clear();
						counts.clear();
						break;
					}
					case DRAW_ARRAYS_INDIRECT: {
						final int mode = (int) data >> 8;
						glDrawArraysIndirect(mode, cmd[readHead++]);
						break;
					}
					case DRAW_ELEMENTS_INDIRECT: {
						final int mode = (int) data >> 8;
						glDrawElementsIndirect(mode, GL_UNSIGNED_INT, cmd[readHead++]);
						break;
					}
					case MULTI_DRAW_ARRAYS_INDIRECT: {
						final int mode = (int) data >> 8;
						final int drawCount = (int) (data >> 32);
						glMultiDrawArraysIndirect(mode, cmd[readHead++], drawCount, 0);
						break;
					}
					case EXECUTE_SUB_COMMAND_BUFFER: {
						((CommandBuffer) objects[(int) (data >> 8)]).execute(renderState);
						break;
					}
					default:
						throw new IllegalArgumentException("Encountered an unknown DrawCall type: " + type);
				}
			}
			renderState.apply();
		} finally {
			callStack.pop();
			if (frameTimer != null)
				frameTimer.end(Timer.EXECUTE_COMMAND_BUFFER);
		}
	}

	private int writeObject(Object obj) {
		if (obj == null)
			return -1;

		if (lastObjectIdx >= 0 && objects[lastObjectIdx] == obj)
			return lastObjectIdx;

		for (int i = 0; i < objectCount; i++)
			if (objects[i] == obj)
				return lastObjectIdx = i;

		if (objectCount == objects.length)
			objects = Arrays.copyOf(objects, objects.length * 2);
		objects[objectCount] = obj;
		return lastObjectIdx = objectCount++;
	}

	public void reset() {
		Arrays.fill(objects, 0, objectCount, null);

		writeHead = 0;
		objectCount = 0;
		lastObjectIdx = -1;
	}
}
