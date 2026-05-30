package rs117.hd.utils.platform;

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.Pointer;

import static org.lwjgl.system.JNI.invokeI;
import static org.lwjgl.system.JNI.invokeP;
import static org.lwjgl.system.JNI.invokePP;

@Slf4j
public final class WindowsBindings extends PlatformBindings {
	private final long GetCurrentThread = findFunction("GetCurrentThread");
	private final long SetThreadAffinityMask = findFunction("SetThreadAffinityMask");
	private final long GetCurrentProcessorNumber = findFunction("GetCurrentProcessorNumber");

	// https://learn.microsoft.com/en-us/windows/win32/
	WindowsBindings() { super("kernel32"); }

	@Override
	boolean supportsSetAffinity() { return GetCurrentThread != 0L && SetThreadAffinityMask != 0L; }

	@Override
	boolean setAffinityImpl(long mask) {
		if (Pointer.POINTER_SIZE == 4 && (mask >>> 32) != 0)
			throw new IllegalArgumentException("Mask exceeds 32-bit width");

		long thread = invokeP(GetCurrentThread);
		return invokePP(thread, mask, SetThreadAffinityMask) == 0;
	}

	@Override
	boolean supportsGetCpu() { return GetCurrentProcessorNumber != 0; }

	@Override
	int getCpuImpl() { return invokeI(GetCurrentProcessorNumber); }
}
