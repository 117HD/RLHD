package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.Destructible;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter.ReservedView;
import rs117.hd.utils.buffer.GLShaderStorage;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.SUPPORTS_INDIRECT_DRAW;
import static rs117.hd.HdPlugin.SUPPORTS_MULTI_INDIRECT_DRAW;
import static rs117.hd.HdPlugin.SUPPORTS_STORAGE_BUFFERS;
import static rs117.hd.renderer.zone.Zone.METADATA_NUM_BYTES;
import static rs117.hd.renderer.zone.Zone.METADATA_NUM_INTS;
import static rs117.hd.renderer.zone.Zone.MODEL_DATA_NUM_INTS;
import static rs117.hd.renderer.zone.Zone.MODEL_FACE_NUM_INTS;
import static rs117.hd.renderer.zone.Zone.ZONE_VERTEX_NUM_BYTES;
import static rs117.hd.renderer.zone.Zone.ZONE_VERTEX_NUM_INTS;
import static rs117.hd.renderer.zone.ZoneRenderer.SHADER_STORAGE_BUFFER_MODEL_DATA;
import static rs117.hd.renderer.zone.ZoneRenderer.SHADER_STORAGE_BUFFER_TEXTURED_FACES;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_MODEL_DATA;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_IMMUTABLE;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_NONE;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_PERSISTENT;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_WRITE;

@Slf4j
public class DynamicModelVAO implements Destructible {
	private static final int INITIAL_SIZE = (int) (64 * KiB);

	@Getter
	private int vao;

	private final StagedBuffer vertexBuffer;
	private final StagedBuffer faceBuffer;
	private final GLBuffer stubMetadata;
	private final GLShaderStorage tboM;

	private final ArrayDeque<View> usedViews = new ArrayDeque<>();
	private final ArrayDeque<View> freeViews = new ArrayDeque<>();

	private final GLMappedBufferIntWriter tboMWriter;

	private boolean isMapped = false;

	private int[] drawOffsets = new int[16];
	private int[] drawCounts = new int[16];

	private int[] packedOffsets = new int[16];
	private int[] mergedOffsets = new int[16];
	private int[] mergedCounts = new int[16];

	private int nextPackedOffset;

	private int allocatedDrawCount;
	private int writtenRangeCount;

	DynamicModelVAO(String name, boolean useStagingBuffer) {
		int storageFlags = SUPPORTS_STORAGE_BUFFERS ? STORAGE_PERSISTENT | STORAGE_IMMUTABLE | STORAGE_WRITE : STORAGE_NONE;

		GLBuffer vboRender, vboStaging;
		if (useStagingBuffer) {
			vboRender = new GLBuffer("DynamicModel::VBO::" + name, GL_ARRAY_BUFFER, GL_STATIC_DRAW, 0);
			vboStaging = new GLBuffer("DynamicModel::VBO_STAGING::" + name, GL_ARRAY_BUFFER, GL_STREAM_DRAW, storageFlags);
		} else {
			vboRender = vboStaging = new GLBuffer("DynamicModel::VBO::" + name, GL_ARRAY_BUFFER, GL_STREAM_DRAW);
		}
		vertexBuffer = new StagedBuffer(vboRender, vboStaging, ZONE_VERTEX_NUM_BYTES);

		GLShaderStorage tboFRender, tboFStaging;
		if (useStagingBuffer) {
			tboFRender = new GLShaderStorage("DynamicModel::TexturedFaces::" + name, GL_STATIC_DRAW, 0);
			tboFStaging = new GLShaderStorage("DynamicModel::TexturedFaces_STAGING::" + name, GL_STREAM_DRAW, storageFlags);
		} else {
			tboFRender = tboFStaging = new GLShaderStorage("DynamicModel::TexturedFaces::" + name, GL_STREAM_DRAW);
		}
		faceBuffer = new StagedBuffer(tboFRender, tboFStaging, Integer.BYTES);

		this.stubMetadata = new GLBuffer("DynamicModel::Metadata", GL_ARRAY_BUFFER, GL_STATIC_DRAW);

		this.tboM = new GLShaderStorage(
			"DynamicModel::ModelData::" + name,
			GL_STREAM_DRAW,
			storageFlags
		);
		this.tboMWriter = new GLMappedBufferIntWriter(this.tboM);

		Arrays.fill(packedOffsets, -1);
	}

