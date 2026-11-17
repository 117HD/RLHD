package rs117.hd.renderer.zone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import rs117.hd.HdPlugin;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.collections.IntHashSet;

import static net.runelite.api.Constants.*;

@Singleton
public class ZoneRoofRemoval {
	private static final int MAX_FADES = 32;
	private static final int STALE_FRAMES = 120;
	private static final int RESUME_GAP_FRAMES = 4;
	private static final int LEVEL_MASK = (1 << MAX_Z) - 1;

	private final CommandBuffer[] cmds = new CommandBuffer[MAX_FADES];
	private final List<State> pool = new ArrayList<>();
	private final List<State> inFlight = new ArrayList<>();

	private final IntHashSet baseHidden = new IntHashSet();
	private final IntHashSet fadingRoofIds = new IntHashSet();

	// Levels partway through fading in or out. These are drawn separately from the normal draw pass.
	private int lastLevel = -1;
	private int visibleLevels;
	private int levelMask;
	private final float[] levelFade = new float[MAX_Z];
	private final boolean[] levelHiddenTarget = new boolean[MAX_Z];
	private final int[] levelBuckets = new int[MAX_Z];

	@Inject
	private HdPlugin plugin;

	@Inject
	private ZoneRenderer renderer;

	public static class State {
		Zone zone;
		boolean initialized;
		int lastUsedFrame;
		int updatedFrame = -1;
		int alphaFrame = -1;
		int lastLevel = -1;
		IntHashSet hidden = new IntHashSet();
		IntHashSet prevHidden = new IntHashSet();

		// Roofs partway through a fade. fade: 0 = visible, 1 = hidden. While delay is above zero, the roof stays fully
		// hidden until its fade in delay has passed.
		int roofCount;
		int[] roofIds = new int[8];
		float[] roofFade = new float[8];
		boolean[] roofHiddenTarget = new boolean[8];
		float[] roofDelay = new float[8];

		// Bit mask of the buckets which contain roof geometry to draw
		int roofBucketsMask;

		final int[] levelBuckets = new int[MAX_Z];

		State() {
			Arrays.fill(levelBuckets, -1);
		}

		public int roofBucket(int rid) {
			final int index = indexOfRoof(rid);
			return index == -1 ? -1 : roofBucketAt(index);
		}

		public int levelBucket(int level) {
			return level >= 0 && level < MAX_Z ? levelBuckets[level] : -1;
		}

		void clear() {
			zone = null;
			initialized = false;
			updatedFrame = -1;
			alphaFrame = -1;
			lastLevel = -1;
			hidden.clear();
			prevHidden.clear();
			clearFades();
		}

		void clearFades() {
			roofCount = 0;
			roofBucketsMask = 0;
			Arrays.fill(levelBuckets, -1);
		}

		int indexOfRoof(int rid) {
			for (int i = 0; i < roofCount; ++i)
				if (roofIds[i] == rid)
					return i;
			return -1;
		}

		void setRoofTarget(int rid, boolean hidden, float delay) {
			final int index = indexOfRoof(rid);
			if (index == -1) {
				if (roofCount == roofIds.length) {
					final int capacity = roofCount * 2;
					roofIds = Arrays.copyOf(roofIds, capacity);
					roofFade = Arrays.copyOf(roofFade, capacity);
					roofHiddenTarget = Arrays.copyOf(roofHiddenTarget, capacity);
					roofDelay = Arrays.copyOf(roofDelay, capacity);
				}

				roofIds[roofCount] = rid;
				roofFade[roofCount] = hidden ? 0 : 1;
				roofHiddenTarget[roofCount] = hidden;
				roofDelay[roofCount] = hidden ? 0 : delay;
				++roofCount;
				return;
			}

			if (hidden) {
				// A roof still waiting out its fade in delay never started fading, so it simply stays hidden
				roofDelay[index] = 0;
				if (roofFade[index] >= 1) {
					removeRoof(index);
					return;
				}
			}
			roofHiddenTarget[index] = hidden;
		}

		void advanceDelays(float deltaTime) {
			for (int i = 0; i < roofCount; ++i)
				if (roofDelay[i] > 0)
					roofDelay[i] = Math.max(0, roofDelay[i] - deltaTime);
		}

		void advanceFades(float step) {
			for (int i = roofCount - 1; i >= 0; --i) {
				if (roofDelay[i] > 0)
					continue;

				final boolean hidden = roofHiddenTarget[i];
				final float fade = roofFade[i] + (hidden ? step : -step);
				if (hidden ? fade >= 1 : fade <= 0) {
					// Finished, so it's either fully hidden or fully visible again, and no longer needs tracking
					removeRoof(i);
				} else {
					roofFade[i] = fade;
				}
			}
		}

		int roofBucketAt(int index) {
			// A roof still waiting out its fade in delay is fully hidden, so it shouldn't be drawn by the fading pass
			return roofDelay[index] > 0 ? -1 : bucketOf(roofFade[index]);
		}

