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
	public final float[] sunAngles = new float[2];
	public final float[] moonAngles = new float[2];
	public final float[] shadowAngles = new float[2];
	public final float[] sunDirection = new float[3];
	public final float[] moonDirection = new float[3];
	public final float[] moonSurfaceLightDirection = new float[3];
	public final float[] moonLibration = new float[2];
	public final float[] celestialPole = new float[3];
	public float celestialRotation;
	public float moonIllumination;
	public float moonLightIllumination;
	public float sunAltitudeDegrees;
	public float moonAltitudeDegrees;
	public float moonVisibility;
	public float auroraStrength;

	public static class GradientSample {
		public final float[] zenith = new float[3];
		public final float[] horizon = new float[3];
		public final float[] sunGlow = new float[3];
	}

	public static final class LightingSample extends GradientSample {
		public int frame;
		public Environment environment;
		public final SkyState sky = new SkyState();
		public final float[] referenceFogColorLinear = new float[3];
	}
}
