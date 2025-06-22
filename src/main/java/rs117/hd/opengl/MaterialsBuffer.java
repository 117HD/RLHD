package rs117.hd.opengl;

import rs117.hd.data.materials.Material;

public class MaterialsBuffer extends UniformBuffer{
	public MaterialsBuffer() {
		super("Materials");
	}

	public MaterialStruct[] materials = addStructs(new MaterialStruct[Material.values().length], MaterialStruct::new);

	public static class MaterialStruct extends StructProperty {
		public Property colorMap = addProperty(PropertyType.Int, "ColorMap");
		public Property normalMap = addProperty(PropertyType.Int, "NormalMap");
		public Property displacementMap = addProperty(PropertyType.Int, "DisplacementMap");
		public Property roughnessMap = addProperty(PropertyType.Int, "RoughnessMap");
		public Property ambientOcclusionMap = addProperty(PropertyType.Int, "AmbientOcclusionMap");
		public Property flowMap = addProperty(PropertyType.Int, "FlowMap");
		public Property flags = addProperty(PropertyType.Int, "Flags");
		public Property brightness = addProperty(PropertyType.Float, "brightness");
		public Property displacementScale = addProperty(PropertyType.Float, "DisplacementScale");
		public Property specularStrength = addProperty(PropertyType.Float, "SpecularStrength");
		public Property specularGloss = addProperty(PropertyType.Float, "SpecularGloss");
		public Property flowMapStrength = addProperty(PropertyType.Float, "FlowMapStrength");
		public Property flowMapDuration = addProperty(PropertyType.FVec2, "FlowMapDuration");
		public Property scrollDuration = addProperty(PropertyType.FVec2, "ScrollDuration");
		public Property textureScale = addProperty(PropertyType.FVec3, "TextureScale");
	}
}
