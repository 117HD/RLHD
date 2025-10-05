package rs117.hd.utils.opengl.texture;

import lombok.RequiredArgsConstructor;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NEAREST_MIPMAP_NEAREST;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

@RequiredArgsConstructor
public enum GLSamplerMode {
	NEAREST_REPEAT(GL_NEAREST, GL_NEAREST_MIPMAP_NEAREST, GL_NEAREST, GL_REPEAT, GL_REPEAT),
	NEAREST_CLAMP(GL_NEAREST, GL_NEAREST_MIPMAP_NEAREST, GL_NEAREST, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE),

	LINEAR_REPEAT(GL_LINEAR, GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR, GL_REPEAT, GL_REPEAT),
	LINEAR_CLAMP(GL_LINEAR, GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE);

	public final int minFilter;
	public final int minFilterMip;
	public final int magFilter;
	public final int wrapS;
	public final int wrapT;
}
