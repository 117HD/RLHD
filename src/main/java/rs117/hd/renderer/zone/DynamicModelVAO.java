package rs117.hd.renderer.zone;

import java.util.ArrayDeque;
import java.util.Arrays;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.Destructible;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter.ReservedView;
import rs117.hd.utils.buffer.GLTextureBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.SUPPORTS_INDIRECT_DRAW;
import static rs117.hd.HdPlugin.SUPPORTS_STORAGE_BUFFERS;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_MODEL_DATA;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_IMMUTABLE;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_PERSISTENT;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_WRITE;

@Slf4j
public class DynamicModelVAO implements Destructible {
	public static final int INITIAL_SIZE = (int) (8 * MiB);

	// Temp vertex format
	// pos short vec3(x, y, z)
	// uvw short vec3(u, v, w)
	// normal/modelIdx short vec4(nx, ny, nz, modelIdx)
	// texturedFaceIdx int
	public static final int VERT_SIZE = 28;
	static final int VERT_SIZE_INTS = VERT_SIZE / 4;

	@Getter
	private int vao;

	private final GLBuffer vboRender;
	private final GLBuffer vboStaging;
	private final GLTextureBuffer tboF;
	private final GLTextureBuffer tboM;

	private final ArrayDeque<View> usedViews = new ArrayDeque<>();
	private final ArrayDeque<View> freeViews = new ArrayDeque<>();

	private final GLMappedBufferIntWriter vboWriter;
	private final GLMappedBufferIntWriter tboFWriter;
	private final GLMappedBufferIntWriter tboMWriter;

	private boolean isMapped = false;

	private int[] drawOffsets = new int[16];
	private int[] drawCounts = new int[16];

	private int[] packedOffsets = new int[16];
	private int[] mergedOffsets = new int[16];
	private int[] mergedCounts = new int[16];

	private long[] pendingCopySrcOffsets = new long[16];
	private long[] pendingCopyDstOffsets = new long[16];
	private long[] pendingCopyNumBytes = new long[16];
	private int pendingCopyCount;
	private int nextPackedOffset;

	private int allocatedDrawCount;
	private int writtenRangeCount;

	DynamicModelVAO(String name, boolean useStagingBuffer) {
		if (useStagingBuffer && SUPPORTS_STORAGE_BUFFERS) {
			this.vboRender = new GLBuffer("VAO::VBO::" + name, GL_ARRAY_BUFFER, GL_STATIC_DRAW, 0);
			this.vboStaging = new GLBuffer(
				"VAO::VBO_STAGING::" + name,
				GL_ARRAY_BUFFER,
				GL_STREAM_DRAW,
				STORAGE_PERSISTENT | STORAGE_IMMUTABLE | STORAGE_WRITE
			);
		} else {
			this.vboRender = this.vboStaging = new GLBuffer(
				"VAO::VBO::" + name,
				GL_ARRAY_BUFFER,
				GL_STREAM_DRAW,
				STORAGE_PERSISTENT | STORAGE_IMMUTABLE | STORAGE_WRITE
			);
		}
		this.vboWriter = new GLMappedBufferIntWriter(this.vboStaging);

		this.tboF = new GLTextureBuffer("VAO::TexturedFaces::" + name, GL_STREAM_DRAW, STORAGE_PERSISTENT | STORAGE_IMMUTABLE | STORAGE_WRITE);
		this.tboM = new GLTextureBuffer("VAO::ModelData::" + name, GL_STREAM_DRAW, STORAGE_PERSISTENT | STORAGE_IMMUTABLE | STORAGE_WRITE);
		this.tboFWriter = new GLMappedBufferIntWriter(this.tboF);
		this.tboMWriter = new GLMappedBufferIntWriter(this.tboM);

		Arrays.fill(packedOffsets, -1);
	}

	public boolean hasStagingBuffer() { return vboRender != vboStaging; }

	void initialize() {
		vao = glGenVertexArrays();
		tboF.initialize(INITIAL_SIZE);
		tboM.initialize(INITIAL_SIZE);
		vboRender.initialize(INITIAL_SIZE);
		if (vboRender != vboStaging)
			vboStaging.initialize(INITIAL_SIZE);

		bindRenderVAO();
	}

	void bindRenderVAO() {
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vboRender.id);

