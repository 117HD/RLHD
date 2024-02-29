package rs117.hd.scene.tile_overrides;

import com.google.gson.JsonElement;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.VariableSupplier;

import static rs117.hd.utils.ExpressionParser.asExpression;
import static rs117.hd.utils.ExpressionParser.parseExpression;

@Slf4j
public class ExpressionBasedReplacement<T> extends TileOverride.Replacement<T> {
	public final String[] expressions;
	public final transient Predicate<VariableSupplier> predicate;

	private transient boolean isConstant;

	public ExpressionBasedReplacement(T replacement, @Nullable Map<String, Object> constants, JsonElement jsonExpressions) {
		super(replacement);

		if (jsonExpressions.isJsonPrimitive()) {
			var primitive = jsonExpressions.getAsJsonPrimitive();
			expressions = new String[] { primitive.getAsString() };
		} else if (jsonExpressions.isJsonArray()) {
			var array = jsonExpressions.getAsJsonArray();
			expressions = new String[array.size()];
			int i = 0;
			for (var primitive : array)
				expressions[i++] = primitive.getAsString();
		} else {
			throw new IllegalStateException("Unsupported expression format: '" + jsonExpressions + "'");
		}

		predicate = parse(constants);
	}

	public ExpressionBasedReplacement(T replacement, @Nullable Map<String, Object> constants, String... cases) {
		super(replacement);
		this.expressions = cases;
		predicate = parse(constants);
	}

	private Predicate<VariableSupplier> parse(@Nullable Map<String, Object> constants) {
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
				final Set<String> knownVariables = Set.of("h", "s", "l", "blending", "textures", "season");
				for (var variable : expr.variables)
					if (!knownVariables.contains(variable))
						throw new IllegalStateException("Expression '" + expression + "' contains unknown variable '" + variable + "'");
			}
		}
		return predicate;
	}

	public boolean isConstant() {
		return isConstant;
	}

	@Nullable
	@Override
	public T resolve(HdPlugin plugin, Scene scene, Tile tile, T original) {
		final String[] HSL_VARS = { "h", "s", "l" };
		int[][] hsl = new int[1][];
		VariableSupplier vars = name -> {
			for (int i = 0; i < HSL_VARS.length; i++) {
				if (HSL_VARS[i].equals(name)) {
					if (hsl[0] == null)
						hsl[0] = HDUtils.getSouthWesternMostTileColor(tile);
					return hsl[0][i];
				}
			}

			switch (name) {
				case "blending":
					return plugin.configGroundBlending;
				case "textures":
					return plugin.configGroundTextures;
				case "season":
					return plugin.configSeasonalTheme.ordinal();
			}

			throw new IllegalArgumentException("Undefined variable '" + name + "'");
		};

		return predicate.test(vars) ? replacement : original;
	}
}
