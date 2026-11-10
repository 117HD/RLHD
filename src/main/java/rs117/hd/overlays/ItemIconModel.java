package rs117.hd.overlays;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Model;
import net.runelite.api.TextureProvider;

import static rs117.hd.utils.MathUtils.*;

/**
 * An item's model placed on its icon the way the game places it, as triangles in the order the game draws them.
 */
public class ItemIconModel {
	public static final int ICON_WIDTH = 36;
	public static final int ICON_HEIGHT = 32;
	// Position on the icon, color, texture plane, opacity, texture layer, texture size and flags
	public static final int FLOATS_PER_VERTEX = 10;
	public static final int FLAT = 1;
	// Textures with see-through texels, which can leave parts smaller than the game's pixels, like sparkles
	public static final int SPARKLING = 2;

	// The game's item icon projection
	private static final int FOCAL_LENGTH = 512;
	public static final int CENTER = 16;
	// The game fills a pixel by where its edges cross the pixel's rows, in fixed point and rounded down, which shows its
	// model this far from where it projects it, on average over its items
	private static final float SHIFT_X = -.2f;
	private static final float SHIFT_Y = .35f;
	private static final float MAX_MISPLACEMENT = 1.5f;
	private static final float MIN_DRAWN_AREA = .5f;
	private static final int[] SINE = new int[2048];
	private static final int[] COSINE = new int[2048];

	static {
		for (int i = 0; i < 2048; i++) {
			SINE[i] = (int) (65536 * Math.sin(i * 0.0030679615));
			COSINE[i] = (int) (65536 * Math.cos(i * 0.0030679615));
		}
	}

