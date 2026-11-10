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
public class ZoneRoofFades {
	private static final int MAX_FADES = 32;
	private static final int STALE_FRAMES = 120;
	private static final int RESUME_GAP_FRAMES = 4;
	private static final int LEVEL_MASK = (1 << MAX_Z) - 1;

	private final CommandBuffer[] cmds = new CommandBuffer[MAX_FADES];
	private final List<State> pool = new ArrayList<>();
	private final List<State> inFlight = new ArrayList<>();

	@Inject
	private HdPlugin plugin;

	@Inject
	private ZoneRenderer renderer;

	public static class Fade {
		public static final Fade NONE = new Fade();

		// Bit mask of the buckets which contain something to draw
		public int buckets;

		int roofCount;
		int[] roofIds = new int[8];
		int[] roofBuckets = new int[8];
		final int[] levelBuckets = new int[MAX_Z];

		// Roofs which are no longer supposed to be hidden, but are being kept hidden until their fade in delay has passed
		int heldCount;
		int[] heldIds = new int[8];

		Fade() {
			Arrays.fill(levelBuckets, -1);
		}

		public int roofBucket(int rid) {
			if (rid <= 0)
				return -1;
			for (int i = 0; i < roofCount; ++i)
				if (roofIds[i] == rid)
					return roofBuckets[i];
			return -1;
		}

		public int levelBucket(int level) {
			return level >= 0 && level < MAX_Z ? levelBuckets[level] : -1;
		}

		public boolean isHeld(int rid) {
			for (int i = 0; i < heldCount; ++i)
				if (heldIds[i] == rid)
					return true;
			return false;
		}

		public boolean hasHeld() {
			return heldCount > 0;
		}

		public boolean hasRoofs() {
			return roofCount > 0;
		}
	}

	static class State extends Fade {
		Zone zone;
		boolean initialized;
		int lastUsedFrame;
		int updatedFrame = -1;
		int alphaFrame = -1;
		int lastLevel = -1;
		int visibleLevels = LEVEL_MASK;
		IntHashSet hidden = new IntHashSet();
		IntHashSet prevHidden = new IntHashSet();

		// Roofs in the middle of a fade. fade: 0 = visible, 1 = hidden. target: the value it's heading towards.
		float[] roofFade = new float[8];
		int[] roofTargets = new int[8];

		// How long each held roof has been waiting, in seconds
		float[] heldTime = new float[8];

		// Levels in the middle of a fade
		int levelMask;
		final float[] levelFade = new float[MAX_Z];
		final int[] levelTargets = new int[MAX_Z];

		void clear() {
			zone = null;
			initialized = false;
			updatedFrame = -1;
			alphaFrame = -1;
			lastLevel = -1;
			visibleLevels = LEVEL_MASK;
			hidden.clear();
			prevHidden.clear();
			clearFades();
		}

		void clearFades() {
			roofCount = 0;
			heldCount = 0;
			levelMask = 0;
			buckets = 0;
			Arrays.fill(levelBuckets, -1);
		}

		int indexOfRoof(int rid) {
			for (int i = 0; i < roofCount; ++i)
				if (roofIds[i] == rid)
					return i;
			return -1;
		}

		void holdRoof(int rid) {
			if (isHeld(rid))
				return;
			if (heldCount == heldIds.length) {
				heldIds = Arrays.copyOf(heldIds, heldCount * 2);
				heldTime = Arrays.copyOf(heldTime, heldCount * 2);
			}
			heldIds[heldCount] = rid;
			heldTime[heldCount] = 0;
			++heldCount;
		}

		boolean releaseHeld(int rid) {
			for (int i = 0; i < heldCount; ++i) {
				if (heldIds[i] == rid) {
					int last = --heldCount;
					heldIds[i] = heldIds[last];
					heldTime[i] = heldTime[last];
					return true;
				}
			}
			return false;
		}

		void advanceHeld(float deltaTime, float delay) {
			for (int i = heldCount - 1; i >= 0; --i) {
				float time = heldTime[i] + deltaTime;
				if (time < delay) {
					heldTime[i] = time;
					continue;
				}

				int rid = heldIds[i];
				int last = --heldCount;
				heldIds[i] = heldIds[last];
				heldTime[i] = heldTime[last];
				setRoofTarget(rid, 0);
			}
		}

		void setRoofTarget(int rid, int target) {
			for (int i = 0; i < roofCount; ++i) {
				if (roofIds[i] == rid) {
					// Reverse from wherever it currently is
					roofTargets[i] = target;
					return;
				}
			}

			if (roofCount == roofIds.length) {
				int capacity = roofCount * 2;
				roofIds = Arrays.copyOf(roofIds, capacity);
				roofBuckets = Arrays.copyOf(roofBuckets, capacity);
				roofFade = Arrays.copyOf(roofFade, capacity);
				roofTargets = Arrays.copyOf(roofTargets, capacity);
			}

			roofIds[roofCount] = rid;
			roofFade[roofCount] = 1 - target;
			roofTargets[roofCount] = target;
			++roofCount;
		}

		void setLevelTarget(int level, int target) {
			if ((levelMask & (1 << level)) == 0) {
				levelMask |= 1 << level;
				levelFade[level] = 1 - target;
			}
			levelTargets[level] = target;
		}

