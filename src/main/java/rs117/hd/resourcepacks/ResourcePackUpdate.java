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
	// For MOVED events, track the old and new indices
	@Getter
	private int fromIndex = -1;
	@Getter
	private int toIndex = -1;

	public ResourcePackUpdate(PackEventType type) {
		this(type, null, null);
	}

	public ResourcePackUpdate(PackEventType type, AbstractResourcePack pack) {
		this(type, pack, pack != null ? pack.getManifest() : null);
	}

	public ResourcePackUpdate(PackEventType type, AbstractResourcePack pack, Manifest manifest) {
		this.type = type;
		this.pack = pack;
		this.manifest = manifest;
		this.internalName = manifest == null ? "" : manifest.getInternalName();
	}

	public ResourcePackUpdate(PackEventType type, AbstractResourcePack pack, int fromIndex, int toIndex) {
		this(type, pack, pack != null ? pack.getManifest() : null);
		this.fromIndex = fromIndex;
		this.toIndex = toIndex;
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

