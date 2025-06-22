package rs117.hd.opengl;

import rs117.hd.data.WaterType;

public class WaterTypesBuffer extends UniformBuffer{
	public WaterTypesBuffer() {
		super("WaterTypes UBO");
	}

	public WaterTypeStruct[] WaterTypes = AddStructs(new WaterTypeStruct[WaterType.values().length], WaterTypeStruct::new);

	public class WaterTypeStruct extends StructProperty {
		public Property IsFlat = AddProperty(PropertyType.Int, "IsFlat");
		public Property SpecularStrength = AddProperty(PropertyType.Float, "SpecularStrength");
		public Property SpecularGloss = AddProperty(PropertyType.Float, "SpecularGloss");
		public Property NormalStrength = AddProperty(PropertyType.Float, "NormalStrength");
		public Property BaseOpacity = AddProperty(PropertyType.Float, "BaseOpacity");
		public Property HasFoam = AddProperty(PropertyType.Int, "HasFoam");
		public Property Duration = AddProperty(PropertyType.Float, "Duration");
		public Property FresnelAmount = AddProperty(PropertyType.Float, "FresnelAmount");
		public Property SurfaceColor = AddProperty(PropertyType.FVec3, "SurfaceColor");
		public Property FoamColor = AddProperty(PropertyType.FVec3, "FoamColor");
		public Property DepthColor = AddProperty(PropertyType.FVec3, "DepthColor");
		public Property CausticsStrength = AddProperty(PropertyType.Float, "CausticsStrength");
		public Property NormalMap = AddProperty(PropertyType.Int, "NormalMap");
		public Property FoamMap = AddProperty(PropertyType.Int, "FoamMap");
		public Property FlowMap = AddProperty(PropertyType.Int, "FlowMap");
		public Property UnderwaterFlowMap = AddProperty(PropertyType.Int, "UnderwaterFlowMap");

	}
}