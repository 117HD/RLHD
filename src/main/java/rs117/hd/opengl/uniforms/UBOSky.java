package rs117.hd.opengl.uniforms;

import rs117.hd.scene.daylight_cycle.StarField;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

public class UBOSky extends UniformBuffer<GLBuffer> {
	public UBOSky() {
		super(GL_DYNAMIC_DRAW);
	}

	public Property gradientEnabled = addProperty(PropertyType.Int, "gradientEnabled");
	public Property zenithColor = addProperty(PropertyType.FVec3, "zenithColor");
	public Property horizonColor = addProperty(PropertyType.FVec3, "horizonColor");
	public Property sunColor = addProperty(PropertyType.FVec3, "sunColor");
	public Property customGradient = addProperty(PropertyType.Float, "customGradient");
	public Property horizonWidth = addProperty(PropertyType.Float, "horizonWidth");
	public Property sunDir = addProperty(PropertyType.FVec3, "sunDir");
	public Property celestialPole = addProperty(PropertyType.FVec3, "celestialPole");
	public Property celestialRotation = addProperty(PropertyType.Float, "celestialRotation");

	public Property moonDir = addProperty(PropertyType.FVec3, "moonDir");
	public Property moonDiskColor = addProperty(PropertyType.FVec3, "moonDiskColor");
	public Property moonIllumination = addProperty(PropertyType.Float, "moonIllumination");
	public Property moonSurfaceLightDirection = addProperty(PropertyType.FVec3, "moonSurfaceLightDirection");
	public Property moonLibration = addProperty(PropertyType.FVec2, "moonLibration");

	public Property fogColor = addProperty(PropertyType.FVec3, "fogColor");
	public Property fogDensity = addProperty(PropertyType.Float, "fogDensity");
	public Property visibility = addProperty(PropertyType.Float, "visibility");
	public Property moonVisibility = addProperty(PropertyType.Float, "moonVisibility");
	public Property starVisibility = addProperty(PropertyType.Float, "starVisibility");
	public Property nebulaVisibility = addProperty(PropertyType.Float, "nebulaVisibility");
	public Property auroraVisibility = addProperty(PropertyType.Float, "auroraVisibility");

	public Property moonSizeMult = addProperty(PropertyType.Float, "moonSizeMult");
	public Property starHorizonHeight = addProperty(PropertyType.Float, "starHorizonHeight");

	public final Property[] nebulaClusters = addPropertyArray(PropertyType.FVec4, "nebulaClusters", StarField.CLUSTER_COUNT);
}
