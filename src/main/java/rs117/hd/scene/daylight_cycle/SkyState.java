package rs117.hd.scene.daylight_cycle;

public final class SkyState {
	public boolean cycleActive;
	public boolean castsShadows;
	public SkyConfiguration fromConfiguration;
	public SkyConfiguration toConfiguration;
	public float configurationTransition;
	public float moonDirectionalStrength;
	public float[] sunAngles;
	public float[] moonAngles;
	public float[] shadowAngles;
	public float[] sunDirection;
	public float[] moonDirection;
	public float[] moonPhaseLightDirection;
	public boolean moonPhaseReversed;
	public float[] moonLibration;
	public float[] celestialPole;
	public float celestialRotation;
	public float moonIllumination;
	public float sunAltitudeDegrees;
	public float moonAltitudeDegrees;
	public float moonVisibility;
	public float auroraStrength;

	public static class GradientSample {
		public float[] zenithSrgb;
		public float[] horizonSrgb;
		public float[] sunGlowSrgb;
		public float brightnessMultiplier;
	}

	public static final class LightingSample extends GradientSample {
		public float[] horizonLinear;
		public float[] referenceFogColorLinear;
		public float sunAltitudeDegrees;
		public float moonAltitudeDegrees;
		public float visibleMoonIllumination;
	}
}
