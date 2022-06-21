package rs117.hd.model;

import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

public class FloatBufferCache
        extends LinkedHashMap<Integer, FloatBuffer> {
    private final int capacity;

    public FloatBufferCache(int capacity) {
        super(capacity, 0.7f, true);
        this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Integer, FloatBuffer> eldest) {
        return this.size() > this.capacity;
    }
}
