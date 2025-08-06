package rs117.hd.opengl.uniforms;

import rs117.hd.HdPlugin;
import rs117.hd.data.materials.Material;
import rs117.hd.scene.TextureManager;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.utils.ColorUtils.linearToSrgb;

public class UBOWaterTypes extends UniformBuffer<GLBuffer> {
	private static class WaterTypeStruct extends StructProperty {
		private final Property isFlat = addProperty(PropertyType.Int, "isFlat");
		private final Property specularStrength = addProperty(PropertyType.Float, "specularStrength");
		private final Property specularGloss = addProperty(PropertyType.Float, "specularGloss");
		private final Property normalStrength = addProperty(PropertyType.Float, "normalStrength");
		private final Property baseOpacity = addProperty(PropertyType.Float, "baseOpacity");
		private final Property hasFoam = addProperty(PropertyType.Int, "hasFoam");
		private final Property duration = addProperty(PropertyType.Float, "duration");
		private final Property fresnelAmount = addProperty(PropertyType.Float, "fresnelAmount");
		private final Property surfaceColor = addProperty(PropertyType.FVec3, "surfaceColor");
		private final Property foamColor = addProperty(PropertyType.FVec3, "foamColor");
		private final Property depthColor = addProperty(PropertyType.FVec3, "depthColor");
		private final Property normalMap = addProperty(PropertyType.Int, "normalMap");
		private final Property foamMap = addProperty(PropertyType.Int, "foamMap");
		private final Property flowMap = addProperty(PropertyType.Int, "flowMap");
	}

	private final WaterTypeStruct[] uboStructs;
	private final WaterType[] waterTypes;

	public UBOWaterTypes(WaterType[] waterTypes, TextureManager textureManager) {
		super(GL_STATIC_DRAW);
		this.waterTypes = waterTypes;
		uboStructs = addStructs(new WaterTypeStruct[waterTypes.length], WaterTypeStruct::new);
		update(textureManager);
	}

	public int getCount() {
		return uboStructs.length;
	}

	public void update(TextureManager textureManager) {
		if (textureManager.uboMaterials == null)
			return;

		initialize(HdPlugin.UNIFORM_BLOCK_WATER_TYPES);
		for (int i = 0; i < waterTypes.length; i++) {
			var type = waterTypes[i];
			var struct = uboStructs[i];
			struct.isFlat.set(type.flat ? 1 : 0);
			struct.specularStrength.set(type.specularStrength);
			struct.specularGloss.set(type.specularGloss);
			struct.normalStrength.set(type.normalStrength);
			struct.baseOpacity.set(type.baseOpacity);
			struct.hasFoam.set(type.hasFoam ? 1 : 0);
			struct.duration.set(type.duration);
			struct.fresnelAmount.set(type.fresnelAmount);
			struct.surfaceColor.set(linearToSrgb(type.surfaceColor));
			struct.foamColor.set(linearToSrgb(type.foamColor));
			struct.depthColor.set(linearToSrgb(type.depthColor));
			struct.normalMap.set(textureManager.getTextureLayer(type.normalMap));
			struct.foamMap.set(textureManager.getTextureLayer(Material.WATER_FOAM));
			struct.flowMap.set(textureManager.getTextureLayer(Material.WATER_FLOW_MAP));
		}
		upload();
	}
}
