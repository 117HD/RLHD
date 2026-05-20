package rs117.hd.utils.platform;

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.system.JNI.invokeI;
import static org.lwjgl.system.JNI.invokeP;
import static org.lwjgl.system.JNI.invokePPPI;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.system.Pointer.POINTER_SIZE;

@Slf4j
public final class LinuxBindings extends PlatformBindings {
	private static final int CPU_SET_BYTES = 128;

	private final long pthread_self = findFunction("pthread_self");
	private final long pthread_setaffinity_np = findFunction("pthread_setaffinity_np");
	private final long sched_getcpu = findFunction("sched_getcpu");

	// https://man7.org/linux/man-pages
	LinuxBindings() { super("libc.so.6"); }

	@Override
	boolean supportsSetAffinity() { return pthread_self != 0 && pthread_setaffinity_np != 0; }

	@Override
	boolean setAffinityImpl(long mask) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long cpuset = stack.ncalloc(POINTER_SIZE, CPU_SET_BYTES, 1);
			memPutLong(cpuset, mask);

			long thread = invokeP(pthread_self);
			return invokePPPI(thread, CPU_SET_BYTES, cpuset, pthread_setaffinity_np) == 0;
		}
	}

	@Override
	boolean supportsGetCpu() { return sched_getcpu != 0; }

	@Override
	int getCpuImpl() { return invokeI(sched_getcpu); }
}