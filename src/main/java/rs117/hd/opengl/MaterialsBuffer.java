package rs117.hd.opengl;

import rs117.hd.data.materials.Material;

public class MaterialsBuffer extends UniformBuffer{
	public MaterialsBuffer() {
		super("Materials UBO");
	}

	public MaterialStruct[] Materials = AddStructs(new MaterialStruct[Material.values().length], MaterialStruct::new);

	public static class MaterialStruct extends StructProperty {
		public Property ColorMap = AddProperty(PropertyType.Int, "ColorMap");
		public Property NormalMap = AddProperty(PropertyType.Int, "NormalMap");
		public Property DisplacementMap = AddProperty(PropertyType.Int, "DisplacementMap");
		public Property RoughnessMap = AddProperty(PropertyType.Int, "RoughnessMap");
		public Property AmbientOcclusionMap = AddProperty(PropertyType.Int, "AmbientOcclusionMap");
		public Property FlowMap = AddProperty(PropertyType.Int, "FlowMap");
		public Property Flags = AddProperty(PropertyType.Int, "Flags");
		public Property Brightness = AddProperty(PropertyType.Float, "brightness");
		public Property DisplacementScale = AddProperty(PropertyType.Float, "DisplacementScale");
		public Property SpecularStrength = AddProperty(PropertyType.Float, "SpecularStrength");
		public Property SpecularGloss = AddProperty(PropertyType.Float, "SpecularGloss");
		public Property FlowMapStrength = AddProperty(PropertyType.Float, "FlowMapStrength");
		public Property FlowMapDuration = AddProperty(PropertyType.FVec2, "FlowMapDuration");
		public Property ScrollDuration = AddProperty(PropertyType.FVec2, "ScrollDuration");
		public Property TextureScale = AddProperty(PropertyType.FVec3, "TextureScale");
	}
}
