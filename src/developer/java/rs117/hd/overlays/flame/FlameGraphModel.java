package rs117.hd.overlays.flame;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import rs117.hd.profiling.ProfileSample;
import rs117.hd.profiling.Timer;

public class FlameGraphModel {
	private static final Map<Timer, Timer[]> CHILDREN = new EnumMap<>(Timer.class);

	static {
		CHILDREN.put(Timer.DRAW_FRAME, new Timer[] {
			Timer.DRAW_PRESCENE,
			Timer.DRAW_SCENE,
			Timer.DRAW_POSTSCENE,
			Timer.DRAW_TILED_LIGHTING,
			Timer.DRAW_SUBMIT,
			Timer.UPDATE_SCENE,
			Timer.UPDATE_ENVIRONMENT,
			Timer.UPDATE_LIGHTS,
			Timer.UPDATE_AREA_HIDING,
			Timer.VISIBILITY_CHECK,
			Timer.GARBAGE_COLLECTION,
			Timer.REPLACE_FISHING_SPOTS,
			Timer.CHARACTER_DISPLACEMENT,
		});
		CHILDREN.put(Timer.DRAW_SCENE, new Timer[] {
			Timer.DRAW_ZONE_OPAQUE,
			Timer.DRAW_ZONE_ALPHA,
			Timer.DRAW_PASS,
			Timer.DRAW_DYNAMIC,
			Timer.DRAW_TEMP,
			Timer.GET_MODEL,
			Timer.DRAW_RENDERABLE,
			Timer.CLICKBOX_CHECK,
			Timer.MODEL_BATCHING,
			Timer.MODEL_PUSHING,
			Timer.MODEL_PUSHING_VERTEX,
			Timer.MODEL_PUSHING_NORMAL,
			Timer.MODEL_PUSHING_UV,
			Timer.IMPOSTOR_TRACKING,
		});
		CHILDREN.put(Timer.RENDER_FRAME, new Timer[] {
			Timer.CLEAR_SCENE,
			Timer.RENDER_SCENE,
			Timer.RENDER_SHADOWS,
			Timer.RENDER_TILED_LIGHTING,
			Timer.UPLOAD_GEOMETRY,
			Timer.UPLOAD_UI,
			Timer.COMPUTE,
			Timer.UNMAP_ROOT_CTX,
			Timer.RENDER_UI,
		});
	}

	public static class Node {
		private final String id;
		private final String name;
		private final Color color;
		@Nullable
		private final Timer timer;
		private final String category;
		private final List<Node> children = new ArrayList<>();
		private long valueNs;
		private long maxNs;
		@Nullable
		private Node parent;

		Node(String id, String name, Color color, @Nullable Timer timer, String category) {
			this.id = id;
			this.name = name;
			this.color = color;
			this.timer = timer;
			this.category = category;
		}

		public String getId() { return id; }
		public String getName() { return name; }
		public Color getColor() { return color; }
		@Nullable public Timer getTimer() { return timer; }
		public String getCategory() { return category; }
		public long getValueNs() { return valueNs; }
		public long getMaxNs() { return maxNs; }
		@Nullable public Node getParent() { return parent; }

		public List<Node> getChildren() {
			return Collections.unmodifiableList(children);
		}

		void addChild(Node child) {
			child.parent = this;
			children.add(child);
		}

		public long exclusiveNs() {
			long childSum = 0;
			for (Node child : children)
				childSum += child.valueNs;
			return Math.max(0, valueNs - childSum);
		}

		public double valueMs() {
			return valueNs / 1e6;
		}

		public double maxMs() {
			return maxNs / 1e6;
		}
	}

	public static class TopRow {
		private final String name;
		private final String category;
		private final Color color;
		@Nullable
		private final Timer timer;
		private final double avgMs;
		private final double maxMs;
		private final double percent;

		TopRow(String name, String category, Color color, @Nullable Timer timer, double avgMs, double maxMs, double percent) {
			this.name = name;
			this.category = category;
			this.color = color;
			this.timer = timer;
			this.avgMs = avgMs;
			this.maxMs = maxMs;
			this.percent = percent;
		}

