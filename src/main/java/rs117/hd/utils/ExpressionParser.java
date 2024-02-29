package rs117.hd.utils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;

public class ExpressionParser {
	public static Predicate<VariableSupplier> parsePredicate(String expression) {
		return parsePredicate(expression, null);
	}

	public static Predicate<VariableSupplier> parsePredicate(String expression, @Nullable Map<String, Object> constants) {
		return asExpression(parseExpression(expression, constants)).toPredicate();
	}

	public static Function<VariableSupplier, Object> parseFunction(String expression) {
		return parseFunction(expression, null);
	}

	public static Function<VariableSupplier, Object> parseFunction(String expression, @Nullable Map<String, Object> constants) {
		return asFunction(parseExpression(expression, constants));
	}

	public static Object parseExpression(String expression) {
		return parseExpression(expression, null);
	}

	public static Object parseExpression(String expression, @Nullable Map<String, Object> constants) {
		Object obj = parseExpression(expression, 0, expression.length());
		if (obj instanceof Expression)
			return ((Expression) obj).simplify(constants == null ? Collections.emptyMap() : constants);
		return obj;
	}

	public static Expression asExpression(Object object) {
		if (object instanceof Expression)
			return (Expression) object;
		return new Expression(object);
	}

	static Function<VariableSupplier, Object> asFunction(Object object) {
		if (object instanceof Expression)
			return ((Expression) object).toFunction();
		if (object instanceof String)
			return vars -> vars.get((String) object);
		return vars -> object;
	}

	public static class SyntaxError extends IllegalArgumentException {
		SyntaxError(ParserContext ctx, String message) {
			super(
				"Error at index " + ctx.index + " while parsing " +
				(ctx.op == null ? "expression" : "operator '" + ctx.op + "' in") +
				" '" + ctx.expr + "': " + message
			);
		}
	}

	// Operators in reverse order of precedence
	@RequiredArgsConstructor
	private enum Operator {
		TERNARY("?"),
		OR("||"),
		AND("&&"),
		EQUAL("=="),
		NOTEQUAL("!="),
		GEQUAL(">="),
		GREATER(">"),
		LEQUAL("<="),
		LESS("<"),
		SUB("-"),
		ADD("+"),
		DIV("/"),
		MUL("*"),
		MOD("%"),
		NOT("!")
		;

		final String symbol;
	}

	@AllArgsConstructor
	private static class ParserContext {
		final String expr;
		int index, endIndex;
		char c;
		Object left, right;
		Operator op;
		boolean zeroIfEmpty;

		ParserContext(String expression, int startIndex, int endIndex, boolean zeroIfEmpty) {
			this.expr = expression;
			this.index = startIndex;
			this.endIndex = endIndex;
			this.zeroIfEmpty = zeroIfEmpty;
			trim();
		}

		boolean done() {
			return index >= endIndex;
		}

		void read() {
			if (done())
				throw new SyntaxError(this, "Unexpected end of expression");
			c = expr.charAt(index);
		}

		void readSafe() {
			c = done() ? 0 : expr.charAt(index);
		}

		char readEnd() {
			return expr.charAt(endIndex - 1);
		}

		void readIgnoringWhitespace() {
			trimWhitespace();
			read();
		}

		void advance() {
			index++;
			readSafe();
		}

		void advanceIgnoringWhitespace() {
			advance();
			trimWhitespace();
		}

		void trim() {
			int remaining;
			do {
				remaining = remaining();
				trimWhitespace();
				trimParentheses();
			} while (remaining != remaining());
		}

		void trimParentheses() {
			while (!done() && c == '(' && readEnd() == ')') {
				advance();
				endIndex--;
			}
		}

		void trimWhitespace() {
			while (!done()) {
				readSafe();
				if (c != ' ')
					break;
				index++;
			}

			while (!done()) {
				var end = expr.charAt(endIndex - 1);
				if (end != ' ')
					break;
				endIndex--;
			}
		}

		int remaining() {
			return endIndex - index;
		}
	}

