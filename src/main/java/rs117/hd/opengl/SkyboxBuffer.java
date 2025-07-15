package rs117.hd.opengl;

import rs117.hd.opengl.uniforms.UniformBuffer;
import rs117.hd.scene.skybox.SkyboxConfig;

import static org.lwjgl.opengl.GL31C.GL_DYNAMIC_DRAW;

public class SkyboxBuffer extends UniformBuffer {
	public SkyboxBuffer() {
		super("Skybox", GL_DYNAMIC_DRAW);
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
		public Property rotation = addProperty(PropertyType.Float, "rotation");

		public void copy(int skyboxIndex, SkyboxConfig.SkyboxEntry skyboxConfig) {
			index.set(skyboxIndex);

			SkyboxConfig.SkyboxPostProcessingConfig postConfig = (skyboxConfig != null) ? skyboxConfig.getPostProcessing() : null;

			boolean hasPost = postConfig != null;
			applyPostPro.set(hasPost ? 1 : 0);
			brightness.set(hasPost ? postConfig.getLightness() : 0f);
			contrast.set(hasPost ? postConfig.getContrast() : 0f);
			saturation.set(hasPost ? postConfig.getSaturation() : 0f);
			hueShift.set(hasPost ? postConfig.getHue() : 0f);
			rotation.set(skyboxConfig != null ? skyboxConfig.getRotation() : 0f);
		}
	}
}
