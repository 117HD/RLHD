package rs117.hd.scene.daylight_cycle;

import java.awt.Color;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.shader.NebulaBakeShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.SEED;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_NEBULA;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;
import static rs117.hd.utils.HDUtils.randomPointOnSphere;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public final class StarField {
	private static final float[] UP_VECTOR = { 0, 1, 0 };
	private static final float[] LEFT_VECTOR = { 1, 0, 0 };

	private static final Color[] STAR_COLORS = {
		new Color(1.0f, 0.7f, 0.45f),  // warm orange
		new Color(1.0f, 0.9f, 0.65f),  // golden yellow
		new Color(1.0f, 0.95f, 0.85f), // pale warm white
		new Color(1.0f, 1.0f, 1.0f),   // neutral white
		new Color(0.85f, 0.92f, 1.0f), // pale blue-white
		new Color(0.70f, 0.80f, 1.0f)  // cool blue
	};

	// VBO layout: direction.xyz, size, brightness, color.rgb, artistic rotation speed.
	private static final int FLOATS_PER_STAR = 9;

	// Match the old field's density independently of screen resolution.
	private static final int BRIGHT_STAR_COUNT = 350;   // layer 0: sparse/bright/large
	private static final int DIM_STAR_COUNT = 2200;     // layer 1: dense/dim/small
	private static final int CLUSTER_STAR_COUNT = 1600; // layer 2: clustered
	private static final int MAX_STAR_COUNT = BRIGHT_STAR_COUNT + DIM_STAR_COUNT + CLUSTER_STAR_COUNT;

	public static final int CLUSTER_COUNT = 12;
	private static final float CLUSTER_ANGULAR_SPREAD = 0.09f;

	private static final int NEBULA_CUBE_MAP_RESOLUTION = 512;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private NebulaBakeShaderProgram nebulaBakeProgram;

	private final Random random = new Random(SEED);
	private final RenderState nebulaBakeRenderState = new RenderState();

	private int texNebulaCubemap = 0;
	private int fboNebulaBake = 0;

	@Getter
	private int vaoStars = 0;

	public int starCount;

	private GLBuffer vboStars;

	private boolean starGeometryCurrent;
	private boolean nebulaMapCurrent;

	public void initialize() {
		resetStarfield();

		vaoStars = glGenVertexArrays();
		glBindVertexArray(vaoStars);

		vboStars = new GLBuffer("Stars::VBO", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
		vboStars.initialize(FLOATS_PER_STAR * MAX_STAR_COUNT);
		vboStars.bind();

		int stride = FLOATS_PER_STAR * Float.BYTES;
		// direction.xyz, size, brightness, color.rgb, artistic rotation speed
		glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(1, 1, GL_FLOAT, false, stride, 3L * Float.BYTES);
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 4L * Float.BYTES);
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(3, 3, GL_FLOAT, false, stride, 5L * Float.BYTES);
		glEnableVertexAttribArray(3);
		glVertexAttribPointer(4, 1, GL_FLOAT, false, stride, 8L * Float.BYTES);
		glEnableVertexAttribArray(4);

		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);

		fboNebulaBake = glGenFramebuffers();
		texNebulaCubemap = glGenTextures();

		glActiveTexture(TEXTURE_UNIT_NEBULA);
		glBindTexture(GL_TEXTURE_CUBE_MAP, texNebulaCubemap);

		for (int face = 0; face < 6; face++)
			glTexImage2D(
				GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GL_RGBA16F,
				NEBULA_CUBE_MAP_RESOLUTION,
				NEBULA_CUBE_MAP_RESOLUTION, 0, GL_RGBA, GL_FLOAT, 0
			);

		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

		glActiveTexture(TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
	}

	public void resetStarfield() {
		starGeometryCurrent = false;
		nebulaMapCurrent = false;
	}

	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		nebulaBakeProgram.compile(includes);
	}

	public void destroyShaders() {
		nebulaBakeProgram.destroy();
	}

	public boolean rebuildIfNeeded() {
		boolean rebuilt = false;
		if (!starGeometryCurrent) {
			random.setSeed(SEED);
			FloatBuffer vertexData = BufferUtils.createFloatBuffer(MAX_STAR_COUNT * FLOATS_PER_STAR);

			generateLayer(vertexData, BRIGHT_STAR_COUNT, 1.2f, 1.0f, 1);
			generateLayer(vertexData, DIM_STAR_COUNT, 0.4f, 0.8f, .7f);

			if (config.enableNebulas())
				generateClusteredLayer(vertexData, CLUSTER_STAR_COUNT, CLUSTER_COUNT, CLUSTER_ANGULAR_SPREAD, 0.5f, 0.5f, 1);

			starCount = vertexData.position() / FLOATS_PER_STAR;
			vboStars.upload(vertexData.flip());
			starGeometryCurrent = true;
			rebuilt = true;
		}

		if (!config.enableNebulas() || nebulaMapCurrent || fboNebulaBake == 0 || texNebulaCubemap == 0 || !nebulaBakeProgram.isValid())
			return rebuilt;

		nebulaBakeRenderState.framebuffer.set(GL_FRAMEBUFFER, fboNebulaBake);
		nebulaBakeRenderState.viewport.set(0, 0, NEBULA_CUBE_MAP_RESOLUTION, NEBULA_CUBE_MAP_RESOLUTION);
		nebulaBakeRenderState.vao.setVao(plugin.vaoTri);
		nebulaBakeRenderState.disable.set(GL_DEPTH_TEST);
		nebulaBakeRenderState.disable.set(GL_BLEND);
		nebulaBakeRenderState.disable.set(GL_CULL_FACE);
		nebulaBakeRenderState.apply();

		plugin.uboSky.upload();

		nebulaBakeProgram.use();
		for (int face = 0; face < 6; face++) {
			nebulaBakeProgram.uniCubeFace.set(face);

			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, texNebulaCubemap, 0);
			glDrawArrays(GL_TRIANGLES, 0, 3);
		}

		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		nebulaBakeRenderState.reset();
		nebulaMapCurrent = true;
		return true;
	}

	public void destroy() {
		if (vboStars != null)
			vboStars.destroy();
		vboStars = null;

		if (vaoStars != 0)
			glDeleteVertexArrays(vaoStars);
		vaoStars = 0;

		if (fboNebulaBake != 0)
			glDeleteFramebuffers(fboNebulaBake);
		fboNebulaBake = 0;

		if (texNebulaCubemap != 0)
			glDeleteTextures(texNebulaCubemap);
		texNebulaCubemap = 0;

		resetStarfield();
	}

	private void generateLayer(FloatBuffer vertexBuffer, int count, float maxBrightness, float sizeScale, float artisticRotationSpeed) {
		final float[] center = new float[3];
		for (int i = 0; i < count; i++) {
			randomPointOnSphere(center, random);
			writeStar(vertexBuffer, center[0], center[1], center[2], maxBrightness, sizeScale, artisticRotationSpeed, 1);
		}
	}

	private void generateClusteredLayer(
		FloatBuffer vertexBuffer, int count, int clusterCount,
		float angularSpread, float maxBrightness, float sizeScale, float artisticRotationSpeed
	) {
		final float[][] clusterCenters = new float[clusterCount][3];
		final float[][] clusterTangentU = new float[clusterCount][3];
		final float[][] clusterTangentV = new float[clusterCount][3];

		for (int c = 0; c < clusterCount; c++) {
			final float[] dir = clusterCenters[c];
			final float[] u = clusterTangentU[c];
			final float[] v = clusterTangentV[c];

			randomPointOnSphere(dir, random);

			cross(u, dir, (Math.abs(dir[1]) < 0.99f) ? UP_VECTOR : LEFT_VECTOR);
			normalize(u, u);
			cross(v, dir, u);

			plugin.uboSky.nebulaClusters[c].set(dir[0], dir[1], dir[2], CLUSTER_ANGULAR_SPREAD * 0.5f);
		}

		for (int i = 0; i < count; i++) {
			final int c = random.nextInt(clusterCount);

			final float[] dir = clusterCenters[c];
			final float[] u = clusterTangentU[c];
			final float[] v = clusterTangentV[c];

			float radius = (float) (Math.min(Math.abs(random.nextGaussian()), 3.0) * angularSpread);
			float theta = (float) (random.nextDouble() * 2.0 * Math.PI);
			float cosR = cos(radius);
			float sinR = sin(radius);
			float cosT = cos(theta);
			float sinT = sin(theta);

			float dx = cosR * dir[0] + sinR * (cosT * u[0] + sinT * v[0]);
			float dy = cosR * dir[1] + sinR * (cosT * u[1] + sinT * v[1]);
			float dz = cosR * dir[2] + sinR * (cosT * u[2] + sinT * v[2]);

			writeStar(vertexBuffer, dx, dy, dz, maxBrightness, sizeScale, artisticRotationSpeed, .5f);
		}
	}

	private void writeStar(
		FloatBuffer vertexBuffer,
		float dx,
		float dy,
		float dz,
		float maxBrightness,
		float sizeScale,
		float artisticRotationSpeed,
		float brightnessScale
	) {
		// Power-law brightness: many dim, few bright (matches pow(seed, 2.5)).
		float brightnessSeed = random.nextFloat();
		float brightness = min((float) Math.pow(brightnessSeed, 2.5) * maxBrightness, .4f) * brightnessScale;

		// Per-star size variation, skewed toward the small end (squaring the
		// random factor biases most stars small with only a few larger ones),
		// scaled per layer. Range ~0.4x to 1.0x.
		float sizeSeed = random.nextFloat();
		float size = (0.4f + sizeSeed * sizeSeed * 0.6f) * sizeScale;

		// Stellar color tint by population fraction (same bands as the shader).
		final Color starColor = STAR_COLORS[random.nextInt(STAR_COLORS.length)];

		vertexBuffer
			.put(dx)
			.put(dy)
			.put(dz)
			.put(size)
			.put(brightness)
			.put(starColor.getRed() / 255.0f)
			.put(starColor.getGreen() / 255.0f)
			.put(starColor.getBlue() / 255.0f)
			.put(artisticRotationSpeed);
	}
}
