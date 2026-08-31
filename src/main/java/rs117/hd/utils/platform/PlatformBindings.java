package rs117.hd.utils.platform;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.OSType;
import org.lwjgl.system.SharedLibrary;

import static org.lwjgl.system.APIUtil.apiCreateLibrary;
import static org.lwjgl.system.APIUtil.apiGetFunctionAddress;

@Slf4j
public abstract class PlatformBindings {
	private static PlatformBindings BINDINGS;

	static synchronized PlatformBindings createBindings() {
		if (BINDINGS != null)
			return BINDINGS;

		final OSType osType = OSType.getOSType();
		if(osType != OSType.Windows && osType != OSType.Linux && osType != OSType.MacOS) {
			BINDINGS = new DummyBindings();
			log.debug("Unknown Platform, falling back to dummy bindings:");
			return BINDINGS;
		}

		try {
			final PlatformBindings candidate;
			switch (OSType.getOSType()) {
				case Windows:
					candidate = new WindowsBindings();
					break;
				case Linux:
					candidate = new LinuxBindings();
					break;
				case MacOS:
					candidate = new MacBindings();
					break;
				default:
					candidate = new DummyBindings();
					break;
			}

			if(candidate.init()) {
				BINDINGS = candidate;
				log.info("Initialized platform bindings");
			}
		} catch (Throwable t) {
			log.error(t.toString());
		}

		if(BINDINGS == null) {
			BINDINGS = new DummyBindings();
			log.warn("Failed to initialize platform bindings, falling back to dummy bindings");
		}

		return BINDINGS;
	}

	private final String libraryName;
	private SharedLibrary library;

	PlatformBindings(String libraryName) {
		this.libraryName = libraryName;
	}

	boolean init() { return true; }

	protected long findFunction(String name) {
		if(library == null)
			library = apiCreateLibrary(libraryName);
		return apiGetFunctionAddress(library, name);
	}

	public static boolean setAffinity(long mask) {
		if (BINDINGS == null)
			createBindings();

		if(BINDINGS.supportsSetAffinity()) {
			try {
				return BINDINGS.setAffinityImpl(mask);
			} catch (Throwable t) {
				log.error("Failed to set affinity", t);
			}
		}

		return false;
	}

	boolean supportsSetAffinity() { return false; }
	boolean setAffinityImpl(long mask) { return false; }

	public static int getCpu() {
		if (BINDINGS == null)
			createBindings();

		if(BINDINGS.supportsGetCpu()) {
			try {
				return BINDINGS.getCpuImpl();
			} catch (Throwable t) {
				log.error("Failed to get Cpu", t);
			}
		}

		return -1;
	}

	boolean supportsGetCpu() { return false; }
	int getCpuImpl() { return -1; }
}
