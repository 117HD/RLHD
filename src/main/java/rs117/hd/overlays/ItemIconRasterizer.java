package rs117.hd.overlays;

import java.util.Arrays;
import java.util.HashMap;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Model;
import net.runelite.api.TextureProvider;
import rs117.hd.utils.ColorUtils;

import static rs117.hd.utils.MathUtils.*;

public class ItemIconRasterizer {
	public static final int ICON_WIDTH = 36;
	public static final int ICON_HEIGHT = 32;

	// The game's item icon projection
	private static final int FOCAL_LENGTH = 512;
	private static final int CENTER = 16;
	private static final int SAMPLES = 4;
	private static final float MAX_MISMATCHED_AREA = .1f;
	private static final int MAX_MISMATCHED_PIXELS = 3;
	private static final int MAX_COLOR_ERROR = 32;

	@RequiredArgsConstructor
	public static class Mesh {
		final float[] x, y, z;
		final int[] a, b, c;
		final int[] color1, color2, color3;
		@Nullable
		final byte[] transparency;
		@Nullable
		final byte[] priority;
		@Nullable
		final int[][] texture;
		@Nullable
		final int[] textureA, textureB, textureC;

		@Nullable
		public static Mesh copyOf(Model model, TextureProvider textureProvider) {
			int vertices = model.getVerticesCount();
			int faces = model.getFaceCount();

			int[][] texture = null;
			int[] textureA = null, textureB = null, textureC = null;
			short[] faceTextures = model.getFaceTextures();
			if (faceTextures != null) {
				texture = new int[faces][];
				textureA = Arrays.copyOf(model.getFaceIndices1(), faces);
				textureB = Arrays.copyOf(model.getFaceIndices2(), faces);
				textureC = Arrays.copyOf(model.getFaceIndices3(), faces);
				byte[] textureFaces = model.getTextureFaces();
				var pixels = new HashMap<Integer, int[]>();
				for (int f = 0; f < faces; f++) {
					if (faceTextures[f] == -1)
						continue;
					texture[f] = pixels.computeIfAbsent((int) faceTextures[f], id -> {
						int[] loaded = textureProvider.load(id);
						return loaded == null ? null : loaded.clone();
					});
					if (texture[f] == null)
						return null;
					if (textureFaces != null && textureFaces[f] != -1) {
						int t = textureFaces[f] & 0xFF;
						textureA[f] = model.getTexIndices1()[t];
						textureB[f] = model.getTexIndices2()[t];
						textureC[f] = model.getTexIndices3()[t];
					}
				}
			}

			return new Mesh(
				Arrays.copyOf(model.getVerticesX(), vertices),
				Arrays.copyOf(model.getVerticesY(), vertices),
				Arrays.copyOf(model.getVerticesZ(), vertices),
				Arrays.copyOf(model.getFaceIndices1(), faces),
				Arrays.copyOf(model.getFaceIndices2(), faces),
				Arrays.copyOf(model.getFaceIndices3(), faces),
				Arrays.copyOf(model.getFaceColors1(), faces),
				Arrays.copyOf(model.getFaceColors2(), faces),
				Arrays.copyOf(model.getFaceColors3(), faces),
				model.getFaceTransparencies() == null ? null : Arrays.copyOf(model.getFaceTransparencies(), faces),
				model.getFaceRenderPriorities() == null ? null : Arrays.copyOf(model.getFaceRenderPriorities(), faces),
				texture, textureA, textureB, textureC
			);
		}
	}

	private final Mesh mesh;
	private final float[] x, y, z;
	private float offsetX, offsetY, distance;
	@Nullable
	private int[] gameIcon;