		int publishRoofs() {
			int mask = 0;
			for (int i = 0; i < roofCount; ++i) {
				final int bucket = roofBucketAt(i);
				if (bucket != -1)
					mask |= 1 << bucket;
			}
			return mask;
		}

		private void removeRoof(int index) {
			final int last = --roofCount;
			roofIds[index] = roofIds[last];
			roofFade[index] = roofFade[last];
			roofHiddenTarget[index] = roofHiddenTarget[last];
			roofDelay[index] = roofDelay[last];
		}
	}

	public void initialize() {
		for (int i = 0; i < MAX_FADES; ++i)
			cmds[i] = new CommandBuffer("FadingRoof" + i);
	}

	public void destroy() {
		for (State state : inFlight) {
			detach(state);
			state.clear();
		}
		inFlight.clear();
		pool.clear();
		Arrays.fill(cmds, null);
	}

	public void reset() {
		for (CommandBuffer cmd : cmds)
			if (cmd != null)
				cmd.reset();

		// Release states of zones that haven't been drawn in a while
		for (int i = inFlight.size() - 1; i >= 0; --i) {
			final State state = inFlight.get(i);
			if (plugin.frame - state.lastUsedFrame <= STALE_FRAMES)
				continue;

			inFlight.remove(i);
			detach(state);
			state.clear();
			pool.add(state);
		}
	}

	public static float getBucketFade(int bucket) {
		return bucket / (float) (MAX_FADES - 1);
	}

	public void update(WorldViewContext ctx) {
		final int minLevel = ctx.baseMinLevel;
		final int maxLevel = ctx.baseMaxLevel;

		baseHidden.clear();
		baseHidden.addAll(ctx.hideRoofIds);

		if (!plugin.configDitherFadeRoofs) {
			clearLevelFades();
			lastLevel = -1;
			ctx.minLevel = minLevel;
			ctx.maxLevel = maxLevel;
			return;
		}

		updateLevels(minLevel, ctx.level, maxLevel);

		for (int x = 0; x < ctx.sizeX; x++) {
			for (int z = 0; z < ctx.sizeZ; z++) {
				final State state = updateZone(ctx.zones[x][z], ctx.level);

				System.arraycopy(levelBuckets, 0, state.levelBuckets, 0, MAX_Z);
				state.roofBucketsMask = state.publishRoofs();

				// Everything partway through fading, including roofs waiting on their fade in delay, counts as hidden
				// to the normal draw pass
				for (int i = 0; i < state.roofCount; ++i)
					ctx.hideRoofIds.add(state.roofIds[i]);
			}
		}

		// Shrink the normal draw range so fading levels are only drawn by the fading pass. Levels only ever fade at the
		// edges of the visible range, so trimming the ends is enough.
		int firstVisibleLevel = minLevel;
		int lastVisibleLevel = maxLevel;
		while (firstVisibleLevel <= lastVisibleLevel && (levelMask & (1 << firstVisibleLevel)) != 0)
			++firstVisibleLevel;
		while (lastVisibleLevel >= firstVisibleLevel && (levelMask & (1 << lastVisibleLevel)) != 0)
			--lastVisibleLevel;

		ctx.minLevel = firstVisibleLevel;
		ctx.maxLevel = lastVisibleLevel;
	}

	public void drawOpaque(Zone zone, WorldViewContext ctx) {
		draw(zone, ctx, 0, 0, false);
	}

	public void drawAlpha(Zone zone, int zx, int zz, WorldViewContext ctx) {
		draw(zone, ctx, zx, zz, true);
	}

	private void draw(Zone zone, WorldViewContext ctx, int zx, int zz, boolean alpha) {
		final State state = zone.fade;
		if (state == null || !state.initialized || state.updatedFrame != plugin.frame)
			return;

		if (alpha) {
			// Fading geometry can lie outside the visible level range, so only draw it once per zone per frame
			if (state.alphaFrame == plugin.frame || (state.roofBucketsMask == 0 && levelMask == 0))
				return;
			state.alphaFrame = plugin.frame;
		}

		// Fading levels are drawn in full, in their own bucket
		int fadingLevels = 0;
		for (int level = 0; level < MAX_Z; ++level) {
			final int bucket = levelBuckets[level];
			if (bucket == -1)
				continue;
			fadingLevels |= 1 << level;
			if (level < ctx.minLevel || level > ctx.maxLevel) {
				if (alpha) {
					zone.renderAlphaLevel(cmds[bucket], zx, zz, level);
				} else {
					zone.renderOpaqueLevel(cmds[bucket], level);
				}
			}
		}

		for (int buckets = state.roofBucketsMask; buckets != 0; buckets &= buckets - 1) {
			final int bucket = Integer.numberOfTrailingZeros(buckets);

			fadingRoofIds.clear();
			for (int i = 0; i < state.roofCount; ++i)
				if (state.roofBucketAt(i) == bucket)
					fadingRoofIds.add(state.roofIds[i]);

			final CommandBuffer cmd = cmds[bucket];
			for (int level = 0; level < MAX_Z; ++level) {
				if ((fadingLevels & (1 << level)) != 0)
					continue;
				if (alpha) {
					zone.renderAlphaRoofs(cmd, zx, zz, level, fadingRoofIds);
				} else {
					zone.renderOpaqueRoofs(cmd, level, fadingRoofIds);
				}
			}
		}
	}

