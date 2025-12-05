package rs117.hd.opengl.buffer.uniforms;

import java.util.ArrayDeque;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.buffer.UniformStructuredBuffer;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;

@Slf4j
public class UBOZoneData extends UniformStructuredBuffer<GLBuffer> {

	public static final int MAX_ZONES = 4000; // Struct is 16 Bytes, UBO Max size is 64 KB

	@RequiredArgsConstructor
	public class ZoneStruct extends StructProperty {
		public final int zoneIdx;

		public final Property worldViewIdx = addProperty(PropertyType.Int, "worldViewIdx");
		public final Property offsetX = addProperty(PropertyType.Int, "offsetX");
		public final Property offsetZ = addProperty(PropertyType.Int, "offsetZ");
		public final Property reveal = addProperty(PropertyType.Float, "reveal");

		public synchronized void free() {
			freeIndices.add(zoneIdx);
		}
	}

	private final ZoneStruct[] uboStructs = new ZoneStruct[MAX_ZONES];
	private final ArrayDeque<Integer> freeIndices = new ArrayDeque<>();

	public UBOZoneData() {
		super(GL_DYNAMIC_DRAW);

		for(int i = 0; i < MAX_ZONES; i++) {
			uboStructs[i] = addStruct(new ZoneStruct(i));
			freeIndices.add(i);
		}
	}

	public synchronized ZoneStruct acquire() {
		if (freeIndices.isEmpty()) {
			log.warn("Too many world views at once: {}", MAX_ZONES);
			return null;
		}

		return uboStructs[freeIndices.poll()];
	}
}
