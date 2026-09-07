package rs117.hd.opengl.uniforms;

import rs117.hd.scene.daylight_cycle.StarField;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

public class UBOSky extends UniformBuffer<GLBuffer> {
	public UBOSky() {
		super(GL_DYNAMIC_DRAW);
	}

	// Sky gradient and celestial state
	public Property skyGradientEnabled = addProperty(PropertyType.Int, "skyGradientEnabled");
	public Property skyZenithColor = addProperty(PropertyType.FVec3, "skyZenithColor");
	public Property skyHorizonColor = addProperty(PropertyType.FVec3, "skyHorizonColor");
	public Property skySunColor = addProperty(PropertyType.FVec3, "skySunColor");
	public Property skySunDir = addProperty(PropertyType.FVec3, "skySunDir");
	public Property skyCelestialPole = addProperty(PropertyType.FVec3, "skyCelestialPole");
	public Property skyCelestialRotation = addProperty(PropertyType.Float, "skyCelestialRotation");

	public Property skyMoonDir = addProperty(PropertyType.FVec3, "skyMoonDir");
	public Property skyMoonDiskColor = addProperty(PropertyType.FVec3, "skyMoonDiskColor");
	public Property skyMoonIllumination = addProperty(PropertyType.Float, "skyMoonIllumination");
	public Property skyMoonPhaseLightDirection = addProperty(PropertyType.FVec3, "skyMoonPhaseLightDirection");
	public Property skyMoonLibration = addProperty(PropertyType.FVec2, "skyMoonLibration");
	public Property skyMoonPhaseReversed = addProperty(PropertyType.Float, "skyMoonPhaseReversed");

	// Environment visibility controls
	public Property skyVisibility = addProperty(PropertyType.Float, "skyVisibility");
	public Property moonVisibility = addProperty(PropertyType.Float, "moonVisibility");
	public Property starVisibility = addProperty(PropertyType.Float, "starVisibility");
	public Property nebulaVisibility = addProperty(PropertyType.Float, "nebulaVisibility");
	public Property auroraVisibility = addProperty(PropertyType.Float, "auroraVisibility");

	public Property moonSizeMult = addProperty(PropertyType.Float, "moonSizeMult");
	public Property starHorizonHeight = addProperty(PropertyType.Float, "starHorizonHeight");

	public final Property[] nebulaClusters = addPropertyArray(PropertyType.FVec4, "nebulaClusters", StarField.CLUSTER_COUNT);
}
