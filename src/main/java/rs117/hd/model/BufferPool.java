package rs117.hd.model;

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.HdPlugin;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;

@Slf4j
public class BufferPool {
    private static final long BUFFER_SIZE = HdPlugin.MAX_TRIANGLE * ModelPusher.DATUM_PER_FACE * ModelPusher.BYTES_PER_DATUM;

    private final ArrayList<Long> allocationHandles = new ArrayList<>();
    private final ArrayDeque<Long> bufferAddressStack = new ArrayDeque<>();

    public BufferPool(long byteCapacity) throws OutOfMemoryError {
        try {
            // Try allocating the whole size as a single chunk
            allocateChunk(byteCapacity);
        } catch (Throwable err) {
            // Largely unnecessary, but free the above allocation in case it failed while initializing buffer addresses
            freeAllocations();
            log.warn("Unable to allocate {} bytes as a single chunk", byteCapacity, err);

            try {
                // Try allocating in 1 GiB chunks
                long bytesRemaining = byteCapacity;
                while (bytesRemaining > 0) {
                    long chunkSize = Math.min(bytesRemaining, ModelCache.GiB);
                    allocateChunk(chunkSize);
                    bytesRemaining -= chunkSize;
                }
            } catch (Throwable err2) {
                freeAllocations();
                log.error("Unable to allocate {} bytes in chunks of 1 GiB", byteCapacity, err2);
                throw err2;
            }
        }
    }

    public boolean isEmpty() {
        return bufferAddressStack.isEmpty();
    }

    private void allocateChunk(long chunkSize) throws OutOfMemoryError {
        long handle = MemoryUtil.nmemAllocChecked(chunkSize);
        allocationHandles.add(handle);

        for (long cursor = 0; chunkSize - cursor >= BUFFER_SIZE; cursor += BUFFER_SIZE) {
            bufferAddressStack.push(handle + cursor);
        }
    }

    public void freeAllocations() {
        bufferAddressStack.clear();

        Iterator<Long> iterator = allocationHandles.iterator();
        while(iterator.hasNext()) {
            MemoryUtil.nmemFree(iterator.next());
            iterator.remove();
        }
    }

    public void putIntBuffer(IntBuffer buffer) {
        bufferAddressStack.push(MemoryUtil.memAddress(buffer));
    }

    public IntBuffer takeIntBuffer(int capacity) {
        if (bufferAddressStack.isEmpty()) {
            return null;
        }

        return MemoryUtil.memIntBuffer(bufferAddressStack.pop(), capacity);
    }

    public void putFloatBuffer(FloatBuffer buffer) {
        bufferAddressStack.push(MemoryUtil.memAddress(buffer));
    }

    public FloatBuffer takeFloatBuffer(int capacity) {
        if (bufferAddressStack.isEmpty()) {
            return null;
        }

        return MemoryUtil.memFloatBuffer(bufferAddressStack.pop(), capacity);
    }
}
