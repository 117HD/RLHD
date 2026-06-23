package rs117.hd.scene.lights;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import javax.annotation.Nullable;


public enum LightTimeOfDay {
	DAY(0f, 0f),
	DAWN(0f, 0.22f),
	DUSK(0f, 0.22f),
	TWILIGHT(0.22f, 0.44f),
	NIGHT(0.44f, 0.65f),
	DEEP_NIGHT(0.65f, 1f);

	public final float start;
	public final float end;

	LightTimeOfDay(float start, float end) {
		this.start = start;
		this.end = end;
	}

	@Nullable
	public static LightTimeOfDay fromString(@Nullable String value) {
		if (value == null || value.isEmpty())
			return null;
		return valueOf(value.trim().toUpperCase().replace(' ', '_'));
	}

	public static class Adapter extends TypeAdapter<LightTimeOfDay> {
		@Override
		public LightTimeOfDay read(JsonReader in) throws IOException {
			return fromString(in.nextString());
		}

		@Override
		public void write(JsonWriter out, LightTimeOfDay value) throws IOException {
			out.value(value == null ? null : value.name());
		}
	}
}