		public String getName() { return name; }
		public String getCategory() { return category; }
		public Color getColor() { return color; }
		@Nullable public Timer getTimer() { return timer; }
		public double getAvgMs() { return avgMs; }
		public double getMaxMs() { return maxMs; }
		public double getPercent() { return percent; }
	}

	private final List<Node> categories;
	private final List<TopRow> topRows;

	private FlameGraphModel(List<Node> categories, List<TopRow> topRows) {
		this.categories = categories;
		this.topRows = topRows;
	}

	public List<Node> getCategories() {
		return categories;
	}

	public Node getRoot() {
		return categories.isEmpty()
			? new Node("empty", "Frame", new Color(80, 80, 80), null, "Frame")
			: categories.get(0);
	}

	public List<TopRow> getTopRows() {
		return topRows;
	}

	public static FlameGraphModel fromFrames(List<ProfileSample> frames) {
		if (frames == null || frames.isEmpty()) {
			return new FlameGraphModel(Collections.emptyList(), Collections.emptyList());
		}

		int n = frames.size();
		long[] sum = new long[Timer.TIMERS.length];
		long[] max = new long[Timer.TIMERS.length];
		for (ProfileSample frame : frames) {
			for (int i = 0; i < Timer.TIMERS.length; i++) {
				long v = i < frame.timers.length ? frame.timers[i] : 0;
				sum[i] += v;
				if (v > max[i])
					max[i] = v;
			}
		}
		long[] avg = new long[Timer.TIMERS.length];
		for (int i = 0; i < avg.length; i++)
			avg[i] = sum[i] / n;

		boolean[] used = new boolean[Timer.TIMERS.length];

		Node cpuRoot = buildBranch("cpu", "CPU", Timer.DRAW_FRAME.color, Timer.DRAW_FRAME, "CPU", avg, max, used);
		Node gpuRoot = buildBranch("gpu", "GPU", Timer.RENDER_FRAME.color, Timer.RENDER_FRAME, "GPU", avg, max, used);

		Node asyncRoot = new Node("async", "Async", new Color(200, 160, 80), null, "Async");
		for (Timer t : Timer.TIMERS) {
			if (!t.isAsyncCpuTimer())
				continue;
			used[t.ordinal()] = true;
			long v = avg[t.ordinal()];
			if (v <= 0)
				continue;
			Node leaf = new Node(t.name(), t.name, t.color, t, "Async");
			leaf.valueNs = v;
			leaf.maxNs = max[t.ordinal()];
			asyncRoot.addChild(leaf);
			asyncRoot.valueNs += v;
			asyncRoot.maxNs = Math.max(asyncRoot.maxNs, leaf.maxNs);
		}
		sortChildren(asyncRoot);

		for (Timer t : Timer.TIMERS) {
			if (used[t.ordinal()] || t.isGpuTimer() || t.isAsyncCpuTimer())
				continue;
			long v = avg[t.ordinal()];
			if (v <= 0)
				continue;
			used[t.ordinal()] = true;
			Node leaf = new Node(t.name(), t.name, t.color, t, "CPU");
			leaf.valueNs = v;
			leaf.maxNs = max[t.ordinal()];
			cpuRoot.addChild(leaf);
			cpuRoot.maxNs = Math.max(cpuRoot.maxNs, leaf.maxNs);
		}
		long cpuChildSum = 0;
		for (Node c : cpuRoot.children)
			cpuChildSum += c.valueNs;
		if (cpuChildSum > cpuRoot.valueNs)
			cpuRoot.valueNs = cpuChildSum;
		sortChildren(cpuRoot);

		List<Node> categories = new ArrayList<>(3);
		if (cpuRoot.valueNs > 0 || !cpuRoot.children.isEmpty())
			categories.add(cpuRoot);
		if (asyncRoot.valueNs > 0 || !asyncRoot.children.isEmpty())
			categories.add(asyncRoot);
		if (gpuRoot.valueNs > 0 || !gpuRoot.children.isEmpty())
			categories.add(gpuRoot);

		List<TopRow> rows = new ArrayList<>();
		long denom = Math.max(1, Math.max(avg[Timer.DRAW_FRAME.ordinal()], avg[Timer.RENDER_FRAME.ordinal()]));
		for (Timer t : Timer.TIMERS) {
			long v = avg[t.ordinal()];
			if (v <= 0)
				continue;
			String cat = t.isGpuTimer() ? "GPU" : t.isAsyncCpuTimer() ? "Async" : "CPU";
			rows.add(new TopRow(
				t.name,
				cat,
				t.color,
				t,
				v / 1e6,
				max[t.ordinal()] / 1e6,
				100.0 * v / denom
			));
		}
		rows.sort((a, b) -> Double.compare(b.getAvgMs(), a.getAvgMs()));
		return new FlameGraphModel(categories, rows);
	}

