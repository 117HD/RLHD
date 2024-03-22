package rs117.hd.model;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryUtil;

import static rs117.hd.utils.HDUtils.GiB;
import static rs117.hd.utils.HDUtils.MiB;

@Slf4j
public class ModelCache {
	private static class Allocation {
		long address;
		long byteCapacity;

		long cursor;
		long freeBytesAhead;

		Allocation(long byteCapacity) {
			assert byteCapacity > 0;
			address = MemoryUtil.nmemAllocChecked(byteCapacity);
			this.byteCapacity = byteCapacity;
			cursor = 0;
			freeBytesAhead = byteCapacity;
		}

		void destroy() {
			if (address != 0L) {
				MemoryUtil.nmemFree(address);
				address = 0;
				byteCapacity = 0;
				cursor = 0;
				freeBytesAhead = 0;
			}
		}

		long reserve(long numBytes) {
			assert numBytes > 0;
			assert numBytes <= freeBytesAhead;
			assert numBytes <= byteCapacity - cursor;
			long address = this.address + cursor;
			cursor += numBytes;
			freeBytesAhead -= numBytes;
			return address;
		}

		long bytesFromEnd() {
			return byteCapacity - cursor;
		}
	}

	private static class Buffer {
		final boolean endMarker;
		final long hash;
		final long byteCapacity;
		final IntBuffer intBuffer;
		final FloatBuffer floatBuffer;

		public Buffer(long byteCapacity) {
			endMarker = true;
			this.hash = 0;
			this.byteCapacity = byteCapacity;
			intBuffer = null;
			floatBuffer = null;
		}

		public Buffer(long hash, IntBuffer buffer) {
			endMarker = false;
			this.hash = hash;
			byteCapacity = buffer.capacity() * 4L;
			intBuffer = buffer;
			floatBuffer = null;
		}

		public Buffer(long hash, FloatBuffer buffer) {
			endMarker = false;
			this.hash = hash;
			byteCapacity = buffer.capacity() * 4L;
			intBuffer = null;
			floatBuffer = buffer;
		}
	}

	private final Runnable terminationHook;
	private final HashMap<Long, Buffer> cache = new HashMap<>();
	private final ArrayDeque<Buffer> buffers = new ArrayDeque<>();
	private final Allocation[] allocations;
	private Allocation currentAllocation;
	private int currentAllocationIndex;

