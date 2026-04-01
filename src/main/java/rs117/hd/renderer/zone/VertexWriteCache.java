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
		int x, int y, int z,
		float u, float v, float w,
		int nx, int ny, int nz,
		int textureFaceIdx,
		float heightRatio,
		float modelWindReach,
		int modelOriginX,
		int modelOriginZ
	) {
		if (stagingPosition + 8 > stagingBuffer.length)
			flushAndGrow();

		final int[] stagingBuffer = this.stagingBuffer;
		final int stagingPosition = this.stagingPosition;

		int packedWindData = packWindData(heightRatio, modelWindReach, modelOriginX, modelOriginZ);
		stagingBuffer[stagingPosition] = x;
		stagingBuffer[stagingPosition + 1] = y;
		stagingBuffer[stagingPosition + 2] = z;
		stagingBuffer[stagingPosition + 3] = float16(v) << 16 | float16(u);
		stagingBuffer[stagingPosition + 4] = float16(w);
		stagingBuffer[stagingPosition + 5] = (ny & 0xFFFF) << 16 | nx & 0xFFFF;
		stagingBuffer[stagingPosition + 6] = packedWindData << 16 | nz & 0xFFFF;
		stagingBuffer[stagingPosition + 7] = textureFaceIdx;

		this.stagingPosition += 8;
	}

	public void putStaticVertex(
		int x, int y, int z,
		float u, float v, float w,
		int nx, int ny, int nz,
		int textureFaceIdx,
		float heightRatio,
		float modelWindReach,
		int modelOriginX,
		int modelOriginZ
	) {
		if (stagingPosition + 7 > stagingBuffer.length)
			flushAndGrow();

		final int[] stagingBuffer = this.stagingBuffer;
		final int stagingPosition = this.stagingPosition;

		int packedWindData = packWindData(heightRatio, modelWindReach, modelOriginX, modelOriginZ);
		stagingBuffer[stagingPosition] = (y & 0xFFFF) << 16 | x & 0xFFFF;
		stagingBuffer[stagingPosition + 1] = z & 0xFFFF;
		stagingBuffer[stagingPosition + 2] = float16(v) << 16 | float16(u);
		stagingBuffer[stagingPosition + 3] = float16(w);
		// Unnormalized normals, assumed to be within short max
		stagingBuffer[stagingPosition + 4] = (ny & 0xFFFF) << 16 | nx & 0xFFFF;
		stagingBuffer[stagingPosition + 5] = packedWindData << 16 | nz & 0xFFFF;
		stagingBuffer[stagingPosition + 6] = textureFaceIdx;

		this.stagingPosition += 7;
	}

	/**
	 * Pack per-vertex wind data into 16 bits (read as GL_SHORT in shader).
	 * Bits 0-5  (6 bits): heightRatio (abs(localY) / modelHeight, 0-63)
	 * Bits 6-9  (4 bits): modelWindReach (abs(modelY) + modelHeight, 0-15 over max 2560)
	 * Bits 10-12 (3 bits): model origin X / 256, mod 8
	 * Bits 13-15 (3 bits): model origin Z / 256, mod 8
	 */
	public static int packWindData(float heightRatio, float modelWindReach, int modelOriginX, int modelOriginZ) {
		int packedRatio = (int) (clamp(heightRatio, 0, 1) * 63) & 0x3F;
		int packedReach = (int) (clamp(modelWindReach / 2560f, 0, 1) * 15) & 0xF;
		int packedOriginX = ((modelOriginX / 256) & 0x7);
		int packedOriginZ = ((modelOriginZ / 256) & 0x7);
		return (packedOriginZ << 13 | packedOriginX << 10 | packedReach << 6 | packedRatio) & 0xFFFF;
	}

	public void flush() {
		if (stagingPosition == 0 || outputBuffer == null)
			return;

		try {
			outputBuffer.put(stagingBuffer, 0, stagingPosition);
			stagingPosition = 0;
		} catch (Exception e) {
			log.error("Error whilst flushing: {}", name, e);
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
