package rs117.hd.opengl;

public class GlobalBuffer extends UniformBuffer {
	public GlobalBuffer() {
		super("Global");
	}

	public Property CameraPos = AddProperty(PropertyType.FVec3, "CameraPos");
	public Property ExpandedMapLoadingChunks = AddProperty(PropertyType.Int, "ExpandedMapLoadingChunks");
	public Property DrawDistance = AddProperty(PropertyType.Float, "DrawDistance");
	public Property ElapsedTime = AddProperty(PropertyType.Float, "ElapsedTime");

	public Property ColorBlindnessIntensity = AddProperty(PropertyType.Float, "ColorBlindnessIntensity");
	public Property GammaCorrection = AddProperty(PropertyType.Float, "GammaCorrection");
	public Property Saturation = AddProperty(PropertyType.Float, "Saturation");
	public Property Contrast = AddProperty(PropertyType.Float, "Contrast");

	public Property AmbientColor = AddProperty(PropertyType.FVec3, "AmbientColor");
	public Property AmbientStrength = AddProperty(PropertyType.Float, "AmbientStrength");

	public Property LightColor = AddProperty(PropertyType.FVec3, "LightColor");
	public Property LightStrength = AddProperty(PropertyType.Float, "LightStrength");

	public Property UnderglowColor = AddProperty(PropertyType.FVec3, "UnderglowColor");
	public Property UnderglowStrength = AddProperty(PropertyType.Float, "UnderglowStrength");

	public Property ProjectionMatrix = AddProperty(PropertyType.Mat4, "ProjectionMatrix");
	public Property LightProjectionMatrix = AddProperty(PropertyType.Mat4, "LightProjectionMatrix");

	public Property UseFog = AddProperty(PropertyType.Int, "UseFog");
	public Property FogDepth = AddProperty(PropertyType.Float, "FogDepth");
	public Property FogColor = AddProperty(PropertyType.FVec3, "FogColor");
	public Property GroundFogStart = AddProperty(PropertyType.Float, "GroundFogStart");
	public Property GroundFogEnd = AddProperty(PropertyType.Float, "GroundFogEnd");
	public Property GroundFogOpacity = AddProperty(PropertyType.Float, "GroundFogOpacity");

	public Property PointLightsCount = AddProperty(PropertyType.Int, "PointLightsCount");
	public Property LightningBrightness = AddProperty(PropertyType.Float, "LightningBrightness");
	public Property LightDir = AddProperty(PropertyType.FVec3, "LightDir");

	public Property ShadowMaxBias = AddProperty(PropertyType.Float, "ShadowMaxBias");
	public Property ShadowsEnabled = AddProperty(PropertyType.Int, "ShadowsEnabled");

	public Property WaterColorLight = AddProperty(PropertyType.FVec3, "WaterColorLight");
	public Property WaterColorMid = AddProperty(PropertyType.FVec3, "WaterColorMid");
	public Property WaterColorDark = AddProperty(PropertyType.FVec3, "WaterColorDark");

	public Property UnderwaterEnvironment = AddProperty(PropertyType.Int, "UnderwaterEnvironment");
	public Property UnderwaterCaustics = AddProperty(PropertyType.Int, "UnderwaterCaustics");
	public Property UnderwaterCausticsColor = AddProperty(PropertyType.FVec3, "UnderwaterCausticsColor");
	public Property UnderwaterCausticsStrength = AddProperty(PropertyType.Float, "UnderwaterCausticsStrength");

	public Property ColorFilterPrevious = AddProperty(PropertyType.Int, "ColorFilterPrevious");
	public Property ColorFilter = AddProperty(PropertyType.Int, "ColorFilter");
	public Property ColorFilterFade = AddProperty(PropertyType.FVec3, "ColorFilterFade");
}