	public ModelCache(int modelCacheSizeMiB, Runnable terminationHook) {
		this.terminationHook = terminationHook;

		// Limit cache size to 128 MiB for 32-bit
		if (modelCacheSizeMiB > 128 && !"64".equals(System.getProperty("sun.arch.data.model"))) {
			log.warn("Defaulting model cache to 128 MiB due to non 64-bit client");
			modelCacheSizeMiB = 128;
		}

		try {
			int totalPhysicalMemoryMiB = (int) (((com.sun.management.OperatingSystemMXBean)
				java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalPhysicalMemorySize() / MiB);

			// Try to limit the cache size to half of the total physical memory
			if (modelCacheSizeMiB > totalPhysicalMemoryMiB / 2) {
				log.warn(
					"Limiting cache size to {} since the selected amount ({}) exceeds half of the total system memory ({} / 2)",
					totalPhysicalMemoryMiB / 2, modelCacheSizeMiB, totalPhysicalMemoryMiB);
				modelCacheSizeMiB = totalPhysicalMemoryMiB / 2;
			}
		} catch (Throwable e) {
			log.warn("Unable to check physical memory size: " + e);
		}

		long byteCapacity = modelCacheSizeMiB * MiB;

		log.debug("Allocating {} MiB model cache", modelCacheSizeMiB);

		Allocation[] allocations = new Allocation[1];
		try {
			// Try allocating the whole size as a single chunk
			allocations[0] = new Allocation(byteCapacity);
		} catch (Throwable err) {
			log.warn("Unable to allocate {} MiB as a single chunk", modelCacheSizeMiB, err);

			try {
				// Try allocating in chunks of up to 1 GiB each
				int numChunks = (int) Math.ceil((double) byteCapacity / GiB);
				allocations = new Allocation[numChunks];
				for (int i = 0; i < numChunks; i++) {
					allocations[i] = new Allocation(Math.min(byteCapacity - i * GiB, GiB));
				}
			} catch (Throwable err2) {
				destroy();
				log.error("Unable to allocate {} MiB in chunks of up to 1 GiB each", modelCacheSizeMiB, err2);
				throw err2;
			}
		}

		this.allocations = allocations;
		currentAllocation = allocations[0];
	}

	public void destroy() {
		cache.clear();
		buffers.clear();
		currentAllocation = null;

		for (int i = 0; i < allocations.length; i++) {
			if (allocations[i] != null) {
				allocations[i].destroy();
				allocations[i] = null;
			}
		}
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void finalize() throws Throwable {
		try {
			// Clean up allocations in case the plugin somehow fails to call destroy
			destroy();
		} finally {
			super.finalize();
		}
	}

	public void clear() {
		cache.clear();
		buffers.clear();
		for (Allocation allocation : allocations) {
			if (allocation != null) {
				allocation.cursor = 0;
				allocation.freeBytesAhead = allocation.byteCapacity;
			}
		}
	}

	private Buffer get(long hash) {
		return cache.get(hash);
	}

	private void nextAllocation() {
		currentAllocation.cursor = 0;
		currentAllocation.freeBytesAhead = 0;

		currentAllocationIndex++;
		currentAllocationIndex %= allocations.length;
		currentAllocation = allocations[currentAllocationIndex];
	}

	private long reserve(long numBytes) {
		assert currentAllocation != null : "model cache used after destruction";

		if (currentAllocation.bytesFromEnd() < numBytes) {
			// ### = taken, ... = free, MMM = end marker
			//                    _________ -> not enough space
			// [##################....###MM]
			// inserting a new end marker as follows will cause issues
			// [##################MMMM###MM]
			// since ### and MM will be freed next, an option is to move these to the end of the buffer list
			// another minor optimization we can make is to pretend that the buffers are shifted to the left like so
			// [##################|MMMM###MM]
			// [##################|###MMMMMM]
			// this leaves us with only a single dummy buffer at the end, and a guarantee that buffers will still be
			// freed in an appropriate order with no collisions

			// Move the existing regions to the end of the buffer list
			while (currentAllocation.bytesFromEnd() != currentAllocation.freeBytesAhead) {
				assert currentAllocation.bytesFromEnd() > currentAllocation.freeBytesAhead;
				Buffer buffer = buffers.pollFirst();
				if (buffer == null) {
					log.error("No more cache entries left to free, yet the allocation is still in use ({} != {})",
						currentAllocation.bytesFromEnd(), currentAllocation.freeBytesAhead);
					terminationHook.run();
					return 0;
				}

				if (buffer.endMarker) {
					// Shift unused space to the end of the buffer, as detailed above
					currentAllocation.freeBytesAhead += buffer.byteCapacity;
					assert currentAllocation.cursor + currentAllocation.freeBytesAhead <= currentAllocation.byteCapacity;
				} else {
					// Move the buffer to the end of the list, and pretend we've shifted it to the left as detailed above
					buffers.addLast(buffer);
					currentAllocation.cursor += buffer.byteCapacity;
				}
			}

			// Consume the remaining free bytes of the allocation
			buffers.addLast(new Buffer(currentAllocation.freeBytesAhead));
			// Advance to the next allocation, or the beginning of the same allocation if there is only one
			nextAllocation();

			if (currentAllocation.bytesFromEnd() < numBytes) {
				log.error("Failed to reserve space for {} bytes. Too large to fit in allocation {} of size {}",
					numBytes, currentAllocationIndex, currentAllocation.byteCapacity);
				terminationHook.run();
				return 0;
			}
		}

		while (currentAllocation.freeBytesAhead < numBytes) {
			if (removeOldestCacheEntry() == null) {
				log.error("No more cache entries left to free, yet there aren't enough free bytes ({} < {})",
					currentAllocation.freeBytesAhead, numBytes);
				terminationHook.run();
				return 0;
			}
		}

		return currentAllocation.reserve(numBytes);
	}

	private Buffer removeOldestCacheEntry() {
		Buffer buffer = buffers.pollFirst();

		if (buffer != null) {
			if (!buffer.endMarker) {
				cache.remove(buffer.hash, buffer);
				// Normally, these addresses will be equal, but in case they've been "shifted" as detailed in the
				// reserve function, the buffer's actual address will be larger than the cursor position
				assert currentAllocation.address + currentAllocation.cursor + currentAllocation.freeBytesAhead <=
					MemoryUtil.memAddress0(buffer.intBuffer == null ? buffer.floatBuffer : buffer.intBuffer);
			}

			currentAllocation.freeBytesAhead += buffer.byteCapacity;
			assert currentAllocation.cursor + currentAllocation.freeBytesAhead <= currentAllocation.byteCapacity;
		}

		return buffer;
	}

	public IntBuffer getIntBuffer(long hash) {
		Buffer buffer = get(hash);
		if (buffer == null)
			return null;
		return buffer.intBuffer;
	}

	public FloatBuffer getFloatBuffer(long hash) {
		Buffer buffer = get(hash);
		if (buffer == null)
			return null;
		return buffer.floatBuffer;
	}

	public IntBuffer reserveIntBuffer(long hash, int capacity) {
		long address = reserve(capacity * 4L);
		if (address == 0L)
			return null;
		Buffer buffer = new Buffer(hash, MemoryUtil.memIntBuffer(address, capacity));
		cache.put(hash, buffer);
		buffers.addLast(buffer);
		return buffer.intBuffer;
	}

	public FloatBuffer reserveFloatBuffer(long hash, int capacity) {
		long address = reserve(capacity * 4L);
		if (address == 0L)
			return null;
		Buffer buffer = new Buffer(hash, MemoryUtil.memFloatBuffer(address, capacity));
		cache.put(hash, buffer);
		buffers.addLast(buffer);
		return buffer.floatBuffer;
	}
}
