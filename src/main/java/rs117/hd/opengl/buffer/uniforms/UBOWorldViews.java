package rs117.hd.opengl.buffer.uniforms;

import java.util.Arrays;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.opengl.buffer.UniformStructuredBuffer;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public class UBOWorldViews extends UniformStructuredBuffer<GLBuffer> {
	// The max concurrent visible worldviews is 25
	// Source: https://discord.com/channels/886733267284398130/1419633364817674351/1429129853592146041
	public static final int MAX_SIMULTANEOUS_WORLD_VIEWS = 128;

	public static class WorldViewStruct extends StructProperty {
		public final Property projection = addProperty(PropertyType.Mat4, "projection");
		public final Property tint = addProperty(PropertyType.IVec4, "tint");
	}

	@Inject
	private Client client;

	private final WorldViewStruct[] uboStructs = addStructs(new WorldViewStruct[MAX_SIMULTANEOUS_WORLD_VIEWS], WorldViewStruct::new);
	private final int[] indexMapping;

	public UBOWorldViews() {
		super(GL_DYNAMIC_DRAW);
		indexMapping = new int[ZoneRenderer.MAX_WORLDVIEWS];
	}

	public void update() {
		Arrays.fill(indexMapping, -1);

		int index = 0;

		// Update index mapping, and projections & tints for all current worldviews
		for (WorldEntity entity : client.getTopLevelWorldView().worldEntities()) {
			WorldView worldView = entity.getWorldView();
			int id = worldView.getId();
			if (id == -1)
				continue;
			if (id < 0 || id >= indexMapping.length) {
				log.warn("Unexpected worldview ID: {}", id);
				continue;
			}

			if (index == uboStructs.length) {
				log.warn("Too many world views at once: {}", MAX_SIMULTANEOUS_WORLD_VIEWS);
				break;
			}

			indexMapping[id] = index;
			var struct = uboStructs[index++];

			var proj = worldView.getMainWorldProjection();
			struct.projection.set(proj instanceof FloatProjection ? ((FloatProjection) proj).getProjection() : Mat4.identity());

			var scene = worldView.getScene();
			if (scene == null) {
				struct.tint.set(0, 0, 0, 0);
			} else {
				struct.tint.set(
					scene.getOverrideHue(),
					scene.getOverrideSaturation(),
					scene.getOverrideLuminance(),
					scene.getOverrideAmount()
				);
			}
		}

		upload();
	}

	public int getIndex(@Nullable Scene scene) {
		if (scene == null)
			return -1;
		return getIndex(scene.getWorldViewId());
	}

	public int getIndex(@Nullable WorldView worldView) {
		if (worldView == null)
			return -1;
		return getIndex(worldView.getId());
	}

	public int getIndex(int worldViewId) {
		if (worldViewId < 0 || worldViewId >= indexMapping.length)
			return -1;
		return indexMapping[worldViewId];
	}
}
