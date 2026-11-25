package rs117.hd.scene;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.opengl.uniforms.UBOGlobal;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.DEG_TO_RAD;
import static rs117.hd.utils.MathUtils.JAU_TO_RAD;

/**
 * Records the paths of nearby boats, so the water shader can draw wakes along them
 */
@Singleton
public class BoatWakes {
	public static final int MAX_BOATS = 16;
	// Enough for the big ships' paths. The wakes use about 11 KB of the 16 KB global UBO.
	public static final int MAX_POINTS = 40;

	// World entity config IDs of boats (https://oldschool.runescape.wiki/w/World_entity), excluding shoals (4)
	private static final Set<Integer> BOAT_CONFIGS = Set.of(
		1, // raft
		2, // skiff
		3, // sloop
		6, // charter ship
		7, // pirate ship, Rock Island Prison
		8, // strange ship
		9, // The Zenith
		10, // skiff, Barracuda Trials
		11, // skiff, Captain Reuben and the bank boat
		12, // pirate ships and the Fremennik ship
		13, // The Bark of the Bight
		14 // The Four of a Kind
	);
	// Big NPC ships, whose wash starts well behind them
	private static final Set<Integer> BIG_SHIP_CONFIGS = Set.of(6, 7, 8);

	// Points curving from the stern into the recorded path
	private static final int CURVE_POINTS = 3;
	private static final int RECORDED_POINTS = MAX_POINTS - CURVE_POINTS - 1;
	// Seconds between recorded points
	private static final float POINT_INTERVAL = 0.25f;
	private static final float LIFETIME = RECORDED_POINTS * POINT_INTERVAL;
	// Seconds of path drawn behind big ships. Cut off by age, so the tail doesn't step back as points are dropped.
	private static final float SHIP_TAIL_AGE = LIFETIME - 2 * POINT_INTERVAL;
	// Shorter for other boats, which is all their wakes need
	private static final float TAIL_AGE = 4.5f;
	// Seconds of movement averaged into the path, one game tick
	private static final float SMOOTHING = 0.6f;
	// Most positions kept for that average
	private static final int RAW_SAMPLES = 512;
	// Time constant easing the hull's position and heading, in seconds
	private static final float HULL_EASING = 0.1f;
	// Seconds without moving before a boat counts as stopped, covering frames between position updates
	private static final float STOP_DELAY = 0.1f;
	// Tiles and tiles per second a boat must move between frames to count as teleporting
	private static final float JUMP_DISTANCE = 4;
	private static final float JUMP_SPEED = 20;
	// Tiles moved over a game tick needed to tell which way a boat is going
	private static final float DIRECTION_MIN_MOVE = 0.1f;
	// How far off a straight line (tiles, seconds) a point can be and still be thinned out
	private static final float THIN_TOLERANCE = 0.01f;

	// Copies of the settings of the same names in water.glsl, where they're described, for working out each wake's
	// bounds. They must match, or the bounds clip the foam.
	private static final float BOAT_WAKE_LIFETIME = 2;
	private static final float BOAT_WAKE_WIDTH = 0.35f;
	private static final float BOAT_WAKE_ANGLE = 7;
	private static final float BOAT_WAKE_HULL_SPREAD = 0.08f;
	private static final float BOAT_WAKE_DRIFT = 0.6f;
	private static final float BOAT_WAKE_SPREAD = 0.5f;
	private static final float BOAT_WAKE_RAGGEDNESS = 0.35f;
	private static final float BOAT_WASH_WIDTH = 3;
	private static final float BOAT_WASH_SPREAD = 0.5f;
	private static final float BOAT_WAKE_STOP_TIME = 2.5f;
	private static final float BOAT_WAKE_STOP_AGEING = 0.5f;
	private static final float BOAT_REFERENCE_LENGTH = 10;
	private static final float BOAT_REFERENCE_WIDTH = 3;
	private static final float BOAT_WAKE_SIZE_FORWARD = 0.1f;
	private static final float BOAT_HULL_LENGTH = 5;
	private static final float BOAT_HULL_WIDTH = 2.4f;
	private static final float BOAT_WAKE_FORWARD = 0.25f;
	private static final float BOAT_WAKE_BOW_LENGTH = BOAT_HULL_LENGTH + BOAT_WAKE_FORWARD;
	private static final float BOAT_SHIP_FORWARD = -0.25f;
	private static final float BOAT_SHIP_ANGLE = 0.3f;
	private static final float BOAT_SHIP_LIFETIME = 1.8f;
	private static final float BOAT_SHIP_WASH_WIDTH = 0.85f;
	private static final float WAKE_ANGLE_SLOPE = (float) Math.tan(BOAT_WAKE_ANGLE * DEG_TO_RAD);

