package rs117.hd.model;

public class BufferInfo {
    private final long address;
    private final long bytes;
    private boolean freed;

    public BufferInfo(long address, long bytes) {
        this.address = address;
        this.bytes = bytes;
        this.freed = false;
    }

    public long getAddress() {
        return address;
    }

    public long getBytes() {
        return bytes;
    }

    public boolean isFreed() {
        return freed;
    }

    public void setFreed(boolean freed) {
        this.freed = freed;
    }
}
