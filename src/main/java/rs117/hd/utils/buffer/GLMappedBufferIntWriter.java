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
	public static final boolean DEBUG_STAGING = false;

	private final GLBuffer buffer;
	private GLBuffer stagingBuffer;

	private final ArrayDeque<ReservedView> freeViews = new ArrayDeque<>();
	private final ArrayDeque<ReservedView> usedStagingViews = new ArrayDeque<>();
	private final ArrayList<ReservedView> usedMappedViews = new ArrayList<>();

	private GLMappedBuffer mappedBuffer;
	private int writeHead;
	private int stagingWriteHead;

	public int getWrittenInts() {
		return writeHead + stagingWriteHead;
	}

	public void map(boolean sync) {
		mappedBuffer = buffer.map(MAP_WRITE | MAP_INVALIDATE | (sync ? 0 : MAP_UNSYNCHRONIZED));
	}

	public synchronized ReservedView reserve(int sizeInts) {
		assert mappedBuffer.isMapped();

		// Need staging if we've already staged or mapped buffer has no space
		if (stagingWriteHead > 0 || mappedBuffer.intView().remaining() < sizeInts || DEBUG_STAGING) {
			ReservedView staging = new ReservedView();
			staging.buffer = BufferUtils.createIntBuffer(sizeInts);
			staging.offsetInts = writeHead + stagingWriteHead;
			stagingWriteHead += sizeInts;
			usedStagingViews.add(staging);
			return staging;
		}

		ReservedView view = freeViews.poll();
		if (view == null)
			view = new ReservedView();

		if (view.backing != mappedBuffer.byteView()) {
			view.backing = mappedBuffer.byteView();
			view.buffer = view.backing.asIntBuffer();
		}

		view.buffer.clear();
		view.buffer.position(writeHead);
		view.buffer.limit(writeHead + sizeInts);
		view.offsetInts = writeHead;

		writeHead += sizeInts;
		mappedBuffer.intView().position(writeHead);

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

		if (stagingBuffer != null)
			stagingBuffer.destroy();
		stagingBuffer = null;

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
		mappedBuffer.setPositionBytes(writeHead * Integer.BYTES);
		mappedBuffer.unmap();

		if (!usedStagingViews.isEmpty()) {
			final GLBuffer owner = mappedBuffer.getOwner();
			final long mappedSize = (long) writeHead * Integer.BYTES;
			final long stagingCapacity = (long) stagingWriteHead * Integer.BYTES;

			owner.ensureCapacity(mappedSize, stagingCapacity);
			mappedBuffer = owner.map(MAP_WRITE, mappedSize, stagingCapacity);

			final IntBuffer overflow = owner.mapped().intView().clear();
			ReservedView staging;
			while ((staging = usedStagingViews.poll()) != null) {
				staging.buffer.flip();
				// Ensure we write to the reserved offset, just in case
				// a previous staging view did not fill its reservation
				overflow.position(staging.offsetInts - writeHead);
				overflow.put(staging.buffer);
				staging.buffer = null;
			}

			mappedBuffer.syncViews();
			mappedBuffer.unmap();
		}

		freeViews.addAll(usedMappedViews);
		usedMappedViews.clear();

		long writtenBytes = (long) (writeHead + stagingWriteHead) * Integer.BYTES;
		writeHead = 0;
		stagingWriteHead = 0;
		return writtenBytes;
	}

	public static final class ReservedView {
		private ByteBuffer backing;
		@Getter
		private IntBuffer buffer;
		@Getter
		private int offsetInts;

		public int getEndOffsetInts() {
			return getPositionOffsetInts() + buffer.position();
		}

		public int getPositionOffsetInts() {
			return backing == null ? offsetInts : 0;
		}
	}
}
