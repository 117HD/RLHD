package rs117.hd.renderer.zone;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import rs117.hd.HdPlugin;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.collections.IntHashSet;

import static net.runelite.api.Constants.*;
import static rs117.hd.utils.MathUtils.*;

@Singleton
public class ZoneRoofFades {
	private static final int MAX_FADES = 16;
	private static final int STALE_FRAMES = 120;
	private static final int LEVEL_MASK = (1 << MAX_Z) - 1;

	private static final Fade NONE = new Fade();

	private final CommandBuffer[] cmds = new CommandBuffer[MAX_FADES];
	private final List<State> pool = new ArrayList<>();
	private final List<State> inFlight = new ArrayList<>();

	@Inject
	private HdPlugin plugin;

	@Inject
	private ZoneRenderer renderer;

	public static class Fade {
		public CommandBuffer cmd;
		public IntHashSet roofs;
		public int levels;
	}

	static class State extends Fade {
		Zone zone;
		float fade;
		boolean initialized;
		boolean fadingOut;
		int lastUsedFrame;
		int alphaFrame = -1;
		int visibleLevels = LEVEL_MASK;
		int fadingLevels;
		IntHashSet hidden = new IntHashSet();
		IntHashSet prevHidden = new IntHashSet();
		final IntHashSet fadingRoofs = new IntHashSet();

		void clear() {
			zone = null;
			fade = 0;
			initialized = false;
			fadingOut = false;
			alphaFrame = -1;
			visibleLevels = LEVEL_MASK;
			fadingLevels = 0;
			hidden.clear();
			prevHidden.clear();
			fadingRoofs.clear();
		}
	}

	public void initialize() {
		for (int i = 0; i < MAX_FADES; ++i)
			cmds[i] = new CommandBuffer("FadingRoof" + i);
	}

	public void reset() {
		for (CommandBuffer cmd : cmds)
			cmd.reset();

		// Release states of zones that haven't been drawn in a while
		for (int i = inFlight.size() - 1; i >= 0; --i) {
			State state = inFlight.get(i);
			if (plugin.frame - state.lastUsedFrame <= STALE_FRAMES)
				continue;

			int last = inFlight.size() - 1;
			inFlight.set(i, inFlight.get(last));
			inFlight.remove(last);

			state.zone.fade = null;
			state.clear();
			pool.add(state);
		}
	}

	public Fade update(Zone zone, WorldViewContext ctx) {
		if (!plugin.configDitherFadeRoofs)
			return NONE;

		State state = zone.fade;
		if (state == null) {
			state = pool.isEmpty() ? new State() : pool.remove(pool.size() - 1);
			state.zone = zone;
			zone.fade = state;
			inFlight.add(state);
		}
		state.lastUsedFrame = plugin.frame;

		final int prevVisible = state.visibleLevels;
		final int visible = LEVEL_MASK & ((1 << (ctx.maxLevel + 1)) - 1) & ~((1 << ctx.minLevel) - 1);
		state.visibleLevels = visible;

		final IntHashSet prevHidden = state.hidden;
		final IntHashSet hidden = state.prevHidden;
		state.hidden = hidden;
		state.prevHidden = prevHidden;

		hidden.clear();
		if (zone.rids != null && !ctx.hideRoofIds.isEmpty()) {
			for (int level = ctx.level + 1; level < MAX_Z; ++level)
				for (int rid : zone.rids[level])
					if (rid > 0 && ctx.hideRoofIds.contains(rid))
						hidden.add(rid);
		}

		final float duration = plugin.configDitherFadeRoofDuration / 1000f;

		// Snap on the first frame so already hidden geometry doesn't flash
		if (!state.initialized || duration <= 0) {
			state.initialized = true;
			state.fadingLevels = 0;
			state.fadingRoofs.clear();
			state.fadingRoofs.addAll(hidden);
			state.fade = hidden.isEmpty() ? 0 : 1;
			return publish(state);
		}

		final int entered = visible & ~prevVisible;
		final int left = prevVisible & ~visible;
		final boolean newlyHidden = left != 0 || !prevHidden.containsAll(hidden);
		final boolean newlyVisible = entered != 0 || !hidden.containsAll(prevHidden);

		if (newlyHidden || newlyVisible) {
			// If both happen in the same frame, fade out
			state.fadingOut = newlyHidden;
			state.fadingRoofs.addAll(prevHidden);
			state.fadingRoofs.addAll(hidden);
			state.fadingLevels |= entered | left;
		}

		final float step = plugin.deltaTime / duration;
		state.fade = saturate(state.fade + (state.fadingOut ? step : -step));

		if (state.fade == 0 || state.fade == 1) {
			state.fadingLevels = 0;
			if (state.fade == 0)
				state.fadingRoofs.clear();
		}

		return publish(state);
	}

	public Fade current(Zone zone) {
		State state = zone.fade;
		return plugin.configDitherFadeRoofs && state != null && state.initialized ? state : NONE;
	}

	public boolean claimAlpha(Zone zone) {
		State state = zone.fade;
		if (!plugin.configDitherFadeRoofs || state == null || !state.initialized)
			return false;
		if (state.fadingRoofs.isEmpty() && state.fadingLevels == 0 || state.alphaFrame == plugin.frame)
			return false;

		state.alphaFrame = plugin.frame;
		return true;
	}

	public void execute(RenderState renderState) {
		boolean drew = false;
		for (int step = 0; step < MAX_FADES; ++step) {
			CommandBuffer cmd = cmds[step];
			if (cmd.isEmpty())
				continue;

			renderer.sceneDiscardProgram.use();
			renderer.sceneDiscardProgram.uniRoofFade.set(step / (float) (MAX_FADES - 1));
			cmd.execute(renderState);
			drew = true;
		}

		if (drew)
			renderer.sceneDiscardProgram.uniRoofFade.set(0.0f);
	}

	private State publish(State state) {
		state.cmd = state.fade > 0 && state.fade < 1 ? cmds[min(MAX_FADES - 1, (int) (state.fade * MAX_FADES))] : null;
		state.roofs = state.fadingRoofs.isEmpty() ? null : state.fadingRoofs;
		state.levels = state.fadingLevels;
		return state;
	}
}