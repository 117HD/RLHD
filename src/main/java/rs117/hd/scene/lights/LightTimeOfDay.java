package rs117.hd.scene.lights;

public enum LightTimeOfDay {
	DAY(0f, 0f),
	DAWN(0f, 0.22f),
	DUSK(0f, 0.22f),
	TWILIGHT(0.22f, 0.44f),
	NIGHT(0.44f, 0.65f),
	DEEP_NIGHT(0.65f, 1f);

	public final float start;
	public final float end;

	LightTimeOfDay(float start, float end) {
		this.start = start;
		this.end = end;
	}
}
