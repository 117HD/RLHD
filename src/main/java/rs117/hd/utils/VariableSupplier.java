package rs117.hd.utils;

import java.util.Map;

@FunctionalInterface
public interface VariableSupplier {
	Object get(String name);

	default float getFloat(String name) {
		Object var = get(name);
		if(var instanceof Integer)
			return ((Integer)var).floatValue();
		return (int) var;
	}

	default int getInt(String name) {
		Object var = get(name);
		if(var instanceof Float)
			return ((Float)var).intValue();
		return (int) var;
	}

	default boolean getBoolean(String name) {
		return (Boolean) get(name);
	}

	default VariableSupplier proxy(VariableSupplier proxy) {
		return new ProxyVariableSupplier(this, proxy);
	}

	default VariableSupplier aliases(Map<String, Object> aliases) {
		return proxy(aliases::get);
	}

	final class ProxyVariableSupplier implements VariableSupplier {
		private final VariableSupplier base;
		private final VariableSupplier proxy;

		ProxyVariableSupplier(VariableSupplier base, VariableSupplier proxy) {
			this.base = base;
			this.proxy = proxy;
		}

		@Override
		public Object get(String name) {
			var value = proxy.get(name);
			if (value == null)
				return base.get(name);
			if (value instanceof String)
				return base.get((String) value);
			return value;
		}

		@Override
		public float getFloat(String name) {
			var value = proxy.get(name);
			if (value == null)
				return base.getFloat(name);
			if (value instanceof String)
				return base.getFloat((String) value);
			return (Float) value;
		}

		@Override
		public int getInt(String name) {
			var value = proxy.get(name);
			if (value == null)
				return base.getInt(name);
			if (value instanceof String)
				return base.getInt((String) value);
			return (Integer) value;
		}

		@Override
		public boolean getBoolean(String name) {
			var value = proxy.get(name);
			if (value == null)
				return base.getBoolean(name);
			if (value instanceof String)
				return base.getBoolean((String) value);
			return (Boolean) value;
		}
	}
}
