package rs117.hd.data;

public class DrawState {
	public static final byte NONE = 0;
	public static final byte PUSHED = 1 << 1;
	public static final byte DRAWCALL = 1 << 2;
}
