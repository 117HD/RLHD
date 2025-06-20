package rs117.hd.opengl;

public class GlobalBuffer extends UniformBuffer {
	@UBOProperty(UBOEntryType.FVec3)	public UBOEntry<float[]> CameraPos;
	@UBOProperty(UBOEntryType.Int)		public UBOEntry<Integer> ExpandedMapLoadingChunks;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> DrawDistance;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> ElapsedTime;

	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> ColorBlindnessIntensity;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> GammaCorrection;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> Saturation;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> Contrast;

	@UBOProperty(UBOEntryType.FVec3)	public UBOEntry<float[]> AmbientColor;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> AmbientStrength;

	@UBOProperty(UBOEntryType.FVec3)	public UBOEntry<float[]> LightColor;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> LightStrength;

	@UBOProperty(UBOEntryType.FVec3)	public UBOEntry<float[]> UnderglowColor;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> UnderglowStrength;

	@UBOProperty(UBOEntryType.Mat4)		public UBOEntry<float[]> ProjectionMatrix;
	@UBOProperty(UBOEntryType.Mat4)		public UBOEntry<float[]> LightProjectionMatrix;

	@UBOProperty(UBOEntryType.Int)		public UBOEntry<Integer> UseFog;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> FogDepth;
	@UBOProperty(UBOEntryType.FVec3)	public UBOEntry<float[]> FogColor;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> GroundFogStart;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> GroundFogEnd;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> GroundFogOpacity;

	@UBOProperty(UBOEntryType.Int)		public UBOEntry<Integer> PointLightsCount;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> LightningBrightness;
	@UBOProperty(UBOEntryType.FVec3)	public UBOEntry<float[]> LightDir;

	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> ShadowMaxBias;
	@UBOProperty(UBOEntryType.Int)		public UBOEntry<Integer> ShadowsEnabled;

	@UBOProperty(UBOEntryType.FVec3)	public UBOEntry<float[]> WaterColorLight;
	@UBOProperty(UBOEntryType.FVec3)	public UBOEntry<float[]> WaterColorMid;
	@UBOProperty(UBOEntryType.FVec3)	public UBOEntry<float[]> WaterColorDark ;

	@UBOProperty(UBOEntryType.Int)		public UBOEntry<Integer> UnderwaterEnvironment;
	@UBOProperty(UBOEntryType.Int)		public UBOEntry<Integer> UnderwaterCaustics;
	@UBOProperty(UBOEntryType.FVec3)	public UBOEntry<float[]> UnderwaterCausticsColor;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> UnderwaterCausticsStrength;

	@UBOProperty(UBOEntryType.Int)		public UBOEntry<Integer> ColorFilterPrevious;
	@UBOProperty(UBOEntryType.Int)		public UBOEntry<Integer> ColorFilter;
	@UBOProperty(UBOEntryType.Float)	public UBOEntry<Float> ColorFilterFade;
}