	private static final Comparator<Wake> BY_FOCUS_DISTANCE = Comparator.comparingDouble(wake -> wake.focusDistance);

	private final Map<Integer, Trail> trails = new HashMap<>();
	// Wakes left fading in place after their boats moved off again
	private final List<Wake> oldWakes = new ArrayList<>();
	// The wakes to draw this frame, nearest first
	private final List<Wake> active = new ArrayList<>();

	/**
	 * A wake as drawn, in local units. Points run from the stern back, with age in seconds and distance in tiles.
	 */
	private static class Wake {
		double sternX, sternZ;
		float headingX, headingZ;
		final double[] x = new double[MAX_POINTS];
		final double[] z = new double[MAX_POINTS];
		final float[] age = new float[MAX_POINTS];
		final float[] distance = new float[MAX_POINTS];
		int count;
		float startTime;
		// When the boat stopped making foam, or now while it still is
		float fadeStart;
		// Reversing boats leave only their wash, with the heading flipped
		boolean reversing;
		// In tiles
		float length, width;
		boolean squareBow;
		boolean bigShip;
		double focusDistance;

		void copy(Wake other) {
			sternX = other.sternX;
			sternZ = other.sternZ;
			headingX = other.headingX;
			headingZ = other.headingZ;
			System.arraycopy(other.x, 0, x, 0, other.count);
			System.arraycopy(other.z, 0, z, 0, other.count);
			System.arraycopy(other.age, 0, age, 0, other.count);
			System.arraycopy(other.distance, 0, distance, 0, other.count);
			count = other.count;
			startTime = other.startTime;
			fadeStart = other.fadeStart;
			reversing = other.reversing;
			length = other.length;
			width = other.width;
			squareBow = other.squareBow;
			bigShip = other.bigShip;
		}
	}

	private static class Trail {
		// Newest last
		final double[] x = new double[MAX_POINTS];
		final double[] z = new double[MAX_POINTS];
		final float[] time = new float[MAX_POINTS];
		int count;
		final Wake wake = new Wake();
		// Eased, so the foam along the hull doesn't jitter
		double headX, headZ;
		double headingX, headingZ;
		// Averaged over SMOOTHING, where the recorded path starts
		double averageX, averageZ;
		float averageTime;
		// Ring of raw positions over the last SMOOTHING seconds, with running sums
		final double[] rawX = new double[RAW_SAMPLES];
		final double[] rawZ = new double[RAW_SAMPLES];
		final float[] rawTime = new float[RAW_SAMPLES];
		int rawStart, rawCount;
		double rawSumX, rawSumZ, rawSumTime;
		float lastUpdate = Float.NaN;
		float lastMoved = Float.NEGATIVE_INFINITY;
		// When the boat last made foam. Its wake ages from then once it stops.
		float lastMoving;
		float startedMoving;
		boolean wasMaking;
		boolean reversing;
		boolean seen;

