package rs117.hd.renderer.zone;

import java.nio.IntBuffer;

import static rs117.hd.utils.MathUtils.*;

public final class VertexStagingBuffer {
	private final int maxCapacity;

	public int[] stagingBuffer;
	public int stagingPosition = 0;

	private IntBuffer buffer;

	public VertexStagingBuffer(int initialCapacity, int maxCapacity) {
		this.maxCapacity = maxCapacity;
		stagingBuffer = new int[initialCapacity];
	}

	public int position() {
		return buffer.position() + stagingPosition;
	}

	public void set(IntBuffer buffer) {
		this.buffer = buffer;
	}

	private void grow() {
		// Flush buffer and then resize to avoid flushing mid put
		flush();

		if (stagingBuffer.length < maxCapacity)
			stagingBuffer = new int[min(stagingBuffer.length * 2, maxCapacity)];
	}

	public int putFace(
		int alphaBiasHslA, int alphaBiasHslB, int alphaBiasHslC,
		int materialDataA, int materialDataB, int materialDataC,
		int terrainDataA, int terrainDataB, int terrainDataC
	) {
		if (stagingPosition + 9 > stagingBuffer.length)
			grow();

		final int textureFaceIdx = (buffer.position() + stagingPosition) / 3;

		stagingBuffer[stagingPosition++] = alphaBiasHslA;
		stagingBuffer[stagingPosition++] = alphaBiasHslB;
		stagingBuffer[stagingPosition++] = alphaBiasHslC;

		stagingBuffer[stagingPosition++] = materialDataA;
		stagingBuffer[stagingPosition++] = materialDataB;
		stagingBuffer[stagingPosition++] = materialDataC;

		stagingBuffer[stagingPosition++] = terrainDataA; // TODO: Remove?
		stagingBuffer[stagingPosition++] = terrainDataB;
		stagingBuffer[stagingPosition++] = terrainDataC;

		return textureFaceIdx;
	}

	public void putVertex(
		float x, float y, float z,
		float u, float v, float w,
		int nx, int ny, int nz,
		int textureFaceIdx
	) {
		if (stagingPosition + 7 > stagingBuffer.length)
			grow();

		stagingBuffer[stagingPosition++] = Float.floatToRawIntBits(x);
		stagingBuffer[stagingPosition++] = Float.floatToRawIntBits(y);
		stagingBuffer[stagingPosition++] = Float.floatToRawIntBits(z);
		stagingBuffer[stagingPosition++] = float16(v) << 16 | float16(u);
		stagingBuffer[stagingPosition++] = (nx & 0xFFFF) << 16 | float16(w);
		stagingBuffer[stagingPosition++] = (nz & 0xFFFF) << 16 | ny & 0xFFFF;
		stagingBuffer[stagingPosition++] = textureFaceIdx;
	}

	public void flush() {
		if (stagingPosition == 0)
			return;

		buffer.put(stagingBuffer, 0, stagingPosition);
		stagingPosition = 0;
	}
}
