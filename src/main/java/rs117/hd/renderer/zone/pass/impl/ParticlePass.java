/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.renderer.zone.pass.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
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
import rs117.hd.utils.buffer.GLBuffer;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static org.lwjgl.opengl.GL15.glUnmapBuffer;
import static org.lwjgl.opengl.GL30.GL_MAP_INVALIDATE_RANGE_BIT;
import static org.lwjgl.opengl.GL30.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
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
	private static final int MAX_DRAWN = 2048;
	private static final int QUAD_VERTS = 6;
	private static final int FLOATS_PER_INSTANCE = 9;
	private static final int INSTANCE_STRIDE_BYTES = 64;
	private static final int INSTANCE_PADDING_BYTES = INSTANCE_STRIDE_BYTES - FLOATS_PER_INSTANCE * 4; // 28
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
	private GLBuffer particleInstanceBuffer;
	private FloatBuffer particleStagingBuffer;
	private final float[] particleDistSq = new float[MAX_PARTICLES];
	private final Integer[] particleSortOrder = new Integer[MAX_PARTICLES];

	private final String[] textureForVisibleIndex = new String[MAX_PARTICLES];
	private ByteBuffer batchUploadBuffer;

	@Getter
	private int lastParticleTotalOnPlane;
	@Getter
	private int lastParticleCulledDistance;
	@Getter
	private int lastParticleCulledFrustum;
	@Getter
	private int lastParticleDrawn;
	private int lastUploadedInstanceCount;

	@Override
	public String passName() {
		return "Particles";
	}

	public void initialize() {
		vaoParticles = glGenVertexArrays();
		vboParticleQuad = glGenBuffers();
		long instanceVboBytes = (long) MAX_DRAWN * INSTANCE_STRIDE_BYTES;
		if (GLBuffer.supportsStorageBuffers()) {
			particleInstanceBuffer = new GLBuffer("particle instances", GL_ARRAY_BUFFER, GL_STREAM_DRAW, GLBuffer.STORAGE_PERSISTENT | GLBuffer.STORAGE_WRITE);
			particleInstanceBuffer.initialize(instanceVboBytes);
			vboParticleInstances = particleInstanceBuffer.id;
		} else {
			vboParticleInstances = glGenBuffers();
			glBindBuffer(GL_ARRAY_BUFFER, vboParticleInstances);
			glBufferData(GL_ARRAY_BUFFER, instanceVboBytes, GL_STREAM_DRAW);
			glBindBuffer(GL_ARRAY_BUFFER, 0);
		}
		FloatBuffer quadBuffer = BufferUtils.createFloatBuffer(PARTICLE_QUAD_CORNERS.length).put(PARTICLE_QUAD_CORNERS).flip();
		glBindBuffer(GL_ARRAY_BUFFER, vboParticleQuad);
		glBufferData(GL_ARRAY_BUFFER, quadBuffer, GL_STATIC_DRAW);
		glBindVertexArray(vaoParticles);
		glBindBuffer(GL_ARRAY_BUFFER, vboParticleQuad);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0);
		glVertexAttribDivisor(0, 0);
		glBindBuffer(GL_ARRAY_BUFFER, vboParticleInstances);
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 3, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 0);
		glVertexAttribDivisor(1, 1);
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 4, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 12);
		glVertexAttribDivisor(2, 1);
		glEnableVertexAttribArray(3);
		glVertexAttribPointer(3, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 28);
		glVertexAttribDivisor(3, 1);
		glEnableVertexAttribArray(4);
		glVertexAttribPointer(4, 1, GL_FLOAT, false, INSTANCE_STRIDE_BYTES, 32);
		glVertexAttribDivisor(4, 1);
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		particleStagingBuffer = BufferUtils.createFloatBuffer(MAX_PARTICLES * FLOATS_PER_INSTANCE);
		batchUploadBuffer = BufferUtils.createByteBuffer(MAX_DRAWN * INSTANCE_STRIDE_BYTES);
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
		batchUploadBuffer = null;
		particleTextureLoader.dispose();
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
		int instanceCount = prepareBatches(currentPlane);
		if (instanceCount == 0)
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
		glBindTexture(GL_TEXTURE_2D_ARRAY, particleTextureLoader.getTextureArrayId());
		particleProgram.setParticleTextureUnit(TEXTURE_UNIT_PARTICLE);
		glBindVertexArray(vaoParticles);
		ctx.beginTimer(Timer.RENDER_PARTICLES);
		uploadInstanceDataToVbo(instanceCount);
		glDrawArraysInstanced(GL_TRIANGLES, 0, QUAD_VERTS, instanceCount);
		ctx.endTimer(Timer.RENDER_PARTICLES);
		glBindVertexArray(0);
	}

	@Override
	public void afterDraw(ScenePassContext ctx) {
		ctx.getRenderState().depthMask.set(true);
	}

	private int prepareBatches(int currentPlane) {
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
			String tex = textureForVisibleIndex[n];
			particleStagingBuffer.put((float) particleTextureLoader.getTextureLayer(tex != null ? tex : ""));
			n++;
			if (n >= MAX_DRAWN)
				break;
		}
		int instanceCount = n;
		lastParticleDrawn = instanceCount;
		lastUploadedInstanceCount = instanceCount;
		if (instanceCount == 0)
			return 0;

		for (int i = 0; i < instanceCount; i++)
			particleSortOrder[i] = i;
		Arrays.sort(particleSortOrder, 0, instanceCount, (a, b) -> Float.compare(particleDistSq[b], particleDistSq[a]));

		// Fill upload buffer in back-to-front order, 64 bytes per instance (9 floats + 28 padding)
		batchUploadBuffer.clear();
		for (int k = 0; k < instanceCount; k++) {
			int src = particleSortOrder[k] * FLOATS_PER_INSTANCE;
			for (int f = 0; f < FLOATS_PER_INSTANCE; f++)
				batchUploadBuffer.putFloat(particleStagingBuffer.get(src + f));
			for (int p = 0; p < INSTANCE_PADDING_BYTES; p++)
				batchUploadBuffer.put((byte) 0);
		}
		return instanceCount;
	}

	private static String getTextureNameForParticle(ParticleBuffer buf, int bufIndex) {
		ParticleEmitter emitter = buf.emitter[bufIndex];
		if (emitter == null) return null;
		var def = emitter.getDefinition();
		if (def == null || def.texture == null || def.texture.isEmpty()) return null;
		return def.texture;
	}

	private void uploadInstanceDataToVbo(int instanceCount) {
		int bytes = instanceCount * INSTANCE_STRIDE_BYTES;
		batchUploadBuffer.flip();
		if (particleInstanceBuffer != null && particleInstanceBuffer.isMapped()) {
			particleInstanceBuffer.upload(batchUploadBuffer);
		} else {
			glBindBuffer(GL_ARRAY_BUFFER, vboParticleInstances);
			ByteBuffer mapped = glMapBufferRange(GL_ARRAY_BUFFER, 0, bytes, GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_RANGE_BIT);
			if (mapped != null) {
				mapped.put(batchUploadBuffer);
				glUnmapBuffer(GL_ARRAY_BUFFER);
			} else {
				glBufferSubData(GL_ARRAY_BUFFER, 0, batchUploadBuffer);
			}
			glBindBuffer(GL_ARRAY_BUFFER, 0);
		}
	}
}