	public static class Expression {
		final Operator op;
		final Object left, right;
		final Expression ternary;
		public final HashSet<String> variables = new HashSet<>();

		Expression(Object value) {
			this(null, value, null, null);
		}

		Expression(Operator op, Object left, Object right, Expression ternary) {
			this.op = op;
			this.left = left;
			this.right = right;
			this.ternary = ternary;
			registerVariables(left);
			registerVariables(right);
			registerVariables(ternary);
		}

		private Object simplify(@Nonnull Map<String, Object> constants) {
			Object l = left instanceof Expression ? ((Expression) left).simplify(constants) : left;
			Object r = right instanceof Expression ? ((Expression) right).simplify(constants) : right;

			if (l instanceof String)
				l = sanitizeValue(constants.getOrDefault(l, l));
			if (r instanceof String)
				r = sanitizeValue(constants.getOrDefault(r, r));

			if (op == Operator.TERNARY) {
				Object t = ternary.simplify(constants);
				if (t instanceof Boolean)
					return (boolean) t ? l : r;
				return new Expression(op, l, r, asExpression(t));
			}

			var expr = this;
			if (l != left || r != right)
				expr = new Expression(op, l, r, null);

			if (isPrimitive(l) && isPrimitive(r))
				return expr.toFunctionInternal().apply(null);

			return expr;
		}

		public Function<VariableSupplier, Object> toFunction() {
			var func = toFunctionInternal();
			return vars -> func.apply(key -> sanitizeValue(vars.get(key)));
		}

		static Object sanitizeValue(Object value) {
			// This is kind of stupid, but it's necessary to convert
			// ints to floats here to avoid messy code later
			if (value instanceof Integer)
				return ((Integer) value).floatValue();
			return value;
		}

		private Function<VariableSupplier, Object> toFunctionInternal() {
			if (op == null)
				return asFunction(left);

			if (op == Operator.TERNARY) {
				var condition = asExpression(ternary).toPredicate();
				if (left instanceof Expression) {
					var ifTrue = ((Expression) left).toFunction();
					if (right instanceof Expression) {
						var ifFalse = ((Expression) right).toFunction();
						return vars -> condition.test(vars) ? ifTrue.apply(vars) : ifFalse.apply(vars);
					}
					return vars -> condition.test(vars) ? ifTrue.apply(vars) : right;
				} else if (right instanceof Expression) {
					var ifFalse = ((Expression) right).toFunction();
					return vars -> condition.test(vars) ? left : ifFalse.apply(vars);
				} else {
					return vars -> condition.test(vars) ? left : right;
				}
			}

			// Convert variables and constants into functions
			var l = asFunction(left);
			var r = asFunction(right);

			switch (op) {
				case AND:
					return vars -> (boolean) l.apply(vars) && (boolean) r.apply(vars);
				case OR:
					return vars -> (boolean) l.apply(vars) || (boolean) r.apply(vars);
				case NOTEQUAL:
				case EQUAL:
					boolean isBoolean =
						left instanceof Boolean || left instanceof Expression && ((Expression) left).isBoolean() ||
						right instanceof Boolean || right instanceof Expression && ((Expression) right).isBoolean();
					if (isBoolean) {
						return op == Operator.EQUAL ?
							vars -> (boolean) l.apply(vars) == (boolean) r.apply(vars) :
							vars -> (boolean) l.apply(vars) != (boolean) r.apply(vars);
					} else {
						return op == Operator.EQUAL ?
							vars -> (float) l.apply(vars) == (float) r.apply(vars) :
							vars -> (float) l.apply(vars) != (float) r.apply(vars);
					}
				case GEQUAL:
					return vars -> (float) l.apply(vars) >= (float) r.apply(vars);
				case GREATER:
					return vars -> (float) l.apply(vars) > (float) r.apply(vars);
				case LEQUAL:
					return vars -> (float) l.apply(vars) <= (float) r.apply(vars);
				case LESS:
					return vars -> (float) l.apply(vars) < (float) r.apply(vars);
				case ADD:
					return vars -> (float) l.apply(vars) + (float) r.apply(vars);
				case SUB:
					return vars -> (float) l.apply(vars) - (float) r.apply(vars);
				case MUL:
					return vars -> (float) l.apply(vars) * (float) r.apply(vars);
				case DIV:
					return vars -> (float) l.apply(vars) / (float) r.apply(vars);
				case MOD:
					return vars -> (float) l.apply(vars) % (float) r.apply(vars);
				case NOT:
					return vars -> !(boolean) r.apply(vars);
			}

			throw new UnsupportedOperationException("Unsupported operands: " + l + " " + op + " " + r);
		}

