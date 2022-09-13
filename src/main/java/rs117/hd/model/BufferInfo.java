package rs117.hd.model;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class BufferInfo {
    private final long address;
    private final long bytes;
    private boolean freed = false;
}
