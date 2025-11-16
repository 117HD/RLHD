package rs117.hd.opengl;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum GLBindingType {
	TEXTURE(true, false, false),
	IMAGE(true, true, false),
	BUFFER(false, false, true),
	STORAGE_BUFFER(true, false, true),
	NONE(true, false, false);

	public final boolean isTextureUnit;
	public final boolean isImageUnit;
	public final boolean isBufferBinding;
}