	@RequiredArgsConstructor
	public static class Mesh {
		final int[] x, y, z;
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
		public static Mesh copyOf(Model model, TextureProvider textureProvider, int resizeX, int resizeY, int resizeZ) {
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

			// Resized like the game resizes the model it lights
			float[] vx = model.getVerticesX();
			float[] vy = model.getVerticesY();
			float[] vz = model.getVerticesZ();
			int[] x = new int[vertices];
			int[] y = new int[vertices];
			int[] z = new int[vertices];
			for (int i = 0; i < vertices; i++) {
				x[i] = (int) vx[i] * resizeX / 128;
				y[i] = (int) vy[i] * resizeY / 128;
				z[i] = (int) vz[i] * resizeZ / 128;
			}

			return new Mesh(
				x, y, z,
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
	private final int[] cameraX, cameraY, cameraZ;
	// Where the game draws each vertex on the icon, in its pixels
	private final float[] iconX, iconY;
	private int[] order;
	// The game's textures this model uses, in the order of their texture layers
	final List<int[]> textures = new ArrayList<>();
	private final Map<int[], Integer> textureLayers = new IdentityHashMap<>();
	private float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = -Float.MAX_VALUE, bottom = -Float.MAX_VALUE;

	/**
	 * Placed like the game's createItemSprite, with the zoom already enlarged for notes.
	 */
	public ItemIconModel(Mesh mesh, int xan2d, int yan2d, int zan2d, int zoom2d, int xOffset2d, int yOffset2d) {
		this.mesh = mesh;
		int count = mesh.x.length;
		cameraX = new int[count];
		cameraY = new int[count];
		cameraZ = new int[count];
		iconX = new float[count];
		iconY = new float[count];

		int modelHeight = 0;
		for (int y : mesh.y)
			modelHeight = max(modelHeight, -y);
		int sinPitch = SINE[xan2d & 2047], cosPitch = COSINE[xan2d & 2047];
		int sinYaw = SINE[yan2d & 2047], cosYaw = COSINE[yan2d & 2047];
		int sinRoll = SINE[zan2d & 2047], cosRoll = COSINE[zan2d & 2047];
		int offsetY = modelHeight / 2 + (zoom2d * sinPitch >> 16) + yOffset2d;
		int offsetZ = (zoom2d * cosPitch >> 16) + yOffset2d;
		for (int i = 0; i < count; i++) {
			int x = mesh.x[i], y = mesh.y[i], z = mesh.z[i];
			if (zan2d != 0) {
				int t = y * sinRoll + x * cosRoll >> 16;
				y = y * cosRoll - x * sinRoll >> 16;
				x = t;
			}
			if (yan2d != 0) {
				int t = z * sinYaw + x * cosYaw >> 16;
				z = z * cosYaw - x * sinYaw >> 16;
				x = t;
			}
			x += xOffset2d;
			y += offsetY;
			z += offsetZ;
			int t = y * cosPitch - z * sinPitch >> 16;
			z = y * sinPitch + z * cosPitch >> 16;
			y = t;
			cameraX[i] = x;
			cameraY[i] = y;
			cameraZ[i] = z;
			iconX[i] = CENTER + towardCenter((float) FOCAL_LENGTH * x / z) + SHIFT_X;
			iconY[i] = CENTER + towardCenter((float) FOCAL_LENGTH * y / z) + SHIFT_Y;
		}

		order = drawOrder();
		for (int f : order) {
			for (int v : new int[] { mesh.a[f], mesh.b[f], mesh.c[f] }) {
				left = min(left, iconX[v]);
				top = min(top, iconY[v]);
				right = max(right, iconX[v]);
				bottom = max(bottom, iconY[v]);
			}
			int[] texture = mesh.texture == null ? null : mesh.texture[f];
			if (texture != null && !textureLayers.containsKey(texture)) {
				textureLayers.put(texture, textures.size());
				textures.add(texture);
			}
		}
	}

	// Everything is in front of the camera, like the game needs it to draw the model on an icon
	public boolean isDrawable() {
		for (int z : cameraZ)
			if (z <= 0)
				return false;
		return order.length > 0;
	}

	/**
	 * Whether the model lands where the game drew its icon, as a check on what was read from the cache.
	 * The game can draw less than the model, like where its textures are see-through, but not more.
	 */
	public boolean fits(int[] gameIcon) {
		int gameLeft = ICON_WIDTH, gameTop = ICON_HEIGHT, gameRight = -1, gameBottom = -1;
		for (int y = 0; y < ICON_HEIGHT; y++) {
			for (int x = 0; x < ICON_WIDTH; x++) {
				if (gameIcon[y * ICON_WIDTH + x] == 0)
					continue;
				gameLeft = min(gameLeft, x);
				gameTop = min(gameTop, y);
				gameRight = max(gameRight, x + 1);
				gameBottom = max(gameBottom, y + 1);
			}
		}
		if (gameRight == -1 || !isDrawable())
			return false;
		// Clipped to the icon, like the game draws it
		float ourLeft = max(left, 0), ourTop = max(top, 0), ourRight = min(right, ICON_WIDTH), ourBottom = min(bottom, ICON_HEIGHT);
		return
			gameLeft >= ourLeft - MAX_MISPLACEMENT && gameRight <= ourRight + MAX_MISPLACEMENT &&
			gameTop >= ourTop - MAX_MISPLACEMENT && gameBottom <= ourBottom + MAX_MISPLACEMENT &&
			// Nor does the game draw much less, which would mean the model is placed wrong
			(gameRight - gameLeft) * (gameBottom - gameTop) >= MIN_DRAWN_AREA * (ourRight - ourLeft) * (ourBottom - ourTop);
	}

	/**
	 * Leaves out faces that lie only on pixels the game left empty, which it doesn't draw, like sparkles,
	 * which would otherwise be buried in outline. Faces that touch a pixel of the game's icon stay whole.
	 */
	public void hideWhatTheGameDoesNotDraw(int[] gameIcon) {
		int kept = 0;
		for (int f : order) {
			int a = mesh.a[f], b = mesh.b[f], c = mesh.c[f];
			int fromX = max(0, (int) Math.floor(min(iconX[a], min(iconX[b], iconX[c]))));
			int toX = min(ICON_WIDTH - 1, (int) Math.floor(max(iconX[a], max(iconX[b], iconX[c]))));
			int fromY = max(0, (int) Math.floor(min(iconY[a], min(iconY[b], iconY[c]))));
			int toY = min(ICON_HEIGHT - 1, (int) Math.floor(max(iconY[a], max(iconY[b], iconY[c]))));
			boolean shown = false;
			for (int y = fromY; y <= toY && !shown; y++)
				for (int x = fromX; x <= toX && !shown; x++)
					shown = gameIcon[y * ICON_WIDTH + x] != 0;
			if (shown)
				order[kept++] = f;
		}
		order = Arrays.copyOf(order, kept);
	}

	public int vertexCount() {
		return order.length * 3;
	}

	public void putTriangles(float[] vertices, int offset) {
		for (int f : order) {
			int a = mesh.a[f], b = mesh.b[f], c = mesh.c[f];
			boolean flat = mesh.color3[f] == -1;
			int alpha = mesh.transparency == null ? 0 : mesh.transparency[f] & 0xFF;
			int[] texture = mesh.texture == null ? null : mesh.texture[f];

			// Texture coordinates come from the plane through the texture's three vertices, like in the game
			int[] u = null, v = null, w = null;
			if (texture != null) {
				int[] p = camera(mesh.textureA[f]);
				int[] m = subtract(camera(mesh.textureB[f]), p);
				int[] n = subtract(camera(mesh.textureC[f]), p);
				u = cross(n, p);
				v = cross(p, m);
				w = cross(m, n);
			}

			int[] corners = { a, b, c };
			int[] colors = { mesh.color1[f], flat ? mesh.color1[f] : mesh.color2[f], flat ? mesh.color1[f] : mesh.color3[f] };
			for (int corner = 0; corner < 3; corner++) {
				int i = corners[corner];
				float rayX = iconX[i] - SHIFT_X - CENTER;
				float rayY = iconY[i] - SHIFT_Y - CENTER;
				vertices[offset++] = iconX[i];
				vertices[offset++] = iconY[i];
				vertices[offset++] = colors[corner];
				vertices[offset++] = texture == null ? 0 : rayX * u[0] + rayY * u[1] + FOCAL_LENGTH * (float) u[2];
				vertices[offset++] = texture == null ? 0 : rayX * v[0] + rayY * v[1] + FOCAL_LENGTH * (float) v[2];
				vertices[offset++] = texture == null ? 1 : rayX * w[0] + rayY * w[1] + FOCAL_LENGTH * (float) w[2];
				vertices[offset++] = alpha;
				vertices[offset++] = texture == null ? -1 : textureLayers.get(texture);
				vertices[offset++] = texture == null ? 0 : (int) Math.sqrt(texture.length);
				vertices[offset++] = (flat ? FLAT : 0) | (texture != null && isSparkling(texture) ? SPARKLING : 0);
			}
		}
	}

	private final Map<int[], Boolean> sparkling = new IdentityHashMap<>();

	private boolean isSparkling(int[] texture) {
		return sparkling.computeIfAbsent(texture, t -> {
			for (int texel : t)
				if (texel == 0)
					return true;
			return false;
		});
	}

	// The game rounds projected positions toward the center, which pulls parts away from it in by half a pixel on average,
	// like the seeds of a stack. Followed smoothly, and the center is left as it is.
	private static float towardCenter(float pixels) {
		return pixels - .5f * Math.signum(pixels) * clamp(abs(pixels) - 1, 0, 1);
	}

	private int[] drawOrder() {
		int faceCount = mesh.a.length;
		Integer[] faces = new Integer[faceCount];
		int[] faceDepth = new int[faceCount];
		int visible = 0;
		for (int f = 0; f < faceCount; f++) {
			// Hidden, or so see-through that the game draws nothing over an empty icon
			if (mesh.color3[f] == -2 || mesh.transparency != null && (mesh.transparency[f] & 0xFF) == 255)
				continue;
			int a = mesh.a[f], b = mesh.b[f], c = mesh.c[f];
			if ((iconX[a] - iconX[b]) * (iconY[c] - iconY[b]) <= (iconX[c] - iconX[b]) * (iconY[a] - iconY[b]))
				continue;
			faceDepth[f] = (cameraZ[a] + cameraZ[b] + cameraZ[c]) / 3;
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

	private int[] camera(int i) {
		return new int[] { cameraX[i], cameraY[i], cameraZ[i] };
	}

	private static int[] subtract(int[] a, int[] b) {
		return new int[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
	}

	private static int[] cross(int[] a, int[] b) {
		return new int[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
	}
}
