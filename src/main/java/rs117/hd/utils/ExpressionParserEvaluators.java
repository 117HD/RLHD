package rs117.hd.utils;

import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import rs117.hd.utils.ExpressionParser.BooleanEval;
import rs117.hd.utils.ExpressionParser.FloatEval;
import rs117.hd.utils.ExpressionParser.IntEval;
import rs117.hd.utils.ExpressionParser.Operator;

public class ExpressionParserEvaluators {
	@RequiredArgsConstructor
	public static final class ConstantFunction implements Function<VariableSupplier, Object> {
		private final Object value;

		@Override
		public Object apply(VariableSupplier vars) { return value; }
	}

	@RequiredArgsConstructor
	public static final class ObjectVariableFunction implements Function<VariableSupplier, Object> {
		private final Object key;

		@Override
		public Object apply(VariableSupplier vars) {
			return key instanceof Enum<?> ? vars.get((Enum<?>) key) : vars.get((String) key);
		}
	}

	@RequiredArgsConstructor
	public static final class IntToObjectFunction implements Function<VariableSupplier, Object> {
		private final IntEval eval;

		@Override
		public Object apply(VariableSupplier vars) { return eval.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatToObjectFunction implements Function<VariableSupplier, Object> {
		private final FloatEval eval;

		@Override
		public Object apply(VariableSupplier vars) { return eval.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class BooleanToObjectFunction implements Function<VariableSupplier, Object> {
		private final BooleanEval eval;

		@Override
		public Object apply(VariableSupplier vars) { return eval.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class BooleanEvalPredicate implements ExpressionPredicate {
		private final BooleanEval eval;

		@Override
		public boolean test(VariableSupplier vars) { return eval.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class ObjectTernaryFunction implements Function<VariableSupplier, Object> {
		private final BooleanEval condition;
		private final Function<VariableSupplier, Object> ifTrue, ifFalse;

		@Override
		public Object apply(VariableSupplier vars) { return condition.apply(vars) ? ifTrue.apply(vars) : ifFalse.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntConstant implements IntEval {
		private final int value;

		@Override
		public int apply(VariableSupplier vars) { return value; }
	}

	@RequiredArgsConstructor
	public static final class IntStringVariable implements IntEval {
		private final String key;

		@Override
		public int apply(VariableSupplier vars) { return vars.getInt(key); }
	}

	@RequiredArgsConstructor
	public static final class IntEnumVariable implements IntEval {
		private final Enum<?> key;

		@Override
		public int apply(VariableSupplier vars) { return vars.getInt(key); }
	}

	@RequiredArgsConstructor
	public static final class FloatConstant implements FloatEval {
		private final float value;

		@Override
		public float apply(VariableSupplier vars) { return value; }
	}

	@RequiredArgsConstructor
	public static final class FloatStringVariable implements FloatEval {
		private final String key;

		@Override
		public float apply(VariableSupplier vars) { return vars.getFloat(key); }
	}

	@RequiredArgsConstructor
	public static final class FloatEnumVariable implements FloatEval {
		private final Enum<?> key;

		@Override
		public float apply(VariableSupplier vars) { return vars.getFloat(key); }
	}

	@RequiredArgsConstructor
	public static final class BooleanConstant implements BooleanEval {
		private final boolean value;

		@Override
		public boolean apply(VariableSupplier vars) { return value; }
	}

	@RequiredArgsConstructor
	public static final class BooleanStringVariable implements BooleanEval {
		private final String key;

		@Override
		public boolean apply(VariableSupplier vars) { return vars.getBoolean(key); }
	}

	@RequiredArgsConstructor
	public static final class BooleanEnumVariable implements BooleanEval {
		private final Enum<?> key;

		@Override
		public boolean apply(VariableSupplier vars) { return vars.getBoolean(key); }
	}

	@RequiredArgsConstructor
	public static final class IntTernary implements IntEval {
		private final BooleanEval condition;
		private final IntEval ifTrue, ifFalse;

		@Override
		public int apply(VariableSupplier vars) { return condition.apply(vars) ? ifTrue.apply(vars) : ifFalse.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatTernary implements FloatEval {
		private final BooleanEval condition;
		private final FloatEval ifTrue, ifFalse;

		@Override
		public float apply(VariableSupplier vars) { return condition.apply(vars) ? ifTrue.apply(vars) : ifFalse.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class BooleanTernary implements BooleanEval {
		private final BooleanEval condition, ifTrue, ifFalse;

		@Override
		public boolean apply(VariableSupplier vars) { return condition.apply(vars) ? ifTrue.apply(vars) : ifFalse.apply(vars); }
	}

	@RequiredArgsConstructor
	public abstract static class IntMathOperation implements IntEval {
		protected final IntEval left;
		protected final IntEval right;

		@Override
		public final int apply(VariableSupplier vars) { return apply(left.apply(vars), right.apply(vars)); }

		protected abstract int apply(int left, int right);
	}

	public static final class IntAdd extends IntMathOperation {
		public IntAdd(IntEval left, IntEval right) { super(left, right); }

		@Override
		protected int apply(int left, int right) { return left + right; }
	}

	public static final class IntSub extends IntMathOperation {
		public IntSub(IntEval left, IntEval right) { super(left, right); }

		@Override
		protected int apply(int left, int right) { return left - right; }
	}

	public static final class IntMul extends IntMathOperation {
		public IntMul(IntEval left, IntEval right) { super(left, right); }

		@Override
		protected int apply(int left, int right) { return left * right; }
	}

	public static final class IntDiv extends IntMathOperation {
		public IntDiv(IntEval left, IntEval right) { super(left, right); }

		@Override
		protected int apply(int left, int right) { return left / right; }
	}

	public static final class IntMod extends IntMathOperation {
		public IntMod(IntEval left, IntEval right) { super(left, right); }

		@Override
		protected int apply(int left, int right) { return left % right; }
	}

	@RequiredArgsConstructor
	public abstract static class FloatMathOperation implements FloatEval {
		protected final FloatEval left;
		protected final FloatEval right;

		@Override
		public final float apply(VariableSupplier vars) { return apply(left.apply(vars), right.apply(vars)); }

		protected abstract float apply(float left, float right);
	}

	public static final class FloatAdd extends FloatMathOperation {
		public FloatAdd(FloatEval left, FloatEval right) { super(left, right); }

		@Override
		protected float apply(float left, float right) { return left + right; }
	}

	public static final class FloatSub extends FloatMathOperation {
		public FloatSub(FloatEval left, FloatEval right) { super(left, right); }

		@Override
		protected float apply(float left, float right) { return left - right; }
	}

	public static final class FloatMul extends FloatMathOperation {
		public FloatMul(FloatEval left, FloatEval right) { super(left, right); }

		@Override
		protected float apply(float left, float right) { return left * right; }
	}

	public static final class FloatDiv extends FloatMathOperation {
		public FloatDiv(FloatEval left, FloatEval right) { super(left, right); }

		@Override
		protected float apply(float left, float right) { return left / right; }
	}

	public static final class FloatMod extends FloatMathOperation {
		public FloatMod(FloatEval left, FloatEval right) { super(left, right); }

		@Override
		protected float apply(float left, float right) { return left % right; }
	}

	@RequiredArgsConstructor
	public static final class BooleanComparisons implements BooleanEval {
		private final Operator op;
		private final BooleanEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) {
			// AND/OR both can short circuit based on lVal value, so rVal is being sampled as part of the check
			final boolean lVal = l.apply(vars);
			switch (op) {
				case AND:
					return lVal && r.apply(vars);
				case OR:
					return lVal || r.apply(vars);
				case EQUAL:
					return lVal == r.apply(vars);
				case NOTEQUAL:
					return lVal != r.apply(vars);
			}

			throw new UnsupportedOperationException("Operator '" + op + "' is not a boolean comparison operator");
		}
	}

	@RequiredArgsConstructor
	public static final class BooleanNot implements BooleanEval {
		private final BooleanEval operand;

		@Override
		public boolean apply(VariableSupplier vars) { return !operand.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntComparisons implements BooleanEval {
		private final Operator op;
		private final IntEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) {
			final int lVal = l.apply(vars);
			final int rVal = r.apply(vars);
			switch (op) {
				case LESS:
					return lVal < rVal;
				case LEQUAL:
					return lVal <= rVal;
				case GREATER:
					return lVal > rVal;
				case GEQUAL:
					return lVal >= rVal;
				case EQUAL:
					return lVal == rVal;
				case NOTEQUAL:
					return lVal != rVal;
			}

			throw new UnsupportedOperationException("Operator '" + op + "' is not a int comparison operator");
		}
	}

	@RequiredArgsConstructor
	public static final class FloatComparisons implements BooleanEval {
		private final Operator op;
		private final FloatEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) {
			final float lVal = l.apply(vars);
			final float rVal = r.apply(vars);
			switch (op) {
				case LESS:
					return lVal < rVal;
				case LEQUAL:
					return lVal <= rVal;
				case GREATER:
					return lVal > rVal;
				case GEQUAL:
					return lVal >= rVal;
				case EQUAL:
					return lVal == rVal;
				case NOTEQUAL:
					return lVal != rVal;
			}

			throw new UnsupportedOperationException("Operator '" + op + "' is not a int comparison operator");
		}
	}
}
