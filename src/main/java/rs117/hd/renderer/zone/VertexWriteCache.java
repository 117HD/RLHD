package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter.ReservedView;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.collections.PooledArrayType;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
public final class VertexWriteCache {
	private IntBuffer outputBuffer;
	private int outputOffset;

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
		outputOffset = 0;
		stagingPosition = 0;
		stagingBuffer = PooledArrayType.INT.ensureCapacity(stagingBuffer, initialCapacity);
	}

	private void setOutputView(ReservedView view) {
		setOutputBuffer(view.getBuffer());
		outputOffset = view.getPositionOffsetInts();
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

	public int putFace(
		int alphaBiasHslA, int alphaBiasHslB, int alphaBiasHslC,
		int materialDataA, int materialDataB, int materialDataC,
		int terrainDataA, int terrainDataB, int terrainDataC
	) {
		if (stagingPosition + 9 > stagingBuffer.length)
			flushAndGrow();

		final int textureFaceIdx = (outputOffset + outputBuffer.position() + stagingPosition) / 3;
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

	@SuppressWarnings("PointlessArithmeticExpression")
	public void putDynamicTriangle(
		int[] positions, int offsetA, int offsetB, int offsetC,
		float[] uvs, int[] normals, int textureFaceIdx
	) {
		if (stagingPosition + 24 > stagingBuffer.length)
			flushAndGrow();

		final int[] stagingBuffer = this.stagingBuffer;
		final int p = stagingPosition;

		stagingBuffer[p + 0] = positions[offsetA];
		stagingBuffer[p + 1] = positions[offsetA + 1];
		stagingBuffer[p + 2] = positions[offsetA + 2];
		stagingBuffer[p + 3] = float16(uvs[1]) << 16 | float16(uvs[0]);
		stagingBuffer[p + 4] = float16(uvs[2]);
		stagingBuffer[p + 5] = (normals[1] & 0xFFFF) << 16 | normals[0] & 0xFFFF;
		stagingBuffer[p + 6] = normals[2] & 0xFFFF;
		stagingBuffer[p + 7] = textureFaceIdx;

		stagingBuffer[p + 8] = positions[offsetB];
		stagingBuffer[p + 9] = positions[offsetB + 1];
		stagingBuffer[p + 10] = positions[offsetB + 2];
		stagingBuffer[p + 11] = float16(uvs[5]) << 16 | float16(uvs[4]);
		stagingBuffer[p + 12] = float16(uvs[6]);
		stagingBuffer[p + 13] = (normals[4] & 0xFFFF) << 16 | normals[3] & 0xFFFF;
		stagingBuffer[p + 14] = normals[5] & 0xFFFF;
		stagingBuffer[p + 15] = textureFaceIdx;

		stagingBuffer[p + 16] = positions[offsetC];
		stagingBuffer[p + 17] = positions[offsetC + 1];
		stagingBuffer[p + 18] = positions[offsetC + 2];
		stagingBuffer[p + 19] = float16(uvs[9]) << 16 | float16(uvs[8]);
		stagingBuffer[p + 20] = float16(uvs[10]);
		stagingBuffer[p + 21] = (normals[7] & 0xFFFF) << 16 | normals[6] & 0xFFFF;
		stagingBuffer[p + 22] = normals[8] & 0xFFFF;
		stagingBuffer[p + 23] = textureFaceIdx;

		stagingPosition = p + 24;
	}

	public void putStaticVertex(
		int x, int y, int z,
		float u, float v, float w,
		int nx, int ny, int nz,
		int textureFaceIdx, boolean windingReversed
	) {
		if (stagingPosition + 7 > stagingBuffer.length)
			flushAndGrow();

		final int[] stagingBuffer = this.stagingBuffer;
		final int stagingPosition = this.stagingPosition;

		stagingBuffer[stagingPosition] = (y & 0xFFFF) << 16 | x & 0xFFFF;
		stagingBuffer[stagingPosition + 1] = z & 0xFFFF;
		stagingBuffer[stagingPosition + 2] = float16(v) << 16 | float16(u);
		stagingBuffer[stagingPosition + 3] = float16(w);
		// Unnormalized normals, assumed to be within short max
		stagingBuffer[stagingPosition + 4] = (ny & 0xFFFF) << 16 | nx & 0xFFFF;
		stagingBuffer[stagingPosition + 5] = nz & 0xFFFF;
		stagingBuffer[stagingPosition + 6] = (windingReversed ? 1 << 31 : 0) | textureFaceIdx;

		this.stagingPosition += 7;
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

		public void setOutputBuffers(ReservedView opaque, ReservedView alpha, ReservedView opaqueTex, ReservedView alphaTex) {
			this.opaque.setOutputView(opaque);
			this.opaqueTex.setOutputView(opaqueTex);
			useAlphaBuffer = alpha != opaque && alphaTex != opaqueTex;
			if (useAlphaBuffer) {
				this.alpha.setOutputView(alpha);
				this.alphaTex.setOutputView(alphaTex);
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
