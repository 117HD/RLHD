/*
 * Copyright (c) 2026
 * All rights reserved.
 */
package rs117.hd.scene.particles.effector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

public final class EffectorRef {

	private static final List<EffectorDefinition> BUILT_IN_REGISTRY = new ArrayList<>();
	private static final Set<String> JSON_REF_IDS = new LinkedHashSet<>();

	private final String id;
	@Nullable
	private final EffectorDefinition builtInDefinition;

	private EffectorRef(String id, @Nullable EffectorDefinition builtInDefinition) {
		this.id = id.toUpperCase();
		this.builtInDefinition = builtInDefinition;
	}

	public static EffectorRef json(String id) {
		if (id == null || id.isEmpty()) {
			throw new IllegalArgumentException("Effector json ref requires an id");
		}
		String upper = id.toUpperCase();
		JSON_REF_IDS.add(upper);
		return new EffectorRef(upper, null);
	}

	static EffectorRef builtIn(EffectorDefinition definition) {
		if (definition.id == null || definition.id.isEmpty()) {
			throw new IllegalArgumentException("Built-in effector requires an id");
		}
		BUILT_IN_REGISTRY.add(definition);
		return new EffectorRef(definition.id, definition);
	}

	static List<EffectorDefinition> builtInRegistry() {
		return BUILT_IN_REGISTRY.isEmpty() ? List.of() : Collections.unmodifiableList(BUILT_IN_REGISTRY);
	}

	static void validateJsonRefs(Map<String, EffectorDefinition> definitions) {
		if (JSON_REF_IDS.isEmpty()) {
			return;
		}
		List<String> missing = new ArrayList<>();
		for (String id : JSON_REF_IDS) {
			if (!definitions.containsKey(id)) {
				missing.add(id);
			}
		}
		if (!missing.isEmpty()) {
			throw new IllegalStateException(
				"[Particles] Missing effector definition(s) for json ref(s): " + missing
			);
		}
	}

	public String id() {
		return id;
	}

	@Nullable
	public EffectorDefinition builtInDefinition() {
		return builtInDefinition;
	}
}
