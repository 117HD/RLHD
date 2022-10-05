package rs117.hd.model;

import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Objects;

@Slf4j
public class ModelCache {
    private BufferPool bufferPool;
    private IntBufferCache vertexDataCache;
    private FloatBufferCache normalDataCache;
    private FloatBufferCache uvDataCache;

    public void init(HdPlugin hdPlugin, HdPluginConfig config) {
        int modelCacheSizeMiB = config.modelCacheSizeMiB();
        if (!Objects.equals(System.getProperty("sun.arch.data.model"), "64") && modelCacheSizeMiB > 1024) {
            log.error("defaulting model cache to 1024MiB due to non 64-bit client");
            modelCacheSizeMiB = 1024;
        }

        try {
            long totalPhysicalMemoryMiB = ((com.sun.management.OperatingSystemMXBean)java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalPhysicalMemorySize() / 1024 / 1024;

            if (modelCacheSizeMiB > totalPhysicalMemoryMiB / 2) {
                modelCacheSizeMiB = (int) (totalPhysicalMemoryMiB / 2);
                log.error("limiting the cache to " + modelCacheSizeMiB + " since the selected amount exceeds half of the total physical memory for the system.");
            }
        } catch (Throwable e) {
            log.error("failed to check physical memory size: " + e);
        }

        this.bufferPool = new BufferPool(modelCacheSizeMiB * 1048576L, hdPlugin);
        this.bufferPool.allocate();

        this.vertexDataCache = new IntBufferCache(this.bufferPool);
        this.normalDataCache = new FloatBufferCache(this.bufferPool);
        this.uvDataCache = new FloatBufferCache(this.bufferPool);
    }

    public void shutDown() {
        clear();

        if (this.bufferPool != null) {
            this.bufferPool.free();
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
