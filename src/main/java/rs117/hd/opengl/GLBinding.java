package rs117.hd.opengl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static org.lwjgl.opengl.GL33C.*;

@RequiredArgsConstructor
@Slf4j
public enum GLBinding {
	BINDING_NONE(GLBindingType.NONE),

	// Texture bindings
	BINDING_TEX_UI(GLBindingType.TEXTURE),
	BINDING_TEX_GAME(GLBindingType.TEXTURE),
	BINDING_TEX_SHADOW_MAP(GLBindingType.TEXTURE),
	BINDING_TEX_TILE_HEIGHT_MAP(GLBindingType.TEXTURE),

	// Image bindings
	BINDING_IMG_TILE_LIGHTING_MAP(GLBindingType.IMAGE),

	// Uniform Buffer bindings
	BINDING_UBO_GLOBAL(GLBindingType.BUFFER),
	BINDING_UBO_MATERIALS(GLBindingType.BUFFER),
	BINDING_UBO_WATER_TYPES(GLBindingType.BUFFER),
	BINDING_UBO_LIGHTS(GLBindingType.BUFFER),
	BINDING_UBO_LIGHTS_CULLING(GLBindingType.BUFFER),
	BINDING_UBO_UI(GLBindingType.BUFFER),
	BINDING_UBO_DISPLACEMENT(GLBindingType.BUFFER),
	BINDING_UBO_COMPUTE(GLBindingType.BUFFER),
	BINDING_UBO_WORLD_VIEW(GLBindingType.BUFFER),
	BINDING_UBO_ZONES(GLBindingType.BUFFER),

	// Storage Buffer bindings
	BINDING_SSAO_MODEL_DATA(GLBindingType.STORAGE_BUFFER);

	private static final int MAX_TEXTURE_UNITS;
	private static final int MAX_IMAGE_UNITS;
	private static int textureUnitCounter = 0;
	private static int imageUnitCounter = 0;
	private static int bufferBindingCounter = 0;

	private final GLBindingType bindingType;
	private int textureUnit = -1;
	private int imageUnit;
	private int bufferBindingIndex = -1;
	private boolean initialized = false;

	static {
		MAX_TEXTURE_UNITS = glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
		if (MAX_TEXTURE_UNITS < textureUnitCounter)
			log.warn("The GPU only supports {} texture units", MAX_TEXTURE_UNITS);

		MAX_IMAGE_UNITS = glGetInteger(GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS);
		if (MAX_IMAGE_UNITS < imageUnitCounter)
			log.warn("The GPU only supports {} image units", MAX_IMAGE_UNITS);
	}

	private void init() {
		if (bindingType.isTextureUnit)
			textureUnit = GL_TEXTURE0 + textureUnitCounter++;

		if (bindingType.isImageUnit)
			imageUnit = imageUnitCounter++;

		if (bindingType.isBufferBinding)
			bufferBindingIndex = bufferBindingCounter++;

		initialized = true;
	}

	public static void reset() {
		for (GLBinding binding : values()) {
			binding.textureUnit = 0;
			binding.imageUnit = 0;
			binding.bufferBindingIndex = 0;
			binding.initialized = false;
		}
		textureUnitCounter = 0;
		imageUnitCounter = 0;
		bufferBindingCounter = 0;
	}

	public int getTextureUnit() {
		assert bindingType.isTextureUnit : this + " is not a  texture binding.";
		if (!initialized) init();
		return textureUnit;
	}

	public void setActive() {
		assert bindingType.isTextureUnit : this + " is not a texture or texture buffer binding.";
		if (!initialized) init();
		glActiveTexture(textureUnit);
	}

	public int getImageUnit() {
		assert bindingType.isImageUnit : this + " is not a image binding.";
		if (!initialized) init();
		return imageUnit;
	}

	public int getBufferBindingIndex() {
		assert bindingType.isBufferBinding : this + " is not a buffer binding.";
		if (!initialized) init();
		return bufferBindingIndex;
	}

	@Override
	public String toString() {
		return name() + " [type=" + bindingType + ", texUnit=" + textureUnit + ", bufIndex=" + bufferBindingIndex + "]";
	}
}
