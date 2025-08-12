package rs117.hd.utils;

import java.util.function.Predicate;

@FunctionalInterface
public interface ExpressionPredicate extends Predicate<VariableSupplier> {
	ExpressionPredicate TRUE = vars -> true;
	ExpressionPredicate FALSE = vars -> false;

	default boolean test() {
		return test(vars -> null);
	}
}
