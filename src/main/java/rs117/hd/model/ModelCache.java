package rs117.hd.model;

import lombok.extern.slf4j.Slf4j;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

@Slf4j
public class ModelCache {
    public static final long KiB = 1024;
    public static final long MiB = 1024 * KiB;
    public static final long GiB = 1024 * MiB;

    private final BufferPool bufferPool;
    private final IntBufferCache vertexDataCache;
    private final FloatBufferCache normalDataCache;
    private final FloatBufferCache uvDataCache;

    public ModelCache(int modelCacheSizeMiB) {
        // Limit cache size to 512MiB for 32-bit
        if (modelCacheSizeMiB > 512 && !"64".equals(System.getProperty("sun.arch.data.model"))) {
            log.warn("Defaulting model cache to 512MiB due to non 64-bit client");
            modelCacheSizeMiB = 512;
        }

        try {
            int totalPhysicalMemoryMiB = (int) (
                ((com.sun.management.OperatingSystemMXBean)
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean())
                .getTotalPhysicalMemorySize() / MiB);

            // Try to limit the cache size to half of the total physical memory
            if (modelCacheSizeMiB > totalPhysicalMemoryMiB / 2) {
                log.warn("Limiting cache size to {} since the selected amount ({}) exceeds half of the total physical memory for the system ({} / 2).",
                    totalPhysicalMemoryMiB / 2, modelCacheSizeMiB, totalPhysicalMemoryMiB);
                modelCacheSizeMiB = totalPhysicalMemoryMiB / 2;
            }
        } catch (Throwable e) {
            log.warn("Unable to check physical memory size: " + e);
        }

        this.bufferPool = new BufferPool(modelCacheSizeMiB * MiB);
        this.vertexDataCache = new IntBufferCache(this.bufferPool);
        this.normalDataCache = new FloatBufferCache(this.bufferPool);
        this.uvDataCache = new FloatBufferCache(this.bufferPool);
    }

    public void destroy() {
        clear();

        if (this.bufferPool != null) {
            this.bufferPool.freeAllocations();
        }
    }

    public IntBuffer getVertexData(int hash) {
        return this.vertexDataCache.get(hash);
    }

    public void putVertexData(int hash, IntBuffer data) {
        this.vertexDataCache.put(hash, data);
    }

    public FloatBuffer getNormalData(int hash) {
        return this.normalDataCache.get(hash);
    }

    public void putNormalData(int hash, FloatBuffer data) {
        this.normalDataCache.put(hash, data);
    }

    public FloatBuffer getUvData(int hash) {
        return this.uvDataCache.get(hash);
    }

    public void putUvData(int hash, FloatBuffer data) {
        this.uvDataCache.put(hash, data);
    }

    public IntBuffer takeIntBuffer(int capacity) {
        if (this.bufferPool.isEmpty()) {
            if(!this.makeRoom()) {
                log.error("failed to make room for int buffer");
            }
        }

        return this.bufferPool.takeIntBuffer(capacity);
    }

    public FloatBuffer takeFloatBuffer(int capacity) {
        if (this.bufferPool.isEmpty()) {
            if(!this.makeRoom()) {
                log.error("failed to make room for float buffer");
            }
        }

        return this.bufferPool.takeFloatBuffer(capacity);
    }

    // a more idealized way to balance the caches might look like:
    // 1) count the number of gets for vertex, normal, and uv data on each frame
    // 2) use the data from the previous frame to determine the cache usage for the scene (e.g. 85% vertex, 10% normal, 5% uv)
    // 3) makeRoom here based on those weights to try and reactively match the cache pressure with usage
    public boolean makeRoom() {
        if (this.uvDataCache.size() * 16 > this.normalDataCache.size() && this.normalDataCache.size() > 0) {
            return this.uvDataCache.makeRoom();
        } else if (this.normalDataCache.size() * 2 > this.vertexDataCache.size()) {
            return this.normalDataCache.makeRoom();
        } else {
            return this.vertexDataCache.makeRoom();
        }
    }

    public void clear() {
        if (this.vertexDataCache != null) {
            this.vertexDataCache.clear();
        }

        if (this.normalDataCache != null) {
            this.normalDataCache.clear();
        }

        if (this.uvDataCache != null) {
            this.uvDataCache.clear();
        }
    }
}
