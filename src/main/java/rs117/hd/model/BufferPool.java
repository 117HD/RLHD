package rs117.hd.model;

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.HdPlugin;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.Stack;

@Slf4j
public class BufferPool {
    private final Stack<Long> bufferAddressStack;
    private final long byteCapacity;
    private boolean allocated;
    private final HdPlugin hdPlugin;

    public BufferPool(long byteCapacity, HdPlugin hdPlugin) {
        this.bufferAddressStack = new Stack<>();
        this.byteCapacity = byteCapacity;
        this.allocated = false;
        this.hdPlugin = hdPlugin;
    }

    public boolean isEmpty() {
        return this.bufferAddressStack.isEmpty();
    }

    public void allocate() {
        if (this.allocated) {
            return;
        }

        // we're going to allocate as many of these as possible
        // these lovely allocations are perfectly sized to fit any piece of model data so they can be reused infinitely
        long allocationSize = HdPlugin.MAX_TRIANGLE * ModelPusher.DATUM_PER_FACE * ModelPusher.BYTES_PER_DATUM;
        long bytesRemaining = this.byteCapacity;

        try {
            while (bytesRemaining - allocationSize >= 0) {
                this.bufferAddressStack.push(MemoryUtil.nmemAllocChecked(allocationSize));
                bytesRemaining -= allocationSize;
            }
        } catch (OutOfMemoryError oom) {
            log.error("out of memory during initialization -- shutting down");
            hdPlugin.stopPlugin();
        }

        this.allocated = true;
    }

    public void free() {
        Iterator<Long> iterator = this.bufferAddressStack.iterator();

        while(iterator.hasNext()) {
            Long address = iterator.next();
            MemoryUtil.nmemFree(address);
            iterator.remove();
        }
    }

    public void putIntBuffer(IntBuffer buffer) {
        this.bufferAddressStack.push(MemoryUtil.memAddress(buffer));
    }

    public IntBuffer takeIntBuffer(int capacity) {
        if (this.bufferAddressStack.isEmpty()) {
            return null;
        }

        return MemoryUtil.memIntBuffer(this.bufferAddressStack.pop(), capacity);
    }

    public void putFloatBuffer(FloatBuffer buffer) {
        this.bufferAddressStack.push(MemoryUtil.memAddress(buffer));
    }

    public FloatBuffer takeFloatBuffer(int capacity) {
        if (this.bufferAddressStack.isEmpty()) {
            return null;
        }

        return MemoryUtil.memFloatBuffer(this.bufferAddressStack.pop(), capacity);
    }
}
