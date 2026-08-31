package rs117.hd.utils.platform;

import java.nio.ByteBuffer;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.libffi.FFICIF;

import static org.lwjgl.system.JNI.invokeP;
import static org.lwjgl.system.JNI.invokePI;
import static org.lwjgl.system.libffi.LibFFI.FFI_DEFAULT_ABI;
import static org.lwjgl.system.libffi.LibFFI.FFI_OK;
import static org.lwjgl.system.libffi.LibFFI.ffi_call;
import static org.lwjgl.system.libffi.LibFFI.ffi_prep_cif;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_pointer;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_sint32;

@Slf4j
public final class MacBindings extends PlatformBindings {
	private static final int THREAD_AFFINITY_POLICY = 4;

	private final long pthread_self = findFunction("pthread_self");
	private final long pthread_mach_thread_np = findFunction("pthread_mach_thread_np");
	private final long thread_policy_set = findFunction("thread_policy_set");

	private FFICIF CIF;

	// https://developer.apple.com/library/archive/documentation/Darwin/Conceptual/KernelProgramming
	MacBindings() { super("/usr/lib/libSystem.B.dylib"); }

	@Override
	boolean init() {
		CIF = FFICIF.malloc();

		PointerBuffer args = BufferUtils.createPointerBuffer(4);
		args.put(ffi_type_sint32);
		args.put(ffi_type_sint32);
		args.put(ffi_type_pointer);
		args.put(ffi_type_sint32);
		args.flip();

		int status = ffi_prep_cif(
			CIF,
			FFI_DEFAULT_ABI,
			ffi_type_sint32,
			args
		);

		if (status != FFI_OK) {
			log.error("ffi_prep_cif failed: {}", status);
			return false;
		}

		return true;
	}

	@Override
	boolean supportsSetAffinity() { return pthread_self != 0 && pthread_mach_thread_np != 0 && thread_policy_set != 0; }

	@Override
	boolean setAffinityImpl(long mask) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long pthread = invokeP(pthread_self);
			int machThread = invokePI(pthread, pthread_mach_thread_np);
			int policyValue = (int) (mask & 0x7FFFFFFF);

			PointerBuffer args = stack.mallocPointer(4);
			args.put(0, stack.ints(machThread));
			args.put(1, stack.ints(THREAD_AFFINITY_POLICY));
			args.put(2, stack.ints(policyValue));
			args.put(3, stack.ints(1));

			ByteBuffer result = stack.malloc(4);
			ffi_call(CIF, thread_policy_set, result, args);

			return result.getInt(0) == 0;
		}
	}
}
