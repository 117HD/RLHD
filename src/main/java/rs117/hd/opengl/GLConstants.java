package rs117.hd.opengl;

import static org.lwjgl.opengl.ARBShaderImageLoadStore.GL_MAX_IMAGE_UNITS;
import static org.lwjgl.opengl.GL11C.GL_MAX_TEXTURE_SIZE;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL13C.GL_SAMPLES;
import static org.lwjgl.opengl.GL20C.GL_MAX_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL30C.GL_MAX_SAMPLES;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT;
import static org.lwjgl.opengl.GL41.GL_NUM_PROGRAM_BINARY_FORMATS;
import static rs117.hd.HdPlugin.GL_CAPS;

public class GLConstants {

	private static int FORCED_SAMPLES_VALUE = -1;
	public static int getForcedSamples() {
		if(FORCED_SAMPLES_VALUE == -1)
			FORCED_SAMPLES_VALUE = glGetInteger(GL_SAMPLES);
		return FORCED_SAMPLES_VALUE;
	}

	private static int MAX_SAMPLES_VALUE = -1;
	public static int getMaxSamples() {
		if(MAX_SAMPLES_VALUE == -1)
			MAX_SAMPLES_VALUE = glGetInteger(GL_MAX_SAMPLES);
		return MAX_SAMPLES_VALUE;
	}

	private static int MAX_IMAGE_UNITS_VALUE = -1;
	public static int getMaxImageUnits() {
		if(!GL_CAPS.GL_ARB_shader_image_load_store)
			return 0;
		if(MAX_IMAGE_UNITS_VALUE == -1)
			MAX_IMAGE_UNITS_VALUE = glGetInteger(GL_MAX_IMAGE_UNITS);
		return MAX_IMAGE_UNITS_VALUE;
	}

	private static int MAX_TEXTURE_UNITS_VALUE = -1;
	public static int getMaxTextureUnits() {
		if(MAX_TEXTURE_UNITS_VALUE == -1)
			MAX_TEXTURE_UNITS_VALUE = glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
		return MAX_TEXTURE_UNITS_VALUE;
	}

	private static int MAX_TEXTURE_SIZE_VALUE = -1;
	public static int getMaxTextureSize() {
		if(MAX_TEXTURE_SIZE_VALUE == -1)
			MAX_TEXTURE_SIZE_VALUE = glGetInteger(GL_MAX_TEXTURE_SIZE);
		return MAX_TEXTURE_SIZE_VALUE;
	}

	private static int BUFFER_OFFSET_ALIGNMENT_VALUE = -1;
	public static int getBufferOffsetAlignment() {
		if(BUFFER_OFFSET_ALIGNMENT_VALUE == -1)
			BUFFER_OFFSET_ALIGNMENT_VALUE = glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);

		return BUFFER_OFFSET_ALIGNMENT_VALUE;
	}

	private static int NUM_PROGRAM_BINARY_FORMATS_VALUE = -1;
	public static int getNumProgramBinaryFormats() {
		if(NUM_PROGRAM_BINARY_FORMATS_VALUE == -1)
			NUM_PROGRAM_BINARY_FORMATS_VALUE = glGetInteger(GL_NUM_PROGRAM_BINARY_FORMATS);
		return NUM_PROGRAM_BINARY_FORMATS_VALUE;
	}
}
