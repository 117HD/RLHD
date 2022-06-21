package rs117.hd.model;

import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.map.ChronicleMap;
import rs117.hd.HdPlugin;

import javax.inject.Singleton;

@Singleton
@Slf4j
public class OffHeapModelCache {
    private final int vertexDataCapacity = 16384;
    private final int normalDataCapacity = 1024;
    private final int uvDataCapacity = 512;
    private final int averageValueSize = HdPlugin.MAX_TRIANGLE * 12;

    private int[] intData = null;
    private float[] floatData = null;

    private int lastVertexDataKey;
    private int lastNormalDataKey;
    private int lastUvDataKey;

    private final ChronicleMap<Integer, int[]> vertexDataCache = ChronicleMap
            .of(Integer.class, int[].class)
            .entries(vertexDataCapacity)
            .averageValueSize(averageValueSize)
            .create();

    private final ChronicleMap<Integer, float[]> normalDataCache = ChronicleMap
            .of(Integer.class, float[].class)
            .entries(normalDataCapacity)
            .averageValueSize(averageValueSize)
            .create();

    private final ChronicleMap<Integer, float[]> uvDataCache = ChronicleMap
            .of(Integer.class, float[].class)
            .entries(uvDataCapacity)
            .averageValueSize(averageValueSize)
            .create();

    public void remove(int key) {
        this.vertexDataCache.remove(key);
        this.normalDataCache.remove(key);
        this.uvDataCache.remove(key);
    }

    public void clear() {
        this.vertexDataCache.clear();
        this.normalDataCache.clear();
        this.uvDataCache.clear();
    }

    public void putVertexData(int key, int[] vertexData) {
        if (this.vertexDataCache.size() >= this.vertexDataCapacity) {
            this.vertexDataCache.remove(this.lastVertexDataKey);
        }

        this.lastVertexDataKey = key;
        this.vertexDataCache.put(key, vertexData);
    }

    public int[] getVertexData(int key) {
        intData = this.vertexDataCache.getUsing(key, intData);
        return intData;
    }

    public void putNormalData(int key, float[] normalData) {
        if (this.normalDataCache.size() >= normalDataCapacity) {
            this.normalDataCache.remove(this.lastNormalDataKey);
        }

        this.lastNormalDataKey = key;
        this.normalDataCache.put(key, normalData);
    }

    public float[] getNormalData(int key) {
        floatData = this.normalDataCache.getUsing(key, floatData);
        return floatData;
    }

    public void putUvData(int key, float[] uvData) {
        if (this.uvDataCache.size() >= uvDataCapacity) {
            this.uvDataCache.remove(this.lastUvDataKey);
        }

        this.lastUvDataKey = key;
        this.uvDataCache.put(key, uvData);
    }

    public float[] getUvData(int key) {
        floatData = this.uvDataCache.getUsing(key, floatData);
        return floatData;
    }
}
