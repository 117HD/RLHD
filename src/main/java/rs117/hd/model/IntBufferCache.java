package rs117.hd.model;

import lombok.extern.slf4j.Slf4j;

import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;

@Slf4j
class IntBufferCache extends LinkedHashMap<Integer, IntBuffer> {
    private final BufferPool bufferPool;

    public IntBufferCache(BufferPool bufferPool) {
        super(512, 0.7f, true);
        this.bufferPool = bufferPool;
    }

    public boolean makeRoom() {
        Iterator<IntBuffer> iterator = super.values().iterator();
        if (iterator.hasNext()) {
            this.bufferPool.putIntBuffer(iterator.next());
            iterator.remove();
            return true;
        }

        return false;
    }

    @Override
    public void clear() {
        Iterator<IntBuffer> iterator = super.values().iterator();
        while (iterator.hasNext()) {
            this.bufferPool.putIntBuffer(iterator.next());
            iterator.remove();
        }
    }
}
