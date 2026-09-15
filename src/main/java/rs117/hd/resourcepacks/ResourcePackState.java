package rs117.hd.resourcepacks;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/** Persistent user state for installed resource packs. */
final class ResourcePackState {
	List<String> packOrder = new ArrayList<>();
	Map<String, String> sha256ByPack = new LinkedHashMap<>();
	@SerializedName(value = "packIdentities", alternate = "unofficialPackIdentities")
	Map<String, PackIdentity> packIdentities = new LinkedHashMap<>();
	Set<String> disabledPacks = new LinkedHashSet<>();
	Map<String, Map<String, AppliedSetting>> settingsByPack = new LinkedHashMap<>();

	static final class AppliedSetting {
		String previousValue;
		String appliedValue;
	}

	static final class PackIdentity {
		String filenameHash;
		String sha256;

		PackIdentity(String filenameHash, String sha256) {
			this.filenameHash = filenameHash;
			this.sha256 = sha256;
		}
	}
}
