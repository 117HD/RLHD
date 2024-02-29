package rs117.hd.utils;

@FunctionalInterface
public interface VariableSupplier {
	Object get(String variableName);
}
