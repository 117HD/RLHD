package rs117.hd.utils.opengl.texture;

import lombok.RequiredArgsConstructor;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;

@RequiredArgsConstructor
public enum GLTextureType {
	TEXTURE2D(GL_TEXTURE_2D),
	TEXTURE2D_ARRAY(GL_TEXTURE_2D_ARRAY);

	public final int glTarget;
}
