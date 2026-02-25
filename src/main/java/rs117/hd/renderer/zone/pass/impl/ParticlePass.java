/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.renderer.zone.pass.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import org.lwjgl.BufferUtils;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.shader.ParticleShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.zone.pass.ScenePass;
import rs117.hd.renderer.zone.pass.ScenePassContext;
import rs117.hd.scene.particles.ParticleBuffer;
import rs117.hd.scene.particles.ParticleManager;
import rs117.hd.scene.particles.ParticleTextureLoader;
import rs117.hd.scene.particles.emitter.ParticleEmitter;
import rs117.hd.utils.HDUtils;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static org.lwjgl.opengl.GL15.glUnmapBuffer;
import static org.lwjgl.opengl.GL30.GL_MAP_INVALIDATE_RANGE_BIT;
import static org.lwjgl.opengl.GL30.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL30.glMapBufferRange;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_PARTICLE;

@Slf4j
@Singleton
public class ParticlePass implements ScenePass {

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	private static final int MAX_PARTICLES = 4096;
	private static final int QUAD_VERTS = 6;
	private static final int FLOATS_PER_INSTANCE = 8;
	private static final float[] PARTICLE_QUAD_CORNERS = {
		-1, -1,  1, -1,  1, 1,
		-1, -1,  1, 1,  -1, 1
	};

	@Inject
	private ParticleManager particleManager;

	@Inject
	private ParticleTextureLoader particleTextureLoader;

	@Inject
	private ParticleShaderProgram particleProgram;

	private int vaoParticles;
	private int vboParticleQuad;
	private int vboParticleInstances;
	private FloatBuffer particleStagingBuffer;
	private final float[] particleDistSq = new float[MAX_PARTICLES];
	private final Integer[] particleSortOrder = new Integer[MAX_PARTICLES];
	private int whiteParticleTextureId;

	private final String[] textureForVisibleIndex = new String[MAX_PARTICLES];
	private final FloatBuffer batchUploadBuffer = BufferUtils.createFloatBuffer(MAX_PARTICLES * FLOATS_PER_INSTANCE);

	@Getter
	private int lastParticleTotalOnPlane;
	@Getter
	private int lastParticleCulledDistance;
	@Getter
	private int lastParticleCulledFrustum;
	@Getter
	private int lastParticleDrawn;

	@Override
	public String passName() {
		return "Particles";
	}

