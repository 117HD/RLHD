package rs117.hd.opengl;

import java.nio.IntBuffer;
import lombok.RequiredArgsConstructor;
import rs117.hd.utils.Job;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;

public class ModelDrawBuffer extends GLBuffer {
	private static final int STAGING_MODEL_DATA_SIZE = 1024;
	private static final int STAGING_MODEL_DATA_COUNT = STAGING_MODEL_DATA_SIZE / 2;

	private int[] stagingModelData = new int[STAGING_MODEL_DATA_SIZE];
	private GpuIntBuffer indicesData;

	private int stagingModelDataOffset = 0;
	private int stagingVertexCount = 0;
	private int indicesCount = 0;

	private final AsyncIndicesWriter asyncIndicesWriter = new AsyncIndicesWriter(this);

	public ModelDrawBuffer(String name) {
		super(name + " Indices", GL_ELEMENT_ARRAY_BUFFER, GL_STREAM_DRAW);
	}

	public void addModel(int renderBufferOffset, int vertexCount) {
		if (vertexCount > 0) {
			stagingModelData[stagingModelDataOffset++] = renderBufferOffset;
			stagingModelData[stagingModelDataOffset++] = vertexCount;
			stagingVertexCount += vertexCount;

			if (stagingModelDataOffset >= STAGING_MODEL_DATA_SIZE) {
				asyncIndicesWriter.submit();
			}
		}
	}

	public void upload() {
		asyncIndicesWriter.complete(true);

		if (stagingModelDataOffset >= 0) {
			indicesData.ensureCapacity(stagingVertexCount);
			writeIndices(stagingModelData, stagingModelDataOffset / 2);
			stagingModelDataOffset = 0;
			stagingVertexCount = 0;
		}

		indicesData.flip();
		upload(indicesData);
		indicesData.clear();
	}

	@Override
	public void initialize() {
		super.initialize();

		indicesData = new GpuIntBuffer();
	}

	@Override
	public void destroy() {
		super.destroy();

		asyncIndicesWriter.complete(true);

		if (indicesData != null) {
			indicesData.destroy();
			indicesData = null;
		}
	}

	public void draw() {
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, id);
		glDrawElements(GL_TRIANGLES, indicesCount, GL_UNSIGNED_INT, 0);
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
	}

	public void clear() {
		stagingModelDataOffset = 0;
		stagingVertexCount = 0;
		indicesCount = 0;
	}

	private void writeIndices(int[] modelData, int modelCount) {
		final IntBuffer buffer = indicesData.getBuffer();
		int modelDataOffset = 0;
		for (int modelIdx = 0; modelIdx < modelCount; modelIdx++) {
			int renderBufferOffset = modelData[modelDataOffset++];
			int vertexCount = modelData[modelDataOffset++];

			for (int v = 0; v < vertexCount; v++) {
				buffer.put(renderBufferOffset++);
				indicesCount++;
			}
		}
	}

	@RequiredArgsConstructor
	public static final class AsyncIndicesWriter extends Job {
		private final ModelDrawBuffer owner;

		public int[] asyncModelDataToWrite = new int[STAGING_MODEL_DATA_SIZE];

		public void prepare() {
			// Swap the Buffers, so we can async write whilst the next buffer is filled
			int[] newAsyncModelDataToWrite = owner.stagingModelData;
			owner.stagingModelData = asyncModelDataToWrite;
			asyncModelDataToWrite = newAsyncModelDataToWrite;

			owner.indicesData.ensureCapacity(owner.stagingVertexCount);
			owner.stagingVertexCount = 0;
			owner.stagingModelDataOffset = 0;
		}

		@Override
		protected void doWork() {
			owner.writeIndices(asyncModelDataToWrite, STAGING_MODEL_DATA_COUNT);
		}
	}
}
