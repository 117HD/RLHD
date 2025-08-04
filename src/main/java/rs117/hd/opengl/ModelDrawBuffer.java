package rs117.hd.opengl;

import java.nio.IntBuffer;
import java.util.Arrays;
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

	private int[] stagingModelData = new int[512];
	private GpuIntBuffer indicesData;

	private int stagingModelDataOffset = 0;
	private int stagingVertexCount = 0;
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
				stagingModelData = Arrays.copyOf(stagingModelData, stagingModelData.length * 2);
			}
		}
		return this;
	}

	public ModelDrawBuffer buildIndicesData() {
		if (stagingModelDataOffset > 0) {
			indicesData.ensureCapacity(stagingVertexCount);

			asyncIndicesWriter.modelCount = stagingModelDataOffset / 2;
			asyncIndicesWriter.submit();

			stagingModelDataOffset = 0;
			stagingVertexCount = 0;
		}
		return this;
	}

	@Override
	public void initialize() {
		super.initialize();

		indicesData = new GpuIntBuffer();
	}

	@Override
	public void destroy() {
		super.destroy();

		asyncIndicesWriter.complete();

		if (indicesData != null) {
			indicesData.destroy();
			indicesData = null;
		}
	}

	public void draw() {
		if (!asyncIndicesWriter.isCompleted()) {
			asyncIndicesWriter.complete();

			indicesData.flip();
			upload(indicesData);
			indicesData.clear();
		}

		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, id);
		glDrawElements(GL_TRIANGLES, indicesCount, GL_UNSIGNED_INT, 0);
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
	}

	public void clear() {
		stagingModelDataOffset = 0;
		stagingVertexCount = 0;
		indicesCount = 0;
	}

	@RequiredArgsConstructor
	public static final class AsyncIndicesWriter extends Job {
		private final ModelDrawBuffer owner;
		private int modelCount;

		private int[] stagingIndices = new int[256];

		@Override
		protected void doWork() {
			final IntBuffer buffer = owner.indicesData.getBuffer();
			int modelDataOffset = 0;
			for (int modelIdx = 0; modelIdx < modelCount; modelIdx++) {
				int renderBufferOffset = owner.stagingModelData[modelDataOffset++];
				final int vertexCount = owner.stagingModelData[modelDataOffset++];

				if (stagingIndices.length < vertexCount) {
					stagingIndices = new int[vertexCount];
				}

				for (int v = 0; v < vertexCount; v++, owner.indicesCount++) {
					stagingIndices[v] = renderBufferOffset++;
				}

				buffer.put(stagingIndices, 0, vertexCount);
			}
		}
	}
}
