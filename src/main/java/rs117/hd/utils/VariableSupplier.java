package rs117.hd.utils;

import java.util.Map;

@FunctionalInterface
public interface VariableSupplier {
	Object get(String name);

	default VariableSupplier proxy(VariableSupplier proxy) {
		return name -> {
			var value = proxy.get(name);
			if (value == null)
				return get(name);
			if (value instanceof String)
				return get((String) value);
			return value;
		};
	}

	default VariableSupplier aliases(Map<String, Object> aliases) {
		return proxy(aliases::get);
	}
}
