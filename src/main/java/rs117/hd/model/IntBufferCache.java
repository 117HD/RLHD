package rs117.hd.model;

import rs117.hd.HdPlugin;

import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

class IntBufferCache extends LinkedHashMap<Integer, IntBuffer> {
    private final long byteCapacity;
    private long bytesConsumed;

    public IntBufferCache(long byteCapacity) {
        super(8192, 0.7f, true);
        this.byteCapacity = byteCapacity;
        this.bytesConsumed = 0;
    }

    public long getBytesConsumed() {
        return this.bytesConsumed;
    }

    public void put(int key, IntBuffer value) {
        this.bytesConsumed += value.remaining() * 4;
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
            this.bytesConsumed -= eldest.getValue().remaining() * 4;
            return true;
        }

        return false;
    }
}
