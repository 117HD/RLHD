package rs117.hd.resourcepacks;

import javax.annotation.Nullable;
import lombok.Getter;
import rs117.hd.resourcepacks.data.Manifest;

@Getter
public class ResourcePackUpdate {

	private final PackEventType type;
	@Nullable
	private final AbstractResourcePack pack;
	@Nullable
	private final Manifest manifest;
	private String internalName = "";

	public ResourcePackUpdate(PackEventType type) {
		this(type, null, null);
	}

	public ResourcePackUpdate(PackEventType type, AbstractResourcePack pack) {
		this(type, pack,  pack.getManifest());
	}

	public ResourcePackUpdate(PackEventType type, AbstractResourcePack pack, Manifest manifest) {
		this.type = type;
		this.pack = pack;
		this.manifest = manifest;
		this.internalName = manifest == null ? "" : manifest.getInternalName();
	}

	/**
	 * Checks if the event type matches any of the provided types.
	 * @param types The types to check against
	 * @return true if the event type matches any of the provided types
	 */
	public boolean stateIs(PackEventType... types) {
		for (PackEventType type : types) {
			if (this.type == type) {
				return true;
			}
		}
		return false;
	}
}

