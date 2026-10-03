package rs117.hd.opengl.uniforms;

import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;

public class UBOGlobal extends UniformBuffer<GLBuffer> {
	public UBOGlobal() {
		super(GL_DYNAMIC_DRAW);
	}

	@Override
	public void initialize() {
		super.initialize();
	}

	// Ordered by frequency of updates, from least to most frequent

	public final Property colorPicker = addProperty(PropertyType.FVec4, "colorPicker");

	public final Property orthographicProjection = addProperty(PropertyType.Int, "orthographicProjection");

	public final Property expandedMapLoadingChunks = addProperty(PropertyType.Int, "expandedMapLoadingChunks");
	public final Property drawDistance = addProperty(PropertyType.Float, "drawDistance");

	public final Property colorBlindnessIntensity = addProperty(PropertyType.Float, "colorBlindnessIntensity");
	public final Property gammaCorrection = addProperty(PropertyType.Float, "gammaCorrection");
	public final Property saturation = addProperty(PropertyType.Float, "saturation");
	public final Property contrast = addProperty(PropertyType.Float, "contrast");
	public final Property colorFilterPrevious = addProperty(PropertyType.Int, "colorFilterPrevious");
	public final Property colorFilter = addProperty(PropertyType.Int, "colorFilter");
	public final Property colorFilterFade = addProperty(PropertyType.Float, "colorFilterFade");

	public final Property viewportSize = addProperty(PropertyType.IVec2, "viewportSize");
	public final Property sceneResolution = addProperty(PropertyType.IVec2, "sceneResolution");
	public final Property tiledLightingResolution = addProperty(PropertyType.IVec2, "tiledLightingResolution");

	public final Property ambientColor = addProperty(PropertyType.FVec3, "ambientColor");
	public final Property ambientStrength = addProperty(PropertyType.Float, "ambientStrength");
	public final Property lightColor = addProperty(PropertyType.FVec3, "lightColor");
	public final Property lightStrength = addProperty(PropertyType.Float, "lightStrength");
	public final Property underglowColor = addProperty(PropertyType.FVec3, "underglowColor");
	public final Property underglowStrength = addProperty(PropertyType.Float, "underglowStrength");

	public final Property useFog = addProperty(PropertyType.Int, "useFog");
	public final Property fogDepth = addProperty(PropertyType.Float, "fogDepth");
	public final Property fogColor = addProperty(PropertyType.FVec3, "fogColor");
	public final Property groundFogStart = addProperty(PropertyType.Float, "groundFogStart");
	public final Property groundFogEnd = addProperty(PropertyType.Float, "groundFogEnd");
	public final Property groundFogOpacity = addProperty(PropertyType.Float, "groundFogOpacity");

	public final Property waterColorLight = addProperty(PropertyType.FVec3, "waterColorLight");
	public final Property waterColorMid = addProperty(PropertyType.FVec3, "waterColorMid");
	public final Property waterColorDark = addProperty(PropertyType.FVec3, "waterColorDark");

	public final Property underwaterEnvironment = addProperty(PropertyType.Int, "underwaterEnvironment");
	public final Property underwaterCaustics = addProperty(PropertyType.Int, "underwaterCaustics");
	public final Property underwaterCausticsColor = addProperty(PropertyType.FVec3, "underwaterCausticsColor");
	public final Property underwaterCausticsStrength = addProperty(PropertyType.Float, "underwaterCausticsStrength");

	public final Property lightDir = addProperty(PropertyType.FVec3, "lightDir");

	public final Property pointLightsCount = addProperty(PropertyType.Int, "pointLightsCount");

	public final Property cameraPos = addProperty(PropertyType.FVec3, "cameraPos");
	public final Property viewMatrix = addProperty(PropertyType.Mat4, "viewMatrix");
	public final Property projectionMatrix = addProperty(PropertyType.Mat4, "projectionMatrix");
	public final Property invProjectionMatrix = addProperty(PropertyType.Mat4, "invProjectionMatrix");
	public final Property lightProjectionMatrix = addProperty(PropertyType.Mat4, "lightProjectionMatrix");
	public final Property invLightProjectionMatrix = addProperty(PropertyType.Mat4, "invLightProjectionMatrix");
	public final Property shadowBiasScale = addProperty(PropertyType.Float, "shadowBiasScale");

	public final Property lightningBrightness = addProperty(PropertyType.Float, "lightningBrightness");
	public final Property elapsedTime = addProperty(PropertyType.Float, "elapsedTime");
}
