package rs117.hd.opengl.uniforms;

import rs117.hd.scene.StarField;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

public class UBOSkybox extends UniformBuffer<GLBuffer> {
	public UBOSkybox() {
		super(GL_DYNAMIC_DRAW);
	}

	// Sky gradient uniforms for day & night cycle
	public Property skyGradientEnabled = addProperty(PropertyType.Int, "skyGradientEnabled");
	public Property skyZenithColor = addProperty(PropertyType.FVec3, "skyZenithColor");
	public Property skyHorizonColor = addProperty(PropertyType.FVec3, "skyHorizonColor");
	public Property skySunColor = addProperty(PropertyType.FVec3, "skySunColor");
	public Property skySunDir = addProperty(PropertyType.FVec3, "skySunDir");

	// Moon uniforms for day & night Cycle
	public Property skyMoonDir = addProperty(PropertyType.FVec3, "skyMoonDir");
	public Property skyMoonColor = addProperty(PropertyType.FVec3, "skyMoonColor");
	public Property skyMoonIllumination = addProperty(PropertyType.Float, "skyMoonIllumination");

	// Star visibility (from environment override)
	public Property starVisibility = addProperty(PropertyType.Float, "starVisibility");

	// Nebula visibility (toggled via config)
	public Property nebulaVisibility = addProperty(PropertyType.Float, "nebulaVisibility");

	// Moon visibility (from environment override)
	public Property moonVisibility = addProperty(PropertyType.Float, "moonVisibility");

	// Aurora visibility (1 on randomly-selected aurora nights, otherwise 0)
	public Property auroraVisibility = addProperty(PropertyType.Float, "auroraVisibility");

	// Moon size multiplier (from environment override) - scales moon disk, glow, and star mask
	public Property moonSizeMult = addProperty(PropertyType.Float, "moonSizeMult");

	public final Property[] nebulaClusters = addPropertyArray(PropertyType.FVec4, "nebulaClusters", StarField.CLUSTER_COUNT);
	
	public void reset() {
		skyGradientEnabled.set(0);
		skyMoonDir.set(0f, 0f, 0f);
		skyMoonColor.set(0f, 0f, 0f);
		skyMoonIllumination.set(0f);
		starVisibility.set(1f);
		nebulaVisibility.set(0f);
		auroraVisibility.set(0f);
		moonSizeMult.set(1f);
		upload();
	}
}
