package rs117.hd.utils.buffer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lwjgl.BufferUtils;

import static rs117.hd.utils.buffer.GLBuffer.MAP_INVALIDATE;
import static rs117.hd.utils.buffer.GLBuffer.MAP_UNSYNCHRONIZED;
import static rs117.hd.utils.buffer.GLBuffer.MAP_WRITE;

@RequiredArgsConstructor
public class GLMappedBufferIntWriter {
	private final GLBuffer buffer;

	private final ArrayDeque<ReservedView> freeViews = new ArrayDeque<>();
	private final ArrayList<ReservedView> usedMappedViews = new ArrayList<>();
	private final ArrayList<ReservedView> usedStagingViews = new ArrayList<>();

	private GLMappedBuffer mappedBuffer;
	private int writtenMappedInts;
	private int writtenStagingInts;

	public void map() {
		mappedBuffer = buffer.map(MAP_WRITE | MAP_INVALIDATE | MAP_UNSYNCHRONIZED);
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

	public synchronized long flush() {
		mappedBuffer.unmap();

		for (ReservedView view : usedStagingViews) {
			assert view.backing == null;
			view.buffer.flip();
			mappedBuffer.getOwner().upload(view.buffer, view.bufferOffsetInts * 4L);
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
