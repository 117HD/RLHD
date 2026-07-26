package rs117.hd.scene;

import java.awt.Color;
import java.nio.FloatBuffer;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE_CUBE_MAP;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE_CUBE_MAP_POSITIVE_X;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_RGBA16F;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30C.glGenFramebuffers;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_NEBULA;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;
import static rs117.hd.utils.HDUtils.randomPointOnSphere;
import static rs117.hd.utils.MathUtils.*;

/**
 * Generates a fixed list of stars once at startup so they can be drawn as point
 * sprites (cost scales with star count) rather than searched for per sky pixel
 * (cost scales with screen pixels). Mirrors the two-layer look the procedural
 * starfield used: a sparse layer of bright, larger stars over a dense layer of
 * dim, small ones, with a power-law brightness distribution and stellar tints.
 * A third layer adds loose clusters of stars for a more fantastical sky.
 */
@Slf4j
@Singleton
public final class StarField {
	private static final float[] UP_VECTOR = {0, 1, 0};
	private static final float[] LEFT_VECTOR = {1, 0, 0};

	private static final Color[] STAR_COLORS = {
		new Color(1.0f, 0.7f, 0.45f),  // warm orange
		new Color(1.0f, 0.9f, 0.65f),  // golden yellow
		new Color(1.0f, 0.95f, 0.85f), // pale warm white
		new Color(1.0f, 1.0f, 1.0f),   // neutral white
		new Color(0.85f, 0.92f, 1.0f), // pale blue-white
		new Color(0.70f, 0.80f, 1.0f)  // cool blue
	};

	// Standard OpenGL cubemap face orientation: {forward, right, up} per face,
	// where dir = normalize(forward + u*right + v*up) for u,v in [-1, 1].
	private static final float[][][] NEBULA_CUBE_FACES = {
		{ { 1, 0, 0 }, { 0, 0, -1 }, { 0, -1, 0 } }, // +X
		{ { -1, 0, 0 }, { 0, 0, 1 }, { 0, -1, 0 } }, // -X
		{ { 0, 1, 0 }, { 1, 0, 0 }, { 0, 0, 1 } },   // +Y
		{ { 0, -1, 0 }, { 1, 0, 0 }, { 0, 0, -1 } }, // -Y
		{ { 0, 0, 1 }, { 1, 0, 0 }, { 0, -1, 0 } },  // +Z
		{ { 0, 0, -1 }, { -1, 0, 0 }, { 0, -1, 0 } } // -Z
	};

	// Per-vertex layout written to the VBO, in floats:
	//   position.xyz (unit direction), size, brightness, color.rgb, speed => 9 floats
	// speed scales the celestial rotation so the layers parallax (depth effect).
	private static final int FLOATS_PER_STAR = 10;

	// Star counts per layer. The procedural field had ~18-24% of cells populated
	// across grids of scale 80 and 200; these counts reproduce a similar on-sky
	// density without being tied to screen resolution.
	private static final int BRIGHT_STAR_COUNT = 350;   // layer 0: sparse/bright/large
	private static final int DIM_STAR_COUNT = 2200;     // layer 1: dense/dim/small
	private static final int CLUSTER_STAR_COUNT = 1600; // layer 2: clustered
	private static final int MAX_STAR_COUNT = BRIGHT_STAR_COUNT + DIM_STAR_COUNT + CLUSTER_STAR_COUNT;

	public static final int CLUSTER_COUNT = 12;
	private static final float CLUSTER_ANGULAR_SPREAD = 0.09f;

	private static final int NEBULA_CUBE_MAP_RESOLUTION = 512;

	private static final long SEED = 0x117D511A5L;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private ZoneRenderer zoneRenderer;

	private final Random random = new Random(SEED);

	private int texNebulaCubemap = 0;
	private int fboNebulaBake = 0;

	@Getter
	private int vaoStars = 0;

	@Getter
	public int starCount;

	private GLBuffer vboStars;

	private boolean starfieldGenerated = false;

	public void initialize() {
		starfieldGenerated = false;

		vaoStars = glGenVertexArrays();
		glBindVertexArray(vaoStars);

		vboStars = new GLBuffer("Stars::VBO", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
		vboStars.initialize(FLOATS_PER_STAR * MAX_STAR_COUNT);
		vboStars.bind();

		int stride = FLOATS_PER_STAR * Float.BYTES;
		// location 0: dir.xyz, 1: size, 2: brightness, 3: color.rgba, 4: speed
		glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(1, 1, GL_FLOAT, false, stride, 3L * Float.BYTES);
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 4L * Float.BYTES);
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(3, 4, GL_FLOAT, false, stride, 5L * Float.BYTES);
		glEnableVertexAttribArray(3);
		glVertexAttribPointer(4, 1, GL_FLOAT, false, stride, 9L * Float.BYTES);
		glEnableVertexAttribArray(4);

		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);

		fboNebulaBake = glGenFramebuffers();
		texNebulaCubemap = glGenTextures();

