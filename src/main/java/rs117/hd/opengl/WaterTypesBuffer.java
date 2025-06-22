package rs117.hd.opengl;

import rs117.hd.data.WaterType;

public class WaterTypesBuffer extends UniformBuffer{
	public WaterTypesBuffer() {
		super("WaterTypes");
	}

	public WaterTypeStruct[] waterTypes = addStructs(new WaterTypeStruct[WaterType.values().length], WaterTypeStruct::new);

	public static class WaterTypeStruct extends StructProperty {
		public Property isFlat = addProperty(PropertyType.Int, "IsFlat");
		public Property specularStrength = addProperty(PropertyType.Float, "SpecularStrength");
		public Property specularGloss = addProperty(PropertyType.Float, "SpecularGloss");
		public Property normalStrength = addProperty(PropertyType.Float, "NormalStrength");
		public Property baseOpacity = addProperty(PropertyType.Float, "BaseOpacity");
		public Property hasFoam = addProperty(PropertyType.Int, "HasFoam");
		public Property duration = addProperty(PropertyType.Float, "Duration");
		public Property fresnelAmount = addProperty(PropertyType.Float, "FresnelAmount");
		public Property surfaceColor = addProperty(PropertyType.FVec3, "SurfaceColor");
		public Property foamColor = addProperty(PropertyType.FVec3, "FoamColor");
		public Property depthColor = addProperty(PropertyType.FVec3, "DepthColor");
		public Property causticsStrength = addProperty(PropertyType.Float, "CausticsStrength");
		public Property normalMap = addProperty(PropertyType.Int, "NormalMap");
		public Property foamMap = addProperty(PropertyType.Int, "FoamMap");
		public Property flowMap = addProperty(PropertyType.Int, "FlowMap");
		public Property underwaterFlowMap = addProperty(PropertyType.Int, "UnderwaterFlowMap");
	}
}
