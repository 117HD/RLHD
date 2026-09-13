package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.collections.PooledArrayType;

import static rs117.hd.renderer.zone.Zone.MODEL_FACE_NUM_INTS;
import static rs117.hd.renderer.zone.Zone.STATIC_FACE_NUM_INTS;
import static rs117.hd.renderer.zone.Zone.TEXTURE_FACE_IS_MODEL;
import static rs117.hd.renderer.zone.Zone.TEXTURE_FACE_IS_WINDING_REVERSED;
import static rs117.hd.renderer.zone.Zone.ZONE_VERTEX_NUM_INTS;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public final class VertexWriteCache {
	private IntBuffer outputBuffer;

	private final String name;
	private final int initialCapacity;
	private final int maxCapacity;
	private int[] stagingBuffer;
	private int stagingPosition;

	public VertexWriteCache(String name, int initialCapacity) {
		this(name, initialCapacity, initialCapacity);
	}

	public VertexWriteCache(String name, int initialCapacity, int maxCapacity) {
		this.name = name;
		this.initialCapacity = initialCapacity;
		this.maxCapacity = maxCapacity;
	}

	public void setOutputBuffer(IntBuffer outputBuffer) {
		this.outputBuffer = outputBuffer;
		stagingPosition = 0;
		stagingBuffer = PooledArrayType.INT.ensureCapacity(stagingBuffer, initialCapacity);
	}

	private void release(){
		flush();

		if (stagingBuffer != null)
			PooledArrayType.INT.release(stagingBuffer);
		stagingBuffer = null;
	}

	private void flushAndGrow() {
		// Flush buffer and then resize to avoid flushing mid put
		flush();

		if (stagingBuffer.length < maxCapacity)
			stagingBuffer = PooledArrayType.INT.ensureCapacity(stagingBuffer, min(stagingBuffer.length * 2, maxCapacity));
	}

	public int findStaticFace(
		int alphaBiasHslA, int alphaBiasHslB, int alphaBiasHslC,
		int materialDataA, int materialDataB, int materialDataC,
		int terrainDataA, int terrainDataB, int terrainDataC
	) {
		final int[] stagingBuffer = this.stagingBuffer;
		final int stagingPosition = this.stagingPosition;

		for (int i = 0; i < stagingPosition; i += 9) {
			if (stagingBuffer[i] == alphaBiasHslA &&
				stagingBuffer[i + 1] == alphaBiasHslB &&
				stagingBuffer[i + 2] == alphaBiasHslC &&

				stagingBuffer[i + 3] == materialDataA &&
				stagingBuffer[i + 4] == materialDataB &&
				stagingBuffer[i + 5] == materialDataC &&

				stagingBuffer[i + 6] == terrainDataA &&
				stagingBuffer[i + 7] == terrainDataB &&
				stagingBuffer[i + 8] == terrainDataC
			) {
				final int textureFaceIdx = outputBuffer.position() + i;
				return textureFaceIdx;
			}
		}

		return -1;
	}

	public int putStaticFace(
		int alphaBiasHslA, int alphaBiasHslB, int alphaBiasHslC,
		int materialDataA, int materialDataB, int materialDataC,
		int terrainDataA, int terrainDataB, int terrainDataC
	) {
		if (stagingPosition + 9 > stagingBuffer.length)
			flushAndGrow();

		final int textureFaceIdx = outputBuffer.position() + stagingPosition;
		final int[] stagingBuffer = this.stagingBuffer;
		final int stagingPosition = this.stagingPosition;

		// STATIC_FACE_FORMAT
		stagingBuffer[stagingPosition] = alphaBiasHslA;
		stagingBuffer[stagingPosition + 1] = alphaBiasHslB;
		stagingBuffer[stagingPosition + 2] = alphaBiasHslC;

		stagingBuffer[stagingPosition + 3] = materialDataA;
		stagingBuffer[stagingPosition + 4] = materialDataB;
		stagingBuffer[stagingPosition + 5] = materialDataC;

		stagingBuffer[stagingPosition + 6] = terrainDataA; // TODO: Remove?
		stagingBuffer[stagingPosition + 7] = terrainDataB;
		stagingBuffer[stagingPosition + 8] = terrainDataC;

		this.stagingPosition += STATIC_FACE_NUM_INTS;

		return textureFaceIdx;
	}

	public int findModelFace(int alphaBiasHslA, int alphaBiasHslB, int alphaBiasHslC, int materialData) {
		final int[] stagingBuffer = this.stagingBuffer;
		final int stagingPosition = this.stagingPosition;

		for (int i = 0; i < stagingPosition; i += 4) {
			if (stagingBuffer[i] == alphaBiasHslA &&
				stagingBuffer[i + 1] == alphaBiasHslB &&
				stagingBuffer[i + 2] == alphaBiasHslC &&
				stagingBuffer[i + 3] == materialData
			) {
				final int textureFaceIdx = outputBuffer.position() + i;
				return TEXTURE_FACE_IS_MODEL | textureFaceIdx;
			}
		}

		return -1;
	}

	public int putModelFace(int alphaBiasHslA, int alphaBiasHslB, int alphaBiasHslC, int materialData) {
		if (stagingPosition + 4 > stagingBuffer.length)
			flushAndGrow();

		final int textureFaceIdx = outputBuffer.position() + stagingPosition;

		final int[] stagingBuffer = this.stagingBuffer;
		final int stagingPosition = this.stagingPosition;

		// MODEL_FACE_FORMAT
		stagingBuffer[stagingPosition] = alphaBiasHslA;
		stagingBuffer[stagingPosition + 1] = alphaBiasHslB;
		stagingBuffer[stagingPosition + 2] = alphaBiasHslC;
		stagingBuffer[stagingPosition + 3] = materialData;

		this.stagingPosition += MODEL_FACE_NUM_INTS;
		return TEXTURE_FACE_IS_MODEL | textureFaceIdx;
	}

	public void putVertex(
		int x, int y, int z,
		float u, float v, float w,
		int nx, int ny, int nz,
		int textureFaceIdx, boolean windingReversed, int modelIdx
	) {
		if (stagingPosition + 6 > stagingBuffer.length)
			flushAndGrow();

		final int[] stagingBuffer = this.stagingBuffer;
		final int stagingPosition = this.stagingPosition;

		// ZONE_VERTEX_FORMAT
		stagingBuffer[stagingPosition]     = (y & 0xFFFF) << 16 | (x & 0xFFFF);
		stagingBuffer[stagingPosition + 1] = (nx & 0xFFFF) << 16 | (z & 0xFFFF);
		stagingBuffer[stagingPosition + 2] = (nz & 0xFFFF) << 16 | (ny & 0xFFFF);
		stagingBuffer[stagingPosition + 3] = (float16(u) & 0xFFFF) << 16 | (modelIdx & 0xFFFF);
		stagingBuffer[stagingPosition + 4] = (float16(w) & 0xFFFF) << 16 | (float16(v) & 0xFFFF);
		stagingBuffer[stagingPosition + 5] = (windingReversed ? TEXTURE_FACE_IS_WINDING_REVERSED : 0) | textureFaceIdx;

		this.stagingPosition += ZONE_VERTEX_NUM_INTS;
	}

	public void flush() {
		if (stagingPosition == 0 || outputBuffer == null)
			return;

		try {
			outputBuffer.put(stagingBuffer, 0, stagingPosition);
		} catch (Exception e) {
			log.error("Failed to flush vertex write cache {} written: {} remaining: {}", name, stagingPosition, outputBuffer.remaining(), e);
		} finally {
			stagingPosition = 0;
		}
	}

	public static class Collection {
		private static final int CAPACITY = (int) (32 * KiB / Integer.BYTES);

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

		public VertexWriteCache getVertexBuffer() { return opaque; }

		public VertexWriteCache getTextureBuffer() { return opaqueTex; }

		public VertexWriteCache getVertexBuffer(boolean hasAlpha) {
			return useAlphaBuffer && hasAlpha ? alpha : opaque;
		}

		public VertexWriteCache getTextureBuffer(boolean hasAlpha) {
			return useAlphaBuffer && hasAlpha ? alphaTex : opaqueTex;
		}

		public void release() {
			opaque.release();
			alpha.release();
			opaqueTex.release();
			alphaTex.release();
		}
	}
}
