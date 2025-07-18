package rs117.hd.opengl.uniforms;

import static org.lwjgl.opengl.GL33C.*;

public class MaterialUniforms extends UniformBuffer {
	public static class MaterialStruct extends StructProperty {
		public Property colorMap = addProperty(PropertyType.Int, "colorMap");
		public Property normalMap = addProperty(PropertyType.Int, "normalMap");
		public Property displacementMap = addProperty(PropertyType.Int, "displacementMap");
		public Property roughnessMap = addProperty(PropertyType.Int, "roughnessMap");
		public Property ambientOcclusionMap = addProperty(PropertyType.Int, "ambientOcclusionMap");
		public Property flowMap = addProperty(PropertyType.Int, "flowMap");
		public Property flags = addProperty(PropertyType.Int, "flags");
		public Property brightness = addProperty(PropertyType.Float, "brightness");
		public Property displacementScale = addProperty(PropertyType.Float, "displacementScale");
		public Property specularStrength = addProperty(PropertyType.Float, "specularStrength");
		public Property specularGloss = addProperty(PropertyType.Float, "specularGloss");
		public Property flowMapStrength = addProperty(PropertyType.Float, "flowMapStrength");
		public Property flowMapDuration = addProperty(PropertyType.FVec2, "flowMapDuration");
		public Property scrollDuration = addProperty(PropertyType.FVec2, "scrollDuration");
		public Property textureScale = addProperty(PropertyType.FVec3, "textureScale");
	}

	public MaterialStruct[] materials;

	public MaterialUniforms(int materialCount) {
		super("Materials", "MaterialUniforms", GL_STATIC_DRAW);
		materials = addStructs(new MaterialStruct[materialCount], MaterialStruct::new);
	}
}
