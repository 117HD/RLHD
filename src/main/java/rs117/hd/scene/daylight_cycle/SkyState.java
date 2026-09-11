package rs117.hd.scene.daylight_cycle;

/**
 * Celestial state is resolved by {@code SkyManager}; {@code SkyRenderer} then resolves shadow eligibility.
 */
public final class SkyState {
	/** Colors evaluated at a specified solar altitude, before environment blending and moon tinting. */
	public static class GradientSample {
		public float[] zenithSrgb;
		public float[] horizonSrgb;
		public float[] sunGlowSrgb;
		public float brightnessMultiplier;
	}

	/** Complete lighting inputs for an authored environment, resolved by SkyManager. */
	public static final class LightingSample extends GradientSample {
		public float[] horizonLinear;
		public float[] referenceFogColorLinear;
		public float sunAltitudeDegrees;
		public float moonAltitudeDegrees;
		public float visibleMoonIllumination;
	}

	/** Whether the current area renders with the daylight cycle rather than its environment lighting. */
	public boolean cycleActive;
	/** Set by SkyRenderer.prepareFrame before shadow rendering: directional lighting has non-zero strength. */
	public boolean castsShadows;
	public SkyConfiguration fromConfiguration;
	public SkyConfiguration toConfiguration;
	public float configurationTransition;
	public float moonDirectionalStrength;
	public float[] sunAngles;
	public float[] moonAngles;
	/** Cycle shadow source, or the environment's shadow angles when the cycle is inactive. */
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
}
