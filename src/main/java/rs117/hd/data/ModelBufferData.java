package rs117.hd.data;

public final class ModelBufferData {
	public int vertexOffset;
	public int uvOffset;
	public int vertexCount;

	public int renderBufferOffset = -1;
	public byte state = DrawState.NONE;

	public ModelBufferData(int vertexOffset, int uvOffset, int vertexCount) {
		this.vertexOffset = vertexOffset;
		this.uvOffset = uvOffset;
		this.vertexCount = vertexCount;
	}

	public ModelBufferData() {}

	public void reset() {
		renderBufferOffset = -1;
		state = DrawState.NONE;
	}

	public int appendToRenderBuffer(int inRenderBufferOffset) {
		if (renderBufferOffset > 0) {
			return inRenderBufferOffset;
		}

		renderBufferOffset = inRenderBufferOffset;
		return inRenderBufferOffset + vertexCount;
	}

	public boolean push(int[] modelInfoData) {
		if (renderBufferOffset >= 0 && (state & DrawState.PUSHED) == 0) {
			modelInfoData[0] = vertexOffset;
			modelInfoData[1] = uvOffset >= 0 ? uvOffset : vertexOffset;
			modelInfoData[2] = vertexCount / 3;
			modelInfoData[3] = renderBufferOffset;
			state |= DrawState.PUSHED;
			return true;
		}

		return false;
	}
}
