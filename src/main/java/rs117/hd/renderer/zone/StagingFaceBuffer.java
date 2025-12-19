package rs117.hd.renderer.zone;

import java.nio.IntBuffer;

import static rs117.hd.utils.MathUtils.*;

public final class StagingFaceBuffer {
	private static final int MAX_CAPACITY = 250000; // 1 MB
	private static final int INITIAL_CAPACITY = 8000; // 32 KB

	public int[] stagingBuffer = new int[INITIAL_CAPACITY];
	public int stagingSize = INITIAL_CAPACITY;
	public int stagingPosition = 0;

	private IntBuffer buffer;

	public int position() {
		return buffer.position() + stagingPosition;
	}

	public void set(IntBuffer buffer) {
		this.buffer = buffer;
	}

	public void grow() {
		// Flush buffer and then resize to avoid flushing mid put
		flush();

		if(stagingSize <= MAX_CAPACITY) {
			stagingSize *= 2;
			stagingBuffer = new int[stagingSize];
		}
	}

	public int putFace(
		int alphaBiasHslA, int alphaBiasHslB, int alphaBiasHslC,
		int materialDataA, int materialDataB, int materialDataC,
		int terrainDataA, int terrainDataB, int terrainDataC
	) {
		final int faceSize = 9;
		if(stagingPosition + faceSize > stagingSize)
			grow();

		final int textureFaceIdx = (buffer.position() + stagingPosition) / 3;

		stagingBuffer[stagingPosition] = (alphaBiasHslA);
		stagingBuffer[stagingPosition + 1] = (alphaBiasHslB);
		stagingBuffer[stagingPosition + 2] = (alphaBiasHslC);

		stagingBuffer[stagingPosition + 3] = (materialDataA);
		stagingBuffer[stagingPosition + 4] = (materialDataB);
		stagingBuffer[stagingPosition + 5] = (materialDataC);

		stagingBuffer[stagingPosition + 6] = (terrainDataA); // TODO: Remove?
		stagingBuffer[stagingPosition + 7] = (terrainDataB);
		stagingBuffer[stagingPosition + 8] = (terrainDataC);

		stagingPosition += faceSize;

		return textureFaceIdx;
	}

	public void putVertex(
		float x, float y, float z,
		float u, float v, float w,
		int nx, int ny, int nz,
		int textureFaceIdx
	) {
		final int vertexSize = 7;
		if(stagingPosition + vertexSize > stagingSize)
			grow();

		stagingBuffer[stagingPosition] = (Float.floatToRawIntBits(x));
		stagingBuffer[stagingPosition + 1] = (Float.floatToRawIntBits(y));
		stagingBuffer[stagingPosition + 2] = (Float.floatToRawIntBits(z));
		stagingBuffer[stagingPosition + 3] = (float16(v) << 16 | float16(u));
		stagingBuffer[stagingPosition + 4] = ((nx & 0xFFFF) << 16 | float16(w));
		stagingBuffer[stagingPosition + 5] = ((nz & 0xFFFF) << 16 | ny & 0xFFFF);
		stagingBuffer[stagingPosition + 6] = (textureFaceIdx);

		stagingPosition += vertexSize;
	}

	public void flush() {
		if(stagingPosition == 0)
			return;

		buffer.put(stagingBuffer, 0, stagingPosition);
		stagingPosition = 0;
	}
}
