package rs117.hd.utils;

import com.google.gson.JsonElement;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.Function;
import javax.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static rs117.hd.utils.MathUtils.*;

public class ExpressionParser {
	public static ExpressionPredicate parsePredicate(String expression) {
		return parsePredicate(expression, null);
	}

	public static ExpressionPredicate parsePredicate(String expression, @Nullable VariableSupplier constants) {
		return asExpression(parseExpression(expression, constants)).toPredicate();
	}

	public static Function<VariableSupplier, Object> parseFunction(String expression) {
		return parseFunction(expression, null);
	}

	public static Function<VariableSupplier, Object> parseFunction(String expression, @Nullable VariableSupplier constants) {
		return asFunction(parseExpression(expression, constants));
	}

	public static Object parseExpression(String expression) {
		return parseExpression(expression, null);
	}

	public static Object parseExpression(String expression, @Nullable VariableSupplier constants) {
		return asExpression(parseExpression(expression, 0, expression.length())).simplify(constants);
	}

	public static String mergeJsonExpressions(String delimiter, JsonElement jsonExpressions) {
		if (jsonExpressions == null || jsonExpressions.isJsonNull())
			throw new IllegalArgumentException("Missing expression, got null");

		if (jsonExpressions.isJsonPrimitive())
			return jsonExpressions.getAsJsonPrimitive().getAsString();

		if (jsonExpressions.isJsonArray()) {
			var array = jsonExpressions.getAsJsonArray();
			var sb = new StringBuilder();
			String prefix = "";
			for (var primitive : array) {
				sb.append(prefix).append('(').append(primitive.getAsString()).append(')');
				prefix = delimiter;
			}
			return sb.toString();
		}

		throw new IllegalArgumentException("Unsupported expression format: '" + jsonExpressions + "'");
	}

	public static String mergeExpressions(String delimiter, Iterable<String> expressions) {
		var sb = new StringBuilder();
		String prefix = "";
		for (var expression : expressions) {
			sb.append(prefix).append('(').append(expression).append(')');
			prefix = delimiter;
		}
		return sb.toString();
	}

	public static Expression asExpression(Object object) {
		if (object instanceof Expression)
			return (Expression) object;
		return new Expression(object);
	}

	public static Function<VariableSupplier, Object> asFunction(Object object) {
		if (object instanceof Expression)
			return ((Expression) object).toFunction();
		if (object instanceof String)
			return vars -> vars.get((String) object);
		return vars -> object;
	}

	public static class SerializableExpressionPredicate implements ExpressionPredicate {
		public final Expression expression;
		public final ExpressionPredicate predicate;

		public SerializableExpressionPredicate(Expression expression) {
			this.expression = expression;
			predicate = expression.toPredicate();
		}

		@Override
		public String toString() {
			return expression.toString();
		}

		@Override
		public boolean test(VariableSupplier variableSupplier) {
			return predicate.test(variableSupplier);
		}
	}

	public static class PredicateAdapter extends TypeAdapter<SerializableExpressionPredicate> {
		private static final Adapter ADAPTER = new Adapter();

		@Override
		public SerializableExpressionPredicate read(JsonReader in) throws IOException {
			return new SerializableExpressionPredicate(ADAPTER.read(in));
		}

		@Override
		public void write(JsonWriter out, SerializableExpressionPredicate predicate) throws IOException {
			ADAPTER.write(out, predicate.expression);
		}
	}

	@Slf4j
	public static class Adapter extends TypeAdapter<Expression> {
		@Override
		public Expression read(JsonReader in) throws IOException {
			if (in.peek() == JsonToken.NULL)
				return null;

			if (in.peek() == JsonToken.BOOLEAN)
				return asExpression(in.nextBoolean());

			if (in.peek() == JsonToken.STRING)
				return asExpression(parseExpression(in.nextString()));

			if (in.peek() == JsonToken.BEGIN_ARRAY) {
				in.beginArray();
				var cases = new ArrayList<String>();
				while (in.peek() == JsonToken.STRING)
					cases.add(in.nextString());
				in.endArray();
				return asExpression(parseExpression(mergeExpressions("||", cases)));
			}

			log.warn("Unexpected type {} at {}", in.peek(), GsonUtils.location(in), new Throwable());
			return null;
		}

