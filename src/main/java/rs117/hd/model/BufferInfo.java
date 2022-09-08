package rs117.hd.model;

public class BufferInfo {
    private long address;
    private long bytes;

    public BufferInfo(long address, long bytes) {
        this.address = address;
        this.bytes = bytes;
    }

    public long getAddress() {
        return address;
    }

    public BufferInfo setAddress(long address) {
        this.address = address;
        return this;
    }

    public long getBytes() {
        return bytes;
    }

    public BufferInfo setBytes(long bytes) {
        this.bytes = bytes;
        return this;
    }
}
