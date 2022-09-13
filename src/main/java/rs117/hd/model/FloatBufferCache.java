package rs117.hd.model;

import java.nio.FloatBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class FloatBufferCache extends LinkedHashMap<Integer, FloatBuffer> {
    private final long byteCapacity;
    private long bytesConsumed;

    private final BufferPool bufferPool;

    public FloatBufferCache(long byteCapacity, BufferPool bufferPool) {
        super(512, 0.7f, true);
        this.byteCapacity = byteCapacity;
        this.bytesConsumed = 0;
        this.bufferPool = bufferPool;
    }

    public long getBytesConsumed() {
        return this.bytesConsumed;
    }

    public void put(int key, FloatBuffer value) {
        long bytes = value.capacity() * 4L;
        if (this.bytesConsumed + bytes > this.byteCapacity) {
            makeRoom(bytes);
        }

        this.bytesConsumed += bytes;
        super.put(key, value);
    }

    public void makeRoom(long size) {
        Iterator<Map.Entry<Integer, FloatBuffer>> iterator = entrySet().iterator();

        long releasedSized = 0;
        while (iterator.hasNext() && releasedSized < size) {
            Map.Entry<Integer, FloatBuffer> entry = iterator.next();
            FloatBuffer buffer = entry.getValue();
            long releasedBytes = buffer.capacity() * 4L;
            releasedSized += releasedBytes;
            this.bytesConsumed -= releasedBytes;

            if (this.bufferPool.canPutBuffer(buffer)) {
                this.bufferPool.putFloatBuffer(buffer);
            }

            iterator.remove();
        }
    }

    @Override
    public void clear() {
        this.bytesConsumed = 0;
        super.clear();
    }
}
