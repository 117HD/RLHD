package rs117.hd.model;

import java.nio.FloatBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;

public class FloatBufferCache extends LinkedHashMap<Integer, FloatBuffer> {
    private final BufferPool bufferPool;

    public FloatBufferCache(BufferPool bufferPool) {
        super(512, 0.7f, true);
        this.bufferPool = bufferPool;
    }

    public long makeRoom(long size) {
        Iterator<FloatBuffer> iterator = super.values().iterator();

        long releasedSized = 0;
        while (iterator.hasNext() && releasedSized < size) {
            FloatBuffer buffer = iterator.next();
            releasedSized += buffer.capacity() * 4L;
            this.bufferPool.putFloatBuffer(buffer);
            iterator.remove();
        }

        return releasedSized;
    }
}