	public ItemIconRasterizer(Mesh mesh, int pitch, int yaw, int roll) {
		this.mesh = mesh;
		int count = mesh.x.length;
		x = new float[count];
		y = new float[count];
		z = new float[count];

		float sinPitch = sin(pitch * JAU_TO_RAD);
		float cosPitch = cos(pitch * JAU_TO_RAD);
		float sinYaw = sin(yaw * JAU_TO_RAD);
		float cosYaw = cos(yaw * JAU_TO_RAD);
		float sinRoll = sin(roll * JAU_TO_RAD);
		float cosRoll = cos(roll * JAU_TO_RAD);
		for (int i = 0; i < count; i++) {
			float vx = mesh.x[i];
			float vy = mesh.y[i];
			float vz = mesh.z[i];
			float t = vy * sinRoll + vx * cosRoll;
			vy = vy * cosRoll - vx * sinRoll;
			vx = t;
			t = vz * sinYaw + vx * cosYaw;
			vz = vz * cosYaw - vx * sinYaw;
			vx = t;
			t = vy * cosPitch - vz * sinPitch;
			vz = vy * sinPitch + vz * cosPitch;
			vy = t;
			x[i] = vx;
			y[i] = vy;
			z[i] = vz;
		}
	}

	// RuneLite doesn't expose the icon's zoom and offsets, so the model is fitted to the game's icon
	public boolean lineUpWith(int[] gameIcon, int[] palette) {
		return lineUpWith(gameIcon, palette, true);
	}

