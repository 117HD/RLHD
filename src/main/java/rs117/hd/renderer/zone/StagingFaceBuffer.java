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
		if(stagingPosition + 9 > stagingSize)
			grow();

		final int textureFaceIdx = (buffer.position() + stagingPosition) / 3;

		stagingBuffer[stagingPosition++] = (alphaBiasHslA);
		stagingBuffer[stagingPosition++] = (alphaBiasHslB);
		stagingBuffer[stagingPosition++] = (alphaBiasHslC);

		stagingBuffer[stagingPosition++] = (materialDataA);
		stagingBuffer[stagingPosition++] = (materialDataB);
		stagingBuffer[stagingPosition++] = (materialDataC);

		stagingBuffer[stagingPosition++] = (terrainDataA); // TODO: Remove?
		stagingBuffer[stagingPosition++] = (terrainDataB);
		stagingBuffer[stagingPosition++] = (terrainDataC);

		return textureFaceIdx;
	}

	public void putVertex(
		float x, float y, float z,
		float u, float v, float w,
		int nx, int ny, int nz,
		int textureFaceIdx
	) {
		if(stagingPosition + 7 > stagingSize)
			grow();

		stagingBuffer[stagingPosition++] = (Float.floatToRawIntBits(x));
		stagingBuffer[stagingPosition++] = (Float.floatToRawIntBits(y));
		stagingBuffer[stagingPosition++] = (Float.floatToRawIntBits(z));
		stagingBuffer[stagingPosition++] = (float16(v) << 16 | float16(u));
		stagingBuffer[stagingPosition++] = ((nx & 0xFFFF) << 16 | float16(w));
		stagingBuffer[stagingPosition++] = ((nz & 0xFFFF) << 16 | ny & 0xFFFF);
		stagingBuffer[stagingPosition++] = (textureFaceIdx);
	}

	public void flush() {
		if(stagingPosition == 0)
			return;

		buffer.put(stagingBuffer, 0, stagingPosition);
		stagingPosition = 0;
	}
}