		public Predicate<VariableSupplier> toPredicate() {
			if (!isBoolean())
				throw new IllegalArgumentException("Expression does not result in a boolean");

			var func = toFunction();
			return vars -> (boolean) func.apply(vars);
		}

		boolean isBoolean() {
			if (op == null)
				return isPossiblyBoolean(left);
			switch (op) {
				case TERNARY:
					return isPossiblyBoolean(left) || isPossiblyBoolean(right);
				case EQUAL:
				case NOTEQUAL:
				case LESS:
				case LEQUAL:
				case GREATER:
				case GEQUAL:
				case AND:
				case OR:
				case NOT:
					return true;
			}
			return false;
		}

		static boolean isPossiblyBoolean(Object obj) {
			return
				obj instanceof Boolean ||
				obj instanceof String ||
				obj instanceof Expression && ((Expression) obj).isBoolean();
		}

		private boolean isPrimitive(Object obj) {
			return obj instanceof Float || obj instanceof Boolean;
		}

		private void registerVariables(@Nullable Object dependency) {
			if (dependency instanceof String) {
				variables.add((String) dependency);
			} else if (dependency instanceof Expression) {
				variables.addAll(((Expression) dependency).variables);
			}
		}
	}

	private static Object parseExpression(String expression, int startIndex, int endIndex) {
		return parseExpression(expression, startIndex, endIndex, false);
	}

	private static Object parseExpression(String expression, int startIndex, int endIndex, boolean zeroIfEmpty) {
		var ctx = new ParserContext(expression, startIndex, endIndex, zeroIfEmpty);
		return parseExpression(ctx);
	}

