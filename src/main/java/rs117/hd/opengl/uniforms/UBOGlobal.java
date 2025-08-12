package rs117.hd.opengl.uniforms;

import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

public class UBOGlobal extends UniformBuffer<GLBuffer> {
	public UBOGlobal() {
		super(GL_DYNAMIC_DRAW);
	}

	public Property expandedMapLoadingChunks = addProperty(PropertyType.Int, "expandedMapLoadingChunks");
	public Property drawDistance = addProperty(PropertyType.Float, "drawDistance");

	public Property colorBlindnessIntensity = addProperty(PropertyType.Float, "colorBlindnessIntensity");
	public Property gammaCorrection = addProperty(PropertyType.Float, "gammaCorrection");
	public Property saturation = addProperty(PropertyType.Float, "saturation");
	public Property contrast = addProperty(PropertyType.Float, "contrast");
	public Property colorFilterPrevious = addProperty(PropertyType.Int, "colorFilterPrevious");
	public Property colorFilter = addProperty(PropertyType.Int, "colorFilter");
	public Property colorFilterFade = addProperty(PropertyType.Float, "colorFilterFade");

	public Property sceneResolution = addProperty(PropertyType.IVec2, "sceneResolution");
	public Property tiledLightingResolution = addProperty(PropertyType.IVec2, "tiledLightingResolution");

	public Property ambientColor = addProperty(PropertyType.FVec3, "ambientColor");
	public Property ambientStrength = addProperty(PropertyType.Float, "ambientStrength");
	public Property lightColor = addProperty(PropertyType.FVec3, "lightColor");
	public Property lightStrength = addProperty(PropertyType.Float, "lightStrength");
	public Property underglowColor = addProperty(PropertyType.FVec3, "underglowColor");
	public Property underglowStrength = addProperty(PropertyType.Float, "underglowStrength");

	public Property useFog = addProperty(PropertyType.Int, "useFog");
	public Property fogDepth = addProperty(PropertyType.Float, "fogDepth");
	public Property fogColor = addProperty(PropertyType.FVec3, "fogColor");
	public Property groundFogStart = addProperty(PropertyType.Float, "groundFogStart");
	public Property groundFogEnd = addProperty(PropertyType.Float, "groundFogEnd");
	public Property groundFogOpacity = addProperty(PropertyType.Float, "groundFogOpacity");

	public Property waterColorLight = addProperty(PropertyType.FVec3, "waterColorLight");
	public Property waterColorMid = addProperty(PropertyType.FVec3, "waterColorMid");
	public Property waterColorDark = addProperty(PropertyType.FVec3, "waterColorDark");

	public Property underwaterEnvironment = addProperty(PropertyType.Int, "underwaterEnvironment");
	public Property underwaterCaustics = addProperty(PropertyType.Int, "underwaterCaustics");
	public Property underwaterCausticsColor = addProperty(PropertyType.FVec3, "underwaterCausticsColor");
	public Property underwaterCausticsStrength = addProperty(PropertyType.Float, "underwaterCausticsStrength");

	public Property lightDir = addProperty(PropertyType.FVec3, "lightDir");

	public Property pointLightsCount = addProperty(PropertyType.Int, "pointLightsCount");

	public Property cameraPos = addProperty(PropertyType.FVec3, "cameraPos");
	public Property viewMatrix = addProperty(PropertyType.Mat4, "viewMatrix");
	public Property projectionMatrix = addProperty(PropertyType.Mat4, "projectionMatrix");
	public Property invProjectionMatrix = addProperty(PropertyType.Mat4, "invProjectionMatrix");
	public Property lightProjectionMatrix = addProperty(PropertyType.Mat4, "lightProjectionMatrix");

	public Property lightningBrightness = addProperty(PropertyType.Float, "lightningBrightness");
	public Property elapsedTime = addProperty(PropertyType.Float, "elapsedTime");
}
