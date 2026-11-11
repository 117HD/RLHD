package rs117.hd.scene.model_overrides;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.Model;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.model.modelreplaceer.ModelStore;
import rs117.hd.model.modelreplaceer.types.objects.ModelDefinition;
import rs117.hd.scene.daylight_cycle.SkyState;
import rs117.hd.utils.ExpressionParser;
import rs117.hd.utils.ExpressionParser.SerializableExpressionPredicate;
import rs117.hd.utils.VariableSupplier;

public class ModelReplacement {
	public String model;
	@JsonAdapter(ConditionAdapter.class)
	public SerializableExpressionPredicate condition;
	public List<Variant> variants = List.of();

	public transient volatile ModelDefinition definition;
	private transient ModelDefinition fallback;

	public boolean hasConditions() {
		return condition != null || !variants.isEmpty();
	}

	public void initialize(VariableSupplier vars) {
		if (model == null && variants.isEmpty())
			throw new IllegalArgumentException("Expected a model or at least one variant");
		fallback = model == null ? null : resolveModel(model);
		validateCondition(condition, vars);
		for (Variant variant : variants) {
			if (variant == null || variant.condition == null)
				throw new IllegalArgumentException("Each variant needs a condition");
			variant.definition = resolveModel(variant.model);
			validateCondition(variant.condition, vars);
		}
		definition = select(vars);
	}

	@Nullable
	public ModelDefinition select(VariableSupplier vars) {
		if (condition != null && !condition.test(vars))
			return null;
		for (Variant variant : variants)
			if (variant.condition.test(vars))
				return variant.definition;
		return fallback;
	}

	private static ModelDefinition resolveModel(String name) {
		ModelDefinition result = ModelStore.get(name);
		if (result == null)
			throw new IllegalArgumentException("Unknown model replacement '" + name + "'");
		return result;
	}

	private static void validateCondition(@Nullable SerializableExpressionPredicate predicate, VariableSupplier vars) {
		if (predicate != null) {
			for (String name : predicate.expression.variables)
				if (vars.get(name) == null)
					throw new IllegalArgumentException("Unknown model replacement variable '" + name + "'");
		}
	}

	public static VariableSupplier variables(VariableSupplier shared, LocalDate localDate, SkyState sky) {
		int month = localDate.getMonthValue();
		int day = localDate.getDayOfMonth();
		boolean cycleActive = sky.cycleActive;
		float sunAltitude = cycleActive ? sky.sunAltitudeDegrees : 90;
		long dayMillis = 86_400_000L;
		long solarMillis = sky.utcMillis + (long) (sky.latLon[1] / 360.0 * dayMillis);
		float skyHour = cycleActive ? Math.floorMod(solarMillis, dayMillis) / 3_600_000f : 12;
		return name -> {
			switch (name) {
				case "monthDay": return month * 100 + day;
				case "date": return Math.floorMod(localDate.getYear(), 100) * 10000 + month * 100 + day;
				case "year": return localDate.getYear();
				case "month": return month;
				case "day": return day;
				case "night": return cycleActive && sunAltitude < 0;
				case "sunAltitude": return sunAltitude;
				case "skyHour": return skyHour;
				case "daylightCycleActive": return cycleActive;
				default: return shared.get(name);
			}
		};
	}

	@Nullable
	public Key key(int objectId, int config) {
		ModelDefinition selected = definition;
		return selected == null ? null : new Key(selected, objectId, config & 0xFF);
	}

	private static class ConditionAdapter implements JsonDeserializer<SerializableExpressionPredicate> {
		private static final Pattern ANNUAL_DATE_COMPARISON = Pattern.compile(
			"(?<![\\w.])date(\\s*(?:==|!=|<=|>=|<|>)\\s*)([0-9]{3,4})(?=\\s*(?:$|[)?:]|&&|\\|\\|))"
		);

		@Override
		public SerializableExpressionPredicate deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
			if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean())
				return new SerializableExpressionPredicate(ExpressionParser.asExpression(json.getAsBoolean()));

			String condition;
			if (json.isJsonArray()) {
				var alternatives = new ArrayList<String>();
				for (JsonElement alternative : json.getAsJsonArray())
					alternatives.add(readExpression(alternative));
				condition = ExpressionParser.mergeExpressions("||", alternatives);
			} else {
				condition = readExpression(json);
			}
			var expression = ExpressionParser.parseExpression(condition, name -> {
				String prefix = "SeasonalTheme.";
				return name.startsWith(prefix) ? SeasonalTheme.valueOf(name.substring(prefix.length())).ordinal() : null;
			});
			return new SerializableExpressionPredicate(ExpressionParser.asExpression(expression));
		}

		private static String readExpression(JsonElement json) {
			if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString())
				throw new JsonParseException("Expected a replacement condition expression");
			return ANNUAL_DATE_COMPARISON.matcher(json.getAsString()).replaceAll("monthDay$1$2");
		}
	}

	public static class Variant {
		public String model;
		@JsonAdapter(ConditionAdapter.class)
		public SerializableExpressionPredicate condition;
		private transient ModelDefinition definition;
	}

	@Value
	public static class Key {
		ModelDefinition definition;
		int objectId;
		int config;

		public Model load(Client client) {
			return definition.getModel(client, objectId, config & 0x3F, config >> 6);
		}
	}
}
