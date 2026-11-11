package rs117.hd.opengl.uniforms;

import java.util.ArrayDeque;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

		public synchronized void free() {
			freeIndices.add(worldViewIdx);
		}
	}

	private final WorldViewStruct[] uboStructs = new WorldViewStruct[MAX_SIMULTANEOUS_WORLD_VIEWS];
	private final ArrayDeque<Integer> freeIndices = new ArrayDeque<>();

	public UBOWorldViews() {
		super(GL_DYNAMIC_DRAW);
		for (int i = 0; i < MAX_SIMULTANEOUS_WORLD_VIEWS; i++) {
			uboStructs[i] = addStruct(new WorldViewStruct(i));
			freeIndices.add(i);
		}
	}

	public synchronized WorldViewStruct acquire() {
		if (freeIndices.isEmpty()) {
			log.warn("Too many world views at once: {}", MAX_SIMULTANEOUS_WORLD_VIEWS);
			return null;
		}

		return uboStructs[freeIndices.poll()];
	}
}