		@Override
		public void write(JsonWriter out, Expression expression) throws IOException {
			if (expression == null) {
				out.nullValue();
			} else {
				// Disable HTML escaping to keep operators intact
				out.setHtmlSafe(false);
				out.value(expression.toString());
			}
		}
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

	@RequiredArgsConstructor
	private enum Operator {
		MOD("%", 6, 2),
		MUL("*", 6, 2),
		DIV("/", 6, 2),
		ADD("+", 5, 2),
		SUB("-", 5, 2),
		LEQUAL("<=", 4, 2),
		LESS("<", 4, 2),
		GEQUAL(">=", 4, 2),
		GREATER(">", 4, 2),
		NOTEQUAL("!=", 3, 2),
		NOT("!", 7, 1),
		EQUAL("==", 3, 2),
		AND("&&", 2, 2),
		OR("||", 1, 2),
		TERNARY("?", 0, 3);

		final String symbol;
		final int precedence;
		final int numOperands;

		@Override
		public String toString() {
			return symbol;
		}
	}

	@AllArgsConstructor
	public static class ParserContext {
		final String expr;
		int index, endIndex;
		char c;
		Operator op;
		Object[] operands = new Object[2];
		boolean isInParentheses;
		boolean isTopLevelParser;
		int minPrecedence;

		ParserContext(String expression, int startIndex, int endIndex, boolean isTopLevelParser, int minPrecedence) {
			this.expr = expression;
			this.index = startIndex;
			this.endIndex = endIndex;
			this.isTopLevelParser = isTopLevelParser;
			this.minPrecedence = minPrecedence;
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
			skipWhitespace();
			read();
		}

		void advance() {
			index++;
			readSafe();
		}

		void advanceIgnoringWhitespace() {
			advance();
			skipWhitespace();
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
			isInParentheses = false;
			while (!done() && c == '(' && readEnd() == ')') {
				int i = index + 1;
				int levels = 1;
				while (i < endIndex - 2) {
					char c = expr.charAt(i++);
					if (c == '(') {
						levels++;
					} else if (c == ')') {
						levels--;
						if (levels == 0)
							return;
					}
				}

				advance();
				endIndex--;
				isInParentheses = true;
			}
		}

		void skipWhitespace() {
			while (!done()) {
				readSafe();
				if (c != ' ')
					break;
				index++;
			}
		}

		void trimWhitespaceEnd() {
			while (!done()) {
				var end = expr.charAt(endIndex - 1);
				if (end != ' ')
					break;
				endIndex--;
			}
		}

		void trimWhitespace() {
			skipWhitespace();
			trimWhitespaceEnd();
		}

		int remaining() {
			return endIndex - index;
		}

		int indexOfClosingParenthesis(int openingParenthesis) {
			int i = openingParenthesis;
			int levels = 1;
			while (++i < endIndex) {
				char c = expr.charAt(i);
				if (c == '(') {
					levels++;
				} else if (c == ')' && --levels == 0) {
					return i;
				}
			}
			throw new SyntaxError(this, "Missing closing parenthesis");
		}

		Object parseOperand() {
			if (!done()) {
				skipWhitespace();

				// Always parse parentheses in a new parsing context
				if (c == '(') {
					int end = indexOfClosingParenthesis(index);
					var exprInParentheses = parseExpression(expr, index, end + 1);
					index = end + 1;
					readSafe();
					return exprInParentheses;
				}

				// Parse all following operations with higher precedence than the current, and return that as the operand
				if (op != null) {
					var higherPrecedenceParser = new ParserContext(expr, index, endIndex, false, op.precedence + 1);
					var expr = parseExpression(higherPrecedenceParser);
					if (expr != null) {
						index = higherPrecedenceParser.index;
						endIndex = higherPrecedenceParser.endIndex;
						return expr;
					}
				}

				if (c == '+' || c == '-' || c == '.' || ('0' <= c && c <= '9'))
					return readNumber();
				if ('A' <= c && c <= 'Z' || 'a' <= c && c <= 'z' || c == '_')
					return readIdentifier();
			}
			return null;
		}

