package rs117.hd.opengl.uniforms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public class UBOWorldViews extends UniformBuffer<GLBuffer> {
	// The max concurrent visible worldviews is 25
	// Source: https://discord.com/channels/886733267284398130/1419633364817674351/1429129853592146041
	public static final int MAX_SIMULTANEOUS_WORLD_VIEWS = 128;

	@RequiredArgsConstructor
	public class WorldViewStruct extends StructProperty {
		public final int worldViewIdx;

		public final Property projection = addProperty(PropertyType.Mat4, "projection");
		public final Property tint = addProperty(PropertyType.IVec4, "tint");

		public WorldView worldView;

		public void update() {
			var proj = worldView.getMainWorldProjection();
			projection.set(proj instanceof FloatProjection ? ((FloatProjection) proj).getProjection() : Mat4.identity());

			var scene = worldView.getScene();
			if (scene == null) {
				tint.set(0, 0, 0, 0);
			} else {
				tint.set(
					scene.getOverrideHue(),
					scene.getOverrideSaturation(),
					scene.getOverrideLuminance(),
					scene.getOverrideAmount()
				);
			}
		}

		public synchronized void free() {
			activeIndices.remove((Integer) worldViewIdx);
			freeIndices.add(worldViewIdx);
			worldView = null;
		}
	}

	private final WorldViewStruct[] uboStructs = new WorldViewStruct[MAX_SIMULTANEOUS_WORLD_VIEWS];
	private final ArrayList<Integer> activeIndices = new ArrayList<>();
	private final ArrayDeque<Integer> freeIndices = new ArrayDeque<>();

	public UBOWorldViews() {
		super(GL_DYNAMIC_DRAW);
		for (int i = 0; i < MAX_SIMULTANEOUS_WORLD_VIEWS; i++) {
			uboStructs[i] = addStruct(new WorldViewStruct(i));
			freeIndices.add(i);
		}
	}

	@Override
	protected synchronized void preUpload() {
		for (Integer activeIndex : activeIndices) {
			uboStructs[activeIndex].update();
		}
	}

	public synchronized WorldViewStruct obtain(WorldView worldView) {
		if (freeIndices.isEmpty()) {
			log.warn("Too many world views at once: {}", MAX_SIMULTANEOUS_WORLD_VIEWS);
			return null;
		}

		WorldViewStruct struct = uboStructs[freeIndices.poll()];
		struct.worldView = worldView;
		struct.update();
		activeIndices.add(struct.worldViewIdx);
		return struct;
	}
}
