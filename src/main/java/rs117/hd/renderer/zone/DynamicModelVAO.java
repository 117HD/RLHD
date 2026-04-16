package rs117.hd.renderer.zone;

import java.util.ArrayDeque;
import java.util.Arrays;
import javax.annotation.Nonnull;
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
import static rs117.hd.HdPlugin.NVIDIA_GPU;
import static rs117.hd.HdPlugin.SUPPORTS_INDIRECT_DRAW;
import static rs117.hd.HdPlugin.SUPPORTS_STORAGE_BUFFERS;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_IMMUTABLE;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_PERSISTENT;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_WRITE;

@Slf4j
public class DynamicModelVAO implements Destructible {
	public static final int INITIAL_SIZE = (int) (8 * MiB);

	// Temp vertex format
	// pos float vec3(x, y, z)
	// uvw short vec3(u, v, w)
	// normal short vec3(nx, ny, nz)
	static final int VERT_SIZE = 32;
	static final int VERT_SIZE_INTS = VERT_SIZE / 4;

	// Metadata format
	// worldViewIndex int
	// dummy sceneOffset ivec2 for macOS workaround
	static final int METADATA_SIZE = 12;

	@Getter
	private int vao;

	private final GLBuffer vboRender;
	private final GLBuffer vboStaging;
	private final GLTextureBuffer tbo;

	private final ArrayDeque<View> usedViews = new ArrayDeque<>();
	private final ArrayDeque<View> freeViews = new ArrayDeque<>();

	private final GLMappedBufferIntWriter vboWriter;
	private final GLMappedBufferIntWriter tboWriter;

	private boolean isMapped = false;
	private int[] drawOffsets = new int[16];
	private int[] drawCounts = new int[16];
	private int writtenRangeCount;
	private int drawRangeCount;

	private long[] srcCopyOffsets = new long[16];
	private long[] dstCopyOffsets = new long[16];
	private long[] copyNumBytes = new long[16];

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

