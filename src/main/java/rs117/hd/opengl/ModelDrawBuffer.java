package rs117.hd.opengl;

import java.nio.ByteBuffer;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.utils.Job;
import rs117.hd.utils.buffer.GLBuffer;

import static net.runelite.api.Constants.*;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_WRITE_ONLY;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glMapBuffer;
import static org.lwjgl.opengl.GL15.glUnmapBuffer;
import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class ModelDrawBuffer extends GLBuffer {
	public static final int STAGING_MODEL_DATA_COUNT = EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE;

	private int[] stagingModelData = new int[STAGING_MODEL_DATA_COUNT * 2];
	private int stagingModelDataOffset = 0;
	private int stagingModelDataNextOffset = 0;
	private int stagingVertexCount = 0;
	private int maxIndicesCount = 0;
	@Getter
	private int indicesCount = 0;

	private final AsyncIndicesWriter asyncIndicesWriter = new AsyncIndicesWriter(this);

	public ModelDrawBuffer(String name) {
		super(name + " Indices", GL_ELEMENT_ARRAY_BUFFER, GL_STREAM_DRAW);
	}

	public final void addModel(int renderBufferOffset, int vertexCount) {
		if (vertexCount > 0) {
			// Check if we're adding a continuous stream of triangles
			if (stagingModelDataOffset > 0 && renderBufferOffset == stagingModelDataNextOffset) {
				stagingModelData[stagingModelDataOffset - 1] += vertexCount;
				stagingModelDataNextOffset += vertexCount;
				stagingVertexCount += vertexCount;
				return;
			}

			stagingModelData[stagingModelDataOffset++] = renderBufferOffset;
			stagingModelData[stagingModelDataOffset++] = vertexCount;
			stagingModelDataNextOffset = renderBufferOffset + vertexCount;
			stagingVertexCount += vertexCount;

			if (stagingModelDataOffset >= EXTENDED_SCENE_SIZE && !asyncIndicesWriter.isInFlight()) {
				asyncIndicesWriter.submit();
			} else if (stagingModelDataOffset >= stagingModelData.length) {
				stagingModelData = Arrays.copyOf(stagingModelData, stagingModelData.length + 32);
			}
		}
	}

	public void flush() {
		if (stagingModelDataOffset > 0) {
			asyncIndicesWriter.submit();
		}
	}

	@Override
	public void destroy() {
		asyncIndicesWriter.complete();
		super.destroy();
	}

	public void draw() {
		asyncIndicesWriter.complete();

		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, id);
		asyncIndicesWriter.unmap();

		glDrawElements(GL_TRIANGLES, indicesCount, GL_UNSIGNED_INT, 0);
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
	}

	public void clear() {
		maxIndicesCount = max(maxIndicesCount, indicesCount);
		stagingModelDataOffset = 0;
		stagingVertexCount = 0;
		indicesCount = 0;
	}

	@RequiredArgsConstructor
	public static final class AsyncIndicesWriter extends Job {
		private final ModelDrawBuffer owner;
		private int[] modelDataToWrite = new int[STAGING_MODEL_DATA_COUNT * 2];
		private ByteBuffer mappedBuffer = null;
		private long mappedBufferAddr;
		private boolean isMapped = false;
		private int modelDataToWriteCount;

		public void unmap() {
			if(isMapped) {
				glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, owner.id);
				glUnmapBuffer(GL_ELEMENT_ARRAY_BUFFER);
				isMapped = false;
			}
		}

		@Override
		protected void prepare() {
			long prevNumBytes = owner.maxIndicesCount * (long) Integer.BYTES;
			long currentNumBytes = ((long) owner.indicesCount + (long) owner.stagingVertexCount) * (long) Integer.BYTES;
			if (currentNumBytes >= owner.size) {
				unmap();
				owner.ensureCapacity(currentNumBytes);
			}

			if (!isMapped || mappedBuffer.capacity() < currentNumBytes) {
				glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, owner.id);
				unmap();
				mappedBuffer = glMapBuffer(
					GL_ELEMENT_ARRAY_BUFFER,
					GL_WRITE_ONLY,
					max(prevNumBytes, currentNumBytes),
					mappedBuffer
				);
				isMapped = mappedBuffer != null;
			}

			modelDataToWriteCount = 0;
			if (isMapped) {
				// Perform Swap, so that we can write the previous staging data whilst the next is written in
				int[] newModelDataToWrite = owner.stagingModelData;
				owner.stagingModelData = modelDataToWrite;
				modelDataToWrite = newModelDataToWrite;

				modelDataToWriteCount = owner.stagingModelDataOffset;
				mappedBufferAddr = MemoryUtil.memAddress(mappedBuffer.clear());
			}
			owner.stagingModelDataOffset = 0;
			owner.stagingVertexCount = 0;
		}

		@Override
		protected void doWork() {
			if (isMapped) {
				long address = mappedBufferAddr + owner.indicesCount * (long) Integer.BYTES;
				for (int modelDataOffset = 0; modelDataOffset < modelDataToWriteCount; ) {
					final int renderBufferOffset = modelDataToWrite[modelDataOffset++];
					final int vertexCount = modelDataToWrite[modelDataOffset++];

					for (int v = 0; v < vertexCount; v++) {
						MemoryUtil.memPutInt(address, renderBufferOffset + v);
						address += 4L;
					}

					owner.indicesCount += vertexCount;
				}
			}
		}
	}
}