		float readNumber() {
			// Parse sign
			int sign = 1;
			while (!done()) {
				if (c == '-') {
					sign *= -1;
				} else if (c != '+') {
					break;
				}
				advanceIgnoringWhitespace();
			}

			// Parse whole part
			int wholePart = 0;
			int numDigits = 0;
			while (!done()) {
				if ('0' <= c && c <= '9') {
					wholePart *= 10;
					wholePart += c - '0';
					numDigits++;
					advance();
				} else {
					break;
				}
			}

			// Parse fractional part
			if (!done() && c == '.') {
				advance();
				int fractionalPart = 0;
				int divisor = 1;
				while (!done()) {
					if ('0' <= c && c <= '9') {
						fractionalPart += c - '0';
						divisor *= 10;
						advance();
					} else {
						break;
					}
				}

				if (divisor > 1)
					return sign * ((float) fractionalPart / divisor + wholePart);
			}

			if (numDigits == 0)
				throw new SyntaxError(this, "Expected a number");

			return sign * wholePart;
		}

		Object readIdentifier() {
			StringBuilder sb = new StringBuilder();
			while (!done()) {
				if ('A' <= c && c <= 'Z' ||
					'a' <= c && c <= 'z' ||
					'0' <= c && c <= '9' ||
					c == '_' ||
					c == '.' && sb.length() > 0
				) {
					sb.append(c);
					advance();
				} else {
					break;
				}
			}

			assert sb.length() > 0;
			var str = sb.toString();

			// Convert string constants
			if (str.equalsIgnoreCase("true"))
				return true;
			if (str.equalsIgnoreCase("false"))
				return false;

			return str;
		}

		Expression createExpression(Object leftOperand, Operator op, Object rightOperand) {
			if (!(leftOperand instanceof Expression)) {
				// Simple combination of left & right operands
				return new Expression(op, leftOperand, rightOperand, null, false);
			}

			Expression left = (Expression) leftOperand;
			// If the left expression is in parentheses, or has the same or higher operator precedence,
			// it should be evaluated first, so use it as the left operand in a new expression
			if (left.isInParentheses || left.op.precedence >= op.precedence)
				return new Expression(op, left, rightOperand, null, false);

			// The new operator should act on the left expression's right-most operand,
			// and should replace the right-most operand with the resulting expression
			left.right = createExpression(left.right, op, rightOperand);
			return left;
		}
	}

	public static class Expression {
		Operator op;
		Object left, right;
		Object ternary;
		boolean isInParentheses;
		public final HashSet<String> variables = new HashSet<>();

		Expression(Object value) {
			this(null, value, null, null, false);
		}

		Expression(Operator op, Object left, Object right, Object ternary, boolean isInParentheses) {
			this.op = op;
			this.left = left;
			this.right = right;
			this.ternary = ternary;
			this.isInParentheses = isInParentheses;
			registerVariables(left);
			registerVariables(right);
			registerVariables(ternary);
		}

		public Object simplify(@Nullable VariableSupplier constants) {
			Object l = left instanceof Expression ? ((Expression) left).simplify(constants) : left;
			Object r = right instanceof Expression ? ((Expression) right).simplify(constants) : right;

			if (constants != null) {
				if (l instanceof String) {
					var value = constants.get((String) l);
					if (value != null)
						l = sanitizeValue(value);
				}
				if (r instanceof String) {
					var value = constants.get((String) r);
					if (value != null)
						r = sanitizeValue(value);
				}
			}

			if (op == Operator.TERNARY) {
				Object t = asExpression(ternary).simplify(constants);
				if (t instanceof Boolean)
					return (boolean) t ? l : r;
				return new Expression(op, l, r, asExpression(t), isInParentheses);
			}

			var expr = this;
			if (l != left || r != right)
				expr = new Expression(op, l, r, null, isInParentheses);

			if (isPrimitive(l) && isPrimitive(r))
				return expr.toFunctionInternal().apply(null);

			return expr;
		}

