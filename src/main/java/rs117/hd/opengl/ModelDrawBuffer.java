package rs117.hd.opengl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
	private int stagingVertexCount = 0;
	private int maxIndicesCount = 0;
	@Getter
	private int indicesCount = 0;

	private final AsyncIndicesWriter asyncIndicesWriter = new AsyncIndicesWriter(this);

	public ModelDrawBuffer(String name) {
		super(name + " Indices", GL_ELEMENT_ARRAY_BUFFER, GL_STREAM_DRAW);
	}

	public ModelDrawBuffer addModel(int renderBufferOffset, int vertexCount) {
		if (vertexCount > 0) {
			stagingModelData[stagingModelDataOffset++] = renderBufferOffset;
			stagingModelData[stagingModelDataOffset++] = vertexCount;
			stagingVertexCount += vertexCount;

			if (stagingModelDataOffset >= stagingModelData.length) {
				asyncIndicesWriter.submit();
			}
		}
		return this;
	}

	public ModelDrawBuffer flush() {
		if (stagingModelDataOffset > 0) {
			asyncIndicesWriter.submit();
		}
		return this;
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
		private int[] stagingIndices = new int[32];
		private int[] modelDataToWrite = new int[STAGING_MODEL_DATA_COUNT * 2];
		private ByteBuffer mappedBuffer = null;
		private boolean isMapped = false;
		private int modelCount;

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

			modelCount = 0;
			if (isMapped) {
				// Perform Swap, so that we can write the previous staging data whilst the next is written in
				int[] newModelDataToWrite = owner.stagingModelData;
				owner.stagingModelData = modelDataToWrite;
				modelDataToWrite = newModelDataToWrite;

				modelCount = owner.stagingModelDataOffset / 2;
			}
			owner.stagingModelDataOffset = 0;
			owner.stagingVertexCount = 0;
		}

		@Override
		protected void doWork() {
			if (isMapped) {
				IntBuffer intBuffer = mappedBuffer.clear().asIntBuffer().position(owner.indicesCount);
				int modelDataOffset = 0;
				int stagingIndicesOffset = 0;
				for (int modelIdx = 0; modelIdx < modelCount; modelIdx++) {
					int renderBufferOffset = modelDataToWrite[modelDataOffset++];
					final int vertexCount = modelDataToWrite[modelDataOffset++];

					if (stagingIndices.length < stagingIndicesOffset + vertexCount) {
						intBuffer.put(stagingIndices, 0, stagingIndicesOffset);
						stagingIndicesOffset = 0;
					}

					if (stagingIndices.length < vertexCount) {
						stagingIndices = new int[vertexCount];
					}

					for (int v = 0; v < vertexCount; v++, owner.indicesCount++) {
						stagingIndices[stagingIndicesOffset++] = renderBufferOffset++;
					}
				}
				intBuffer.put(stagingIndices, 0, stagingIndicesOffset);
			}
		}
	}
}
