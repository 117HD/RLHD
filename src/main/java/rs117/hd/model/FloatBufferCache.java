package rs117.hd.model;

import rs117.hd.HdPlugin;

import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

public class FloatBufferCache extends LinkedHashMap<Integer, FloatBuffer> {
    private int removals = 0;

    public FloatBufferCache() {
        super(512, 0.7f, true);
    }

    public void requestRemoval() {
        this.removals++;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Integer, FloatBuffer> eldest) {
        // leave room for at least one max size entry
        if (this.removals > 0) {
            this.removals--;
            return true;
        }

        return false;
    }
}
