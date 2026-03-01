package rs117.hd.renderer.zone;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter.ReservedView;
import rs117.hd.utils.buffer.GLTextureBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.NVIDIA_GPU;
import static rs117.hd.HdPlugin.SUPPORTS_INDIRECT_DRAW;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_IMMUTABLE;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_PERSISTENT;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_WRITE;

@Slf4j
class DynamicModelVAO {
	public static final int INITIAL_SIZE = (int) (8 * MiB);

	// Temp vertex format
	// pos float vec3(x, y, z)
	// uvw short vec3(u, v, w)
	// normal short vec3(nx, ny, nz)
	static final int VERT_SIZE = 28;
	static final int VERT_SIZE_INTS = VERT_SIZE / 4;

	// Metadata format
	// worldViewIndex int
	// dummy sceneOffset ivec2 for macOS workaround
	static final int METADATA_SIZE = 12;

	int vao;
	boolean used;

	private final GLBuffer vboRender;
	private final GLBuffer vboStaging;
	private final GLTextureBuffer tbo;

	private final AtomicInteger inflightDraws = new AtomicInteger();
	private final ConcurrentLinkedQueue<View> usedViews = new ConcurrentLinkedQueue<>();
	private final ArrayDeque<View> freeViews = new ArrayDeque<>();

	private final GLMappedBufferIntWriter vboWriter;
	private final GLMappedBufferIntWriter tboWriter;

	private int[] drawOffsets = new int[16];
	private int[] drawCounts = new int[16];
	private int drawRangeCount;

	private long[] srcCopyOffsets = new long[16];
	private long[] dstCopyOffsets = new long[16];
	private long[] copyNumBytes = new long[16];

	DynamicModelVAO(String name, boolean useStagingBuffer) {
		if (useStagingBuffer) {
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
		if (vboRender != vboStaging) {
			vboStaging.initialize(INITIAL_SIZE);
		}

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
		glVertexAttribPointer(1, 3, GL_HALF_FLOAT, false, VERT_SIZE, 12);

		// Normals
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 3, GL_SHORT, false, VERT_SIZE, 18);

		// TextureFaceIdx
		glEnableVertexAttribArray(3);
		glVertexAttribIPointer(3, 1, GL_INT, VERT_SIZE, 24);
	}

	void map() {
		vboWriter.map();
		tboWriter.map();

		reset();
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
	}

	void destroy() {
		vboStaging.destroy();
		tbo.destroy();
		glDeleteVertexArrays(vao);
		vao = 0;
	}

	synchronized View beginDraw(int faceCount) {
		final int drawIdx = drawRangeCount++;
		if (drawRangeCount >= drawOffsets.length) {
			drawOffsets = Arrays.copyOf(drawOffsets, drawOffsets.length * 2);
			drawCounts = Arrays.copyOf(drawCounts, drawCounts.length * 2);
		}

		drawOffsets[drawIdx] = -1;
		drawCounts[drawIdx] = -1;

		View view = freeViews.poll();
		if (view == null) view = new View();
		view.vbo = vboWriter.reserve(faceCount * 3 * VERT_SIZE_INTS);
		view.tbo = tboWriter.reserve(faceCount * 9);
		view.vao = vao;
		view.tboTexId = tbo.getTexId();
		view.drawIdx = drawIdx;

		inflightDraws.incrementAndGet();
		return view;
	}

	private void endDraw(View view) {
		drawOffsets[view.drawIdx] = view.getStartOffset() / VERT_SIZE_INTS;
		drawCounts[view.drawIdx] = view.getVertexCount();

		usedViews.add(view);
	}

	void mergeRanges() {
		int newDrawRangeCount = 0;
		for (int i = 0; i < drawRangeCount; i++) {
			if (drawOffsets[i] != -1 && drawCounts[i] != -1) {
				if (newDrawRangeCount > 0 && drawOffsets[newDrawRangeCount - 1] + drawCounts[newDrawRangeCount - 1] == drawOffsets[i]) {
					drawCounts[newDrawRangeCount - 1] += drawCounts[i];
				} else {
					if (newDrawRangeCount != i) {
						drawOffsets[newDrawRangeCount] = drawOffsets[i];
						drawCounts[newDrawRangeCount] = drawCounts[i];
					}
					newDrawRangeCount++;
				}
			}
		}
		drawRangeCount = newDrawRangeCount;
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
		used = false;
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
