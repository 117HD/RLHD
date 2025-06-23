package rs117.hd.opengl;

public class UIUniforms extends UniformBuffer {
	public UIUniforms() {
		super("UI");
	}

	public Property sourceDimensions = addProperty(PropertyType.IVec2, "sourceDimensions");
	public Property targetDimensions = addProperty(PropertyType.IVec2, "targetDimensions");

	public Property colorBlindnessIntensity = addProperty(PropertyType.Float, "colorBlindnessIntensity");
	public Property alphaOverlay = addProperty(PropertyType.FVec4, "alphaOverlay");

	public Property showGammaCalibration = addProperty(PropertyType.Int, "showGammaCalibration");
	public Property gammaCalibrationTimer = addProperty(PropertyType.Float, "gammaCalibrationTimer");
	public Property gammaCorrection = addProperty(PropertyType.Float, "gammaCorrection");
}