	private static Object parseExpression(ParserContext ctx) {
		if (ctx.done()) {
			if (ctx.zeroIfEmpty)
				return 0.f;
			throw new SyntaxError(ctx, "Empty expressions are not supported");
		}

		// Search for operators in order of precedence, and assume operators that are subsequences of others are ordered later
		opSearch:
		for (var op : Operator.values()) {
			int opIndex = ctx.index;
			topLevelSearch:
			while (true) {
				opIndex = ctx.expr.indexOf(op.symbol, opIndex);
				if (opIndex == -1 || opIndex >= ctx.endIndex)
					continue opSearch;

				// Check if the operator is contained within parentheses
				int openingParen = ctx.expr.lastIndexOf('(', opIndex - 1);
				int closingParenSearch = openingParen + 1;
				while (openingParen >= ctx.index) {
					int closingParen = ctx.expr.indexOf(')', closingParenSearch);
					if (closingParen == -1)
						throw new SyntaxError(ctx, "No matching closing parenthesis for parenthesis at index " + openingParen);

					// If the parentheses are closed before the operator, keep searching for higher level parentheses
					if (closingParen < opIndex) {
						closingParenSearch++;
						openingParen = ctx.expr.lastIndexOf('(', openingParen - 1);
						continue;
					}

					// The operator is in parentheses, so it's not at the top level
					opIndex += op.symbol.length();
					continue topLevelSearch;
				}

				// The operator is at the top level. Begin processing it

				// The ternary operator consists of 3 parts, instead of the usual 2
				if (op == Operator.TERNARY) {
					int colon = ctx.expr.indexOf(':', opIndex + 1);
					if (colon >= ctx.endIndex)
						throw new SyntaxError(ctx, "Expected colon following ternary operator '?' at index " + opIndex);

					var ternary = parseExpression(ctx.expr, ctx.index, opIndex);
					var ifTrue = parseExpression(ctx.expr, opIndex + op.symbol.length(), colon);
					var ifFalse = parseExpression(ctx.expr, colon + 1, ctx.endIndex);
					if (ternary instanceof Boolean)
						return (boolean) ternary ? ifTrue : ifFalse;
					return new Expression(op, ifTrue, ifFalse, asExpression(ternary));
				} else if (op == Operator.NOT) {
					var right = parseExpression(ctx.expr, opIndex + op.symbol.length(), ctx.endIndex);
					if (right instanceof Boolean)
						return !(boolean) right;
					if (Expression.isPossiblyBoolean(right))
						return new Expression(op, null, right, null);
					throw new SyntaxError(ctx, "Expected boolean expression");
				}

				boolean zeroIfEmpty = op == Operator.ADD || op == Operator.SUB;
				var left = parseExpression(ctx.expr, ctx.index, opIndex, zeroIfEmpty);
				var right = parseExpression(ctx.expr, opIndex + op.symbol.length(), ctx.endIndex, zeroIfEmpty);
				return new Expression(op, left, right, null);
			}
		}

		// Found no top-level operators, so this should be a value expression
		if (ctx.c == '+' || ctx.c == '-' || ctx.c == '.' || ('0' <= ctx.c && ctx.c <= '9'))
			return parseNumber(ctx);
		if ('A' <= ctx.c && ctx.c <= 'Z' || 'a' <= ctx.c && ctx.c <= 'z' || ctx.c == '_')
			return parseIdentifier(ctx);

		throw new SyntaxError(ctx, "Unexpected character '" + ctx.c + "'");
	}

	private static float parseNumber(ParserContext ctx) {
		// Parse sign
		int sign = 1;
		while (!ctx.done()) {
			if (ctx.c == '-') {
				sign *= -1;
			} else if (ctx.c != '+') {
				break;
			}
			ctx.advanceIgnoringWhitespace();
		}

		// Parse whole part
		int wholePart = 0;
		int numDigits = 0;
		while (!ctx.done()) {
			if ('0' <= ctx.c && ctx.c <= '9') {
				wholePart *= 10;
				wholePart += ctx.c - '0';
				numDigits++;
				ctx.advance();
			} else {
				break;
			}
		}

		// Parse fractional part
		if (!ctx.done() && ctx.c == '.') {
			ctx.advance();
			int fractionalPart = 0;
			int divisor = 1;
			while (!ctx.done()) {
				if ('0' <= ctx.c && ctx.c <= '9') {
					fractionalPart += ctx.c - '0';
					divisor *= 10;
					ctx.advance();
				} else {
					break;
				}
			}

			if (divisor > 1)
				return sign * ((float) fractionalPart / divisor + wholePart);
		}

		if (!ctx.done())
			throw new SyntaxError(ctx, "Unexpected character '" + ctx.c + "'");

		if (numDigits == 0)
			throw new SyntaxError(ctx, "Expected a number");

		return sign * wholePart;
	}

	private static Object parseIdentifier(ParserContext ctx) {
		StringBuilder sb = new StringBuilder();
		while (!ctx.done()) {
			if ('A' <= ctx.c && ctx.c <= 'Z' ||
				'a' <= ctx.c && ctx.c <= 'z' ||
				'0' <= ctx.c && ctx.c <= '9' ||
				ctx.c == '_'
			) {
				sb.append(ctx.c);
				ctx.advance();
			} else {
				break;
			}
		}

		if (!ctx.done())
			throw new SyntaxError(ctx, "Unexpected character '" + ctx.c + "'");

		assert sb.length() > 0;
		var str = sb.toString();

		// Convert string constants
		if (str.equalsIgnoreCase("true"))
			return true;
		if (str.equalsIgnoreCase("false"))
			return false;

		return str;
	}
}