	public void execute(RenderState renderState) {
		boolean drew = false;
		for (int bucket = 0; bucket < MAX_FADES; ++bucket) {
			final CommandBuffer cmd = cmds[bucket];
			if (cmd == null || cmd.isEmpty())
				continue;

			renderer.sceneDiscardProgram.use();
			renderer.sceneDiscardProgram.uniRoofFade.set(getBucketFade(bucket));
			cmd.execute(renderState);
			drew = true;
		}

		if (drew)
			renderer.sceneDiscardProgram.uniRoofFade.set(0.0f);
	}

	private State updateZone(Zone zone, int level) {
		State state = zone.fade;
		if (state == null) {
			state = pool.isEmpty() ? new State() : pool.remove(pool.size() - 1);
			state.zone = zone;
			state.lastUsedFrame = plugin.frame;
			zone.fade = state;
			inFlight.add(state);
		}

		// Don't advance twice in the same frame
		if (state.updatedFrame == plugin.frame)
			return state;

		final boolean resumed = plugin.frame - state.lastUsedFrame > RESUME_GAP_FRAMES;
		state.lastUsedFrame = state.updatedFrame = plugin.frame;

		final boolean levelChanged = state.lastLevel != level;
		state.lastLevel = level;

		final float duration = plugin.configDitherFadeRoofDuration / 1000f;
		final float delay = plugin.configDitherFadeRoofDelay / 1000f;

		// Snap rather than fade on the first frame so that already hidden geometry doesn't flash, when the player changes
		// level, since the roofs hiding is then a consequence of that rather than of walking under or out from one, and
		// when the zone hasn't been drawn for a while, since what it was fading is out of date by then.
		final boolean snap = !state.initialized || levelChanged || resumed || duration <= 0;

		final IntHashSet prevHidden = state.hidden;
		final IntHashSet hidden = state.prevHidden;
		state.hidden = hidden;
		state.prevHidden = prevHidden;

		if (!snap)
			state.advanceDelays(plugin.deltaTime);

		hidden.clear();
		if (zone.rids != null && (!baseHidden.isEmpty() || !prevHidden.isEmpty())) {
			for (int roofLevel = level + 1; roofLevel < MAX_Z; ++roofLevel) {
				for (int rid : zone.rids[roofLevel]) {
					if (rid <= 0)
						continue;
					final boolean isHidden = baseHidden.contains(rid);
					if (isHidden)
						hidden.add(rid);
					if (snap || isHidden == prevHidden.contains(rid))
						continue;
					state.setRoofTarget(rid, isHidden, delay);
				}
			}
		}

		if (snap) {
			state.initialized = true;
			state.clearFades();
		} else {
			state.advanceFades(plugin.deltaTime / duration);
		}

		return state;
	}

	private void updateLevels(int minLevel, int level, int maxLevel) {
		final int visible = LEVEL_MASK & ((1 << (maxLevel + 1)) - 1) & -(1 << minLevel);
		final float duration = plugin.configDitherFadeRoofDuration / 1000f;
		final boolean snap = lastLevel != level || duration <= 0;

		lastLevel = level;

		if (snap) {
			visibleLevels = visible;
			clearLevelFades();
			return;
		}

		final int entered = visible & ~visibleLevels;
		final int left = visibleLevels & ~visible;
		visibleLevels = visible;

		for (int l = 0; l < MAX_Z; ++l) {
			if ((entered & (1 << l)) != 0)
				setLevelTarget(l, false);
			if ((left & (1 << l)) != 0)
				setLevelTarget(l, true);
		}

		advanceLevels(plugin.deltaTime / duration);

		for (int l = 0; l < MAX_Z; ++l)
			levelBuckets[l] = (levelMask & (1 << l)) != 0 ? bucketOf(levelFade[l]) : -1;
	}

	private void setLevelTarget(int level, boolean hidden) {
		if ((levelMask & (1 << level)) == 0) {
			levelMask |= 1 << level;
			levelFade[level] = hidden ? 0 : 1;
		}
		levelHiddenTarget[level] = hidden;
	}

	private void advanceLevels(float step) {
		for (int level = 0; level < MAX_Z; ++level) {
			if ((levelMask & (1 << level)) == 0)
				continue;

			final boolean hidden = levelHiddenTarget[level];
			final float fade = levelFade[level] + (hidden ? step : -step);
			if (hidden ? fade >= 1 : fade <= 0) {
				levelMask &= ~(1 << level);
			} else {
				levelFade[level] = fade;
			}
		}
	}

	private void clearLevelFades() {
		levelMask = 0;
		Arrays.fill(levelBuckets, -1);
	}

	private static void detach(State state) {
		if (state.zone != null && state.zone.fade == state)
			state.zone.fade = null;
	}

	private static int bucketOf(float fade) {
		return Math.max(0, Math.min(MAX_FADES - 1, (int) (fade * MAX_FADES)));
	}
}
