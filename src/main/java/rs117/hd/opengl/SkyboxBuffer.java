package rs117.hd.opengl;

import rs117.hd.scene.skybox.SkyboxConfig;

public class SkyboxBuffer extends UniformBuffer {
	public SkyboxBuffer() {
		super("Skybox");
	}

	public SkyboxConfigStruct activeSkybox = addStruct(new SkyboxConfigStruct());
	public SkyboxConfigStruct nextSkybox = addStruct(new SkyboxConfigStruct());
	public Property skyboxBlend = addProperty(PropertyType.Float, "skyboxBlend");
	public Property skyboxOffset = addProperty(PropertyType.Float, "skyboxOffset");
	public Property skyboxViewProj = addProperty(PropertyType.Mat4, "skyboxViewProj");

	public static class SkyboxConfigStruct extends StructProperty {
		public Property index = addProperty(PropertyType.Int, "index");
		public Property applyPostPro = addProperty(PropertyType.Int, "applyPostPro");
		public Property brightness = addProperty(PropertyType.Float, "brightness");
		public Property contrast = addProperty(PropertyType.Float, "contrast");
		public Property saturation = addProperty(PropertyType.Float, "saturation");
		public Property hueShift = addProperty(PropertyType.Float, "hueShift");

		public void copy(int skyboxIndex, SkyboxConfig.SkyboxPostProcessingConfig skyboxConfig) {
			index.set(skyboxIndex);
			applyPostPro.set(skyboxConfig != null ? 1 : 0);
			brightness.set(skyboxConfig != null ? skyboxConfig.getLightness() : 0.0f);
			contrast.set(skyboxConfig != null ? skyboxConfig.getContrast() : 0.0f);
			saturation.set(skyboxConfig != null ? skyboxConfig.getSaturation() : 0.0f);
			hueShift.set(skyboxConfig != null ? skyboxConfig.getHue() : 0.0f);
		}
	}
}
