package rs117.hd.resourcepacks;

import lombok.Value;

/** Immutable state rendered by the resource-pack download view. */
@Value
public class ResourcePackStatus {
	String title;
	String description;
}