		void advance(float step) {
			for (int i = roofCount - 1; i >= 0; --i) {
				int target = roofTargets[i];
				float f = roofFade[i] + (target == 1 ? step : -step);
				if (target == 1 ? f >= 1 : f <= 0) {
					// Finished, so it's either fully hidden or fully visible again, and no longer needs tracking
					int last = --roofCount;
					roofIds[i] = roofIds[last];
					roofFade[i] = roofFade[last];
					roofTargets[i] = roofTargets[last];
				} else {
					roofFade[i] = f;
				}
			}

			for (int level = 0; level < MAX_Z; ++level) {
				if ((levelMask & (1 << level)) == 0)
					continue;
				int target = levelTargets[level];
				float f = levelFade[level] + (target == 1 ? step : -step);
				if (target == 1 ? f >= 1 : f <= 0) {
					levelMask &= ~(1 << level);
				} else {
					levelFade[level] = f;
				}
			}
		}
	}

	public void initialize() {
		for (int i = 0; i < MAX_FADES; ++i)
			cmds[i] = new CommandBuffer("FadingRoof" + i);
	}

	public void destroy() {
		for (State state : inFlight) {
			if (state.zone != null && state.zone.fade == state)
				state.zone.fade = null;
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
			State state = inFlight.get(i);
			if (plugin.frame - state.lastUsedFrame <= STALE_FRAMES)
				continue;

			int last = inFlight.size() - 1;
			inFlight.set(i, inFlight.get(last));
			inFlight.remove(last);

			if (state.zone != null && state.zone.fade == state)
				state.zone.fade = null;
			state.clear();
			pool.add(state);
		}
	}

	public static float getBucketFade(int bucket) {
		return bucket / (float) (MAX_FADES - 1);
	}

	public CommandBuffer getCommandBuffer(int bucket) {
		return cmds[bucket];
	}

	public Fade update(Zone zone, WorldViewContext ctx) {
		if (!plugin.configDitherFadeRoofs)
			return Fade.NONE;

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

		final int prevVisible = state.visibleLevels;
		final int visible = LEVEL_MASK & ((1 << (ctx.maxLevel + 1)) - 1) & ~((1 << ctx.minLevel) - 1);
		state.visibleLevels = visible;

		final boolean levelChanged = state.lastLevel != ctx.level;
		state.lastLevel = ctx.level;

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

		if (!snap && state.heldCount > 0)
			state.advanceHeld(plugin.deltaTime, delay);

		hidden.clear();
		if (zone.rids != null && (!ctx.hideRoofIds.isEmpty() || !prevHidden.isEmpty())) {
			for (int level = ctx.level + 1; level < MAX_Z; ++level) {
				for (int rid : zone.rids[level]) {
					if (rid <= 0)
						continue;
					boolean isHidden = ctx.hideRoofIds.contains(rid);
					if (isHidden)
						hidden.add(rid);
					if (snap || isHidden == prevHidden.contains(rid))
						continue;

					if (!isHidden) {
						// Hold off on fading in a roof that's fully hidden. One that's already partway through fading out
						// just turns around instead.
						if (delay > 0 && state.indexOfRoof(rid) == -1)
							state.holdRoof(rid);
						else
							state.setRoofTarget(rid, 0);
					} else if (!state.releaseHeld(rid)) {
						// Hidden again before the delay ran out, so it never stopped being hidden and there's nothing to fade
						state.setRoofTarget(rid, 1);
					}
				}
			}
		}

		if (snap) {
			state.initialized = true;
			state.clearFades();
		} else {
			final int entered = visible & ~prevVisible;
			final int left = prevVisible & ~visible;
			for (int level = 0; level < MAX_Z; ++level) {
				if ((entered & (1 << level)) != 0)
					state.setLevelTarget(level, 0);
				if ((left & (1 << level)) != 0)
					state.setLevelTarget(level, 1);
			}
			state.advance(plugin.deltaTime / duration);
		}

		return publish(state);
	}

	public Fade current(Zone zone) {
		State state = zone.fade;
		if (!plugin.configDitherFadeRoofs || state == null || !state.initialized || state.updatedFrame != plugin.frame)
			return Fade.NONE;
		return state;
	}
	
	public boolean claimAlpha(Zone zone) {
		State state = zone.fade;
		if (!plugin.configDitherFadeRoofs || state == null || !state.initialized)
			return false;
		if (state.buckets == 0 || state.alphaFrame == plugin.frame)
			return false;

		state.alphaFrame = plugin.frame;
		return true;
	}

	public void execute(RenderState renderState) {
		boolean drew = false;
		for (int step = 0; step < MAX_FADES; ++step) {
			CommandBuffer cmd = cmds[step];
			if (cmd == null || cmd.isEmpty())
				continue;

			renderer.sceneDiscardProgram.use();
			renderer.sceneDiscardProgram.uniRoofFade.set(step / (float) (MAX_FADES - 1));
			cmd.execute(renderState);
			drew = true;
		}

		if (drew)
			renderer.sceneDiscardProgram.uniRoofFade.set(0.0f);
	}

	private static int bucketOf(float fade) {
		return Math.max(0, Math.min(MAX_FADES - 1, (int) (fade * MAX_FADES)));
	}

	private State publish(State state) {
		int buckets = 0;

		for (int i = 0; i < state.roofCount; ++i) {
			int bucket = bucketOf(state.roofFade[i]);
			state.roofBuckets[i] = bucket;
			buckets |= 1 << bucket;
		}

		for (int level = 0; level < MAX_Z; ++level) {
			if ((state.levelMask & (1 << level)) != 0) {
				int bucket = bucketOf(state.levelFade[level]);
				state.levelBuckets[level] = bucket;
				buckets |= 1 << bucket;
			} else {
				state.levelBuckets[level] = -1;
			}
		}

		state.buckets = buckets;
		return state;
	}
}