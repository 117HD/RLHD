package rs117.hd.model;

import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

class IntBufferCache extends LinkedHashMap<Integer, IntBuffer> {
    private final int capacity;

    public IntBufferCache(int capacity) {
        super(capacity, 0.7f, true);
        this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Integer, IntBuffer> eldest) {
        return this.size() > this.capacity;
    }
}
