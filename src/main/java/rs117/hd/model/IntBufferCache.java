package rs117.hd.model;

import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;

class IntBufferCache extends LinkedHashMap<Integer, IntBuffer> {
    private final BufferPool bufferPool;

    public IntBufferCache(BufferPool bufferPool) {
        super(512, 0.7f, true);
        this.bufferPool = bufferPool;
    }

    public long makeRoom(long size) {
        Iterator<IntBuffer> iterator = values().iterator();

        long releasedSized = 0;
        while (iterator.hasNext() && releasedSized < size) {
            IntBuffer buffer = iterator.next();
            releasedSized += buffer.capacity() * 4L;
            this.bufferPool.putIntBuffer(buffer);
            iterator.remove();
        }

        return releasedSized;
    }
}
