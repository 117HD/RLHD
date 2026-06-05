package rs117.hd.renderer.zone.passes;

import java.io.IOException;
import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.GLPrimitives;
import rs117.hd.opengl.shader.DebugDrawShaderProgram;
import rs117.hd.opengl.shader.DebugDrawShaderProgram.DebugDrawCubeShaderProgram;
import rs117.hd.opengl.shader.DebugDrawShaderProgram.DebugDrawLineShaderProgram;
import rs117.hd.opengl.shader.DebugDrawShaderProgram.DebugDrawSphereShaderProgram;
import rs117.hd.opengl.shader.DebugDrawShaderProgram.DebugDrawTextShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.utils.DebugDraw;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.collections.ConcurrentPool;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FILL;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL11.GL_GEQUAL;
import static org.lwjgl.opengl.GL11.GL_INT;
import static org.lwjgl.opengl.GL11.GL_LINE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glPolygonMode;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL30.glVertexAttribIPointer;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;
import static rs117.hd.overlays.Timer.RENDER_DEBUG_DRAW;

@Slf4j
@Singleton
public class DebugDrawPass implements RenderPass {
	private static final ConcurrentPool<Draw> POOL = new ConcurrentPool<>(Draw::new);

	private static final int INITIAL_CAPACITY = 256;

	private static final int CUBE_FLOATS   = 7; // cx cy cz  hx hy hz  argb
	private static final int SPHERE_FLOATS = 5; // cx cy cz  r  argb
	private static final int LINE_FLOATS   = 8; // x1 y1 z1  x2 y2 z2  thickness  argb
	private static final int TEXT_FLOATS   = 7; // wx wy wz scale charCode charIndex argb

	private final ConcurrentLinkedQueue<Draw> lineQueue   = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Draw> aabbQueue   = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Draw> sphereQueue = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Draw> textQueue   = new ConcurrentLinkedQueue<>();

	private IntBuffer aabbBufSolid;
	private IntBuffer aabbBufWire;
	private IntBuffer sphereBufSolid;
	private IntBuffer sphereBufWire;
	private IntBuffer lineBuf;
	private IntBuffer textBuf;

	private PrimitiveDraw cubeDraw;
	private PrimitiveDraw sphereDraw;
	private PrimitiveDraw lineDraw;
	private PrimitiveDraw textDraw;

	@Inject
	private HdPlugin plugin;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private DebugDrawCubeShaderProgram cubeShader;

	@Inject
	private DebugDrawSphereShaderProgram sphereShader;

	@Inject
	private DebugDrawLineShaderProgram lineShader;

	@Inject
	private DebugDrawTextShaderProgram textShader;

	@Override
	public RenderPassType getType() { return RenderPassType.DEBUG_DRAW; }

	@Override
	public void initialize() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			cubeDraw = new PrimitiveDraw(GLPrimitives.buildCube(stack), "Cube", cubeShader, CUBE_FLOATS);

