package rs117.hd.opengl.uniforms;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.model.ModelPusher;
import rs117.hd.scene.materials.Material;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class UBOMaterials extends UniformBuffer<GLBuffer> {
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

	public MaterialStruct[] uboStructs;
	public Material[] materials;

	public UBOMaterials(int materialCount) {
		super(GL_STATIC_DRAW);
		assert materialCount - 1 <= ModelPusher.MAX_MATERIAL_INDEX :
			"Too many materials (" + materialCount + ") to fit into packed material data.";
		uboStructs = addStructs(new MaterialStruct[materialCount], MaterialStruct::new);
		initialize(HdPlugin.UNIFORM_BLOCK_MATERIALS);
	}

	public void update(Material[] materials, Texture[] vanillaTextures) {
		this.materials = materials;

		for (int i = 0; i < materials.length; i++) {
			var mat = materials[i];
			mat.uboIndex = i;
			float vanillaScrollX = 0;
			float vanillaScrollY = 0;
			// Replacement materials will only apply vanilla scrolling if they also specify a vanillaTextureIndex
			if (mat.vanillaTextureIndex != -1) {
				var texture = vanillaTextures[mat.vanillaTextureIndex];
				if (texture != null) {
					int direction = texture.getAnimationDirection();
					if (direction != 0) {
						// Convert vanilla texture animations to the same format as Material scroll parameters
						float speed = texture.getAnimationSpeed() * 50 / 128.f;
						float radians = direction * -HALF_PI;
						vanillaScrollX = cos(radians) * speed;
						vanillaScrollY = sin(radians) * speed;
					}
				}
			}
			mat.fillMaterialStruct(uboStructs[i], vanillaScrollX, vanillaScrollY);
		}

		upload();
	}
}
