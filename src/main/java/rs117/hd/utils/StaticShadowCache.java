package rs117.hd.utils;

import java.nio.IntBuffer;
import java.util.Arrays;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.opengl.shader.StaticShadowBlitProgram;
import rs117.hd.utils.TextureAtlasPacker.Rect;
import rs117.hd.utils.collections.PrimitiveIntArray;

import static org.lwjgl.opengl.GL11.GL_ALWAYS;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_INT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NONE;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDrawBuffer;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.glTexImage3D;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT16;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glBufferSubData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_MAX_ARRAY_TEXTURE_LAYERS;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL30.glVertexAttribIPointer;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_COUNT;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;
import static rs117.hd.HdPlugin.checkGLErrors;

@Slf4j
public class StaticShadowCache {
	private static final int NUM_FACES = 6;

	// x, y, size, localSlot (all as ints)
	private static final int INSTANCE_COMPONENTS = 4;
	private static final int INSTANCE_STRIDE_BYTES = INSTANCE_COMPONENTS * Integer.BYTES;

	private static final int TEXTURE_UNIT_STATIC_SHADOW_CACHE = GL_TEXTURE0 + TEXTURE_UNIT_COUNT++;

	private final int maxSlots;
	@Getter
	private final int maxFaceResolution;
	private final int atlasSize;

	private StaticShadowBlitProgram staticShadowBlitProgram;

	private int[] texCaches;
	private int slotsPerArray;
	private int arrayCount;
	private int fboBake;

	private int vaoInstancedQuad;
	private int vboInstances;

	private int[] arrayInstanceCounts;
	private int[] arrayInstanceOffsets;

	private int[] instanceScratch;
	private int[] writeCursor;
	private IntBuffer instanceUploadBuffer;

	private final PrimitiveIntArray freeSlots = new PrimitiveIntArray();

	public StaticShadowCache(int maxSlots, int maxFaceResolution, int atlasSize) {
		this.maxSlots = maxSlots;
		this.maxFaceResolution = maxFaceResolution;
		this.atlasSize = atlasSize;
	}

	public void initialize(StaticShadowBlitProgram staticShadowBlitProgram) {
		this.staticShadowBlitProgram = staticShadowBlitProgram;

		// Maximum layers supported by one GL_TEXTURE_2D_ARRAY
		final int maxTextureLayers = glGetInteger(GL_MAX_ARRAY_TEXTURE_LAYERS);
		final int facesPerArray = (maxTextureLayers / NUM_FACES) * NUM_FACES;
		slotsPerArray = facesPerArray / NUM_FACES;

		if (slotsPerArray == 0)
			throw new IllegalStateException("GPU only supports " + maxTextureLayers + " array layers, cannot fit even one shadow cubemap.");

		arrayCount = (maxSlots + slotsPerArray - 1) / slotsPerArray;
		texCaches = new int[arrayCount];

		glActiveTexture(TEXTURE_UNIT_UI);
		for (int i = 0; i < arrayCount; i++) {
			texCaches[i] = glGenTextures();
			glBindTexture(GL_TEXTURE_2D_ARRAY, texCaches[i]);

			int slotsInThisArray = Math.min(
				slotsPerArray,
				maxSlots - i * slotsPerArray);

			glTexImage3D(
				GL_TEXTURE_2D_ARRAY,
				0,
				GL_DEPTH_COMPONENT16,
				maxFaceResolution,
				maxFaceResolution,
				slotsInThisArray * NUM_FACES,
				0,
				GL_DEPTH_COMPONENT,
				GL_FLOAT,
				0
			);

			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		}
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);