			glBindVertexArray(cubeDraw.vao);
			glBindBuffer(GL_ARRAY_BUFFER, cubeDraw.mesh.getVbo().id);
			glEnableVertexAttribArray(0);
			glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);

			cubeDraw.instanceVbo.bind();
			glEnableVertexAttribArray(1);                                                       // center
			glVertexAttribPointer(1, 3, GL_FLOAT, false, cubeDraw.stride, 0);
			glVertexAttribDivisor(1, 1);

			glEnableVertexAttribArray(2);                                                       // halfExtents
			glVertexAttribPointer(2, 3, GL_FLOAT, false, cubeDraw.stride, 3 * Float.BYTES);
			glVertexAttribDivisor(2, 1);

			glEnableVertexAttribArray(3);                                                       // argb
			glVertexAttribIPointer(3, 1, GL_INT, cubeDraw.stride, 6 * Float.BYTES);
			glVertexAttribDivisor(3, 1);

			glBindBuffer(GL_ARRAY_BUFFER, 0);
			glBindVertexArray(0);
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			sphereDraw = new PrimitiveDraw(GLPrimitives.buildSphere(stack, 16, 32), "Sphere", sphereShader, SPHERE_FLOATS);

			glBindVertexArray(sphereDraw.vao);
			glBindBuffer(GL_ARRAY_BUFFER, sphereDraw.mesh.getVbo().id);
			glEnableVertexAttribArray(0);
			glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);

			sphereDraw.instanceVbo.bind();
			glEnableVertexAttribArray(1);                                                       // center
			glVertexAttribPointer(1, 3, GL_FLOAT, false, sphereDraw.stride, 0);
			glVertexAttribDivisor(1, 1);

			glEnableVertexAttribArray(2);                                                       // radius
			glVertexAttribPointer(2, 1, GL_FLOAT, false, sphereDraw.stride, 3 * Float.BYTES);
			glVertexAttribDivisor(2, 1);

			glEnableVertexAttribArray(3);                                                       // argb
			glVertexAttribIPointer(3, 1, GL_INT, sphereDraw.stride, 4 * Float.BYTES);
			glVertexAttribDivisor(3, 1);

			glBindBuffer(GL_ARRAY_BUFFER, 0);
			glBindVertexArray(0);
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			lineDraw = new PrimitiveDraw(GLPrimitives.buildLine(stack), "Line", lineShader, LINE_FLOATS);

			glBindVertexArray(lineDraw.vao);
			glBindBuffer(GL_ARRAY_BUFFER, lineDraw.mesh.getVbo().id);
			glEnableVertexAttribArray(0);
			glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);

			lineDraw.instanceVbo.bind();
			glEnableVertexAttribArray(1);                                                       // start
			glVertexAttribPointer(1, 3, GL_FLOAT, false, lineDraw.stride, 0);
			glVertexAttribDivisor(1, 1);

			glEnableVertexAttribArray(2);                                                       // end
			glVertexAttribPointer(2, 3, GL_FLOAT, false, lineDraw.stride, 3 * Float.BYTES);
			glVertexAttribDivisor(2, 1);

			glEnableVertexAttribArray(3);                                                       // thickness
			glVertexAttribPointer(3, 1, GL_FLOAT, false, lineDraw.stride, 6 * Float.BYTES);
			glVertexAttribDivisor(3, 1);

			glEnableVertexAttribArray(4);                                                       // argb
			glVertexAttribIPointer(4, 1, GL_INT, lineDraw.stride, 7 * Float.BYTES);
			glVertexAttribDivisor(4, 1);

			glBindBuffer(GL_ARRAY_BUFFER, 0);
			glBindVertexArray(0);
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			textDraw = new PrimitiveDraw(GLPrimitives.buildQuad(stack), "Text", textShader, TEXT_FLOATS);

			glBindVertexArray(textDraw.vao);
			glBindBuffer(GL_ARRAY_BUFFER, textDraw.mesh.getVbo().id);
			glEnableVertexAttribArray(0);
			glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);

			textDraw.instanceVbo.bind();
			glEnableVertexAttribArray(1);                                                       // aCenter
			glVertexAttribPointer(1, 3, GL_FLOAT, false, textDraw.stride, 0);
			glVertexAttribDivisor(1, 1);

			glEnableVertexAttribArray(2);                                                       // aScale
			glVertexAttribPointer(2, 1, GL_FLOAT, false, textDraw.stride, 3 * Float.BYTES);
			glVertexAttribDivisor(2, 1);

			glEnableVertexAttribArray(3);                                                       // aCharCode
			glVertexAttribIPointer(3, 1, GL_INT, textDraw.stride, 4 * Float.BYTES);
			glVertexAttribDivisor(3, 1);

			glEnableVertexAttribArray(4);                                                       // aCharIndex
			glVertexAttribIPointer(4, 1, GL_INT, textDraw.stride, 5 * Float.BYTES);
			glVertexAttribDivisor(4, 1);

			glEnableVertexAttribArray(5);                                                       // argb
			glVertexAttribIPointer(5, 1, GL_INT, textDraw.stride, 6 * Float.BYTES);
			glVertexAttribDivisor(5, 1);

			glBindBuffer(GL_ARRAY_BUFFER, 0);
			glBindVertexArray(0);
		}

		aabbBufSolid   = MemoryUtil.memAllocInt(INITIAL_CAPACITY * CUBE_FLOATS);
		aabbBufWire    = MemoryUtil.memAllocInt(INITIAL_CAPACITY * CUBE_FLOATS);
		sphereBufSolid = MemoryUtil.memAllocInt(INITIAL_CAPACITY * SPHERE_FLOATS);
		sphereBufWire  = MemoryUtil.memAllocInt(INITIAL_CAPACITY * SPHERE_FLOATS);
		lineBuf        = MemoryUtil.memAllocInt(INITIAL_CAPACITY * LINE_FLOATS);
		textBuf 	   = MemoryUtil.memAllocInt(8192 * TEXT_FLOATS);

		DebugDraw.INSTANCE = this;
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		cubeShader.compile(includes);
		sphereShader.compile(includes);
		lineShader.compile(includes);
		textShader.compile(includes);
	}

	@Override
	public void destroyShaders() {
		cubeShader.destroy();
		sphereShader.destroy();
		lineShader.destroy();
		textShader.destroy();
	}

	@Override
	public void destroy() {
		DebugDraw.INSTANCE = null;

		if(cubeDraw != null)
			cubeDraw.destroy();
		cubeDraw = null;

		if(sphereDraw != null)
			sphereDraw.destroy();
		sphereDraw = null;

		if(lineDraw != null)
			lineDraw.destroy();
		lineDraw = null;

		if(textDraw != null)
			textDraw.destroy();
		textDraw = null;

		if (aabbBufSolid   != null) { MemoryUtil.memFree(aabbBufSolid);   aabbBufSolid   = null; }
		if (aabbBufWire    != null) { MemoryUtil.memFree(aabbBufWire);    aabbBufWire    = null; }
		if (sphereBufSolid != null) { MemoryUtil.memFree(sphereBufSolid); sphereBufSolid = null; }
		if (sphereBufWire  != null) { MemoryUtil.memFree(sphereBufWire);  sphereBufWire  = null; }
		if (lineBuf        != null) { MemoryUtil.memFree(lineBuf);        lineBuf        = null; }

		lineQueue.clear();
		aabbQueue.clear();
		sphereQueue.clear();
		textQueue.clear();
	}

	@Override
	public void draw(RenderState renderState) {
		if (lineQueue.isEmpty() && aabbQueue.isEmpty() && sphereQueue.isEmpty() && textQueue.isEmpty())
			return;

		frameTimer.begin(RENDER_DEBUG_DRAW);

		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.fboScene);
		renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthFunc.set(GL_GEQUAL);
		renderState.depthMask.set(false);
		renderState.enable.set(GL_BLEND);
		renderState.blendFunc.set(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		renderState.apply();

		aabbBufSolid   = ensureCapacity(aabbBufSolid,   aabbQueue.size()   * CUBE_FLOATS);
		aabbBufWire    = ensureCapacity(aabbBufWire,     aabbQueue.size()   * CUBE_FLOATS);
		sphereBufSolid = ensureCapacity(sphereBufSolid,  sphereQueue.size() * SPHERE_FLOATS);
		sphereBufWire  = ensureCapacity(sphereBufWire,   sphereQueue.size() * SPHERE_FLOATS);
		lineBuf        = ensureCapacity(lineBuf,         lineQueue.size()   * LINE_FLOATS);

		aabbBufSolid.clear();
		aabbBufWire.clear();
		sphereBufSolid.clear();
		sphereBufWire.clear();
		lineBuf.clear();
		textBuf.clear();

		for (Draw d : aabbQueue)
			d.writeCube(d.filled ? aabbBufSolid : aabbBufWire);

		for (Draw d : sphereQueue)
			d.writeSphere(d.filled ? sphereBufSolid : sphereBufWire);

		for (Draw d : lineQueue)
			d.writeLine(lineBuf);

		for (Draw d : textQueue)
			d.writeText(textBuf);

		renderState.enable.set(GL_CULL_FACE);
		renderState.apply();

		cubeDraw.uploadAndDraw(aabbBufSolid);
		sphereDraw.uploadAndDraw(sphereBufSolid);
		textDraw.uploadAndDraw(textBuf);

		renderState.disable.set(GL_CULL_FACE);
		renderState.apply();

		glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);

		cubeDraw.uploadAndDraw(aabbBufWire);
		sphereDraw.uploadAndDraw(sphereBufWire);

		glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

		lineDraw.uploadAndDraw(lineBuf);

		renderState.disable.set(GL_DEPTH_TEST);
		renderState.disable.set(GL_BLEND);
		renderState.disable.set(GL_CULL_FACE);
		renderState.depthMask.set(true);
		renderState.apply();

		frameTimer.end(RENDER_DEBUG_DRAW);

		expireQueue(lineQueue);
		expireQueue(aabbQueue);
		expireQueue(sphereQueue);
		expireQueue(textQueue);
	}

	public Draw pushDraw(PrimitiveDrawType type) {
		Draw d = POOL.acquire();
		switch (type) {
			case AABB:
				aabbQueue.add(d);
				break;
			case SPHERE:
				sphereQueue.add(d);
				break;
			case LINE:
				lineQueue.add(d);
				break;
			case TEXT:
				textQueue.add(d);
				break;
			default:
				throw new RuntimeException("Invalid draw type: " + type);
		}
		return d;
	}

	private void expireQueue(ConcurrentLinkedQueue<Draw> queue) {
		final float delta = plugin.deltaTime;
		Iterator<Draw> it = queue.iterator();
		while (it.hasNext()) {
			Draw d = it.next();
			d.duration -= delta;
			if (d.duration <= 0f) {
				it.remove();
				POOL.recycle(d);
			}
		}
	}

	private static IntBuffer ensureCapacity(IntBuffer buf, int requiredInts) {
		if (buf.capacity() >= requiredInts) return buf;
		MemoryUtil.memFree(buf);
		int cap = Math.max(Integer.highestOneBit(requiredInts - 1) << 1, INITIAL_CAPACITY);
		return MemoryUtil.memAllocInt(cap);
	}

	public static class Draw {
		public float x1, y1, z1;
		public float x2, y2, z2;
		public float thickness;
		public int rgb;
		public float duration;
		public boolean filled;
		public String text;

		private void writeCube(IntBuffer buf) {
			buf.put(Float.floatToRawIntBits(x1))
				.put(Float.floatToRawIntBits(y1))
				.put(Float.floatToRawIntBits(z1))
				.put(Float.floatToRawIntBits(x2))
				.put(Float.floatToRawIntBits(y2))
				.put(Float.floatToRawIntBits(z2))
				.put(rgb);
		}

		private void writeSphere(IntBuffer buf) {
			buf.put(Float.floatToRawIntBits(x1))
				.put(Float.floatToRawIntBits(y1))
				.put(Float.floatToRawIntBits(z1))
				.put(Float.floatToRawIntBits(x2))
				.put(rgb);
		}

		private void writeLine(IntBuffer buf) {
			buf.put(Float.floatToRawIntBits(x1))
				.put(Float.floatToRawIntBits(y1))
				.put(Float.floatToRawIntBits(z1))
				.put(Float.floatToRawIntBits(x2))
				.put(Float.floatToRawIntBits(y2))
				.put(Float.floatToRawIntBits(z2))
				.put(Float.floatToRawIntBits(thickness))
				.put(rgb);
		}

		private void writeText(IntBuffer buf) {
			for(int c = 0; c < text.length(); c++) {
				buf.put(Float.floatToRawIntBits(x1))
					.put(Float.floatToRawIntBits(y1))
					.put(Float.floatToRawIntBits(z1))
					.put(Float.floatToRawIntBits(x2))
					.put(text.charAt(c))
					.put(c)
					.put(rgb);
			}
		}
	}

	public enum PrimitiveDrawType {
		AABB,
		SPHERE,
		LINE,
		TEXT
	};

	private static class PrimitiveDraw {
		final GLPrimitives.Mesh mesh;
		final GLBuffer instanceVbo;
		final DebugDrawShaderProgram shader;
		final int vao;
		final int floatsPerInstance;
		final int stride;

		PrimitiveDraw(GLPrimitives.Mesh mesh, String name, DebugDrawShaderProgram shader, int floatsPerInstance) {
			this.mesh   = mesh;
			this.floatsPerInstance = floatsPerInstance;
			this.stride = floatsPerInstance * Float.BYTES;
			this.instanceVbo = new GLBuffer("VBO::" + name + "::Instances", GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW);
			this.instanceVbo.initialize((long) INITIAL_CAPACITY * stride);
			this.shader = shader;
			this.vao = glGenVertexArrays();
		}

		void uploadAndDraw(IntBuffer data) {
			if(data.position() == 0)
				return;
			data.flip();

			assert data.limit() % floatsPerInstance == 0;
			final int count = data.limit() / floatsPerInstance;
			if(count == 0)
				return;

			shader.use();
			instanceVbo.upload(data);
			glBindVertexArray(vao);
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mesh.getEbo().id);
			glDrawElementsInstanced(GL_TRIANGLES, mesh.getIndexCount(), GL_UNSIGNED_INT, 0, count);
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
			glBindVertexArray(0);
		}

		void destroy() {
			glDeleteVertexArrays(vao);
			mesh.destroy();
			instanceVbo.destroy();
		}
	}
}