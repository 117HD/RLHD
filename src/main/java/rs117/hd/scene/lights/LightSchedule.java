package rs117.hd.scene.lights;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.GsonUtils;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class LightSchedule {
	private static final float DEFAULT_RANDOM_OFFSET = 2.7f;

	private Turn turn = Turn.ON;
	private Range[] during;
	public float randomOffset = DEFAULT_RANDOM_OFFSET;

	private enum Turn { ON, OFF }

	@RequiredArgsConstructor
	private static class Range {
		private final float from;
		private final float through;
		private final Mode mode;

		private enum Mode { ASCENDING, DESCENDING, BOTH }

		private enum Named {
			DAWN(-2, -8.8f, Mode.ASCENDING),
			SUNRISE(5, -2, Mode.ASCENDING),
			DAY(-2, 5, Mode.BOTH),
			SUNSET(5, -2, Mode.DESCENDING),
			DUSK(-2, -8.8f, Mode.DESCENDING),
			NIGHT(5, -2, Mode.BOTH),
			DEEP_NIGHT(-8.8f, -18, Mode.BOTH),
			;

			private static final Named[] VALUES = values();

			private final Range range;

			Named(float from, float through, Mode mode) {
				range = new Range(from, through, mode);
			}
		}

		private static Named resolvedNamedRange(Range range) {
			for (int i = 0; i < Named.VALUES.length; i++) {
				Named named = Named.VALUES[i];
				if (range.from == named.range.from &&
					range.through == named.range.through &&
					range.mode == named.range.mode)
					return named;
			}
			return null;
		}
	}

	/**
	 * Return this schedule's [0, 1] activation factor at the current sun altitude.
	 */
	public float getActivation(float sunAltitude, boolean sunDescending, float offset) {
		sunAltitude -= offset;
		float rangeActivation = 0;
		for (int i = 0; i < during.length; i++)
			rangeActivation = max(rangeActivation, getRangeActivation(during[i], sunAltitude, sunDescending));
		return turn == Turn.ON ? rangeActivation : 1 - rangeActivation;
	}

	private static float getRangeActivation(Range range, float sunAltitude, boolean sunDescending) {
		float transition = 1 - smoothstep(range.through, range.from, sunAltitude);
		// Directional phases are a smooth pulse between their two altitude boundaries.
		switch (range.mode) {
			case ASCENDING:
				return sunDescending ? 0 : 4 * transition * (1 - transition);
			case DESCENDING:
				return sunDescending ? 4 * transition * (1 - transition) : 0;
			case BOTH:
				return transition;
		}
		throw new IllegalStateException("Unhandled light schedule range mode: " + range.mode);
	}

	public static class Adapter extends TypeAdapter<LightSchedule> {
		private final JsonParser JSON_ELEMENT_PARSER = new JsonParser();

		@Override
		public LightSchedule read(JsonReader in) {
			String location = GsonUtils.location(in);
			try {
				JsonElement json = JSON_ELEMENT_PARSER.parse(in);
				if (json.isJsonNull())
					return null;

				var schedule = new LightSchedule();
				if (json.isJsonPrimitive()) {
					schedule.during = new Range[] { parseRange(json) };
				} else {
					var object = json.getAsJsonObject();
					if (object.has("turn"))
						schedule.turn = Turn.valueOf(object.get("turn").getAsString());
					if (!object.has("during"))
						throw new IllegalArgumentException("missing 'during'");
					schedule.during = parseRanges(object.get("during"), location);
					if (object.has("randomOffset"))
						schedule.randomOffset = object.get("randomOffset").getAsFloat();
				}

				if (!Float.isFinite(schedule.randomOffset) || schedule.randomOffset < 0)
					throw new IllegalArgumentException("'randomOffset' must be finite and non-negative");
				if (schedule.during.length == 0)
					throw new IllegalArgumentException("'during' contains no valid ranges");
				return schedule;
			} catch (RuntimeException ex) {
				log.error("Invalid light schedule at {}; ignoring schedule: {}", location, ex.getMessage());
				return null;
			}
		}

		private static Range[] parseRanges(JsonElement json, String location) {
			if (!json.isJsonArray())
				return new Range[] { parseRange(json) };

			var ranges = new ArrayList<Range>();
			var array = json.getAsJsonArray();
			for (int i = 0; i < array.size(); i++) {
				try {
					ranges.add(parseRange(array.get(i)));
				} catch (RuntimeException ex) {
					log.error("Invalid light schedule range at {}; ignoring range: {}", location, ex.getMessage());
				}
			}
			return ranges.toArray(Range[]::new);
		}

		private static Range parseRange(JsonElement json) {
			if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString())
				return Range.Named.valueOf(json.getAsString()).range;

			var object = json.getAsJsonObject();
			boolean hasMode = object.has("mode");
			if (!object.has("from") || !object.has("through") || object.size() != (hasMode ? 3 : 2))
				throw new IllegalArgumentException("range must contain 'from', 'through', and optionally 'mode'");

			float from = parseAltitude(object.get("from"), true);
			float through = parseAltitude(object.get("through"), false);
			if (from == through)
				throw new IllegalArgumentException("equal 'from' and 'through' altitudes cover the full cycle");
			return new Range(
				from, through,
				hasMode ? Range.Mode.valueOf(object.get("mode").getAsString()) : Range.Mode.BOTH
			);
		}

		private static float parseAltitude(JsonElement json, boolean from) {
			if (json.isJsonPrimitive()) {
				var value = json.getAsJsonPrimitive();
				if (value.isString()) {
					Range range = Range.Named.valueOf(value.getAsString()).range;
					return from ? range.from : range.through;
				}
				if (value.isNumber()) {
					float altitude = value.getAsFloat();
					if (Float.isFinite(altitude) && altitude >= -90 && altitude <= 90)
						return altitude;
				}
			}
			throw new IllegalArgumentException("altitudes must be either predefined names or numbers between -90 and 90");
		}

		@Override
		public void write(JsonWriter out, LightSchedule schedule) throws IOException {
			if (schedule == null) {
				out.nullValue();
				return;
			}

			if (schedule.during.length == 1 &&
				Range.resolvedNamedRange(schedule.during[0]) != null &&
				schedule.turn == Turn.ON &&
				schedule.randomOffset == DEFAULT_RANDOM_OFFSET
			) {
				writeRange(out, schedule.during[0]);
				return;
			}

			out.beginObject();
			if (schedule.turn != Turn.ON)
				out.name("turn").value(schedule.turn.name());
			out.name("during");
			boolean array = schedule.during.length > 1;
			if (array)
				out.beginArray();
			for (int i = 0; i < schedule.during.length; i++)
				writeRange(out, schedule.during[i]);
			if (array)
				out.endArray();
			if (schedule.randomOffset != DEFAULT_RANDOM_OFFSET)
				out.name("randomOffset").value(schedule.randomOffset);
			out.endObject();
		}

		private static void writeRange(JsonWriter out, Range range) throws IOException {
			Range.Named named = Range.resolvedNamedRange(range);
			if (named != null) {
				out.value(named.name());
				return;
			}

			out.beginObject();
			out.name("from").value(range.from);
			out.name("through").value(range.through);
			if (range.mode != Range.Mode.BOTH)
				out.name("mode").value(range.mode.name());
			out.endObject();
		}
	}
}
