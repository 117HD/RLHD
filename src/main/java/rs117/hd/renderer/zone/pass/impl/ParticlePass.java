/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.renderer.zone.pass.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;
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
import rs117.hd.scene.particles.Particle;
import rs117.hd.scene.particles.ParticleManager;
import rs117.hd.scene.particles.ParticleTextureLoader;
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
		int particleInstanceCount = uploadParticles(currentPlane);
		if (particleInstanceCount > 0) {
			var renderState = ctx.getRenderState();
			renderState.program.set(particleProgram);
			renderState.disable.set(GL_CULL_FACE);
			renderState.depthMask.set(false);
			renderState.apply();
			Integer textureId = particleTextureLoader.getTextureId(particleManager.getTexturePath());
			int idToBind = (textureId != null && textureId != 0) ? textureId : whiteParticleTextureId;
			glActiveTexture(TEXTURE_UNIT_PARTICLE);
			glBindTexture(GL_TEXTURE_2D, idToBind);
			particleProgram.setParticleTextureUnit(TEXTURE_UNIT_PARTICLE);
			glBindVertexArray(vaoParticles);
			ctx.beginTimer(Timer.RENDER_PARTICLES);
			glDrawArraysInstanced(GL_TRIANGLES, 0, QUAD_VERTS, particleInstanceCount);
			ctx.endTimer(Timer.RENDER_PARTICLES);
			glBindVertexArray(0);
		}
	}

	@Override
	public void afterDraw(ScenePassContext ctx) {
		ctx.getRenderState().depthMask.set(true);
	}

	private int uploadParticles(int currentPlane) {
		List<Particle> particles = particleManager.getSceneParticles();
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
		for (Particle p : particles) {
			if (p.plane != currentPlane)
				continue;
			lastParticleTotalOnPlane++;
			float dx = p.position[0] - cxCam;
			float dy = p.position[1] - cyCam;
			float dz = p.position[2] - czCam;
			float dSq = dx * dx + dy * dy + dz * dz;
			if (dSq > maxDistSq) {
				lastParticleCulledDistance++;
				continue;
			}
			if (!HDUtils.isSphereIntersectingFrustum(p.position[0], p.position[1], p.position[2], p.size, frustumPlanes, frustumPlanes.length)) {
				lastParticleCulledFrustum++;
				continue;
			}
			particleDistSq[n] = dSq;
			p.getCurrentColor(particleColor);
			float cx = p.position[0] + plugin.cameraShift[0];
			float cy = p.position[1];
			float cz = p.position[2] + plugin.cameraShift[1];
			particleStagingBuffer.put(cx).put(cy).put(cz);
			particleStagingBuffer.put(particleColor[0]).put(particleColor[1]).put(particleColor[2]).put(particleColor[3]);
			particleStagingBuffer.put(p.size);
			n++;
		}
		int instanceCount = n;
		lastParticleDrawn = instanceCount;
		if (instanceCount == 0)
			return 0;
		for (int i = 0; i < instanceCount; i++)
			particleSortOrder[i] = i;
		Arrays.sort(particleSortOrder, 0, instanceCount, (a, b) -> Float.compare(particleDistSq[b], particleDistSq[a]));

		int uploadBytes = instanceCount * FLOATS_PER_INSTANCE * 4;
		glBindBuffer(GL_ARRAY_BUFFER, vboParticleInstances);
		ByteBuffer mapped = glMapBufferRange(GL_ARRAY_BUFFER, 0, uploadBytes, GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_RANGE_BIT);
		if (mapped != null) {
			FloatBuffer mappedFloat = mapped.asFloatBuffer();
			for (int k = 0; k < instanceCount; k++) {
				int src = particleSortOrder[k] * FLOATS_PER_INSTANCE;
				for (int f = 0; f < FLOATS_PER_INSTANCE; f++)
					mappedFloat.put(particleStagingBuffer.get(src + f));
			}
			glUnmapBuffer(GL_ARRAY_BUFFER);
		} else {
			particleStagingBuffer.flip();
			glBufferSubData(GL_ARRAY_BUFFER, 0, particleStagingBuffer);
		}
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		return instanceCount;
	}

}
