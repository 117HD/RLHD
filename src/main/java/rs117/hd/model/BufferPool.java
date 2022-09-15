package rs117.hd.model;

import lombok.extern.slf4j.Slf4j;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

@Slf4j
public class BufferPool {
    private final BufferStackMap<IntBuffer> intBufferStackMap;
    private final BufferStackMap<FloatBuffer> floatBufferStackMap;
    private long bytesRemaining;

    public BufferPool(long byteCapacity) {
        this.intBufferStackMap = new BufferStackMap<>();
        this.floatBufferStackMap = new BufferStackMap<>();
        this.bytesRemaining = byteCapacity;
    }

    public void putIntBuffer(IntBuffer buffer) {
        long bytesNeeded = buffer.capacity() * 4L;

        if (bytesNeeded > this.bytesRemaining) {
            this.bytesRemaining += this.intBufferStackMap.makeRoom(bytesNeeded - this.bytesRemaining);
        }

        this.intBufferStackMap.putBuffer(buffer);
        this.bytesRemaining -= bytesNeeded;
    }

    public IntBuffer takeIntBuffer(int capacity) {
        IntBuffer acquired = this.intBufferStackMap.takeBuffer(capacity);
        if (acquired != null) {
            this.bytesRemaining += acquired.capacity() * 4L;
        }
        return acquired;
    }

    public void putFloatBuffer(FloatBuffer buffer) {
        long bytesNeeded = buffer.capacity() * 4L;

        if (bytesNeeded > this.bytesRemaining) {
            this.bytesRemaining += this.floatBufferStackMap.makeRoom(bytesNeeded - this.bytesRemaining);
        }

        this.floatBufferStackMap.putBuffer(buffer);
        this.bytesRemaining -= bytesNeeded;
    }

    public FloatBuffer takeFloatBuffer(int capacity) {
        FloatBuffer acquired = this.floatBufferStackMap.takeBuffer(capacity);
        if (acquired != null) {
            this.bytesRemaining += acquired.capacity() * 4L;
        }
        return acquired;
    }
}
