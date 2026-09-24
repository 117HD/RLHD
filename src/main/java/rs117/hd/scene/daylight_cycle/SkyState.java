package rs117.hd.scene.daylight_cycle;

import rs117.hd.config.DaylightCycle;
import rs117.hd.scene.environments.Environment;

public final class SkyState {
	public boolean cycleActive;
	public DaylightCycle cycle;
	public long utcMillis;
	public final float[] latLon = new float[2];
	public long transitionId;
	public Environment fromEnvironment;
	public Environment toEnvironment;
	public float transitionProgress;
	public float moonDirectionalStrength;
	public float[] sunAngles;
	public float[] moonAngles;
	public float[] shadowAngles;
	public float[] sunDirection;
	public float[] moonDirection;
	/** Direction from the moon toward its illuminant. */
	public float[] moonIlluminationDirection;
	public final float[] moonLibration = new float[2];
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
	}

	public static final class LightingSample extends GradientSample {
		public final SkyState sky = new SkyState();
		public float[] referenceFogColorLinear;
	}
}
