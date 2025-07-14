package rs117.hd.opengl;

import rs117.hd.data.WaterType;

import static org.lwjgl.opengl.GL15C.*;

public class WaterTypeUniforms extends UniformBuffer {
	public WaterTypeUniforms() {
		super("WaterTypes", GL_STATIC_DRAW);
	}

	public WaterTypeStruct[] waterTypes = addStructs(new WaterTypeStruct[WaterType.values().length], WaterTypeStruct::new);

	public static class WaterTypeStruct extends StructProperty {
		public Property isFlat = addProperty(PropertyType.Int, "isFlat");
		public Property specularStrength = addProperty(PropertyType.Float, "specularStrength");
		public Property specularGloss = addProperty(PropertyType.Float, "specularGloss");
		public Property normalStrength = addProperty(PropertyType.Float, "normalStrength");
		public Property baseOpacity = addProperty(PropertyType.Float, "baseOpacity");
		public Property hasFoam = addProperty(PropertyType.Int, "hasFoam");
		public Property duration = addProperty(PropertyType.Float, "duration");
		public Property fresnelAmount = addProperty(PropertyType.Float, "fresnelAmount");
		public Property surfaceColor = addProperty(PropertyType.FVec3, "surfaceColor");
		public Property foamColor = addProperty(PropertyType.FVec3, "foamColor");
		public Property depthColor = addProperty(PropertyType.FVec3, "depthColor");
		public Property causticsStrength = addProperty(PropertyType.Float, "causticsStrength");
		public Property normalMap = addProperty(PropertyType.Int, "normalMap");
		public Property foamMap = addProperty(PropertyType.Int, "foamMap");
		public Property flowMap = addProperty(PropertyType.Int, "flowMap");
		public Property underwaterFlowMap = addProperty(PropertyType.Int, "underwaterFlowMap");
	}
}
