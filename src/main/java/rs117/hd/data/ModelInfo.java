package rs117.hd.data;

import lombok.Getter;

public class ModelInfo {
	public static final int VERTEX_OFFSET_IDX = 0;
	public static final int UV_OFFSET_IDX = 1;
	public static final int FACE_COUNT_IDX = 2;
	public static final int RENDER_BUFFER_OFFSET_IDX = 3;
	public static final int MODEL_FLAGS_IDX = 4;
	public static final int X_POSITION_IDX = 5;
	public static final int Y_POSITION_AND_HEIGHT_IDX = 6;
	public static final int Z_POSITION_IDX = 7;
	public static final int ELEMENT_COUNT = 8;

	@Getter
	private final int[] stagingData = new int[ELEMENT_COUNT];

	public int getVertexOffset() { return stagingData[VERTEX_OFFSET_IDX]; }

	public ModelInfo setVertexOffset(int vertexOffset) {
		stagingData[VERTEX_OFFSET_IDX] = vertexOffset;
		return this;
	}

	public int getUVOffset() { return stagingData[UV_OFFSET_IDX]; }

	public ModelInfo setUVOffset(int uvOffset) {
		stagingData[UV_OFFSET_IDX] = uvOffset;
		return this;
	}

	public int getFaceOffset() { return stagingData[FACE_COUNT_IDX]; }

	public ModelInfo setFaceCount(int faceCount) {
		stagingData[FACE_COUNT_IDX] = faceCount;
		return this;
	}

	public int getRenderBufferOffset() { return stagingData[RENDER_BUFFER_OFFSET_IDX]; }

	public ModelInfo setRenderBufferOffset(int renderBufferOffset) {
		stagingData[RENDER_BUFFER_OFFSET_IDX] = renderBufferOffset;
		return this;
	}

	public int getModelFlags() { return stagingData[MODEL_FLAGS_IDX]; }

	public ModelInfo setModelFlags(int flags) {
		stagingData[MODEL_FLAGS_IDX] = flags;
		return this;
	}

	public int getPositionX() { return stagingData[X_POSITION_IDX]; }

	public ModelInfo setPositionX(int x) {
		stagingData[X_POSITION_IDX] = x;
		return this;
	}

	public int getPositionY() { return (stagingData[Y_POSITION_AND_HEIGHT_IDX] >> 16) & 0xFFFF; }

	public int getHeight() { return stagingData[Y_POSITION_AND_HEIGHT_IDX] & 0xFFFF; }

	public ModelInfo setPositionYAndHeight(int packed) {
		stagingData[Y_POSITION_AND_HEIGHT_IDX] = packed;
		return this;
	}

	public ModelInfo setPositionYAndHeight(int y, int height) {
		// Pack Y into the upper bits to easily preserve the sign
		return setPositionYAndHeight(y << 16 | height & 0xFFFF);
	}

	public int getPositionZ() { return stagingData[Z_POSITION_IDX]; }

	public ModelInfo setPositionZ(int z) {
		stagingData[Z_POSITION_IDX] = z;
		return this;
	}
}