	public boolean hasStagingBuffer() { return vertexBuffer.hasStaging(); }

	void initialize() {
		vao = glGenVertexArrays();
		faceBuffer.initialize(SHADER_STORAGE_BUFFER_TEXTURED_FACES, TEXTURE_UNIT_TEXTURED_FACES);
		tboM.initialize(INITIAL_SIZE, SHADER_STORAGE_BUFFER_MODEL_DATA, TEXTURE_UNIT_MODEL_DATA);
		vertexBuffer.initialize();

		// Build stub metadata
		stubMetadata.initialize(METADATA_NUM_BYTES);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int intsCount = METADATA_NUM_INTS;
			IntBuffer buf = stack.mallocInt(intsCount);
			for (int i = 0; i < intsCount; i++)
				buf.put(0);
			stubMetadata.upload(buf);
		}

		bindRenderVAO();
	}

	void bindRenderVAO() {
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer.render.id);

		// Position
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_HALF_FLOAT, false, ZONE_VERTEX_NUM_BYTES, 0);

		// UVs (float16 vec2)
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 2, GL_HALF_FLOAT, false, ZONE_VERTEX_NUM_BYTES, 10);

		// Normal (oct16 int16 vec2)
		glEnableVertexAttribArray(2);
		glVertexAttribIPointer(2, 2, GL_SHORT, ZONE_VERTEX_NUM_BYTES, 6);

		// Model index (uint16)
		glEnableVertexAttribArray(3);
		glVertexAttribIPointer(3, 1, GL_UNSIGNED_SHORT, ZONE_VERTEX_NUM_BYTES, 14);

		glBindBuffer(GL_ARRAY_BUFFER, stubMetadata.id);

		// WorldView index (not ID)
		glEnableVertexAttribArray(6);
		glVertexAttribDivisor(6, 1);
		glVertexAttribIPointer(6, 1, GL_INT, METADATA_NUM_BYTES, 0);

		// Scene offset
		glEnableVertexAttribArray(7);
		glVertexAttribDivisor(7, 1);
		glVertexAttribIPointer(7, 2, GL_INT, METADATA_NUM_BYTES, 4);

		// Zone fading
		glEnableVertexAttribArray(8);
		glVertexAttribDivisor(8, 1);
		glVertexAttribPointer(8, 1, GL_FLOAT, false, METADATA_NUM_BYTES, 12);


		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);
	}

	void map() {
		vertexBuffer.orphanRender();

		vertexBuffer.map();
		faceBuffer.map();
		tboMWriter.map(false);

		reset();
		isMapped = true;
	}

	synchronized void unmap() {
		final int renderVBOId = vertexBuffer.render.id;
		vertexBuffer.flush();
		faceBuffer.flush();
		tboMWriter.flush();

		vertexBuffer.copyToRender();
		faceBuffer.copyToRender();

		if (renderVBOId != vertexBuffer.render.id)
			bindRenderVAO();
		isMapped = false;
	}

	@Override
	public void destroy() {
		tboMWriter.destroy();
		vertexBuffer.destroy();
		faceBuffer.destroy();
		tboM.destroy();

		if (vao != 0)
			glDeleteVertexArrays(vao);
		vao = 0;
	}

	synchronized int obtainDrawIndex() {
		final int drawIndex = allocatedDrawCount++;
		if (allocatedDrawCount >= drawOffsets.length) {
			final int oldLength = drawOffsets.length;
			final int oldPackedLength = packedOffsets.length;

			drawOffsets = Arrays.copyOf(drawOffsets, oldLength * 2);
			drawCounts = Arrays.copyOf(drawCounts, oldLength * 2);
			packedOffsets = Arrays.copyOf(packedOffsets, oldLength * 2);

			Arrays.fill(packedOffsets, oldPackedLength, packedOffsets.length, -1);
		}
		return drawIndex;
	}

	View beginDraw(int faceCount) {
		return beginDraw(-1, faceCount);
	}

	public synchronized View beginDraw(int drawIdx, int faceCount) {
		if (drawIdx == -1)
			drawIdx = obtainDrawIndex();

		assert drawIdx >= 0 && drawIdx < allocatedDrawCount : "drawIdx " + drawIdx + " out of bounds from 0 to " + allocatedDrawCount;
		assert drawOffsets[drawIdx] == 0 && drawCounts[drawIdx] == 0 : String.format(
			"Provided draw index is already in use: %d %d %d",
			drawIdx, drawOffsets[drawIdx], drawCounts[drawIdx]
		);
		assert isMapped : "beginDraw called while not mapped, this is not allowed!";

		View view = freeViews.poll();
		if (view == null)
			view = new View();
		view.vbo = vertexBuffer.reserve(faceCount * 3 * ZONE_VERTEX_NUM_INTS);
		view.tboF = faceBuffer.reserve(faceCount * MODEL_FACE_NUM_INTS);
		view.tboM = tboMWriter.reserve(MODEL_DATA_NUM_INTS);
		view.vao = vao;
		view.tboFBuffer = (GLShaderStorage) faceBuffer.render;
		view.tboMBuffer = tboM;
		view.drawIdx = drawIdx;

		return view;
	}

	private synchronized void endDraw(View view) {
		assert drawOffsets[view.drawIdx] == 0 && drawCounts[view.drawIdx] == 0 : String.format(
			"Provided draw index is already in use: %d %d %d",
			view.drawIdx, drawOffsets[view.drawIdx], drawCounts[view.drawIdx]
		);
		assert isMapped : "beginDraw called while not mapped, this is not allowed!";

		drawOffsets[view.drawIdx] = view.getStartOffset() / ZONE_VERTEX_NUM_INTS;
		drawCounts[view.drawIdx] = view.getVertexCount();
		writtenRangeCount = max(writtenRangeCount, view.drawIdx + 1);

		// Clear ReservedViews before returning to pool
		view.vbo = null;
		view.tboF = null;
		view.tboM = null;
		view.tboFBuffer = null;
		view.tboMBuffer = null;

		usedViews.add(view);
	}

	void draw(CommandBuffer cmd, int fromDrawIdx, int toDrawIdx) {
		ensureMergedCapacity(toDrawIdx - fromDrawIdx);

		int rangeCount = 0;
		for (int i = fromDrawIdx; i < toDrawIdx; i++)
			rangeCount = appendMergedRange(i, rangeCount);

		issueDraw(cmd, rangeCount);
	}

	private void ensureMergedCapacity(int maxCount) {
		if (mergedOffsets.length < maxCount) {
			mergedOffsets = Arrays.copyOf(mergedOffsets, maxCount);
			mergedCounts = Arrays.copyOf(mergedCounts, maxCount);
		}
	}

	private int appendMergedRange(int i, int count) {
		int c = drawCounts[i];
		if (c <= 0)
			return count;

		int offset;
		if (hasStagingBuffer()) {
			int packed = packedOffsets[i];
			if (packed == -1) {
				packed = nextPackedOffset;
				nextPackedOffset += c;
				// Both buffers are packed in lockstep: one face record per vertex, so the same
				// vertex-unit offsets address the matching byte ranges in each buffer.
				vertexBuffer.addPendingCopy(drawOffsets[i], packed, c);
				faceBuffer.addPendingCopy(drawOffsets[i], packed, c);
				packedOffsets[i] = packed;
			}
			offset = packed;
		} else {
			offset = drawOffsets[i];
		}

		if (count > 0 && mergedOffsets[count - 1] + mergedCounts[count - 1] == offset) {
			mergedCounts[count - 1] += c;
		} else {
			mergedOffsets[count] = offset;
			mergedCounts[count] = c;
			count++;
		}
		return count;
	}

	private void issueDraw(CommandBuffer cmd, int rangeCount) {
		if (rangeCount <= 0)
			return;

		cmd.BindVertexArray(vao);
		cmd.bindShaderStorage((GLShaderStorage) faceBuffer.render);
		cmd.bindShaderStorage(tboM);

		if (rangeCount == 1) {
			if (SUPPORTS_INDIRECT_DRAW) {
				cmd.DrawArraysIndirect(GL_TRIANGLES, mergedOffsets[0], mergedCounts[0], ZoneRenderer.indirectDrawCmdsStaging);
			} else {
				cmd.DrawArrays(GL_TRIANGLES, mergedOffsets[0], mergedCounts[0]);
			}
		} else {
			if (SUPPORTS_MULTI_INDIRECT_DRAW) {
				cmd.MultiDrawArraysIndirect(GL_TRIANGLES, mergedOffsets, mergedCounts, rangeCount, ZoneRenderer.indirectDrawCmdsStaging);
			} else {
				cmd.MultiDrawArrays(GL_TRIANGLES, mergedOffsets, mergedCounts, rangeCount);
			}
		}
	}

	void reset() {
		Arrays.fill(drawOffsets, 0, writtenRangeCount, 0);
		Arrays.fill(drawCounts, 0, writtenRangeCount, 0);
		Arrays.fill(packedOffsets, 0, writtenRangeCount, -1);
		allocatedDrawCount = 0;
		writtenRangeCount = 0;
		nextPackedOffset = 0;
		vertexBuffer.resetPending();
		faceBuffer.resetPending();
		freeViews.addAll(usedViews);
		usedViews.clear();
	}

	private static final class StagedBuffer {
		final GLBuffer render;
		final GLBuffer staging;
		final GLMappedBufferIntWriter writer;
		/** Number of bytes per reserved unit, used to convert reserved offsets into byte ranges. */
		final int bytesPerUnit;

		long[] pendingSrcOffsets = new long[16];
		long[] pendingDstOffsets = new long[16];
		long[] pendingNumBytes = new long[16];
		int pendingCount;

		StagedBuffer(GLBuffer render, GLBuffer staging, int bytesPerUnit) {
			this.render = render;
			this.staging = staging;
			this.bytesPerUnit = bytesPerUnit;
			this.writer = new GLMappedBufferIntWriter(staging);
		}

		boolean hasStaging() {
			return render != staging;
		}

		void initialize() {
			render.initialize(INITIAL_SIZE);
			if (hasStaging())
				staging.initialize(INITIAL_SIZE);
		}

		void initialize(int bindingIndex, int textureUnit) {
			((GLShaderStorage) render).initialize(INITIAL_SIZE, bindingIndex, textureUnit);
			if (hasStaging())
				staging.initialize(INITIAL_SIZE);
		}

		void orphanRender() {
			if (!render.isStorageBuffer())
				render.orphan();
		}

		void map() {
			writer.map(false);
		}

		void flush() {
			writer.flush();
		}

		ReservedView reserve(int sizeInts) {
			return writer.reserve(sizeInts);
		}

		void addPendingCopy(int srcOffsetInts, int dstOffsetInts, int countInts) {
			long srcBytes = srcOffsetInts * (long) bytesPerUnit;
			long dstBytes = dstOffsetInts * (long) bytesPerUnit;
			long numBytes = countInts * (long) bytesPerUnit;

			if (pendingCount > 0) {
				int last = pendingCount - 1;
				if (
					pendingSrcOffsets[last] + pendingNumBytes[last] == srcBytes &&
					pendingDstOffsets[last] + pendingNumBytes[last] == dstBytes
				) {
					pendingNumBytes[last] += numBytes;
					return;
				}
			}

			if (pendingCount >= pendingSrcOffsets.length) {
				pendingSrcOffsets = Arrays.copyOf(pendingSrcOffsets, pendingSrcOffsets.length * 2);
				pendingDstOffsets = Arrays.copyOf(pendingDstOffsets, pendingDstOffsets.length * 2);
				pendingNumBytes = Arrays.copyOf(pendingNumBytes, pendingNumBytes.length * 2);
			}

			pendingSrcOffsets[pendingCount] = srcBytes;
			pendingDstOffsets[pendingCount] = dstBytes;
			pendingNumBytes[pendingCount] = numBytes;
			pendingCount++;
		}

		void copyToRender() {
			if (pendingCount > 0)
				staging.copyMultiTo(render, pendingSrcOffsets, pendingDstOffsets, pendingNumBytes, pendingCount);
			pendingCount = 0;
		}

		void resetPending() {
			pendingCount = 0;
		}

		void destroy() {
			writer.destroy();
			render.destroy();
			if (hasStaging())
				staging.destroy();
		}
	}

	public final class View {
		public ReservedView vbo;
		public ReservedView tboF;
		public ReservedView tboM;
		public int vao;
		public GLShaderStorage tboFBuffer;
		public GLShaderStorage tboMBuffer;

		@Getter
		private int drawIdx;

		public int getStartOffset() {
			return vbo.getOffsetInts();
		}

		public int getEndOffset() {
			return vbo.getEndOffsetInts();
		}

		public int getVertexCount() {
			return (getEndOffset() - getStartOffset()) / ZONE_VERTEX_NUM_INTS;
		}

		public void end() {
			endDraw(this);
		}
	}
}