	// Images with colors of their own, like those of RuneLite's Rune Pouch plugin, only have to match in shape
	public boolean lineUpWith(int[] gameIcon, int[] palette, boolean matchColors) {
		this.gameIcon = gameIcon;
		float[] coverage = new float[gameIcon.length];
		for (int i = 0; i < gameIcon.length; i++)
			coverage[i] = gameIcon[i] == 0 ? 0 : 1;
		float[] shape = measureShape(coverage, ICON_WIDTH, 0, 0);
		float area = shape[0], centerX = shape[1], centerY = shape[2], spread = shape[3];
		if (area == 0)
			return false;

		float radius = 0;
		float[] min = { Float.MAX_VALUE, Float.MAX_VALUE };
		float[] max = { -Float.MAX_VALUE, -Float.MAX_VALUE };
		for (int i = 0; i < x.length; i++) {
			radius = max(radius, sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i]));
			min[0] = min(min[0], x[i]);
			min[1] = min(min[1], y[i]);
			max[0] = max(max[0], x[i]);
			max[1] = max(max[1], y[i]);
		}

		// Fitted by spread rather than area, since the game draws thin parts thicker
		distance = radius + FOCAL_LENGTH * radius / sqrt(area / PI);
		offsetX = -(min[0] + max[0]) / 2;
		offsetY = -(min[1] + max[1]) / 2;
		for (int i = 0; i < 10; i++) {
			int[] pixels = drawPixels(distance, 1, 1, -ICON_WIDTH, -ICON_HEIGHT, ICON_WIDTH * 3, ICON_HEIGHT * 3, 0, palette);
			float[] ourCoverage = new float[pixels.length];
			for (int j = 0; j < pixels.length; j++)
				ourCoverage[j] = (pixels[j] >>> 24) / 255f;
			float[] ourShape = measureShape(ourCoverage, ICON_WIDTH * 3, -ICON_WIDTH, -ICON_HEIGHT);
			if (ourShape[0] == 0)
				return false;
			float ourCenterX = ourShape[1];
			float ourCenterY = ourShape[2];

			float scale = ourShape[3] / spread;
			distance *= scale;
			offsetX += (centerX - ourCenterX) * distance / FOCAL_LENGTH;
			offsetY += (centerY - ourCenterY) * distance / FOCAL_LENGTH;
			if (distance <= radius)
				return false;
			if (abs(scale - 1) < .001f && abs(centerX - ourCenterX) < .01f && abs(centerY - ourCenterY) < .01f)
				break;
		}

		int[] pixels = drawPixels(distance, 1, 1, 0, 0, ICON_WIDTH, ICON_HEIGHT, 0, palette);
		boolean[] ours = new boolean[pixels.length];
		boolean[] oursAtAll = new boolean[pixels.length];
		boolean[] theirs = new boolean[pixels.length];
		for (int i = 0; i < pixels.length; i++) {
			ours[i] = pixels[i] >>> 24 >= 128;
			oursAtAll[i] = pixels[i] >>> 24 > 0;
			theirs[i] = gameIcon[i] != 0;
		}
		int mismatches = 0;
		float ourArea = 0;
		float[] ourColor = new float[3];
		float[] theirColor = new float[3];
		for (int i = 0; i < pixels.length; i++) {
			if (theirs[i] && !isNear(oursAtAll, i) || ours[i] && !isNear(theirs, i))
				mismatches++;
			ourArea += (pixels[i] >>> 24) / 255f;
			for (int c = 0; c < 3; c++) {
				ourColor[c] += pixels[i] >> c * 8 & 0xFF;
				if (theirs[i])
					theirColor[c] += gameIcon[i] >> c * 8 & 0xFF;
			}
		}
		if (mismatches > max(MAX_MISMATCHED_PIXELS, MAX_MISMATCHED_AREA * area))
			return false;
		for (int c = 0; c < 3 && matchColors; c++)
			if (abs(ourColor[c] / ourArea - theirColor[c] / area) > MAX_COLOR_ERROR)
				return false;
		return true;
	}

	private static float[] measureShape(float[] coverage, int width, int left, int top) {
		float area = 0, sumX = 0, sumY = 0, sumSquares = 0;
		for (int i = 0; i < coverage.length; i++) {
			float x = i % width + left + .5f;
			float y = i / width + top + .5f;
			area += coverage[i];
			sumX += coverage[i] * x;
			sumY += coverage[i] * y;
			sumSquares += coverage[i] * (x * x + y * y);
		}
		if (area == 0)
			return new float[4];
		float centerX = sumX / area;
		float centerY = sumY / area;
		return new float[] { area, centerX, centerY, sqrt(max(0, sumSquares / area - centerX * centerX - centerY * centerY)) };
	}

	public int[] draw(float scaleX, float scaleY, int margin, int border, int[] palette) {
		int width = round((ICON_WIDTH + 2 * margin) * scaleX);
		int height = round((ICON_HEIGHT + 2 * margin) * scaleY);
		return drawPixels(distance, scaleX, scaleY, -margin, -margin, width, height, border, palette);
	}

	public static float[] surroundings(int[] pixels, int width, int height, float scaleX, float scaleY) {
		float[] near = new float[pixels.length];
		int reachX = ceil(scaleX) + 1;
		int reachY = ceil(scaleY) + 1;
		float sharpness = min(scaleX, scaleY);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				float coverage = (pixels[y * width + x] >>> 24) / 255f;
				if (coverage == 0)
					continue;
				for (int ny = max(0, y - reachY); ny <= min(height - 1, y + reachY); ny++) {
					for (int nx = max(0, x - reachX); nx <= min(width - 1, x + reachX); nx++) {
						float distance = sqrt(square((nx - x) / scaleX) + square((ny - y) / scaleY));
						float spread = coverage * clamp((1 - distance) * sharpness + .5f, 0, 1);
						int i = ny * width + nx;
						near[i] = max(near[i], spread);
					}
				}
			}
		}
		return near;
	}

	public static int[] drawOver(int[] top, int[] bottom) {
		int[] pixels = new int[top.length];
		for (int i = 0; i < top.length; i++) {
			int behind = 255 - (top[i] >>> 24);
			for (int shift = 0; shift < 32; shift += 8)
				pixels[i] |= min(255, (top[i] >>> shift & 0xFF) + ((bottom[i] >>> shift & 0xFF) * behind + 127) / 255) << shift;
		}
		return pixels;
	}

	private int[] drawPixels(float distance, float scaleX, float scaleY, float left, float top, int width, int height, int border, int[] palette) {
		int count = x.length;
		var camera = new float[3][count];
		float[] sampleX = new float[count];
		float[] sampleY = new float[count];
		float[] depth = camera[2];
		for (int i = 0; i < count; i++) {
			camera[0][i] = x[i] + offsetX;
			camera[1][i] = y[i] + offsetY;
			depth[i] = z[i] + distance;
			sampleX[i] = (CENTER + FOCAL_LENGTH * camera[0][i] / depth[i] - left) * scaleX * SAMPLES;
			sampleY[i] = (CENTER + FOCAL_LENGTH * camera[1][i] / depth[i] - top) * scaleY * SAMPLES;
		}
		float[] toRay = { 1 / (scaleX * SAMPLES), left - CENTER, 1 / (scaleY * SAMPLES), top - CENTER };

		int samplesWide = width * SAMPLES;
		int samplesHigh = height * SAMPLES;
		int[] samples = new int[samplesWide * samplesHigh];
		for (int face : drawOrder(sampleX, sampleY, depth))
			drawFace(face, sampleX, sampleY, camera, toRay, samplesWide, samplesHigh, palette, samples);
		if (border > 0) {
			removeHiddenParts(samples, samplesWide, samplesHigh, scaleX, scaleY, left, top);
			addOutline(samples, samplesWide, samplesHigh, scaleX, scaleY);
		}

		int[] pixels = new int[width * height];
		int samplesPerPixel = SAMPLES * SAMPLES;
		int half = samplesPerPixel / 2;
		for (int py = 0; py < height; py++) {
			for (int px = 0; px < width; px++) {
				int n = 0, r = 0, g = 0, b = 0;
				for (int sy = 0; sy < SAMPLES; sy++) {
					int i = (py * SAMPLES + sy) * samplesWide + px * SAMPLES;
					for (int sx = 0; sx < SAMPLES; sx++, i++) {
						int rgb = samples[i];
						if (rgb != 0) {
							n++;
							r += rgb >> 16 & 0xFF;
							g += rgb >> 8 & 0xFF;
							b += rgb & 0xFF;
						}
					}
				}
				pixels[py * width + px] =
					(n * 255 + half) / samplesPerPixel << 24 |
					(r + half) / samplesPerPixel << 16 |
					(g + half) / samplesPerPixel << 8 |
					(b + half) / samplesPerPixel;
			}
		}
		return pixels;
	}

	private int[] drawOrder(float[] sampleX, float[] sampleY, float[] depth) {
		int faceCount = mesh.a.length;
		Integer[] faces = new Integer[faceCount];
		int[] faceDepth = new int[faceCount];
		int visible = 0;
		for (int f = 0; f < faceCount; f++) {
			if (mesh.color3[f] == -2)
				continue; // Hidden
			int a = mesh.a[f], b = mesh.b[f], c = mesh.c[f];
			if ((sampleX[a] - sampleX[b]) * (sampleY[c] - sampleY[b]) <= (sampleX[c] - sampleX[b]) * (sampleY[a] - sampleY[b]))
				continue;
			faceDepth[f] = floor((depth[a] + depth[b] + depth[c]) / 3);
			faces[visible++] = f;
		}
		faces = Arrays.copyOf(faces, visible);
		Arrays.sort(faces, (f1, f2) -> faceDepth[f2] - faceDepth[f1]);

		int[] order = new int[visible];
		if (mesh.priority == null) {
			for (int i = 0; i < visible; i++)
				order[i] = faces[i];
			return order;
		}

		float[] depthSum = new float[12];
		int[] count = new int[12];
		for (int f : faces) {
			depthSum[mesh.priority[f]] += faceDepth[f];
			count[mesh.priority[f]]++;
		}
		// Faces of priority 10 and 11 are drawn between the others by depth, like the game does
		float[] slotDepth = new float[10];
		Arrays.fill(slotDepth, Float.MAX_VALUE);
		slotDepth[0] = averageDepth(depthSum, count, 1, 2);
		slotDepth[3] = averageDepth(depthSum, count, 3, 4);
		slotDepth[5] = averageDepth(depthSum, count, 6, 8);

		int[] late = new int[count[10] + count[11]];
		int lateCount = 0;
		for (int priority = 10; priority <= 11; priority++)
			for (int f : faces)
				if (mesh.priority[f] == priority)
					late[lateCount++] = f;

		int next = 0, nextLate = 0;
		for (int priority = 0; priority < 10; priority++) {
			while (nextLate < lateCount && faceDepth[late[nextLate]] > slotDepth[priority])
				order[next++] = late[nextLate++];
			for (int f : faces)
				if (mesh.priority[f] == priority)
					order[next++] = f;
		}
		while (nextLate < lateCount)
			order[next++] = late[nextLate++];
		return order;
	}

	private static float averageDepth(float[] depthSum, int[] count, int priority1, int priority2) {
		int n = count[priority1] + count[priority2];
		return n == 0 ? 0 : (depthSum[priority1] + depthSum[priority2]) / n;
	}

	private void drawFace(int face, float[] sampleX, float[] sampleY, float[][] camera, float[] toRay, int width, int height, int[] palette, int[] samples) {
		int a = mesh.a[face], b = mesh.b[face], c = mesh.c[face];
		float xa = sampleX[a], ya = sampleY[a];
		float xb = sampleX[b], yb = sampleY[b];
		float xc = sampleX[c], yc = sampleY[c];
		float area = (xb - xa) * (yc - ya) - (xc - xa) * (yb - ya);
		if (area == 0)
			return;
		float inverseArea = 1 / area;

		int minX = max(0, ceil(min(xa, min(xb, xc)) - .5f));
		int maxX = min(width - 1, floor(max(xa, max(xb, xc)) - .5f));
		int minY = max(0, ceil(min(ya, min(yb, yc)) - .5f));
		int maxY = min(height - 1, floor(max(ya, max(yb, yc)) - .5f));

		boolean flat = mesh.color3[face] == -1;
		int colorA = mesh.color1[face], colorB = mesh.color2[face], colorC = mesh.color3[face];
		if (flat)
			colorB = colorC = colorA;
		int alpha = mesh.transparency == null ? 0 : mesh.transparency[face] & 0xFF;

		// Texture coordinates come from the plane through the texture's three vertices, like in the game
		int[] texture = mesh.texture == null ? null : mesh.texture[face];
		int textureSize = 0;
		float[] u = null, v = null, w = null;
		if (texture != null) {
			textureSize = (int) sqrt(texture.length);
			float[] p = vertex(camera, mesh.textureA[face]);
			float[] m = subtract(vertex(camera, mesh.textureB[face]), p);
			float[] n = subtract(vertex(camera, mesh.textureC[face]), p);
			u = cross(n, p);
			v = cross(p, m);
			w = cross(m, n);
		}

		for (int sy = minY; sy <= maxY; sy++) {
			float py = sy + .5f;
			for (int sx = minX; sx <= maxX; sx++) {
				float px = sx + .5f;
				float wa = ((xb - px) * (yc - py) - (xc - px) * (yb - py)) * inverseArea;
				float wb = ((xc - px) * (ya - py) - (xa - px) * (yc - py)) * inverseArea;
				float wc = 1 - wa - wb;
				if (wa < 0 || wb < 0 || wc < 0)
					continue;

				int color;
				if (texture == null) {
					color = palette[flat ? colorA : clamp((int) (wa * colorA + wb * colorB + wc * colorC), 0, 0xFFFF)];
				} else {
					float rayX = px * toRay[0] + toRay[1];
					float rayY = py * toRay[2] + toRay[3];
					float along = rayX * w[0] + rayY * w[1] + FOCAL_LENGTH * w[2];
					float texU = (rayX * u[0] + rayY * u[1] + FOCAL_LENGTH * u[2]) / along;
					float texV = (rayX * v[0] + rayY * v[1] + FOCAL_LENGTH * v[2]) / along;
					int texel = texture[
						mod(floor(texV * textureSize), textureSize) * textureSize +
						clamp(floor(texU * textureSize), 0, textureSize - 1)];
					if (texel == 0)
						continue;
					int shade = (int) (wa * colorA + wb * colorB + wc * colorC) * 2;
					color = max(1, shade(texel, shade));
				}
				int i = sy * width + sx;
				samples[i] = alpha == 0 ? color : blend(color, samples[i], alpha);
			}
		}
	}

	private static int shade(int rgb, int shade) {
		return ((rgb & 0xFF00FF) * shade >> 8 & 0xFF00FF) + ((rgb & 0xFF00) * shade >> 8 & 0xFF00);
	}

	private static float[] vertex(float[][] camera, int i) {
		return new float[] { camera[0][i], camera[1][i], camera[2][i] };
	}

	private static float[] subtract(float[] a, float[] b) {
		return new float[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
	}

	private static float[] cross(float[] a, float[] b) {
		return new float[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
	}

	private static int blend(int color, int behind, int alpha) {
		int opacity = 256 - alpha;
		return
			((color & 0xFF00FF) * opacity + (behind & 0xFF00FF) * alpha >> 8 & 0xFF00FF) +
			((color & 0xFF00) * opacity + (behind & 0xFF00) * alpha >> 8 & 0xFF00);
	}

	private static void addOutline(int[] samples, int width, int height, float scaleX, float scaleY) {
		boolean[] model = new boolean[samples.length];
		for (int i = 0; i < samples.length; i++)
			model[i] = samples[i] != 0;

		int reachX = floor(scaleX * SAMPLES);
		int reachY = floor(scaleY * SAMPLES);
		int[] offsets = new int[(reachX * 2 + 1) * (reachY * 2 + 1) * 2];
		int offsetCount = 0;
		for (int dy = -reachY; dy <= reachY; dy++) {
			for (int dx = -reachX; dx <= reachX; dx++) {
				if (square(dx / (scaleX * SAMPLES)) + square(dy / (scaleY * SAMPLES)) <= 1) {
					offsets[offsetCount++] = dx;
					offsets[offsetCount++] = dy;
				}
			}
		}

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				if (!model[y * width + x] || isSurrounded(model, x, y, width, height))
					continue;
				for (int o = 0; o < offsetCount; o += 2) {
					int sx = x + offsets[o];
					int sy = y + offsets[o + 1];
					// 0 means nothing was drawn, so black is 1
					if (sx >= 0 && sx < width && sy >= 0 && sy < height && samples[sy * width + sx] == 0)
						samples[sy * width + sx] = 1;
				}
			}
		}
	}

	// Parts smaller than one of the game's pixels that the game doesn't draw, like sparkles, would be buried in outline
	private void removeHiddenParts(int[] samples, int width, int height, float scaleX, float scaleY, float left, float top) {
		if (gameIcon == null)
			return;
		float pixelSize = scaleX * scaleY * SAMPLES * SAMPLES;
		boolean[] seen = new boolean[samples.length];
		int[] part = new int[samples.length];
		for (int start = 0; start < samples.length; start++) {
			if (samples[start] == 0 || seen[start])
				continue;
			int count = 0;
			part[count++] = start;
			seen[start] = true;
			boolean shown = false;
			for (int p = 0; p < count; p++) {
				int x = part[p] % width;
				int y = part[p] / width;
				int iconX = floor(left + (x + .5f) / (scaleX * SAMPLES));
				int iconY = floor(top + (y + .5f) / (scaleY * SAMPLES));
				shown |= iconX >= 0 && iconX < ICON_WIDTH && iconY >= 0 && iconY < ICON_HEIGHT &&
					gameIcon[iconY * ICON_WIDTH + iconX] != 0;
				for (int ny = max(0, y - 1); ny <= min(height - 1, y + 1); ny++) {
					for (int nx = max(0, x - 1); nx <= min(width - 1, x + 1); nx++) {
						int n = ny * width + nx;
						if (samples[n] != 0 && !seen[n]) {
							seen[n] = true;
							part[count++] = n;
						}
					}
				}
			}
			if (!shown && count < pixelSize)
				for (int p = 0; p < count; p++)
					samples[part[p]] = 0;
		}
	}

	private static boolean isSurrounded(boolean[] mask, int x, int y, int width, int height) {
		return
			x > 0 && mask[y * width + x - 1] &&
			x < width - 1 && mask[y * width + x + 1] &&
			y > 0 && mask[(y - 1) * width + x] &&
			y < height - 1 && mask[(y + 1) * width + x];
	}

	private static boolean isNear(boolean[] mask, int i) {
		int x = i % ICON_WIDTH;
		int y = i / ICON_WIDTH;
		for (int ny = max(0, y - 2); ny <= min(ICON_HEIGHT - 1, y + 2); ny++)
			for (int nx = max(0, x - 2); nx <= min(ICON_WIDTH - 1, x + 2); nx++)
				if (mask[ny * ICON_WIDTH + nx])
					return true;
		return false;
	}

	public static int[] palette(double brightness) {
		int[] palette = new int[0x10000];
		for (int hsl = 0; hsl < palette.length; hsl++) {
			float[] srgb = ColorUtils.packedHslToSrgb(hsl);
			int rgb = 0;
			for (float channel : srgb) {
				// Rounded to 8 bits before the brightness is applied, like the game does
				float rounded = (int) (channel * 256) / 256f;
				rgb = rgb << 8 | clamp((int) (pow(rounded, (float) brightness) * 256), 0, 255);
			}
			// 0 means nothing was drawn
			palette[hsl] = max(1, rgb);
		}
		return palette;
	}
}
