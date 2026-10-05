package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import javax.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter;
import rs117.hd.utils.jobs.Job;

@Slf4j
public final class EboAlphaWriterJob extends Job {

	@Inject
	private FrameTimer frameTimer;

	private final ArrayDeque<ZoneAlphaModelCollection> free = new ArrayDeque<>();
	private final ArrayDeque<ZoneAlphaModelCollection> pending = new ArrayDeque<>();

	public final WorldViewContext context;

	public EboAlphaWriterJob(WorldViewContext context) {
		super(true);
		this.context = context;
	}

	public ZoneAlphaModelCollection obtain() {
		ZoneAlphaModelCollection collection = free.poll();
		if (collection == null)
			collection = new ZoneAlphaModelCollection(this);
		collection.count = 0;
		return collection;
	}

	@Override
	protected void onRun() {
		long start = System.nanoTime();
		ZoneAlphaModelCollection collection;
		while ((collection = pending.poll()) != null) {
			try {
				final IntBuffer eboAlphaBuffer = collection.eboAlphaView.getBuffer();
				if (eboAlphaBuffer == null)
					continue;

				int pendingOffset = -1;
				int pendingLen = 0;

				for(int i = 0; i < collection.count; i++) {
					final int base = i * 2;
					final int offset = collection.offsetCounts[base];
					final int count = collection.offsetCounts[base + 1];
					if (count <= 0)
						continue;

					if (pendingLen > 0 && offset == pendingOffset + pendingLen) {
						pendingLen += count;
						continue;
					}

					if (pendingLen > 0 && !flush(eboAlphaBuffer, pendingOffset, pendingLen))
						return;

					pendingOffset = offset;
					pendingLen = count;
				}

				if (pendingLen > 0)
					flush(eboAlphaBuffer, pendingOffset, pendingLen);
			} finally {
				collection.eboAlphaView = null;
				free.add(collection);
			}
		}
		frameTimer.add(Timer.STATIC_ALPHA_UPLOAD, System.nanoTime() - start);
	}

	private boolean flush(IntBuffer eboAlphaBuffer, int offset, int len) {
		if (eboAlphaBuffer.remaining() < len) {
			log.warn("Not enough space in eboAlphaBuffer for alpha faces");
			return false;
		}
		eboAlphaBuffer.put(context.eboAlphaIndices, offset, len);
		return true;
	}

	@RequiredArgsConstructor
	public static final class ZoneAlphaModelCollection {
		private final EboAlphaWriterJob owner;
		private GLMappedBufferIntWriter.ReservedView eboAlphaView;
		private int[] offsetCounts = new int[2];
		private int count;

		public void add(Zone.AlphaModel model) {
			final int base = count++ * 2;
			if(base >= offsetCounts.length)
				offsetCounts = Arrays.copyOf(offsetCounts, offsetCounts.length * 2);

			offsetCounts[base] = model.sortedIndiciesOffset;
			offsetCounts[base + 1] = model.sortedIndiciesCount;
		}

		public void queue(int alphaCount) {
			if(alphaCount <= 0 || count == 0) {
				owner.free.add(this);
				return;
			}

			eboAlphaView = ZoneRenderer.eboAlphaWriter.reserve(alphaCount);
			owner.pending.add(this);
		}
	}
}
