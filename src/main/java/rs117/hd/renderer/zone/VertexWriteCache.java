package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
public final class VertexWriteCache {
	private IntBuffer outputBuffer;

	private final String name;
	private final int maxCapacity;
	private int[] stagingBuffer;
	private int stagingPosition;

	public VertexWriteCache(String name, int initialCapacity) {
		this(name, initialCapacity, initialCapacity);
	}

	public VertexWriteCache(String name, int initialCapacity, int maxCapacity) {
		this.name = name;
		this.maxCapacity = maxCapacity;
		stagingBuffer = new int[initialCapacity];
	}

	public void setOutputBuffer(IntBuffer outputBuffer) {
		this.outputBuffer = outputBuffer;
		stagingPosition = 0;
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
		final int[] stagingBuffer = this.stagingBuffer;
		final int stagingPosition = this.stagingPosition;

		stagingBuffer[stagingPosition] = alphaBiasHslA;
		stagingBuffer[stagingPosition + 1] = alphaBiasHslB;
		stagingBuffer[stagingPosition + 2] = alphaBiasHslC;

		stagingBuffer[stagingPosition + 3] = materialDataA;
		stagingBuffer[stagingPosition + 4] = materialDataB;
		stagingBuffer[stagingPosition + 5] = materialDataC;

		stagingBuffer[stagingPosition + 6] = terrainDataA; // TODO: Remove?
		stagingBuffer[stagingPosition + 7] = terrainDataB;
		stagingBuffer[stagingPosition + 8] = terrainDataC;

		this.stagingPosition += 9;

		return textureFaceIdx;
	}

	public void putVertex(
		float x, float y, float z,
		float u, float v, float w,
		int nx, int ny, int nz,
		int textureFaceIdx
	) {
		putVertex(
			Float.floatToRawIntBits(x),
			Float.floatToRawIntBits(y),
			Float.floatToRawIntBits(z),
			u, v, w, nx, ny, nz, textureFaceIdx);
	}

	public void putVertex(
		int x, int y, int z,
		float u, float v, float w,
		int nx, int ny, int nz,
		int textureFaceIdx
	) {
		if (stagingPosition + 7 > stagingBuffer.length)
			flushAndGrow();

		final int[] stagingBuffer = this.stagingBuffer;
		final int stagingPosition = this.stagingPosition;

		stagingBuffer[stagingPosition] = x;
		stagingBuffer[stagingPosition + 1] = y;
		stagingBuffer[stagingPosition + 2] = z;
		stagingBuffer[stagingPosition + 3] = float16(v) << 16 | float16(u);
		stagingBuffer[stagingPosition + 4] = (nx & 0xFFFF) << 16 | float16(w);
		stagingBuffer[stagingPosition + 5] = (nz & 0xFFFF) << 16 | ny & 0xFFFF;
		stagingBuffer[stagingPosition + 6] = textureFaceIdx;

		this.stagingPosition += 7;
	}

	public void putStaticVertex(
		int x, int y, int z,
		float u, float v, float w,
		int nx, int ny, int nz,
		int textureFaceIdx
	) {
		if (stagingPosition + 6 > stagingBuffer.length)
			flushAndGrow();

		final int[] stagingBuffer = this.stagingBuffer;
		final int stagingPosition = this.stagingPosition;

		stagingBuffer[stagingPosition] = (y & 0xFFFF) << 16 | x & 0xFFFF;
		stagingBuffer[stagingPosition + 1] = float16(u) << 16 | z & 0xFFFF;
		stagingBuffer[stagingPosition + 2] = float16(w) << 16 | float16(v);
		// Unnormalized normals, assumed to be within short max
		stagingBuffer[stagingPosition + 3] = (ny & 0xFFFF) << 16 | nx & 0xFFFF;
		stagingBuffer[stagingPosition + 4] = nz & 0xFFFF;
		stagingBuffer[stagingPosition + 5] = textureFaceIdx;

		this.stagingPosition += 6;
	}

	public void flush() {
		if (stagingPosition == 0 || outputBuffer == null)
			return;

		try {
			outputBuffer.put(stagingBuffer, 0, stagingPosition);
			stagingPosition = 0;
		} catch (Exception e) {
			log.error("Error whilst flushing: {}", name);
			log.error("Exception ", e);
		}
	}

	public static class Collection {
		private static final int CAPACITY = (int) (128 * KiB / Integer.BYTES);

		public final VertexWriteCache opaque = new VertexWriteCache("OPAQUE", CAPACITY);
		public final VertexWriteCache alpha = new VertexWriteCache("ALPHA", CAPACITY);
		public final VertexWriteCache opaqueTex = new VertexWriteCache("OPAQUE_TEX", CAPACITY);
		public final VertexWriteCache alphaTex = new VertexWriteCache("ALPHA_TEX", CAPACITY);
		public boolean useAlphaBuffer;

		public void setOutputBuffers(IntBuffer opaque, IntBuffer alpha, IntBuffer opaqueTex, IntBuffer alphaTex) {
			this.opaque.setOutputBuffer(opaque);
			this.opaqueTex.setOutputBuffer(opaqueTex);
			useAlphaBuffer = alpha != opaque && alphaTex != opaqueTex;
			if (useAlphaBuffer) {
				this.alpha.setOutputBuffer(alpha);
				this.alphaTex.setOutputBuffer(alphaTex);
			} else {
				this.alpha.setOutputBuffer(null);
				this.alphaTex.setOutputBuffer(null);
			}
		}

		public void setOutputBuffers(GpuIntBuffer opaque, GpuIntBuffer alpha, GpuIntBuffer tex) {
			this.opaque.setOutputBuffer(opaque.getBuffer());
			this.opaqueTex.setOutputBuffer(tex.getBuffer());
			useAlphaBuffer = alpha != null && opaque != alpha;
			if (useAlphaBuffer) {
				this.alpha.setOutputBuffer(alpha.getBuffer());
				this.alphaTex.setOutputBuffer(null);
			} else {
				this.alpha.setOutputBuffer(null);
				this.alphaTex.setOutputBuffer(null);
			}
		}

		public void flush() {
			opaque.flush();
			alpha.flush();
			opaqueTex.flush();
			alphaTex.flush();
		}
	}
}