		fboBake = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboBake);
		glDrawBuffer(GL_NONE);
		glReadBuffer(GL_NONE);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);

		vboInstances = glGenBuffers();
		glBindBuffer(GL_ARRAY_BUFFER, vboInstances);
		glBufferData(GL_ARRAY_BUFFER, (long) maxSlots * INSTANCE_STRIDE_BYTES, GL_DYNAMIC_DRAW);

		vaoInstancedQuad = glGenVertexArrays();
		glBindVertexArray(vaoInstancedQuad);
		glBindBuffer(GL_ARRAY_BUFFER, vboInstances);
		glEnableVertexAttribArray(0);
		glVertexAttribIPointer(0, INSTANCE_COMPONENTS, GL_INT, INSTANCE_STRIDE_BYTES, 0);
		glVertexAttribDivisor(0, 1);
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);

		arrayInstanceCounts = new int[arrayCount];
		arrayInstanceOffsets = new int[arrayCount + 1];
		instanceScratch = new int[maxSlots * INSTANCE_COMPONENTS];
		writeCursor = new int[arrayCount];
		instanceUploadBuffer = MemoryUtil.memAllocInt(maxSlots * INSTANCE_COMPONENTS);

		freeSlots.reset();
		freeSlots.ensureCapacity(maxSlots);
		for (int i = maxSlots - 1; i >= 0; i--)
			freeSlots.put(i);

		checkGLErrors();
	}

	public void destroy() {
		if (texCaches != null) {
			for (int tex : texCaches)
				if (tex != 0)
					glDeleteTextures(tex);

			texCaches = null;
		}

		if (fboBake != 0)
			glDeleteFramebuffers(fboBake);
		fboBake = 0;

		if (vaoInstancedQuad != 0)
			glDeleteVertexArrays(vaoInstancedQuad);
		vaoInstancedQuad = 0;

		if (vboInstances != 0)
			glDeleteBuffers(vboInstances);
		vboInstances = 0;

		if (instanceUploadBuffer != null)
			MemoryUtil.memFree(instanceUploadBuffer);
		instanceUploadBuffer = null;

		freeSlots.reset();
	}

	public int acquireSlot() {
		if (freeSlots.length == 0) {
			log.warn("StaticShadowCache exhausted ({} slots)", maxSlots);
			return -1;
		}
		return freeSlots.array[--freeSlots.length];
	}

	public void releaseSlot(int slot) {
		if (slot < 0)
			return;
		freeSlots.put(slot);
	}

	public void updateGrid(int[] slotsBySortedLight, Rect[] rectsBySortedLight, int count) {
		Arrays.fill(arrayInstanceCounts, 0);

		//count how many active rects land in each backing array.
		for (int i = 0; i < count; i++) {
			final int slot = slotsBySortedLight[i];
			final Rect r = rectsBySortedLight[i];
			if (slot < 0 || r == null)
				continue;

			arrayInstanceCounts[slot / slotsPerArray]++;
		}

		arrayInstanceOffsets[0] = 0;
		for (int a = 0; a < arrayCount; a++)
			arrayInstanceOffsets[a + 1] = arrayInstanceOffsets[a] + arrayInstanceCounts[a];

		final int totalInstances = arrayInstanceOffsets[arrayCount];
		if (totalInstances == 0)
			return;

		// scatter each rect into its array's bucket.
		// writeCursor starts at each array's bucket offset and advances as we fill it.
		System.arraycopy(arrayInstanceOffsets, 0, writeCursor, 0, arrayCount);

		for (int i = 0; i < count; i++) {
			final int slot = slotsBySortedLight[i];
			final Rect r = rectsBySortedLight[i];
			if (slot < 0 || r == null)
				continue;

			final int array = slot / slotsPerArray;
			final int localSlot = slot % slotsPerArray;

			final int pos = writeCursor[array]++;
			final int base = pos * INSTANCE_COMPONENTS;
			instanceScratch[base] = r.x;
			instanceScratch[base + 1] = r.y;
			instanceScratch[base + 2] = r.size;
			instanceScratch[base + 3] = localSlot;
		}

		instanceUploadBuffer.clear();
		instanceUploadBuffer.put(instanceScratch, 0, totalInstances * INSTANCE_COMPONENTS);
		instanceUploadBuffer.flip();

		glBindBuffer(GL_ARRAY_BUFFER, vboInstances);
		glBufferSubData(GL_ARRAY_BUFFER, 0, instanceUploadBuffer);
	}

	public void beginBakeLayer(RenderState renderState, int slot, int face) {
		int array = slot / slotsPerArray;
		int localSlot = slot % slotsPerArray;

		renderState.framebuffer.set(GL_FRAMEBUFFER, fboBake);
		renderState.framebufferTextureLayer.set(
			GL_FRAMEBUFFER,
			GL_DEPTH_ATTACHMENT,
			texCaches[array],
			0,
			localSlot * NUM_FACES + face);
		renderState.viewport.set(0, 0, maxFaceResolution, maxFaceResolution);
	}

	public void blitFace(RenderState renderState, int face) {
		renderState.depthFunc.set(GL_ALWAYS);
		renderState.apply();

		staticShadowBlitProgram.use();

		staticShadowBlitProgram.staticCache.set(TEXTURE_UNIT_STATIC_SHADOW_CACHE);
		staticShadowBlitProgram.face.set(face);
		staticShadowBlitProgram.atlasSize.set(atlasSize);
		staticShadowBlitProgram.maxFaceResolution.set(maxFaceResolution);

		glBindVertexArray(vaoInstancedQuad);
		glBindBuffer(GL_ARRAY_BUFFER, vboInstances);

		for (int array = 0; array < arrayCount; array++) {
			final int instCount = arrayInstanceCounts[array];
			if (instCount == 0)
				continue;

			final long byteOffset = (long) arrayInstanceOffsets[array] * INSTANCE_STRIDE_BYTES;
			glVertexAttribIPointer(0, INSTANCE_COMPONENTS, GL_INT, INSTANCE_STRIDE_BYTES, byteOffset);

			glActiveTexture(TEXTURE_UNIT_STATIC_SHADOW_CACHE);
			glBindTexture(GL_TEXTURE_2D_ARRAY, texCaches[array]);

			glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, instCount);
		}

		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
	}
}