	public void initialize() {
		vaoParticles = glGenVertexArrays();
		vboParticleQuad = glGenBuffers();
		vboParticleInstances = glGenBuffers();
		FloatBuffer quadBuffer = BufferUtils.createFloatBuffer(PARTICLE_QUAD_CORNERS.length).put(PARTICLE_QUAD_CORNERS).flip();
		glBindBuffer(GL_ARRAY_BUFFER, vboParticleQuad);
		glBufferData(GL_ARRAY_BUFFER, quadBuffer, GL_STATIC_DRAW);
		glBindVertexArray(vaoParticles);
		glBindBuffer(GL_ARRAY_BUFFER, vboParticleQuad);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0);
		glVertexAttribDivisor(0, 0);
		glBindBuffer(GL_ARRAY_BUFFER, vboParticleInstances);
		int instanceStride = FLOATS_PER_INSTANCE * 4;
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 3, GL_FLOAT, false, instanceStride, 0);
		glVertexAttribDivisor(1, 1);
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 4, GL_FLOAT, false, instanceStride, 12);
		glVertexAttribDivisor(2, 1);
		glEnableVertexAttribArray(3);
		glVertexAttribPointer(3, 1, GL_FLOAT, false, instanceStride, 28);
		glVertexAttribDivisor(3, 1);
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindBuffer(GL_ARRAY_BUFFER, vboParticleInstances);
		glBufferData(GL_ARRAY_BUFFER, (long) MAX_PARTICLES * FLOATS_PER_INSTANCE * 4, GL_STREAM_DRAW);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		particleStagingBuffer = BufferUtils.createFloatBuffer(MAX_PARTICLES * FLOATS_PER_INSTANCE);

		// 1x1 white texture used when no particle texture is set
		whiteParticleTextureId = glGenTextures();
		glActiveTexture(TEXTURE_UNIT_PARTICLE);
		glBindTexture(GL_TEXTURE_2D, whiteParticleTextureId);
		ByteBuffer whitePixel = BufferUtils.createByteBuffer(4);
		whitePixel.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, whitePixel);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	}

	public void destroy() {
		if (vaoParticles != 0) {
			glDeleteVertexArrays(vaoParticles);
			vaoParticles = 0;
		}
		if (vboParticleQuad != 0) {
			glDeleteBuffers(vboParticleQuad);
			vboParticleQuad = 0;
		}
		if (vboParticleInstances != 0) {
			glDeleteBuffers(vboParticleInstances);
			vboParticleInstances = 0;
		}
		particleStagingBuffer = null;
		particleTextureLoader.dispose();
		if (whiteParticleTextureId != 0) {
			glDeleteTextures(whiteParticleTextureId);
			whiteParticleTextureId = 0;
		}
	}

	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		particleProgram.compile(includes);
	}

	public void destroyShaders() {
		particleProgram.destroy();
	}

	@Override
	public void beforeDraw(ScenePassContext ctx) {
		if (ctx.getSceneContext() != null) {
			ctx.beginTimer(Timer.UPDATE_PARTICLES);
			particleManager.update(ctx.getSceneContext(), plugin.deltaTime);
			ctx.endTimer(Timer.UPDATE_PARTICLES);
		}
	}

	@Override
	public void draw(ScenePassContext ctx) {
		int currentPlane = client.getTopLevelWorldView().getPlane();
		List<ParticleTextureBatch> batches = prepareBatches(currentPlane);
		if (batches.isEmpty())
			return;
		var renderState = ctx.getRenderState();
		renderState.program.set(particleProgram);
		renderState.enable.set(GL_BLEND);
		renderState.blendFunc.reset();
		renderState.blendFunc.set(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		renderState.disable.set(GL_CULL_FACE);
		renderState.depthMask.set(false);
		renderState.apply();
		glActiveTexture(TEXTURE_UNIT_PARTICLE);
		particleProgram.setParticleTextureUnit(TEXTURE_UNIT_PARTICLE);
		glBindVertexArray(vaoParticles);
		ctx.beginTimer(Timer.RENDER_PARTICLES);
		for (ParticleTextureBatch batch : batches) {
			int idToBind = (batch.textureId != 0) ? batch.textureId : whiteParticleTextureId;
			glBindTexture(GL_TEXTURE_2D, idToBind);
			uploadBatchInstanceData(batch.visibleIndices, batch.count);
			glDrawArraysInstanced(GL_TRIANGLES, 0, QUAD_VERTS, batch.count);
		}
		ctx.endTimer(Timer.RENDER_PARTICLES);
		glBindVertexArray(0);
	}

	/** One draw batch: same texture, instance count, and visible indices (in draw order). */
	private static class ParticleTextureBatch {
		final int textureId;
		final int count;
		final int[] visibleIndices;

		ParticleTextureBatch(int textureId, int[] visibleIndices) {
			this.textureId = textureId;
			this.count = visibleIndices.length;
			this.visibleIndices = visibleIndices;
		}
	}

	@Override
	public void afterDraw(ScenePassContext ctx) {
		ctx.getRenderState().depthMask.set(true);
	}

	/** Prepares particle instance data and batches by texture so each particle uses its emitter's texture. */
	private List<ParticleTextureBatch> prepareBatches(int currentPlane) {
		ParticleBuffer buf = particleManager.getParticleBuffer();
		particleStagingBuffer.clear();
		lastParticleTotalOnPlane = 0;
		lastParticleCulledDistance = 0;
		lastParticleCulledFrustum = 0;
		lastParticleDrawn = 0;
		float[] particleColor = new float[4];
		float cxCam = plugin.cameraPosition[0];
		float cyCam = plugin.cameraPosition[1];
		float czCam = plugin.cameraPosition[2];
		float maxDistSq = (float) (plugin.getDrawDistance() * LOCAL_TILE_SIZE);
		maxDistSq *= maxDistSq;
		float[][] frustumPlanes = plugin.cameraFrustum;
		int n = 0;
		for (int i = 0; i < buf.count; i++) {
			if (buf.plane[i] != currentPlane)
				continue;
			lastParticleTotalOnPlane++;
			float dx = buf.posX[i] - cxCam;
			float dy = buf.posY[i] - cyCam;
			float dz = buf.posZ[i] - czCam;
			float dSq = dx * dx + dy * dy + dz * dz;
			if (dSq > maxDistSq) {
				lastParticleCulledDistance++;
				continue;
			}
			if (!HDUtils.isSphereIntersectingFrustum(buf.posX[i], buf.posY[i], buf.posZ[i], buf.size[i], frustumPlanes, frustumPlanes.length)) {
				lastParticleCulledFrustum++;
				continue;
			}
			particleDistSq[n] = dSq;
			textureForVisibleIndex[n] = getTextureNameForParticle(buf, i);
			buf.getCurrentColor(i, particleColor);
			float cx = buf.posX[i] + plugin.cameraShift[0];
			float cy = buf.posY[i];
			float cz = buf.posZ[i] + plugin.cameraShift[1];
			particleStagingBuffer.put(cx).put(cy).put(cz);
			particleStagingBuffer.put(particleColor[0]).put(particleColor[1]).put(particleColor[2]).put(particleColor[3]);
			particleStagingBuffer.put(buf.size[i]);
			n++;
		}
		int instanceCount = n;
		lastParticleDrawn = instanceCount;
		if (instanceCount == 0)
			return new ArrayList<>();

		for (int i = 0; i < instanceCount; i++)
			particleSortOrder[i] = i;
		Arrays.sort(particleSortOrder, 0, instanceCount, (a, b) -> Float.compare(particleDistSq[b], particleDistSq[a]));

		// Group by texture (order preserved: back-to-front per texture)
		Map<String, List<Integer>> textureToIndices = new LinkedHashMap<>();
		for (int k = 0; k < instanceCount; k++) {
			int visibleIndex = particleSortOrder[k];
			String tex = textureForVisibleIndex[visibleIndex];
			if (tex == null) tex = "";
			textureToIndices.computeIfAbsent(tex, x -> new ArrayList<>()).add(visibleIndex);
		}

		List<ParticleTextureBatch> batches = new ArrayList<>();
		for (Map.Entry<String, List<Integer>> e : textureToIndices.entrySet()) {
			List<Integer> indices = e.getValue();
			String textureName = e.getKey();
			Integer tid = textureName.isEmpty() ? null : particleTextureLoader.getTextureId(textureName);
			int textureId = (tid != null && tid != 0) ? tid : 0;
			int[] arr = new int[indices.size()];
			for (int i = 0; i < indices.size(); i++) arr[i] = indices.get(i);
			batches.add(new ParticleTextureBatch(textureId, arr));
		}
		return batches;
	}

	private static String getTextureNameForParticle(ParticleBuffer buf, int bufIndex) {
		ParticleEmitter emitter = buf.emitter[bufIndex];
		if (emitter == null) return null;
		var def = emitter.getDefinition();
		if (def == null || def.texture == null || def.texture.isEmpty()) return null;
		return def.texture;
	}

	private void uploadBatchInstanceData(int[] visibleIndices, int count) {
		batchUploadBuffer.clear();
		for (int k = 0; k < count; k++) {
			int src = visibleIndices[k] * FLOATS_PER_INSTANCE;
			for (int f = 0; f < FLOATS_PER_INSTANCE; f++)
				batchUploadBuffer.put(particleStagingBuffer.get(src + f));
		}
		batchUploadBuffer.flip();
		glBindBuffer(GL_ARRAY_BUFFER, vboParticleInstances);
		glBufferSubData(GL_ARRAY_BUFFER, 0, batchUploadBuffer);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

}
