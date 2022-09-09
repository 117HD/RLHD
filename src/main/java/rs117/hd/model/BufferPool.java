package rs117.hd.model;

import javax.inject.Singleton;
import java.nio.Buffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

public class BufferPool {
    private final Map<Integer, Stack<Buffer>> bufferStackMap;
    private final long byteCapacity;
    private long bytesStored;

    public BufferPool(long byteCapacity) {
        this.bufferStackMap = new HashMap<>();
        this.byteCapacity = byteCapacity;
        bytesStored = 0;
    }

    public boolean canPutBuffer(int size) {
        return this.bytesStored + size <= this.byteCapacity;
    }

    public void put(int capacity, Buffer value) {
        this.bytesStored += capacity * 4L;

        Stack<Buffer> stack = bufferStackMap.get(capacity);
        if (stack == null) {
            stack = new Stack<>();
        }

        stack.push(value);
        bufferStackMap.putIfAbsent(capacity, stack);
    }

    public Buffer take(int capacity) {
        Stack<Buffer> stack = bufferStackMap.get(capacity);
        return stack == null || stack.empty() ? null : stack.pop().clear();
    }
}
