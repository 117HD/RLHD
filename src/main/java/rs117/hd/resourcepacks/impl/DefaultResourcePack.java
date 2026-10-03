package rs117.hd.resourcepacks.impl;

import java.util.List;
import rs117.hd.resourcepacks.AbstractResourcePack;
import rs117.hd.utils.ResourcePath;

public class DefaultResourcePack extends AbstractResourcePack {
	public DefaultResourcePack(ResourcePath resourcePackFileIn) {
		super(resourcePackFileIn);
	}

	@Override
	public List<ResourcePath> listJsonFiles(String directory) {
		// Classpath resources cannot be listed, but the built-in environment definition has a known path.
		if (!directory.equals("environments")) {
			return List.of();
		}

		ResourcePath environments = getResource(directory, "environments.json");
		return environments.exists() ? List.of(environments) : List.of();
	}

	@Override
	protected boolean hasPackContent() {
		return true;
	}
}
