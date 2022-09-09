package rs117.hd.model;

import rs117.hd.HdPlugin;

import java.nio.Buffer;
import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Map;


class IntBufferCache extends LinkedHashMap<Integer, IntBuffer> {
    private final long byteCapacity;
    private long bytesConsumed;

    private final BufferPool bufferPool;

    public IntBufferCache(long byteCapacity, BufferPool bufferPool) {
        super(512, 0.7f, true);
        this.byteCapacity = byteCapacity;
        this.bytesConsumed = 0;
        this.bufferPool = bufferPool;
    }

    public long getBytesConsumed() {
        return this.bytesConsumed;
    }

    public void put(int key, IntBuffer value) {
        this.bytesConsumed += value.capacity() * 4L;
        super.put(key, value);
    }

    @Override
    public void clear() {
        this.bytesConsumed = 0;
        super.clear();
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Integer, IntBuffer> eldest) {
        // leave room for at least one max size entry
        if (this.bytesConsumed + (HdPlugin.MAX_TRIANGLE * 12 * 4) >= this.byteCapacity) {
            IntBuffer buffer = eldest.getValue();
            this.bytesConsumed -= buffer.capacity() * 4L;

            // recycle the buffer if possible
            if (this.bufferPool.canPutBuffer(buffer.capacity())) {
                this.bufferPool.putIntBuffer(buffer.capacity(), buffer);
            }

            return true;
        }

        return false;
    }
}
