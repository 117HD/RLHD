package rs117.hd.scene.daylight_cycle;

import rs117.hd.config.DaylightCycle;

public final class SkyState {
	public boolean cycleActive;
	public DaylightCycle cycle;
	public long utcMillis;
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
	public float moonLightIllumination;
	public float sunAltitudeDegrees;
	public float moonAltitudeDegrees;
	public float moonVisibility;
	public float auroraStrength;

	public static class GradientSample {
		// Keep evaluated colors linear through transitions, moon tinting, and UBO upload.
		public float[] zenithLinear;
		public float[] horizonLinear;
		public float[] sunGlowLinear;
		public float brightnessMultiplier;
	}

	public static final class LightingSample extends GradientSample {
		public final SkyState sky = new SkyState();
		public float[] referenceFogColorLinear;
	}
}
