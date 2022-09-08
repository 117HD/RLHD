package rs117.hd.model;

import rs117.hd.HdPlugin;

import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

class IntBufferCache extends LinkedHashMap<Integer, IntBuffer> {
    // how many times we want to remove the eldest entry
    private int removals = 0;

    public IntBufferCache() {
        super(512, 0.7f, true);
    }

    public void requestRemoval() {
        this.removals++;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Integer, IntBuffer> eldest) {
        // remove the eldest entry if requested
        if (this.removals > 0) {
            this.removals--;
            return true;
        }

        return false;
    }
}
