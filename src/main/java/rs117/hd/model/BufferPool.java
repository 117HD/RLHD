package rs117.hd.model;

import lombok.extern.slf4j.Slf4j;

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

@Slf4j
public class BufferPool {
    private final BufferStackMap<IntBuffer> intBufferStackMap;
    private final BufferStackMap<FloatBuffer> floatBufferStackMap;
    private final long byteCapacity;
    private long bytesStored;

    public BufferPool(long byteCapacity) {
        this.intBufferStackMap = new BufferStackMap<>();
        this.floatBufferStackMap = new BufferStackMap<>();
        this.byteCapacity = byteCapacity;
        this.bytesStored = 0;
    }

    public void putIntBuffer(IntBuffer buffer) {
        long bytesNeeded = buffer.capacity() * 4L;
        if (this.bytesStored + bytesNeeded > this.byteCapacity) {
            this.intBufferStackMap.makeRoom(bytesNeeded);
        }

        this.intBufferStackMap.putBuffer(buffer);
        this.bytesStored += bytesNeeded;
    }

    public IntBuffer takeIntBuffer(int capacity) {
        return this.intBufferStackMap.takeBuffer(capacity);
    }

    public void putFloatBuffer(FloatBuffer buffer) {
        long bytesNeeded = buffer.capacity() * 4L;
        if (this.bytesStored + bytesNeeded > this.byteCapacity) {
            this.floatBufferStackMap.makeRoom(bytesNeeded);
        }

        this.floatBufferStackMap.putBuffer(buffer);
        this.bytesStored += bytesNeeded;
    }

    public FloatBuffer takeFloatBuffer(int capacity) {
        return this.floatBufferStackMap.takeBuffer(capacity);
    }
}