		/**
		 * @return the previous wake, left to fade out in place, if this update started a new one, otherwise null
		 */
		Wake update(double posX, double posZ, int orientation, float now) {
			// JAU orientation 0 faces south, and turns anticlockwise seen from above
			double angle = orientation * JAU_TO_RAD;
			double faceX = -Math.sin(angle);
			double faceZ = -Math.cos(angle);

			// Start afresh after a teleport, so no foam is drawn across the gap
			Wake oldWake = null;
			if (rawCount > 0) {
				int last = (rawStart + rawCount - 1) % RAW_SAMPLES;
				if (posX != rawX[last] || posZ != rawZ[last])
					lastMoved = now;
				double moved = Math.hypot(posX - rawX[last], posZ - rawZ[last]) / LOCAL_TILE_SIZE;
				if (moved > JUMP_DISTANCE && moved > JUMP_SPEED * (now - rawTime[last])) {
					if (count > 0) {
						oldWake = new Wake();
						oldWake.copy(wake);
					}
					count = 0;
					rawCount = 0;
					rawSumX = rawSumZ = rawSumTime = 0;
					lastUpdate = Float.NaN;
					startedMoving = now;
				}
			}

			// The hull is eased a little, while the path averages over a game tick so turning steps don't zigzag it
			if (Float.isNaN(lastUpdate)) {
				headX = posX;
				headZ = posZ;
				headingX = faceX;
				headingZ = faceZ;
			} else {
				double follow = 1 - Math.exp(-(now - lastUpdate) / HULL_EASING);
				headX += (posX - headX) * follow;
				headZ += (posZ - headZ) * follow;
				headingX += (faceX - headingX) * follow;
				headingZ += (faceZ - headingZ) * follow;
				double length = Math.hypot(headingX, headingZ);
				if (length > 1e-6) {
					headingX /= length;
					headingZ /= length;
				} else {
					headingX = faceX;
					headingZ = faceZ;
				}
			}

			// Add the position to the ring, dropping those older than SMOOTHING
			while (rawCount > 0 && (rawCount == RAW_SAMPLES || now - rawTime[rawStart] > SMOOTHING)) {
				rawSumX -= rawX[rawStart];
				rawSumZ -= rawZ[rawStart];
				rawSumTime -= rawTime[rawStart];
				rawStart = (rawStart + 1) % RAW_SAMPLES;
				rawCount--;
			}
			if (rawCount == 0)
				rawSumX = rawSumZ = rawSumTime = 0;
			int slot = (rawStart + rawCount) % RAW_SAMPLES;
			rawX[slot] = posX;
			rawZ[slot] = posZ;
			rawTime[slot] = now;
			rawCount++;
			rawSumX += posX;
			rawSumZ += posZ;
			rawSumTime += now;

			// Use the average time too, or the foam's age would jump each time a point is added
			posX = rawSumX / rawCount;
			posZ = rawSumZ / rawCount;
			float pointTime = (float) (rawSumTime / rawCount);
			lastUpdate = now;

			// Which way it's going, once it has moved far enough to tell
			boolean turnedAround = false;
			double moveX = rawX[slot] - rawX[rawStart];
			double moveZ = rawZ[slot] - rawZ[rawStart];
			if (Math.hypot(moveX, moveZ) > DIRECTION_MIN_MOVE * LOCAL_TILE_SIZE) {
				boolean backwards = moveX * faceX + moveZ * faceZ < 0;
				turnedAround = backwards != reversing;
				reversing = backwards;
			}

			boolean making = now - lastMoved < STOP_DELAY;
			if (making) {
				// Moving off or changing direction starts a new wake, leaving the old one to fade in place
				if (!wasMaking || turnedAround) {
					if (count > 0 && now - lastMoving < BOAT_WAKE_STOP_TIME) {
						oldWake = new Wake();
						oldWake.copy(wake);
					}
					count = 0;
					startedMoving = now;
				}
				lastMoving = now;
			}
			wasMaking = making;
			averageX = posX;
			averageZ = posZ;
			averageTime = pointTime;

			// Freeze the path while the wake fades out
			if (!making)
				return oldWake;

			// Drop expired points, then record a new one every POINT_INTERVAL
			int expired = 0;
			while (expired < count && lastMoving - time[expired] > LIFETIME)
				expired++;
			if (expired > 0) {
				System.arraycopy(x, expired, x, 0, count - expired);
				System.arraycopy(z, expired, z, 0, count - expired);
				System.arraycopy(time, expired, time, 0, count - expired);
				count -= expired;
			}
			if (count > 0 && pointTime - time[count - 1] < POINT_INTERVAL)
				return oldWake;
			if (count == RECORDED_POINTS) {
				System.arraycopy(x, 1, x, 0, count - 1);
				System.arraycopy(z, 1, z, 0, count - 1);
				System.arraycopy(time, 1, time, 0, count - 1);
				count--;
			}
			x[count] = posX;
			z[count] = posZ;
			time[count] = pointTime;
			count++;
			return oldWake;
		}

