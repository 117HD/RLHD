package rs117.hd.scene.lights;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.GsonUtils;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class LightSchedule {
	private static final float DEFAULT_RANDOM_OFFSET = 2.7f;

	private Turn turn = Turn.ON;
	private Range[] during;
	public float randomOffset = DEFAULT_RANDOM_OFFSET;

	private enum Turn {
		ON,
		OFF
	}

	private enum Phase {
		DAWN(-2, -8.8f, Range.Mode.ASCENDING),
		SUNRISE(5, -2, Range.Mode.ASCENDING),
		DAY(-2, 5, Range.Mode.BOTH),
		SUNSET(5, -2, Range.Mode.DESCENDING),
		DUSK(-2, -8.8f, Range.Mode.DESCENDING),
		NIGHT(5, -2, Range.Mode.BOTH),
		// Preserve the old deep-night altitude boundaries.
		DEEP_NIGHT(-8.8f, -18, Range.Mode.BOTH);

		private static final Phase[] VALUES = values();

		private final Range[] ranges;

		Phase(float from, float through, Range.Mode mode) {
			ranges = new Range[] { new Range(from, through, mode) };
		}
	}

	private static class Range {
		private enum Mode {
			BOTH,
			ASCENDING,
			DESCENDING
		}

		private final float from;
		private final float through;
		private final Mode mode;

		private Range(float from, float through, Mode mode) {
			this.from = from;
			this.through = through;
			this.mode = mode;
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
			case BOTH:
				return transition;
			case ASCENDING:
				return sunDescending ? 0 : 4 * transition * (1 - transition);
			case DESCENDING:
				return sunDescending ? 4 * transition * (1 - transition) : 0;
		}
		throw new IllegalStateException("Unhandled light schedule range mode: " + range.mode);
	}

	public static class Adapter extends TypeAdapter<LightSchedule> {
		@Override
		public LightSchedule read(JsonReader in) throws IOException {
			String scheduleLocation = GsonUtils.location(in);
			if (in.peek() == JsonToken.NULL) {
				in.nextNull();
				return null;
			}
			if (in.peek() != JsonToken.STRING && in.peek() != JsonToken.BEGIN_OBJECT) {
				log.error("Expected a light schedule at {}; ignoring value", scheduleLocation);
				in.skipValue();
				return null;
			}

			var schedule = new LightSchedule();
			if (in.peek() == JsonToken.STRING) {
				schedule.during = readRange(in);
				return schedule.during == null ? null : schedule;
			}

			boolean valid = true;
			in.beginObject();
			while (in.hasNext()) {
				String name = in.nextName();
				String location = GsonUtils.location(in);
				switch (name) {
					case "turn":
						if (in.peek() != JsonToken.STRING) {
							log.error("Light schedule turn must be ON or OFF at {}; ignoring schedule", location);
							in.skipValue();
							valid = false;
							break;
						}
						try {
							schedule.turn = Turn.valueOf(in.nextString());
						} catch (IllegalArgumentException ex) {
							log.error("Unknown light schedule turn at {}; ignoring schedule", location);
							valid = false;
						}
						break;
					case "during":
						if (in.peek() != JsonToken.BEGIN_ARRAY) {
							schedule.during = readRange(in);
							break;
						}
						var ranges = new ArrayList<Range>();
						in.beginArray();
						while (in.hasNext()) {
							Range[] range = readRange(in);
							if (range != null)
								Collections.addAll(ranges, range);
						}
						in.endArray();
						schedule.during = ranges.toArray(Range[]::new);
						break;
					case "randomOffset":
						if (in.peek() != JsonToken.NUMBER) {
							log.error("Light schedule randomOffset must be a number at {}; ignoring schedule", location);
							in.skipValue();
							valid = false;
							break;
						}
						schedule.randomOffset = (float) in.nextDouble();
						if (!Float.isFinite(schedule.randomOffset) || schedule.randomOffset < 0) {
							log.error("Light schedule randomOffset must be finite and non-negative at {}; ignoring schedule", location);
							valid = false;
						}
						break;
					default:
						log.error("Unknown light schedule property at {}; ignoring schedule", location);
						in.skipValue();
						valid = false;
				}
			}
			in.endObject();

			return valid && schedule.during != null && schedule.during.length > 0 ? schedule : null;
		}

		private static Range[] readRange(JsonReader in) throws IOException {
			String location = GsonUtils.location(in);
			if (in.peek() == JsonToken.STRING) {
				String name = in.nextString();
				try {
					return Phase.valueOf(name).ranges;
				} catch (IllegalArgumentException ex) {
					log.error("Unknown light schedule phase '{}' at {}; ignoring range", name, location);
					return null;
				}
			}

			if (in.peek() != JsonToken.BEGIN_OBJECT) {
				log.error("Expected a named phase or { from, through } range at {}; ignoring range", location);
				in.skipValue();
				return null;
			}

			Float from = null;
			Float through = null;
			boolean valid = true;
			in.beginObject();
			while (in.hasNext()) {
				String property = in.nextName();
				if (!property.equals("from") && !property.equals("through")) {
					log.error("Unknown light schedule range property at {}; ignoring range", GsonUtils.location(in));
					in.skipValue();
					valid = false;
					continue;
				}

				Float time = null;
				String timeLocation = GsonUtils.location(in);
				if (in.peek() == JsonToken.STRING) {
					String name = in.nextString();
					try {
						Phase phase = Phase.valueOf(name);
						time = property.equals("from") ? phase.ranges[0].from : phase.ranges[0].through;
					} catch (IllegalArgumentException ex) {
						log.error("Unknown light schedule phase '{}' at {}; ignoring range", name, timeLocation);
					}
				} else if (in.peek() == JsonToken.NUMBER) {
					time = (float) in.nextDouble();
					if (!Float.isFinite(time) || time < -90 || time > 90) {
						log.error(
							"Light schedule altitudes must be finite and between -90 and 90 degrees at {}; ignoring range",
							timeLocation
						);
						time = null;
					}
				} else {
					log.error("Light schedule time must be a named phase or altitude at {}; ignoring range", timeLocation);
					in.skipValue();
				}
				valid &= time != null;

				if (property.equals("from")) {
					from = time;
				} else {
					through = time;
				}
			}
			in.endObject();

			if (!valid)
				return null;
			if (from == null || through == null) {
				log.error("Light schedule range needs valid from and through values at {}; ignoring range", location);
				return null;
			}
			if ((float) from == through) {
				log.error(
					"Light schedule range from {} through {} covers all solar altitudes at {}; ignoring range",
					from,
					through,
					location
				);
				return null;
			}
			return new Range[] { new Range(from, through, Range.Mode.BOTH) };
		}

		@Override
		public void write(JsonWriter out, LightSchedule schedule) throws IOException {
			Phase phase = getPhase(schedule.during);
			if (phase != null && schedule.turn == Turn.ON && schedule.randomOffset == DEFAULT_RANDOM_OFFSET) {
				out.value(phase.name());
				return;
			}

			out.beginObject();
			if (schedule.turn != Turn.ON) {
				out.name("turn").value(schedule.turn.name());
			}
			out.name("during");
			writeRanges(out, schedule.during, phase);
			if (schedule.randomOffset != DEFAULT_RANDOM_OFFSET)
				out.name("randomOffset").value(schedule.randomOffset);
			out.endObject();
		}

		private static void writeRanges(JsonWriter out, Range[] ranges, Phase phase) throws IOException {
			if (phase != null) {
				out.value(phase.name());
			} else if (ranges.length == 1) {
				writeRange(out, ranges[0]);
			} else {
				out.beginArray();
				for (int i = 0; i < ranges.length; i++)
					writeRange(out, ranges[i]);
				out.endArray();
			}
		}

		private static void writeRange(JsonWriter out, Range range) throws IOException {
			Phase phase = getPhase(range);
			if (phase != null) {
				out.value(phase.name());
				return;
			}

			out.beginObject();
			out.name("from").value(range.from);
			out.name("through").value(range.through);
			out.endObject();
		}

		private static Phase getPhase(Range[] ranges) {
			if (ranges.length == 1)
				return getPhase(ranges[0]);
			return null;
		}

		private static Phase getPhase(Range range) {
			for (int i = 0; i < Phase.VALUES.length; i++) {
				Phase phase = Phase.VALUES[i];
				if (range.from == phase.ranges[0].from &&
					range.through == phase.ranges[0].through &&
					range.mode == phase.ranges[0].mode)
					return phase;
			}
			return null;
		}
	}
}
