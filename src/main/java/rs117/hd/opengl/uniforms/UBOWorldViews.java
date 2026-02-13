package rs117.hd.opengl.uniforms;

import java.util.ArrayDeque;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class UBOWorldViews extends UniformBuffer<GLBuffer> {
	// The max concurrent visible worldviews is 25
	// Source: https://discord.com/channels/886733267284398130/1419633364817674351/1429129853592146041
	public static final int MAX_SIMULTANEOUS_WORLD_VIEWS = 128;
	private static final float[] IDENTITY_MATRIX = Mat4.identity();

	@RequiredArgsConstructor
	public class WorldViewStruct extends StructProperty {
		public final int worldViewIdx;

		public final Property projection = addProperty(PropertyType.Mat4, "projection");
		public final Property tint = addProperty(PropertyType.IVec4, "tint");

		public WorldView worldView;

		private final float[] currentProjection = new float[16];
		private final int[] currentTint = new int[4];
		private final int[] newTint = new int[4];

		public void update() {
			float[] newProjection = IDENTITY_MATRIX;
			final Projection worldViewProjection = worldView.getMainWorldProjection();
			if (worldViewProjection instanceof FloatProjection)
				newProjection = ((FloatProjection) worldViewProjection).getProjection();

			if (!Arrays.equals(currentProjection, newProjection)) {
				projection.set(newProjection);
				copyTo(currentProjection, newProjection);
			}

			newTint[3] = 0;
			Scene scene = worldView.getScene();
			if (scene != null) {
				newTint[0] = scene.getOverrideHue();
				newTint[1] = scene.getOverrideSaturation();
				newTint[2] = scene.getOverrideLuminance();
				newTint[3] = scene.getOverrideAmount();
			}
			if (!Arrays.equals(currentTint, newTint)) {
				tint.set(newTint);
				copyTo(currentTint, newTint);
			}
		}

		public void project(float[] out) {
			Mat4.mulVec(out, currentProjection, out);
		}

		public synchronized void free() {
			freeIndices.add(worldViewIdx);
			worldView = null;
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

	public synchronized WorldViewStruct acquire(WorldView worldView) {
		if (freeIndices.isEmpty()) {
			log.warn("Too many world views at once: {}", MAX_SIMULTANEOUS_WORLD_VIEWS);
			return null;
		}

		WorldViewStruct struct = uboStructs[freeIndices.poll()];
		struct.worldView = worldView;
		struct.update();
		return struct;
	}
}