		/**
		 * Build the wake: a curve from the stern into the recorded path, then the path itself
		 */
		void build() {
			Wake w = wake;
			double headingX = reversing ? -this.headingX : this.headingX;
			double headingZ = reversing ? -this.headingZ : this.headingZ;
			w.reversing = reversing;
			w.headingX = (float) headingX;
			w.headingZ = (float) headingZ;
			w.sternX = headX;
			w.sternZ = headZ;
			w.startTime = startedMoving;
			w.fadeStart = lastMoving;
			// Ages freeze once the boat stops, and the shader adds the time since
			float ageNow = lastMoving;
			float startAge = Math.max(ageNow - averageTime, 0);

			double forwardX = headingX, forwardZ = headingZ;
			if (count > 0) {
				double dx = averageX - x[count - 1];
				double dz = averageZ - z[count - 1];
				double length = Math.hypot(dx, dz);
				if (length > 0.01 * LOCAL_TILE_SIZE) {
					forwardX = dx / length;
					forwardZ = dz / length;
				}
			}

			// Bezier from the stern along the heading, into the path along its direction
			double handle = Math.hypot(averageX - headX, averageZ - headZ) / 3;
			double c1X = headX - headingX * handle, c1Z = headZ - headingZ * handle;
			double c2X = averageX + forwardX * handle, c2Z = averageZ + forwardZ * handle;
			int n = 0;
			for (int i = 1; i <= CURVE_POINTS; i++) {
				double t = (double) i / (CURVE_POINTS + 1);
				double u = 1 - t;
				w.x[n] = u * u * u * headX + 3 * u * u * t * c1X + 3 * u * t * t * c2X + t * t * t * averageX;
				w.z[n] = u * u * u * headZ + 3 * u * u * t * c1Z + 3 * u * t * t * c2Z + t * t * t * averageZ;
				w.age[n] = (float) (startAge * t);
				n++;
			}

			w.x[n] = averageX;
			w.z[n] = averageZ;
			w.age[n] = startAge;
			n++;
			float tailAge = w.bigShip ? SHIP_TAIL_AGE : TAIL_AGE;
			for (int i = count - 1; i >= 0; i--) {
				float age = Math.max(ageNow - time[i], 0);
				if (age > tailAge) {
					float before = w.age[n - 1];
					double t = age > before ? (tailAge - before) / (age - before) : 0;
					w.x[n] = w.x[n - 1] + (x[i] - w.x[n - 1]) * t;
					w.z[n] = w.z[n - 1] + (z[i] - w.z[n - 1]) * t;
					w.age[n] = tailAge;
					n++;
					break;
				}
				w.x[n] = x[i];
				w.z[n] = z[i];
				w.age[n] = age;
				n++;
			}
			w.count = n;

			// Precomputed so the shader needn't measure every segment per pixel
			double distance = 0;
			double prevX = headX, prevZ = headZ;
			for (int i = 0; i < n; i++) {
				distance += Math.hypot(w.x[i] - prevX, w.z[i] - prevZ);
				w.distance[i] = (float) (distance / LOCAL_TILE_SIZE);
				prevX = w.x[i];
				prevZ = w.z[i];
			}

			// The path starts at the boat's middle, so while turning it lags behind the back half of the hull. Bend that
			// stretch onto the hull's centre line to avoid a step in the foam.
			double halfLength = w.length * 0.5 * LOCAL_TILE_SIZE;
			for (int i = 0; i < n; i++) {
				double back = w.distance[i] * LOCAL_TILE_SIZE;
				if (back >= halfLength)
					break;
				double t = back / halfLength;
				double bend = 1 - t * t * (3 - 2 * t);
				w.x[i] += (headX - headingX * back - w.x[i]) * bend;
				w.z[i] += (headZ - headingZ * back - w.z[i]) * bend;
			}

			thin(w);
		}

		/**
		 * Drop points the shader could interpolate back, keeping the first and last
		 */
		private static void thin(Wake w) {
			int kept = 1;
			int from = 0;
			while (from < w.count - 1) {
				int to = from + 1;
				while (to + 1 < w.count && isStraight(w, from, to + 1))
					to++;
				w.x[kept] = w.x[to];
				w.z[kept] = w.z[to];
				w.age[kept] = w.age[to];
				w.distance[kept] = w.distance[to];
				kept++;
				from = to;
			}
			w.count = kept;
		}

