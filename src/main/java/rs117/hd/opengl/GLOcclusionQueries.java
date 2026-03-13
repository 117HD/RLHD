package rs117.hd.opengl;

import rs117.hd.utils.Destructible;

import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL15.GL_SAMPLES_PASSED;
import static org.lwjgl.opengl.GL15.glBeginQuery;
import static org.lwjgl.opengl.GL15.glDeleteQueries;
import static org.lwjgl.opengl.GL15.glEndQuery;
import static org.lwjgl.opengl.GL15.glGenQueries;
import static org.lwjgl.opengl.GL15.glGetQueryObjecti;
import static org.lwjgl.opengl.GL33.GL_ANY_SAMPLES_PASSED;
import static org.lwjgl.opengl.GL33.GL_QUERY_NO_WAIT;
import static org.lwjgl.opengl.GL33.GL_QUERY_WAIT;
import static org.lwjgl.opengl.GL33.glBeginConditionalRender;
import static org.lwjgl.opengl.GL33.glEndConditionalRender;
import static rs117.hd.utils.MathUtils.*;

public final class GLOcclusionQueries implements Destructible {

	private static final long ID_SHIFT = 32;
	private static final long ISSUED_BIT = 1L << 31;
	private static final long RESULT_MASK = 0x7FFFFFFFL;

	private long query0, query1, query2;

	private int currentQuery;
	private int currentTarget;

	private static long packId(int id) { return ((long) id) << ID_SHIFT; }

	private static int getId(long packed) { return (int) (packed >>> ID_SHIFT); }

	private static boolean isIssued(long packed) { return (packed & ISSUED_BIT) != 0; }

	private static long setIssued(long packed) { return packed | ISSUED_BIT; }

	private static long setResult(long packed, int result) { return (packed & ~RESULT_MASK) | (result & RESULT_MASK); }

	private static int getResult(long packed) { return (int) (packed & RESULT_MASK); }

	public void initialize() {
		query0 = packId(glGenQueries());
		query1 = packId(glGenQueries());
		query2 = packId(glGenQueries());
	}

	private long getSlot(int idx) {
		return idx == 0 ? query0 : idx == 1 ? query1 : query2;
	}

	private void setSlot(int idx, long value) {
		if (idx == 0) query0 = value;
		else if (idx == 1) query1 = value;
		else query2 = value;
	}

	private int readbackIndex() { return (currentQuery + 1) % 3; }

	public void beginQuery(boolean exactSamples) {
		currentTarget = exactSamples ? GL_SAMPLES_PASSED : GL_ANY_SAMPLES_PASSED;
		glBeginQuery(currentTarget, getId(getSlot(currentQuery)));
	}

	public void endQuery() {
		glEndQuery(currentTarget);

		long slot = setIssued(getSlot(currentQuery));
		setSlot(currentQuery, slot);

		currentQuery = (currentQuery + 1) % 3;
	}

	private void readBackResult() {
		int idx = readbackIndex();
		long slot = getSlot(idx);

		if (!isIssued(slot))
			return;

		int id = getId(slot);

		if (glGetQueryObjecti(id, GL_QUERY_RESULT_AVAILABLE) != 0) {
			slot = setResult(slot, glGetQueryObjecti(id, GL_QUERY_RESULT));
			setSlot(idx, slot);
		}
	}

	public int getResult() {
		readBackResult();
		return getResult(getSlot(readbackIndex()));
	}

	public boolean isVisible() { return getResult() > 0; }

	public int getVisiblePixels(int msaaSamples) {
		int result = getResult();
		return msaaSamples > 0 ? result / msaaSamples : result;
	}

	public float getVisibilityRatio(int proxyPixelArea, int msaaSamples) {
		if (proxyPixelArea <= 0)
			return 0f;

		float samples = getResult();
		if(samples == 0)
			return 0f;

		float denom = msaaSamples > 0
			? proxyPixelArea * (float) msaaSamples
			: proxyPixelArea;
		float ratio = samples / denom;
		return ratio < 0f ? 0f : min(ratio, 1f);
	}

	public void beginConditionalRender(boolean wait) {
		int idx = readbackIndex();
		long slot = getSlot(idx);

		if (!isIssued(slot))
			return;

		glBeginConditionalRender(
			getId(slot),
			wait ? GL_QUERY_WAIT : GL_QUERY_NO_WAIT
		);
	}

	public void endConditionalRender() { glEndConditionalRender(); }

	public int getCurrentQuery() { return getId(getSlot(currentQuery)); }

	@Override
	public void destroy() {
		if (query0 != 0) glDeleteQueries(getId(query0));
		if (query1 != 0) glDeleteQueries(getId(query1));
		if (query2 != 0) glDeleteQueries(getId(query2));

		query0 = query1 = query2 = 0;
	}
}