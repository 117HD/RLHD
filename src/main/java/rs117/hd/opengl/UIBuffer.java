package rs117.hd.opengl;

public class UIBuffer extends UniformBuffer {
	@UBOProperty(UBOEntryType.IVec2)	public UBOEntry<Integer[]> SourceDimensions;
	@UBOProperty(UBOEntryType.IVec2)	public UBOEntry<Integer[]> TargetDimensions;

	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> ColorBlindnessIntensity;
	@UBOProperty(UBOEntryType.FVec4)	public UBOEntry<float[]> AlphaOverlay;

	@UBOProperty(UBOEntryType.Int)		public UBOEntry<Integer> ShowGammaCalibration;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> GammaCalibrationTimer;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> GammaCorrection;
}
