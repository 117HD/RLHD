package rs117.hd.model;

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

public class BufferPool {
    private final Map<Integer, Stack<IntBuffer>> intBufferStackMap;
    private final Map<Integer, Stack<FloatBuffer>> floatBufferStackMap;
    private final long byteCapacity;
    private long bytesStored;
    private long takes;
    private long hits;

    public BufferPool(long byteCapacity) {
        this.intBufferStackMap = new HashMap<>();
        this.floatBufferStackMap = new HashMap<>();
        this.byteCapacity = byteCapacity;
        this.bytesStored = 0;
        this.takes = 0;
        this.hits = 0;
    }

    public void resetHitRatio() {
        this.takes = 0;
        this.hits = 0;
    }

    public boolean canPutBuffer(Buffer buffer) {
        return this.bytesStored + buffer.capacity() * 4L <= this.byteCapacity;
    }

    public void putIntBuffer(IntBuffer buffer) {
        int capacity = buffer.capacity();
        this.bytesStored += capacity * 4L;

        Stack<IntBuffer> stack = this.intBufferStackMap.get(capacity);
        if (stack == null) {
            stack = new Stack<>();
        }

        stack.push(buffer);
        this.intBufferStackMap.putIfAbsent(capacity, stack);
    }

    public IntBuffer takeIntBuffer(int capacity) {
        this.takes++;

        Stack<IntBuffer> stack = this.intBufferStackMap.get(capacity);
        if (stack == null || stack.empty()) {
            return null;
        } else {
            this.hits++;
            IntBuffer buffer = stack.pop();
            buffer.clear();
            this.bytesStored -= buffer.capacity() * 4L;
            return buffer;
        }
    }

    public void putFloatBuffer(FloatBuffer buffer) {
        int capacity = buffer.capacity();
        this.bytesStored += capacity * 4L;

        Stack<FloatBuffer> stack = this.floatBufferStackMap.get(capacity);
        if (stack == null) {
            stack = new Stack<>();
        }

        stack.push(buffer);
        this.floatBufferStackMap.putIfAbsent(capacity, stack);
    }

    public FloatBuffer takeFloatBuffer(int capacity) {
        this.takes++;

        Stack<FloatBuffer> stack = this.floatBufferStackMap.get(capacity);
        if (stack == null || stack.empty()) {
            return null;
        } else {
            this.hits++;
            FloatBuffer buffer = stack.pop();
            buffer.clear();
            this.bytesStored -= buffer.capacity() * 4L;
            return buffer;
        }
    }

    public void checkRatio() {
        // clear the pools if the hit ratio is less than 50% and we're over 75% capacity
        if ((double) this.bytesStored / this.byteCapacity >= 0.75 && (double) this.takes / this.hits < 0.50) {
            this.floatBufferStackMap.clear();
            this.intBufferStackMap.clear();
            this.bytesStored = 0;
            this.resetHitRatio();
        }
    }
}
