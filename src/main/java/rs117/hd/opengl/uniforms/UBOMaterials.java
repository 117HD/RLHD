package rs117.hd.opengl.uniforms;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.model.ModelPusher;
import rs117.hd.scene.MaterialManager;
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

	@RequiredArgsConstructor
	public static class MaterialEntry {
		public final Material material;
		public final int vanillaIndex;
	}

	public MaterialStruct[] uboStructs;
	public List<MaterialEntry> entries;
	public int[] materialOrdinalToMaterialUniformIndex;
	public int[] vanillaTextureIndexToMaterialUniformIndex;

	public UBOMaterials(int materialCount) {
		super(GL_STATIC_DRAW);
		assert materialCount - 1 <= ModelPusher.MAX_MATERIAL_INDEX :
			"Too many materials (" + materialCount + ") to fit into packed material data.";
		uboStructs = addStructs(new MaterialStruct[materialCount], MaterialStruct::new);
		initialize(HdPlugin.UNIFORM_BLOCK_MATERIALS);
	}

	public void update(MaterialManager materialManager, List<MaterialEntry> entries, Texture[] vanillaTextures) {
		this.entries = entries;
		vanillaTextureIndexToMaterialUniformIndex = new int[vanillaTextures.length];
		materialOrdinalToMaterialUniformIndex = new int[MaterialManager.MATERIALS.length];

		// Convert vanilla texture animations to the same format as Material scroll parameters
		float[] vanillaTextureAnimations = new float[vanillaTextures.length * 2];
		for (int i = 0; i < vanillaTextures.length; i++) {
			var texture = vanillaTextures[i];
			if (texture == null)
				continue;

			int direction = texture.getAnimationDirection();
			if (direction != 0) {
				float speed = texture.getAnimationSpeed() * 50 / 128.f;
				float radians = direction * -HALF_PI;
				vanillaTextureAnimations[i * 2] = cos(radians) * speed;
				vanillaTextureAnimations[i * 2 + 1] = sin(radians) * speed;
			}
		}

		for (int i = 0; i < entries.size(); i++) {
			MaterialEntry entry = entries.get(i);
			int vanillaIndex = entry.vanillaIndex;
			materialOrdinalToMaterialUniformIndex[entry.material.index] = i;
			if (vanillaIndex != -1)
				vanillaTextureIndexToMaterialUniformIndex[vanillaIndex] = i;
			float vanillaScrollX = 0;
			float vanillaScrollY = 0;
			if (vanillaIndex != -1) {
				vanillaScrollX = vanillaTextureAnimations[vanillaIndex * 2];
				vanillaScrollY = vanillaTextureAnimations[vanillaIndex * 2 + 1];
			}
			entry.material.fillMaterialStruct(materialManager, uboStructs[i], vanillaIndex, vanillaScrollX, vanillaScrollY);
		}
		for (var material : MaterialManager.MATERIALS)
			materialOrdinalToMaterialUniformIndex[material.index] =
				materialOrdinalToMaterialUniformIndex[material.resolveReplacements().index];

		upload();
	}
}
