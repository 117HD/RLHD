package rs117.hd.utils.buffer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lwjgl.BufferUtils;
import rs117.hd.utils.Destructible;
import rs117.hd.utils.DestructibleHandler;

import static rs117.hd.utils.buffer.GLBuffer.MAP_INVALIDATE;
import static rs117.hd.utils.buffer.GLBuffer.MAP_UNSYNCHRONIZED;
import static rs117.hd.utils.buffer.GLBuffer.MAP_WRITE;

@RequiredArgsConstructor
public class GLMappedBufferIntWriter implements Destructible {
	private final GLBuffer buffer;

	private final ArrayDeque<ReservedView> freeViews = new ArrayDeque<>();
	private final ArrayList<ReservedView> usedMappedViews = new ArrayList<>();
	private final ArrayList<ReservedView> usedStagingViews = new ArrayList<>();

	private GLMappedBuffer mappedBuffer;
	private int writtenMappedInts;
	private int writtenStagingInts;

	public int getWrittenInts() {
		return writtenMappedInts + writtenStagingInts;
	}

	public void map(boolean sync) {
		mappedBuffer = buffer.map(MAP_WRITE | MAP_INVALIDATE | (sync ? 0 : MAP_UNSYNCHRONIZED));
	}

	public synchronized ReservedView reserve(int sizeInts) {
		assert mappedBuffer.isMapped();

		// Need staging if we've already staged or mapped buffer has no space
		if (writtenStagingInts > 0 || mappedBuffer.intView().remaining() < sizeInts) {
			ReservedView view = new ReservedView();
			view.buffer = BufferUtils.createIntBuffer(sizeInts);
			view.bufferOffsetInts = writtenMappedInts + writtenStagingInts;
			writtenStagingInts += sizeInts;
			usedStagingViews.add(view);
			return view;
		}

		ReservedView view = freeViews.poll();
		if (view == null)
			view = new ReservedView();

		if (view.backing != mappedBuffer.byteView()) {
			view.backing = mappedBuffer.byteView();
			view.buffer = view.backing.asIntBuffer();
		}

		view.buffer.clear();
		view.buffer.position(writtenMappedInts);
		view.buffer.limit(writtenMappedInts + sizeInts);
		view.bufferOffsetInts = writtenMappedInts;

		writtenMappedInts += sizeInts;
		mappedBuffer.intView().position(writtenMappedInts);

		usedMappedViews.add(view);
		return view;
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void finalize() {
		if (!usedStagingViews.isEmpty() || !usedMappedViews.isEmpty() || !freeViews.isEmpty())
			DestructibleHandler.queueLeakedDestruction(this);
	}

	@Override
	public void destroy() {
		if (mappedBuffer != null && mappedBuffer.isMapped())
			mappedBuffer.unmap();
		mappedBuffer = null;

		for (ReservedView view : freeViews) {
			view.backing = null;
			view.buffer = null;
		}
		freeViews.clear();

		for (ReservedView view : usedMappedViews) {
			view.backing = null;
			view.buffer = null;
		}
		usedMappedViews.clear();

		for (ReservedView view : usedStagingViews) {
			view.backing = null;
			view.buffer = null;
		}
		usedStagingViews.clear();
	}

	public synchronized long flush() {
		mappedBuffer.unmap();

		for (ReservedView view : usedStagingViews) {
			assert view.backing == null;
			view.buffer.flip();
			mappedBuffer.getOwner().upload(view.buffer, view.bufferOffsetInts * 4L);
			view.buffer = null;
		}

		freeViews.addAll(usedMappedViews);
		usedMappedViews.clear();
		usedStagingViews.clear();

		mappedBuffer.syncViews();

		long writtenBytes = (long) (writtenMappedInts + writtenStagingInts) * Integer.BYTES;
		writtenMappedInts = 0;
		writtenStagingInts = 0;
		return writtenBytes;
	}

	public static final class ReservedView {
		private ByteBuffer backing;
		@Getter
		private IntBuffer buffer;
		@Getter
		private int bufferOffsetInts;

		public int getEndOffsetInts() {
			return (backing == null ? bufferOffsetInts : 0) + buffer.position();
		}
	}
}