	private static Node buildBranch(
		String id,
		String name,
		Color color,
		Timer rootTimer,
		String category,
		long[] avg,
		long[] max,
		boolean[] used
	) {
		Node root = new Node(id, name, color, rootTimer, category);
		root.valueNs = avg[rootTimer.ordinal()];
		root.maxNs = max[rootTimer.ordinal()];
		used[rootTimer.ordinal()] = true;
		addDeclaredChildren(root, rootTimer, avg, max, used, category);

		long childSum = 0;
		for (Node c : root.children)
			childSum += c.valueNs;
		if (childSum > root.valueNs)
			root.valueNs = childSum;
		sortChildren(root);
		return root;
	}

	private static void addDeclaredChildren(
		Node parent,
		Timer parentTimer,
		long[] avg,
		long[] max,
		boolean[] used,
		String category
	) {
		Timer[] kids = CHILDREN.get(parentTimer);
		if (kids == null)
			return;

		for (Timer childTimer : kids) {
			if (used[childTimer.ordinal()])
				continue;
			used[childTimer.ordinal()] = true;
			long v = avg[childTimer.ordinal()];
			Node child = new Node(childTimer.name(), childTimer.name, childTimer.color, childTimer, category);
			child.valueNs = v;
			child.maxNs = max[childTimer.ordinal()];
			addDeclaredChildren(child, childTimer, avg, max, used, category);

			long childSum = 0;
			for (Node c : child.children)
				childSum += c.valueNs;
			if (childSum > child.valueNs)
				child.valueNs = childSum;

			if (child.valueNs > 0 || !child.children.isEmpty())
				parent.addChild(child);
		}
	}

	private static void sortChildren(Node node) {
		node.children.sort((a, b) -> Long.compare(b.valueNs, a.valueNs));
		for (Node child : node.children)
			sortChildren(child);
	}

	@Nullable
	public Node findById(String id) {
		for (Node category : categories) {
			Node hit = findById(category, id);
			if (hit != null)
				return hit;
		}
		return null;
	}

	@Nullable
	private static Node findById(Node node, String id) {
		if (node.id.equals(id))
			return node;
		for (Node child : node.children) {
			Node hit = findById(child, id);
			if (hit != null)
				return hit;
		}
		return null;
	}

	public List<Node> breadcrumb(Node zoomed) {
		List<Node> path = new ArrayList<>();
		for (Node n = zoomed; n != null; n = n.parent)
			path.add(0, n);
		return path;
	}

	public boolean matchesFilter(Node node, @Nullable String filter) {
		if (filter == null || filter.isEmpty())
			return true;
		String q = filter.trim().toLowerCase(Locale.ROOT);
		if (node.name.toLowerCase(Locale.ROOT).contains(q))
			return true;
		if (node.category.toLowerCase(Locale.ROOT).contains(q))
			return true;
		for (Node child : node.children) {
			if (matchesFilter(child, filter))
				return true;
		}
		return false;
	}
}
