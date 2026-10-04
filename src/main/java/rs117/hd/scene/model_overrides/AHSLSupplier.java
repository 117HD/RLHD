package rs117.hd.scene.model_overrides;

import rs117.hd.utils.Props;
import rs117.hd.utils.VariableSupplier;

public final class AHSLSupplier implements VariableSupplier {
	public enum Var { a, h, s, l, ahsl, hsl }

	private final int[] values = new int[Var.values().length];

	public AHSLSupplier ahsl(int ahsl) {
		values[Var.hsl.ordinal()] = ahsl & 0xFFFF;
		values[Var.a.ordinal()]   = (ahsl >>> 16) & 0xFF;
		values[Var.h.ordinal()]   = (ahsl >>> 10) & 0x3F;
		values[Var.s.ordinal()]   = (ahsl >>> 7) & 0x7;
		values[Var.l.ordinal()]   = ahsl & 0x7F;
		values[Var.ahsl.ordinal()] = ahsl;
		return this;
	}

	public AHSLSupplier ahsl(int transparency, int color) {
		return ahsl(((0xFF - transparency) << 16) | (color & 0xFFFF));
	}

	@Override
	public int getInt(Enum<?> key) {
		if(Props.DEVELOPMENT && !(key instanceof Var))
			throw new IllegalArgumentException("Undefined variable '" + key + "'");
		return values[key.ordinal()];
	}

	@Override
	public float getFloat(Enum<?> key) { return getInt(key); }

	@Override
	public Object get(String name) { return getInt(Var.valueOf(name)); }
}
