package rs117.hd.scene.model_overrides;

import rs117.hd.utils.VariableSupplier;

public final class AHSLSupplier implements VariableSupplier {
	private int alpha;
	private int h, s, l;
	private int ahsl, hsl;

	public AHSLSupplier ahsl(int ahsl) {
		this.ahsl = ahsl;
		alpha = (ahsl >> 16) & 0xFF;
		hsl = ahsl & 0xFFFF;
		h = ahsl >>> 10 & 0x3F;
		s = ahsl >>> 7 & 0x7;
		l = ahsl & 0x7F;
		return this;
	}

	public AHSLSupplier ahsl(int transparency, int color) {
		ahsl = (0xFF - transparency) << 16 | hsl;
		alpha = transparency & 0xFF;
		hsl = color & 0xFFFF;
		h = ahsl >>> 10 & 0x3F;
		s = ahsl >>> 7 & 0x7;
		l = ahsl & 0x7F;
		return this;
	}

	@Override
	public Object get(String name) {
		return getInt(name);
	}

	@Override
	public int getInt(String name) {
		if(name.length() == 1) {
			switch (name.charAt(0)) {
				case 'a':
					return alpha;
				case 'h':
					return h;
				case 's':
					return s;
				case 'l':
					return l;
				default:
					assert false : "Unexpected variable: " + name;
					return 0;
			}
		}

		switch (name) {
			case "ahsl":
				return ahsl;
			case "hsl":
				return hsl;
			default:
				assert false : "Unexpected variable: " + name;
				return 0;
		}
	}

	@Override
	public float getFloat(String name) {
		return getInt(name);
	}
}
