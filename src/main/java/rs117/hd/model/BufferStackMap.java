package rs117.hd.model;

import java.nio.Buffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Stack;

public class BufferStackMap<T extends Buffer> extends LinkedHashMap<Integer, Stack<T>> {
    public BufferStackMap() {}

    public void putBuffer(T buffer) {
        int capacity = buffer.capacity();

        Stack<T> stack = get(capacity);
        if (stack == null) {
            stack = new Stack<>();
        }

        stack.push(buffer);
        putIfAbsent(capacity, stack);
    }

    public T takeBuffer(int capacity) {
        Stack<T> stack = get(capacity);
        if (stack == null ) {
            return null;
        } else if (stack.empty()) {
            remove(capacity);
            return null;
        } else {
            T buffer = stack.pop();
            buffer.clear();
            return buffer;
        }
    }

    public void makeRoom(long bytes) {
        Iterator<Stack<T>> iterator = values().iterator();

        long releasedSize = 0;
        while (iterator.hasNext() && releasedSize < bytes) {
            Stack<T> stack = iterator.next();
            while (!stack.isEmpty() && releasedSize < bytes) {
                Buffer buffer = stack.pop();
                releasedSize += buffer.capacity() * 4L;
            }

            if (stack.isEmpty()) {
                iterator.remove();
            }
        }
    }
}
