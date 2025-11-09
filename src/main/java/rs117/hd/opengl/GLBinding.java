package rs117.hd.opengl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL20C.GL_MAX_TEXTURE_IMAGE_UNITS;

@RequiredArgsConstructor
@Slf4j
public enum GLBinding {
	NONE(GLBindingType.NONE),

	// Texture bindings
	TEXTURE_UI(GLBindingType.TEXTURE),
	TEXTURE_GAME(GLBindingType.TEXTURE),
	TEXTURE_SHADOW_MAP(GLBindingType.TEXTURE),
	TEXTURE_TILE_HEIGHT_MAP(GLBindingType.TEXTURE),
	TEXTURE_TILE_LIGHTING_MAP(GLBindingType.TEXTURE),

	// Image bindings
	IMAGE_TILE_LIGHTING_MAP(GLBindingType.IMAGE),

	// Uniform Buffer bindings
	UNIFORM_GLOBAL(GLBindingType.BUFFER),
	UNIFORM_MATERIALS(GLBindingType.BUFFER),
	UNIFORM_WATER_TYPES(GLBindingType.BUFFER),
	UNIFORM_LIGHTS(GLBindingType.BUFFER),
	UNIFORM_LIGHTS_CULLING(GLBindingType.BUFFER),
	UNIFORM_UI(GLBindingType.BUFFER),
	UNIFORM_DISPLACEMENT(GLBindingType.BUFFER),
	UNIFORM_COMPUTE(GLBindingType.BUFFER),
	UNIFORM_WORLD_VIEW(GLBindingType.BUFFER),

	// Storage Buffer bindings
	STORAGE_MODEL_DATA(GLBindingType.STORAGE_BUFFER);

	private static final int MAX_TEXTURE_UNITS = glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
	private static final int MAX_IMAGE_UNITS = glGetInteger(GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS);
	private static int textureUnitCounter = 0;
	private static int imageUnitCounter = 0;
	private static int bufferBindingCounter = 0;

	private final GLBindingType bindingType;
	private int textureUnit = -1;
	private int imageUnit;
	private int bufferBindingIndex = -1;
	private boolean initialized = false;

	static {
		if (MAX_TEXTURE_UNITS < textureUnitCounter)
			log.warn("The GPU only supports {} texture units", MAX_TEXTURE_UNITS);

		if (MAX_IMAGE_UNITS < imageUnitCounter)
			log.warn("The GPU only supports {} image units", MAX_IMAGE_UNITS);
	}

	private void init() {
		if ((bindingType == GLBindingType.NONE && this == NONE) || bindingType == GLBindingType.TEXTURE || bindingType == GLBindingType.STORAGE_BUFFER)
			textureUnit = GL_TEXTURE0 + textureUnitCounter++;

		if (bindingType == GLBindingType.IMAGE)
			imageUnit = imageUnitCounter++;

		if (bindingType == GLBindingType.BUFFER || bindingType == GLBindingType.STORAGE_BUFFER)
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
	}

	public int getTextureUnit() {
		assert bindingType == GLBindingType.TEXTURE || bindingType == GLBindingType.STORAGE_BUFFER : this + " is not a  texture binding.";
		if (!initialized) init();
		return textureUnit;
	}

	public int getImageUnit() {
		assert bindingType == GLBindingType.IMAGE : this + " is not a image binding.";
		if (!initialized) init();
		return imageUnit;
	}

	public int getBufferBindingIndex() {
		assert bindingType == GLBindingType.BUFFER || bindingType == GLBindingType.STORAGE_BUFFER : this + " is not a buffer binding.";
		if (!initialized) init();
		return bufferBindingIndex;
	}

	public void setActive() {
		assert bindingType == GLBindingType.NONE || bindingType == GLBindingType.TEXTURE || bindingType == GLBindingType.STORAGE_BUFFER : this + " is not a texture or texture buffer binding.";
		if (!initialized) init();
		glActiveTexture(textureUnit);
	}

	@Override
	public String toString() {return name() + " [type=" + bindingType + ", texUnit=" + textureUnit + ", bufIndex=" + bufferBindingIndex + "]"; }
}