		private String formatOperand(Object operand) {
			if (operand instanceof Number) {
				int nearest = round((float) operand);
				if (abs((float) operand - nearest) < 1e-10)
					operand = nearest;
			}
			return operand.toString();
		}

		@Override
		public String toString() {
			if (op == null)
				return formatOperand(left);
			if (op.numOperands == 1)
				return op.symbol + formatOperand(left);
			if (op.numOperands == 2)
				return String.format("%s %s %s", formatOperand(left), op, formatOperand(right));
			if (op.numOperands == 3)
				return String.format("%s %s %s : %s", formatOperand(ternary), op, formatOperand(left), formatOperand(right));
			throw new IllegalStateException(String.format("Cannot stringify operator '%s'", op));
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

		public ExpressionPredicate toPredicate() {
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
			return obj == null || obj instanceof Float || obj instanceof Boolean;
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
		return parseExpression(new ParserContext(expression, startIndex, endIndex, true, 0));
	}

	private static Object parseExpression(ParserContext ctx) {
		ctx.trimWhitespace();
		if (ctx.done())
			throw new SyntaxError(ctx, "Empty expression");

		ctx.trimParentheses();
		boolean wasInParentheses = ctx.isInParentheses;
		boolean wasTopLevelParser = ctx.isTopLevelParser;
		// Since we'll be reusing the same parser context for parsing sub-expressions, mark it as not top-level
		ctx.isTopLevelParser = false;

		// The general gist:
		// 1. Begin parsing from left to right until any operator is reached
		// 2. Parse all following higher precedence operations
		// 3. Continue parsing operators regardless of precedence
		// 4. If a lower precedence operator is reached, make that the new parent node, and return to step 2
		// 5. At the end, return the left operand and parenthesis information

		ctx.operands[0] = ctx.parseOperand();

		parsing:
		while (!ctx.done()) {
			ctx.skipWhitespace();
			ctx.op = null;
			for (var op : Operator.values()) {
				// Skip lower precedence operators
				if (op.precedence >= ctx.minPrecedence && ctx.expr.startsWith(op.symbol, ctx.index)) {
					if (op == Operator.TERNARY) {
						// Parse the ternary into an expression to be the new left operand, and keep parsing
						var condition = ctx.operands[0];
						if (condition == null)
							throw new SyntaxError(ctx, "Unexpected operator '" + op.symbol + "' without preceding condition");
						ctx.index += op.symbol.length();
						var ifTrue = parseExpression(ctx);
						ctx.trim();
						if (ctx.c != ':')
							throw new SyntaxError(ctx, "Expected ':' in ternary expression");
						ctx.advance();
						var ifFalse = parseExpression(ctx);
						ctx.operands[0] = new Expression(op, ifTrue, ifFalse, condition, wasInParentheses);
						continue parsing;
					}

					if (ctx.operands[0] == null) {
						if (op.numOperands > 1)
							throw new SyntaxError(ctx, "Missing left operand for operator '" + op.symbol + "'");
					} else if (op.numOperands == 1) {
						throw new SyntaxError(ctx, "Unexpected left operand before '" + op.symbol + "'");
					}

					ctx.op = op;
					ctx.index += op.symbol.length();
					break;
				}
			}

			if (ctx.op == null)
				break;

			if (ctx.op != Operator.NOT && ctx.operands[0] == null)
				throw new SyntaxError(ctx, "Missing left operand for operator '" + ctx.op.symbol + "'");

			// Will parse all following higher precedence operations, or a single value or identifier
			ctx.operands[1] = ctx.parseOperand();
			if (ctx.operands[1] == null)
				throw new SyntaxError(ctx, "Missing right operand for operator '" + ctx.op.symbol + "'");

			ctx.operands[0] = ctx.createExpression(ctx.operands[0], ctx.op, ctx.operands[1]);
		}

		if (wasTopLevelParser && !ctx.done())
			throw new SyntaxError(ctx, "Unexpected character '" + ctx.c + "'");

		if (ctx.operands[0] instanceof Expression)
			((Expression) ctx.operands[0]).isInParentheses = wasInParentheses;

		return ctx.operands[0];
	}
}
