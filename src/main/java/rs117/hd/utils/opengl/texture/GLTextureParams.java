package rs117.hd.utils.opengl.texture;

public class GLTextureParams {
	public GLSamplerMode sampler = GLSamplerMode.LINEAR_REPEAT;
	public GLTextureType type = GLTextureType.TEXTURE2D;
	public int textureUnit = -1;
	public int imageUnit = -1;
	public int imageUnitWriteMode;
	public float[] borderColor = null;
	public boolean generateMipmaps = false;
	public String debugName = "";

	public static GLTextureParams DEFAULT() { return new GLTextureParams(); }

	public GLTextureParams setType(GLTextureType type) {
		this.type = type;
		return this;
	}

	public GLTextureParams setSampler(GLSamplerMode sampler) {
		this.sampler = sampler;
		return this;
	}

	public GLTextureParams setTextureUnit(int textureUnit) {
		this.textureUnit = textureUnit;
		return this;
	}

	public GLTextureParams setImageUnit(int imageUnit, int writeMode) {
		this.imageUnit = imageUnit;
		this.imageUnitWriteMode = writeMode;
		return this;
	}

	public GLTextureParams setBorderColor(float[] borderColor) {
		this.borderColor = borderColor.clone();
		return this;
	}

	public GLTextureParams enableMipmaps(boolean enable) {
		this.generateMipmaps = enable;
		return this;
	}

	public GLTextureParams setDebugName(String debugName) {
		this.debugName = debugName;
		return this;
	}
}
