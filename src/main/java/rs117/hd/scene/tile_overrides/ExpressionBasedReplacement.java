package rs117.hd.scene.tile_overrides;

import com.google.gson.JsonElement;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.Props;
import rs117.hd.utils.VariableSupplier;

import static rs117.hd.utils.ExpressionParser.asExpression;
import static rs117.hd.utils.ExpressionParser.parseExpression;

@Slf4j
public class ExpressionBasedReplacement {
	public final TileOverride replacement;
	public final transient Predicate<VariableSupplier> predicate;

	private transient boolean isConstant;

	public boolean isConstant() {
		return isConstant;
	}

	public ExpressionBasedReplacement(
		@Nullable TileOverride replacement,
		@Nullable Map<String, Object> constants,
		JsonElement jsonExpressions
	) {
		this(replacement, constants, jsonToStringExpressions(jsonExpressions));
	}

	public ExpressionBasedReplacement(@Nullable TileOverride replacement, @Nullable Map<String, Object> constants, String... cases) {
		this.replacement = replacement;
		predicate = parse(constants, cases);
	}

	private static String[] jsonToStringExpressions(JsonElement jsonExpressions) {
		if (jsonExpressions == null || jsonExpressions.isJsonNull())
			return new String[0];

		if (jsonExpressions.isJsonPrimitive())
			return new String[] { jsonExpressions.getAsJsonPrimitive().getAsString() };

		if (jsonExpressions.isJsonArray()) {
			var array = jsonExpressions.getAsJsonArray();
			var expressions = new String[array.size()];
			int i = 0;
			for (var primitive : array)
				expressions[i++] = primitive.getAsString();
			return expressions;
		}

		throw new IllegalStateException("Unsupported expression format: '" + jsonExpressions + "'");
	}

	private Predicate<VariableSupplier> parse(@Nullable Map<String, Object> constants, String... expressions) {
		if (expressions.length == 0) {
			isConstant = true;
			return vars -> false;
		}

		Predicate<VariableSupplier> predicate = null;
		for (String expression : expressions) {
			var result = parseExpression(expression, constants);

			if (result instanceof Boolean) {
				isConstant = true;
				return vars -> (boolean) result;
			}

			var expr = asExpression(result);
			var p = expr.toPredicate();
			predicate = predicate == null ? p : predicate.or(p);

			if (Props.DEVELOPMENT) {
				// Ensure all variables are defined
				final Set<String> knownVariables = Set.of("h", "s", "l");
				for (var variable : expr.variables)
					if (!knownVariables.contains(variable))
						throw new IllegalStateException("Expression '" + expression + "' contains unknown variable '" + variable + "'");
			}
		}
		return predicate;
	}
}
