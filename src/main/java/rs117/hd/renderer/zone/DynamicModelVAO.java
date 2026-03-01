package rs117.hd.renderer.zone;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.GLVao;
import rs117.hd.opengl.GLVertexLayout;
import rs117.hd.opengl.GLVertexLayout.ArrayField;
import rs117.hd.opengl.GLVertexLayout.ComponentType;
import rs117.hd.opengl.GLVertexLayout.FormatType;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter.ReservedView;
import rs117.hd.utils.buffer.GLTextureBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.GL_CAPS;
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

	public static final GLVertexLayout DYNAMIC_MODEL_VERTEX_LAYOUT = new GLVertexLayout("DYNAMIC_MODEL_VERTEX_LAYOUT")
		// Mesh Data
		.edit(ArrayField.VERTEX_FIELD_0).enabled().component(ComponentType.RGB).format(FormatType.FLOAT).stride(VERT_SIZE).offset(0)
		.edit(ArrayField.VERTEX_FIELD_1).enabled().component(ComponentType.RGB).format(FormatType.HALF_FLOAT).stride(VERT_SIZE).offset(12)
		.edit(ArrayField.VERTEX_FIELD_2).enabled().component(ComponentType.RGB).format(FormatType.SHORT).stride(VERT_SIZE).offset(18)
		.edit(ArrayField.VERTEX_FIELD_3).enabled().component(ComponentType.R).format(FormatType.INT).stride(VERT_SIZE).offset(24).asInteger()
		// Meta Data
		.edit(ArrayField.VERTEX_FIELD_6).enabled().component(ComponentType.R).format(FormatType.INT).stride(METADATA_SIZE).offset(0).asInteger().divisor(1)
		.edit(ArrayField.VERTEX_FIELD_7).enabled().component(ComponentType.RG).format(FormatType.INT).stride(METADATA_SIZE).offset(4).asInteger().divisor(1)
		.finish();

	GLVao vao = new GLVao("DynamicModel::VAO", DYNAMIC_MODEL_VERTEX_LAYOUT);
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
		tbo.initialize(INITIAL_SIZE);
		vboRender.initialize(INITIAL_SIZE);
		if (vboRender != vboStaging) {
			vboStaging.initialize(INITIAL_SIZE);
		}
		vao.associateBufferRange(vboRender, ArrayField.VERTEX_FIELD_0, ArrayField.VERTEX_FIELD_3);
	}

	public void bindMetadataVAO(@Nonnull GLBuffer vboMetadata) {
		vao.associateBufferRange(vboMetadata, ArrayField.VERTEX_FIELD_6, ArrayField.VERTEX_FIELD_7);
	}

	void map() {
		vboWriter.map();
		tboWriter.map();

		reset();
	}

	synchronized void unmap(boolean coalesce) {
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
	}

	void destroy() {
		vboStaging.destroy();
		tbo.destroy();
		vao.destroy();
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
		public GLVao vao;
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