		/**
		 * Whether every point between start and end is within THIN_TOLERANCE of the line between them
		 */
		private static boolean isStraight(Wake w, int start, int end) {
			double dx = w.x[end] - w.x[start];
			double dz = w.z[end] - w.z[start];
			double length2 = dx * dx + dz * dz;
			double tolerance = THIN_TOLERANCE * LOCAL_TILE_SIZE;
			for (int i = start + 1; i < end; i++) {
				double t = 0;
				if (length2 > 0)
					t = Math.max(0, Math.min(1, ((w.x[i] - w.x[start]) * dx + (w.z[i] - w.z[start]) * dz) / length2));
				double offX = w.x[i] - (w.x[start] + dx * t);
				double offZ = w.z[i] - (w.z[start] + dz * t);
				if (offX * offX + offZ * offZ > tolerance * tolerance)
					return false;
				if (Math.abs(w.age[i] - (w.age[start] + (w.age[end] - w.age[start]) * t)) > THIN_TOLERANCE)
					return false;
				if (Math.abs(w.distance[i] - (w.distance[start] + (w.distance[end] - w.distance[start]) * t)) > THIN_TOLERANCE)
					return false;
			}
			return true;
		}
	}

	/**
	 * Forget every path, since the plugin's clock restarts from 0
	 */
	void reset() {
		trails.clear();
		oldWakes.clear();
	}

	/**
	 * @param drawnScene the scene being drawn, which can lag behind the client's across loading lines
	 */
	void update(Client client, Scene drawnScene, UBOGlobal ubo, float now) {
		WorldView topLevel = client.getTopLevelWorldView();
		Scene scene = topLevel == null ? null : topLevel.getScene();
		active.clear();
		if (scene != null && drawnScene != null) {
			int clientBaseX = scene.getBaseX() * LOCAL_TILE_SIZE;
			int clientBaseZ = scene.getBaseY() * LOCAL_TILE_SIZE;
			int baseX = drawnScene.getBaseX() * LOCAL_TILE_SIZE;
			int baseZ = drawnScene.getBaseY() * LOCAL_TILE_SIZE;
			for (var trail : trails.values())
				trail.seen = false;
			for (WorldEntity entity : topLevel.worldEntities()) {
				var config = entity.getConfig();
				if (!BOAT_CONFIGS.contains(config.getId()))
					continue;
				// Flattened boats keep their paths, in case they're only hidden briefly
				LocalPoint location = entity.getLocalLocation();
				var trail = trails.computeIfAbsent(entity.getWorldView().getId(), id -> new Trail());
				trail.wake.length = (float) config.getBoundsHeight() / LOCAL_TILE_SIZE;
				trail.wake.width = (float) config.getBoundsWidth() / LOCAL_TILE_SIZE;
				// Rafts are the only boats a single tile wide, and the only ones with a square front
				trail.wake.squareBow = config.getBoundsWidth() <= LOCAL_TILE_SIZE;
				trail.wake.bigShip = BIG_SHIP_CONFIGS.contains(config.getId());
				Wake oldWake = trail.update(
					location.getX() + clientBaseX, location.getY() + clientBaseZ, entity.getOrientation(), now);
				if (oldWake != null)
					oldWakes.add(oldWake);
				trail.build();
				trail.seen = true;
				if (!entity.isHiddenForOverlap() && now - trail.wake.fadeStart <= BOAT_WAKE_STOP_TIME)
					active.add(trail.wake);
			}
			trails.values().removeIf(trail -> !trail.seen);
			oldWakes.removeIf(wake -> now - wake.fadeStart > BOAT_WAKE_STOP_TIME);

			// Nearest first, preferring live wakes over fading ones
			double focusX = client.getCameraFocalPointX() + clientBaseX;
			double focusZ = client.getCameraFocalPointZ() + clientBaseZ;
			sortByDistance(active, focusX, focusZ);
			sortByDistance(oldWakes, focusX, focusZ);
			active.addAll(oldWakes);
			while (active.size() > MAX_BOATS)
				active.remove(active.size() - 1);

			for (int boat = 0; boat < active.size(); boat++)
				upload(ubo, boat, active.get(boat), baseX, baseZ, now);
		}

		for (int boat = active.size(); boat < MAX_BOATS; boat++)
			ubo.boatWakeStates[boat].set(0, 0, 0, 0);
	}

