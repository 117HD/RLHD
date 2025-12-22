package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
public final class VertexWriteCache {
	@Setter
	private IntBuffer outputBuffer;

	private final int maxCapacity;
	private int[] stagingBuffer;
	private int stagingPosition;

	public VertexWriteCache(int initialCapacity, int maxCapacity) {
		this.maxCapacity = maxCapacity;
		stagingBuffer = new int[initialCapacity];
	}

	private void flushAndGrow() {
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
			flushAndGrow();

		final int textureFaceIdx = (outputBuffer.position() + stagingPosition) / 3;

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
			flushAndGrow();

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

		outputBuffer.put(stagingBuffer, 0, stagingPosition);
		stagingPosition = 0;
	}

	public static class Collection {
		private static final int MAX_CAPACITY = (int) (MiB / Integer.BYTES);
		private static final int INITIAL_CAPACITY = (int) (32 * KiB / Integer.BYTES);

		public final VertexWriteCache opaque = new VertexWriteCache(INITIAL_CAPACITY, MAX_CAPACITY);
		public final VertexWriteCache alpha = new VertexWriteCache(INITIAL_CAPACITY, MAX_CAPACITY);
		public final VertexWriteCache opaqueTex = new VertexWriteCache(INITIAL_CAPACITY, MAX_CAPACITY);
		public final VertexWriteCache alphaTex = new VertexWriteCache(INITIAL_CAPACITY, MAX_CAPACITY);

		public void setOutputBuffers(IntBuffer opaque, IntBuffer alpha, IntBuffer opaqueTex, IntBuffer alphaTex) {
			this.opaque.setOutputBuffer(opaque);
			this.alpha.setOutputBuffer(alpha);
			this.opaqueTex.setOutputBuffer(opaqueTex);
			this.alphaTex.setOutputBuffer(alphaTex);
		}

		public void flush() {
			opaque.flush();
			alpha.flush();
			opaqueTex.flush();
			alphaTex.flush();
		}
	}
}
