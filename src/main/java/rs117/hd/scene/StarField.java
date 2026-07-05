package rs117.hd.scene;

import java.nio.FloatBuffer;
import java.util.Random;
import lombok.Getter;
import org.lwjgl.BufferUtils;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;

/**
 * Generates a fixed list of stars once at startup so they can be drawn as point
 * sprites (cost scales with star count) rather than searched for per sky pixel
 * (cost scales with screen pixels). Mirrors the two-layer look the procedural
 * starfield used: a sparse layer of bright, larger stars over a dense layer of
 * dim, small ones, with a power-law brightness distribution and stellar tints.
 */
public class StarField {
	// Per-vertex layout written to the VBO, in floats:
	//   position.xyz (unit direction), size, brightness, color.rgb, speed => 9 floats
	// speed scales the celestial rotation so the layers parallax (depth effect).
	public static final int FLOATS_PER_STAR = 9;

	// Star counts per layer. The procedural field had ~18-24% of cells populated
	// across grids of scale 80 and 200; these counts reproduce a similar on-sky
	// density without being tied to screen resolution.
	private static final int BRIGHT_STAR_COUNT = 350;  // layer 0: sparse/bright/large
	private static final int DIM_STAR_COUNT = 2200;    // layer 1: dense/dim/small

	private static final long SEED = 0x117D511A5L;

	public final int starCount = BRIGHT_STAR_COUNT + DIM_STAR_COUNT;

	@Getter
	private int vaoStars = 0;

	@Getter
	private int vboStars = 0;

	public void initialize() {
		FloatBuffer vertexData = BufferUtils.createFloatBuffer(starCount * FLOATS_PER_STAR);
		var rng = new Random(SEED);

		// Layer 0: bright, sparse, larger, full rotation speed (the "near" layer).
		// Layer 1: dim, dense, smaller, rotating ~30% slower for a parallax depth feel.
		generateLayer(rng, vertexData, BRIGHT_STAR_COUNT, 1.2f, 1.0f, 1.0f);
		generateLayer(rng, vertexData, DIM_STAR_COUNT, 0.4f, 0.8f, 0.7f);

		vaoStars = glGenVertexArrays();
		vboStars = glGenBuffers();
		glBindVertexArray(vaoStars);
		glBindBuffer(GL_ARRAY_BUFFER, vboStars);
		glBufferData(GL_ARRAY_BUFFER, vertexData.flip(), GL_STATIC_DRAW);

		int stride = StarField.FLOATS_PER_STAR * Float.BYTES;
		// location 0: dir.xyz, 1: size, 2: brightness, 3: color.rgb, 4: speed
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
	}

	public void destroy() {
		if (vboStars != 0)
			glDeleteBuffers(vboStars);
		vboStars = 0;

		if (vaoStars != 0)
			glDeleteVertexArrays(vaoStars);
		vaoStars = 0;
	}

	private static void generateLayer(Random rng, FloatBuffer buffer, int count, float maxBrightness, float sizeScale, float speed) {
		for (int i = 0; i < count; i++) {
			// Uniform point on the unit sphere.
			double z = rng.nextDouble() * 2.0 - 1.0;
			double t = rng.nextDouble() * 2.0 * Math.PI;
			double r = Math.sqrt(Math.max(0.0, 1.0 - z * z));
			float dx = (float) (r * Math.cos(t));
			float dy = (float) z;
			float dz = (float) (r * Math.sin(t));

			// Power-law brightness: many dim, few bright (matches pow(seed, 2.5)).
			float brightnessSeed = rng.nextFloat();
			float brightness = (float) Math.pow(brightnessSeed, 2.5) * maxBrightness;

			// Per-star size variation, skewed toward the small end (squaring the
			// random factor biases most stars small with only a few larger ones),
			// scaled per layer. Range ~0.4x to 1.0x.
			float sizeSeed = rng.nextFloat();
			float size = (0.4f + sizeSeed * sizeSeed * 0.6f) * sizeScale;

			// Stellar color tint by population fraction (same bands as the shader).
			float c = rng.nextFloat();
			float tr, tg, tb;
			if (c < 0.06f)      { tr = 1.0f; tg = 0.70f; tb = 0.45f; } // warm orange
			else if (c < 0.18f) { tr = 1.0f; tg = 0.90f; tb = 0.65f; } // golden yellow
			else if (c < 0.30f) { tr = 1.0f; tg = 0.95f; tb = 0.85f; } // pale warm white
			else if (c < 0.70f) { tr = 1.0f; tg = 1.00f; tb = 1.00f; } // neutral white
			else if (c < 0.85f) { tr = 0.85f; tg = 0.92f; tb = 1.0f; } // pale blue-white
			else                { tr = 0.70f; tg = 0.80f; tb = 1.0f; } // cool blue

			buffer.put(dx);
			buffer.put(dy);
			buffer.put(dz);
			buffer.put(size);
			buffer.put(brightness);
			buffer.put(tr);
			buffer.put(tg);
			buffer.put(tb);
			buffer.put(speed);
		}
	}
}
