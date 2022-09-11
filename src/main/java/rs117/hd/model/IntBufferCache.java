package rs117.hd.model;

import lombok.extern.slf4j.Slf4j;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Iterator;
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
        long bytes = value.capacity() * 4L;
        if (this.bytesConsumed + bytes > this.byteCapacity) {
            makeRoom(bytes);
        }

        this.bytesConsumed += bytes;
        super.put(key, value);
    }

    public void makeRoom(long size) {
        Iterator<Map.Entry<Integer, IntBuffer>> iterator = entrySet().iterator();

        long releasedSized = 0;
        ArrayList<Integer> toBeReleased = new ArrayList<>();
        while (iterator.hasNext() && releasedSized < size) {
            Map.Entry<Integer, IntBuffer> entry = iterator.next();
            toBeReleased.add(entry.getKey());
            releasedSized += entry.getValue().capacity() * 4L;
        }

        toBeReleased.forEach(key -> {
            IntBuffer buffer = this.remove(key);

            if (buffer != null) {
                this.bytesConsumed -= buffer.capacity() * 4L;

                if (this.bufferPool.canPutBuffer(buffer.capacity() * 4L)) {
                    this.bufferPool.putIntBuffer(buffer.capacity(), buffer);
                }
            }
        });
    }

    @Override
    public void clear() {
        this.bytesConsumed = 0;
        super.clear();
    }
}