	private static void sortByDistance(List<Wake> wakes, double focusX, double focusZ) {
		for (int i = 0; i < wakes.size(); i++) {
			var wake = wakes.get(i);
			double dx = wake.sternX - focusX;
			double dz = wake.sternZ - focusZ;
			wake.focusDistance = dx * dx + dz * dz;
		}
		wakes.sort(BY_FOCUS_DISTANCE);
	}

	/**
	 * Upload a wake relative to the drawn scene, in tiles, with bounds covering everywhere its foam can reach
	 */
	private static void upload(UBOGlobal ubo, int boat, Wake wake, int baseX, int baseZ, float now) {
		float sinceSlowed = now - wake.fadeStart;
		float sternX = (float) ((wake.sternX - baseX) / LOCAL_TILE_SIZE);
		float sternZ = (float) ((wake.sternZ - baseZ) / LOCAL_TILE_SIZE);
		ubo.boatWakeHeadings[boat].set(wake.headingX, wake.headingZ, sternX, sternZ);
		ubo.boatWakeStates[boat].set(wake.count, sinceSlowed, now - wake.startTime, wake.reversing ? 1 : 0);
		ubo.boatWakeSizes[boat].set(wake.length, wake.width, wake.squareBow ? 1 : 0, wake.bigShip ? 1 : 0);

		float minX = sternX, maxX = sternX;
		float minZ = sternZ, maxZ = sternZ;
		int index = boat * MAX_POINTS;
		for (int i = 0; i < wake.count; i++) {
			float px = (float) ((wake.x[i] - baseX) / LOCAL_TILE_SIZE);
			float pz = (float) ((wake.z[i] - baseZ) / LOCAL_TILE_SIZE);
			ubo.boatWakePoints[index++].set(px, pz, wake.age[i], wake.distance[i]);
			minX = Math.min(minX, px);
			maxX = Math.max(maxX, px);
			minZ = Math.min(minZ, pz);
			maxZ = Math.max(maxZ, pz);
		}

		// Placed as the shader places the bow
		float lengthScale = wake.length > 0 ? wake.length / BOAT_REFERENCE_LENGTH : 1;
		float sideScale = wake.width > 0 ? wake.width / BOAT_REFERENCE_WIDTH : 1;
		float forward = wake.squareBow ? 0 : (BOAT_REFERENCE_LENGTH - wake.length) * BOAT_WAKE_SIZE_FORWARD;
		if (wake.bigShip)
			forward += BOAT_SHIP_FORWARD;
		float toBow = forward + BOAT_WAKE_BOW_LENGTH * lengthScale;
		float bowX = sternX + wake.headingX * toBow;
		float bowZ = sternZ + wake.headingZ * toBow;
		minX = Math.min(minX, bowX);
		maxX = Math.max(maxX, bowX);
		minZ = Math.min(minZ, bowZ);
		maxZ = Math.max(maxZ, bowZ);

		// The furthest the foam can reach from the path, in tiles, based on the oldest point's age
		float oldest = wake.age[wake.count - 1] + sinceSlowed * BOAT_WAKE_STOP_AGEING;
		float armAge = Math.min(BOAT_WAKE_LIFETIME * (wake.bigShip ? BOAT_SHIP_LIFETIME : 1), oldest);
		float band = BOAT_HULL_WIDTH * 0.5f +
			(BOAT_WAKE_BOW_LENGTH * WAKE_ANGLE_SLOPE + armAge * BOAT_WAKE_DRIFT) * (wake.bigShip ? BOAT_SHIP_ANGLE : 1) +
			0.5f * (BOAT_WAKE_WIDTH + BOAT_HULL_LENGTH * BOAT_WAKE_HULL_SPREAD + armAge * BOAT_WAKE_SPREAD);
		float washReach = (BOAT_WASH_WIDTH * 0.5f + oldest * BOAT_WASH_SPREAD) * (wake.bigShip ? BOAT_SHIP_WASH_WIDTH : 1);
		float ragged = 0.5f * BOAT_WAKE_RAGGEDNESS * (0.3f + BOAT_HULL_LENGTH * 0.15f + oldest);
		float reach = (Math.max(band, washReach) + ragged) * Math.max(lengthScale, sideScale);
		ubo.boatWakeBounds[boat].set(minX - reach, minZ - reach, maxX + reach, maxZ + reach);
	}
}