		glActiveTexture(TEXTURE_UNIT_NEBULA);
		glBindTexture(GL_TEXTURE_CUBE_MAP, texNebulaCubemap);

		for (int face = 0; face < 6; face++)
			glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GL_RGBA16F,
				NEBULA_CUBE_MAP_RESOLUTION,
				NEBULA_CUBE_MAP_RESOLUTION, 0, GL_RGBA, GL_FLOAT, 0);

		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

		glActiveTexture(TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
	}

	public void resetStarfield() { starfieldGenerated = false; }

	public boolean generateStarField() {
		if(starfieldGenerated)
			return false;

		starfieldGenerated = true;
		random.setSeed(SEED);

		FloatBuffer vertexData = BufferUtils.createFloatBuffer(MAX_STAR_COUNT * FLOATS_PER_STAR);

		// Layer 0: bright, sparse, larger, full rotation speed (the "near" layer).
		// Layer 1: dim, dense, smaller, rotating ~30% slower for a parallax depth feel.
		// Layer 2: clustered stars, moderate brightness, same speed as the bright
		// layer so clusters don't visibly drift apart from the rest of the sky.
		generateLayer(vertexData, BRIGHT_STAR_COUNT, 1.2f, 1.0f, 1.0f);
		generateLayer(vertexData, DIM_STAR_COUNT, 0.4f, 0.8f, 0.7f);

		if(config.enableNebulas())
			generateClusteredLayer(vertexData, CLUSTER_STAR_COUNT, CLUSTER_COUNT, CLUSTER_ANGULAR_SPREAD, 0.5f, 0.5f, 1.0f);

		starCount = vertexData.position() / FLOATS_PER_STAR;
		vboStars.upload(vertexData.flip());

		var bakeShader = zoneRenderer.nebulaBakeProgram;
		if (fboNebulaBake == 0 || texNebulaCubemap == 0 || !bakeShader.isValid() || !config.enableNebulas())
			return true;

		final RenderState renderState = zoneRenderer.renderState;
		renderState.framebuffer.set(GL_FRAMEBUFFER, fboNebulaBake);
		renderState.viewport.set(0, 0, NEBULA_CUBE_MAP_RESOLUTION, NEBULA_CUBE_MAP_RESOLUTION);
		renderState.vao.setVao(plugin.vaoTri);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.disable.set(GL_BLEND);
		renderState.disable.set(GL_CULL_FACE);
		renderState.apply();

		plugin.uboSkybox.upload();

		zoneRenderer.nebulaBakeProgram.use();
		for (int face = 0; face < 6; face++) {
			bakeShader.uniFaceForward.set(NEBULA_CUBE_FACES[face][0]);
			bakeShader.uniFaceRight.set(NEBULA_CUBE_FACES[face][1]);
			bakeShader.uniFaceUp.set(NEBULA_CUBE_FACES[face][2]);

			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, texNebulaCubemap, 0);
			glDrawArrays(GL_TRIANGLES, 0, 3);
		}

		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		zoneRenderer.renderState.reset();
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

		starfieldGenerated = false;
	}

	private void generateLayer(FloatBuffer vertexBuffer, int count, float maxBrightness, float sizeScale, float speed) {
		final float[] center = new float[3];
		for (int i = 0; i < count; i++) {
			randomPointOnSphere(random, center);
			writeStar(vertexBuffer, center[0], center[1], center[2], maxBrightness, sizeScale, speed, 1.0f);
		}
	}

	private void generateClusteredLayer(
		FloatBuffer vertexBuffer, int count, int clusterCount,
		float angularSpread, float maxBrightness, float sizeScale, float speed
	) {
		final float[][] clusterCenters = new float[clusterCount][3];
		final float[][] clusterTangentU = new float[clusterCount][3];
		final float[][] clusterTangentV = new float[clusterCount][3];

		for (int c = 0; c < clusterCount; c++) {
			final float[] dir = clusterCenters[c];
			final float[] u = clusterTangentU[c];
			final float[] v = clusterTangentV[c];

			randomPointOnSphere(random, dir);

			cross(u, dir, (Math.abs(dir[1]) < 0.99f) ? UP_VECTOR : LEFT_VECTOR);
			normalize(u, u);
			cross(v, dir, u);

			plugin.uboSkybox.nebulaClusters[c].set(dir[0], dir[1], dir[2], CLUSTER_ANGULAR_SPREAD * 0.5f);
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

			writeStar(vertexBuffer, dx, dy, dz, maxBrightness, sizeScale, speed, 0.5f);
		}
	}

	private void writeStar(FloatBuffer vertexBuffer, float dx, float dy, float dz, float maxBrightness, float sizeScale, float speed, float alpha) {
		// Power-law brightness: many dim, few bright (matches pow(seed, 2.5)).
		float brightnessSeed = random.nextFloat();
		float brightness = (float) Math.pow(brightnessSeed, 2.5) * maxBrightness;

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
			.put(alpha)
			.put(speed);
	}
}