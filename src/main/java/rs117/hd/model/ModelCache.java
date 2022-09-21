package rs117.hd.model;

import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPluginConfig;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

@Slf4j
public class ModelCache {
    private BufferPool bufferPool;
    private IntBufferCache vertexDataCache;
    private FloatBufferCache normalDataCache;
    private FloatBufferCache uvDataCache;

    public void init(HdPluginConfig config) {
        this.bufferPool = new BufferPool(config.modelCacheSizeMiB() * 1048576L);
        this.bufferPool.allocate();

        this.vertexDataCache = new IntBufferCache(this.bufferPool);
        this.normalDataCache = new FloatBufferCache(this.bufferPool);
        this.uvDataCache = new FloatBufferCache(this.bufferPool);
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
            if(!this.vertexDataCache.makeRoom()) {
                log.error("failed to make room for int buffer");
            }
        }

        return this.bufferPool.takeIntBuffer(capacity);
    }

    public FloatBuffer takeFloatBuffer(int capacity) {
        if (this.bufferPool.isEmpty()) {
            if(!this.vertexDataCache.makeRoom()) {
                log.error("failed to make room for float buffer");
            }
        }

        return this.bufferPool.takeFloatBuffer(capacity);
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

    public void freeAllBuffers() {
        if (this.bufferPool != null) {
            this.bufferPool.free();
        }
    }
}
