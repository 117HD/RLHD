package rs117.hd.opengl;

public class UIBuffer extends UniformBuffer {
	public UIBuffer() {
		super("UI");
	}

	public Property SourceDimensions = AddProperty(PropertyType.IVec2, "SourceDimensions");
	public Property TargetDimensions = AddProperty(PropertyType.IVec2, "TargetDimensions");

	public Property ColorBlindnessIntensity = AddProperty(PropertyType.Float, "ColorBlindnessIntensity");
	public Property AlphaOverlay = AddProperty(PropertyType.FVec4, "AlphaOverlay");

	public Property ShowGammaCalibration = AddProperty(PropertyType.Int, "ShowGammaCalibration");
	public Property GammaCalibrationTimer = AddProperty(PropertyType.Float, "GammaCalibrationTimer");
	public Property GammaCorrection = AddProperty(PropertyType.Float, "GammaCorrection");
}
