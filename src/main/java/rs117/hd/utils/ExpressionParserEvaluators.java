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
		private final String key;

		@Override
		public Object apply(VariableSupplier vars) { return vars.get(key); }
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
	public static final class IntVariable implements IntEval {
		private final String key;

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
	public static final class FloatVariable implements FloatEval {
		private final String key;

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
	public static final class BooleanVariable implements BooleanEval {
		private final String key;

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
	public static final class IntMathOperation implements IntEval {
		private final Operator op;
		private final IntEval l, r;

		@Override
		public int apply(VariableSupplier vars) {
			final int lVal = l.apply(vars);
			final int rVal = r.apply(vars);

			switch (op) {
				case ADD:
					return lVal + rVal;
				case SUB:
					return lVal - rVal;
				case MUL:
					return lVal * rVal;
				case DIV:
					return lVal / rVal;
				case MOD:
					return lVal % rVal;
			}

			throw new UnsupportedOperationException("Operator '" + op + "' is not a math operator");
		}
	}

	@RequiredArgsConstructor
	public static final class FloatMathOperation implements FloatEval {
		private final Operator op;
		private final FloatEval l, r;

		@Override
		public float apply(VariableSupplier vars) {
			final float lVal = l.apply(vars);
			final float rVal = r.apply(vars);

			switch (op) {
				case ADD:
					return lVal + rVal;
				case SUB:
					return lVal - rVal;
				case MUL:
					return lVal * rVal;
				case DIV:
					return lVal / rVal;
				case MOD:
					return lVal % rVal;
			}

			throw new UnsupportedOperationException("Operator '" + op + "' is not a math operator");
		}
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
