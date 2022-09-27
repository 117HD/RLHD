package rs117.hd.model;

import lombok.extern.slf4j.Slf4j;

import java.nio.FloatBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;

@Slf4j
public class FloatBufferCache extends LinkedHashMap<Integer, FloatBuffer> {
    private final BufferPool bufferPool;

    public FloatBufferCache(BufferPool bufferPool) {
        super(512, 0.7f, true);
        this.bufferPool = bufferPool;
    }

    public boolean makeRoom() {
        Iterator<FloatBuffer> iterator = super.values().iterator();
        if (iterator.hasNext()) {
            this.bufferPool.putFloatBuffer(iterator.next());
            iterator.remove();
            return true;
        }

        return false;
    }

    @Override
    public void clear() {
        Iterator<FloatBuffer> iterator = super.values().iterator();
        while (iterator.hasNext()) {
            this.bufferPool.putFloatBuffer(iterator.next());
            iterator.remove();
        }
    }
}