		// Position
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_HALF_FLOAT, false, VERT_SIZE, 0);

		// UVs
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 4, GL_HALF_FLOAT, false, VERT_SIZE, 8);

		// Normals
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 4, GL_SHORT, false, VERT_SIZE, 16);

		// TextureFaceIdx
		glEnableVertexAttribArray(3);
		glVertexAttribIPointer(3, 1, GL_INT, VERT_SIZE, 24);

		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);
	}

	void map() {
		if(!vboRender.isStorageBuffer())
			vboRender.orphan();

		vboWriter.map(false);
		tboFWriter.map(false);
		tboMWriter.map(false);

		reset();
		isMapped = true;
	}

	synchronized void unmap() {
		final int renderVBOId = vboRender.id;
		vboWriter.flush();
		tboFWriter.flush();
		tboMWriter.flush();

		if (pendingCopyCount > 0)
			vboStaging.copyMultiTo(vboRender, pendingCopySrcOffsets, pendingCopyDstOffsets, pendingCopyNumBytes, pendingCopyCount);
		pendingCopyCount = 0;

		if (renderVBOId != vboRender.id)
			bindRenderVAO();
		isMapped = false;
	}

	@Override
	public void destroy() {
		vboWriter.destroy();
		tboFWriter.destroy();
		tboMWriter.destroy();
		vboRender.destroy();
		vboStaging.destroy();
		tboF.destroy();
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
		view.vbo = vboWriter.reserve(faceCount * 3 * VERT_SIZE_INTS);
		view.tboF = tboFWriter.reserve(faceCount * 4);
		view.tboM = tboMWriter.reserve(Zone.MODEL_DATA_SIZE / Integer.BYTES);
		view.vao = vao;
		view.tboFId = tboF.getTexId();
		view.tboMId = tboM.getTexId();
		view.drawIdx = drawIdx;

		return view;
	}

	private synchronized void endDraw(View view) {
		assert drawOffsets[view.drawIdx] == 0 && drawCounts[view.drawIdx] == 0 : String.format(
			"Provided draw index is already in use: %d %d %d",
			view.drawIdx, drawOffsets[view.drawIdx], drawCounts[view.drawIdx]
		);
		assert isMapped : "beginDraw called while not mapped, this is not allowed!";

		drawOffsets[view.drawIdx] = view.getStartOffset() / VERT_SIZE_INTS;
		drawCounts[view.drawIdx] = view.getVertexCount();
		writtenRangeCount = max(writtenRangeCount, view.drawIdx + 1);

		// Clear ReservedViews before returning to pool
		view.vbo = null;
		view.tboF = null;
		view.tboM = null;

		usedViews.add(view);
	}

	private void addPendingCopy(int srcOffsetInts, int dstOffsetInts, int countInts) {
		long srcBytes = srcOffsetInts * (long) VERT_SIZE;
		long dstBytes = dstOffsetInts * (long) VERT_SIZE;
		long numBytes = countInts * (long) VERT_SIZE;

		if (pendingCopyCount > 0) {
			int last = pendingCopyCount - 1;
			if (
				pendingCopySrcOffsets[last] + pendingCopyNumBytes[last] == srcBytes &&
				pendingCopyDstOffsets[last] + pendingCopyNumBytes[last] == dstBytes
			) {
				pendingCopyNumBytes[last] += numBytes;
				return;
			}
		}

		if (pendingCopyCount >= pendingCopySrcOffsets.length) {
			pendingCopySrcOffsets = Arrays.copyOf(pendingCopySrcOffsets, pendingCopySrcOffsets.length * 2);
			pendingCopyDstOffsets = Arrays.copyOf(pendingCopyDstOffsets, pendingCopyDstOffsets.length * 2);
			pendingCopyNumBytes = Arrays.copyOf(pendingCopyNumBytes, pendingCopyNumBytes.length * 2);
		}

		pendingCopySrcOffsets[pendingCopyCount] = srcBytes;
		pendingCopyDstOffsets[pendingCopyCount] = dstBytes;
		pendingCopyNumBytes[pendingCopyCount] = numBytes;
		pendingCopyCount++;
	}

	private int mergeRanges(int fromDrawIdx, int toDrawIdx) {
		int count = 0;
		int maxCount = toDrawIdx - fromDrawIdx;
		if (mergedOffsets.length < maxCount) {
			mergedOffsets = Arrays.copyOf(mergedOffsets, maxCount);
			mergedCounts = Arrays.copyOf(mergedCounts, maxCount);
		}

		final boolean staging = hasStagingBuffer();
		final int[] mOffsets = mergedOffsets;
		final int[] mCounts = mergedCounts;

		for (int i = fromDrawIdx; i < toDrawIdx; i++) {
			int c = drawCounts[i];
			if (c <= 0)
				continue;

			int offset;
			if (staging) {
				int packed = packedOffsets[i];
				if (packed == -1) {
					packed = nextPackedOffset;
					nextPackedOffset += c;
					addPendingCopy(drawOffsets[i], packed, c);
					packedOffsets[i] = packed;
				}
				offset = packed;
			} else {
				offset = drawOffsets[i];
			}

			if (count > 0 && mOffsets[count - 1] + mCounts[count - 1] == offset) {
				mCounts[count - 1] += c;
			} else {
				mOffsets[count] = offset;
				mCounts[count] = c;
				count++;
			}
		}
		return count;
	}

	void draw(CommandBuffer cmd, int fromDrawIdx, int toDrawIdx) {
		int rangeCount = mergeRanges(fromDrawIdx, toDrawIdx);
		if (rangeCount <= 0)
			return;

		cmd.BindVertexArray(vao);
		cmd.BindTextureUnit(GL_TEXTURE_BUFFER, tboF.getTexId(), TEXTURE_UNIT_TEXTURED_FACES);
		cmd.BindTextureUnit(GL_TEXTURE_BUFFER, tboM.getTexId(), TEXTURE_UNIT_MODEL_DATA);

		if (rangeCount == 1) {
			if (GL_CAPS.OpenGL40 && SUPPORTS_INDIRECT_DRAW) {
				cmd.DrawArraysIndirect(GL_TRIANGLES, mergedOffsets[0], mergedCounts[0], ZoneRenderer.indirectDrawCmdsStaging);
			} else {
				cmd.DrawArrays(GL_TRIANGLES, mergedOffsets[0], mergedCounts[0]);
			}
		} else {
			if (GL_CAPS.OpenGL43 && SUPPORTS_INDIRECT_DRAW) {
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
		pendingCopyCount = 0;
		freeViews.addAll(usedViews);
		usedViews.clear();
	}

	public final class View {
		public ReservedView vbo;
		public ReservedView tboF;
		public ReservedView tboM;
		public int vao;
		public int tboFId;
		public int tboMId;

		@Getter
		private int drawIdx;

		public int getStartOffset() {
			return vbo.getBufferOffsetInts();
		}

		public int getEndOffset() {
			return vbo.getEndOffsetInts();
		}

		public int getVertexCount() {
			return (getEndOffset() - getStartOffset()) / VERT_SIZE_INTS;
		}

		public void end() {
			endDraw(this);
		}
	}
}