		this.tbo = new GLTextureBuffer("VAO::TBO::" + name, GL_STREAM_DRAW, STORAGE_PERSISTENT | STORAGE_IMMUTABLE | STORAGE_WRITE);
		this.tboWriter = new GLMappedBufferIntWriter(this.tbo);
	}

	public boolean hasStagingBuffer() { return vboRender != vboStaging; }

	void initialize() {
		vao = glGenVertexArrays();
		tbo.initialize(INITIAL_SIZE);
		vboRender.initialize(INITIAL_SIZE);
		if (vboRender != vboStaging)
			vboStaging.initialize(INITIAL_SIZE);

		bindRenderVAO();
	}

	public void bindMetadataVAO(@Nonnull GLBuffer vboMetadata) {
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vboMetadata.id);

		// WorldView index (not ID)
		glEnableVertexAttribArray(6);
		glVertexAttribDivisor(6, 1);
		glVertexAttribIPointer(6, 1, GL_INT, METADATA_SIZE, 0);

		if (!NVIDIA_GPU) {
			// Workaround for incorrect implementations of disabled vertex attribs, particularly on macOS
			glEnableVertexAttribArray(7);
			glVertexAttribDivisor(7, 1);
			glVertexAttribIPointer(7, 2, GL_INT, METADATA_SIZE, 4);
		}

		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);
	}

	void bindRenderVAO() {
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vboRender.id);

		// Position
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, VERT_SIZE, 0);

		// UVs
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 4, GL_HALF_FLOAT, false, VERT_SIZE, 12);

		// Normals
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 4, GL_SHORT, false, VERT_SIZE, 20);

		// TextureFaceIdx
		glEnableVertexAttribArray(3);
		glVertexAttribIPointer(3, 1, GL_INT, VERT_SIZE, 28);

		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);
	}

	void map() {
		vboWriter.map(false);
		tboWriter.map(false);

		reset();
		isMapped = true;
	}

	synchronized void unmap(boolean coalesce) {
		final int renderVBOId = vboRender.id;
		long vboWrittenBytes = vboWriter.flush();
		tboWriter.flush();

		if (drawRangeCount > 0) {
			mergeRanges();

			if (hasStagingBuffer()) {
				vboRender.orphan();
				if (drawRangeCount > 1 && coalesce) {
					if (srcCopyOffsets.length < drawRangeCount) {
						srcCopyOffsets = new long[drawRangeCount];
						dstCopyOffsets = new long[drawRangeCount];
						copyNumBytes = new long[drawRangeCount];
					}

					long dstOffset = 0;
					for (int i = 0; i < drawRangeCount; i++) {
						srcCopyOffsets[i] = drawOffsets[i] * (long) VERT_SIZE;
						dstCopyOffsets[i] = dstOffset;
						copyNumBytes[i] = drawCounts[i] * (long) VERT_SIZE;
						dstOffset += copyNumBytes[i];
					}
					vboStaging.copyMultiTo(vboRender, srcCopyOffsets, dstCopyOffsets, copyNumBytes, drawRangeCount);

					drawOffsets[0] = 0;
					drawCounts[0] = (int) (dstOffset / VERT_SIZE);
					drawRangeCount = 1;
				} else {
					vboStaging.copyTo(vboRender, 0, 0, vboWrittenBytes);
				}
			}
		}

		if (renderVBOId != vboRender.id)
			bindRenderVAO();
		isMapped = false;
	}

	@Override
	public void destroy() {
		vboWriter.destroy();
		tboWriter.destroy();
		vboRender.destroy();
		vboStaging.destroy();
		tbo.destroy();

		if (vao != 0)
			glDeleteVertexArrays(vao);
		vao = 0;
	}

	synchronized int obtainDrawIndex() {
		int drawIndex = drawRangeCount++;
		if (drawRangeCount >= drawOffsets.length) {
			int oldLength = drawOffsets.length;
			drawOffsets = Arrays.copyOf(drawOffsets, oldLength * 2);
			drawCounts = Arrays.copyOf(drawCounts, oldLength * 2);
		}
		return drawIndex;
	}

	View beginDraw(int faceCount) {
		return beginDraw(-1, faceCount);
	}

	public synchronized View beginDraw(int drawIdx, int faceCount) {
		if (drawIdx == -1)
			drawIdx = obtainDrawIndex();

		assert drawIdx >= 0 && drawIdx < drawRangeCount : "drawIdx " + drawIdx + " out of bounds from 0 to " + drawRangeCount;
		assert drawOffsets[drawIdx] == 0 && drawCounts[drawIdx] == 0 : String.format(
			"Provided draw index is already in use: %d %d %d",
			drawIdx, drawOffsets[drawIdx], drawCounts[drawIdx]
		);
		assert isMapped : "beginDraw called while not mapped, this is not allowed!";

		View view = freeViews.poll();
		if (view == null)
			view = new View();
		view.vbo = vboWriter.reserve(faceCount * 3 * VERT_SIZE_INTS);
		view.tbo = tboWriter.reserve(faceCount * 9);
		view.vao = vao;
		view.tboTexId = tbo.getTexId();
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
		view.tbo = null;

		usedViews.add(view);
	}

	void mergeRanges() {
		drawRangeCount = 0;
		for (int i = 0; i < writtenRangeCount; i++) {
			if (drawCounts[i] <= 0)
				continue;

			if (drawRangeCount > 0 && drawOffsets[drawRangeCount - 1] + drawCounts[drawRangeCount - 1] == drawOffsets[i]) {
				drawCounts[drawRangeCount - 1] += drawCounts[i];
			} else {
				if (drawRangeCount != i) {
					drawOffsets[drawRangeCount] = drawOffsets[i];
					drawCounts[drawRangeCount] = drawCounts[i];
				}
				drawRangeCount++;
			}
		}
	}

	void draw(CommandBuffer cmd) {
		if (drawRangeCount <= 0)
			return;

		cmd.BindVertexArray(vao);
		cmd.BindTextureUnit(GL_TEXTURE_BUFFER, tbo.getTexId(), TEXTURE_UNIT_TEXTURED_FACES);

		if (drawRangeCount == 1) {
			if (GL_CAPS.OpenGL40 && SUPPORTS_INDIRECT_DRAW) {
				cmd.DrawArraysIndirect(GL_TRIANGLES, drawOffsets[0], drawCounts[0], ZoneRenderer.indirectDrawCmdsStaging);
			} else {
				cmd.DrawArrays(GL_TRIANGLES, drawOffsets[0], drawCounts[0]);
			}
		} else {
			if (GL_CAPS.OpenGL43 && SUPPORTS_INDIRECT_DRAW) {
				cmd.MultiDrawArraysIndirect(GL_TRIANGLES, drawOffsets, drawCounts, drawRangeCount, ZoneRenderer.indirectDrawCmdsStaging);
			} else {
				cmd.MultiDrawArrays(GL_TRIANGLES, drawOffsets, drawCounts, drawRangeCount);
			}
		}
	}

	void reset() {
		Arrays.fill(drawOffsets, 0, writtenRangeCount, 0);
		Arrays.fill(drawCounts, 0, writtenRangeCount, 0);
		drawRangeCount = 0;
		freeViews.addAll(usedViews);
		usedViews.clear();
	}

	public final class View {
		public ReservedView vbo;
		public ReservedView tbo;
		public int vao;
		public int tboTexId;
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
