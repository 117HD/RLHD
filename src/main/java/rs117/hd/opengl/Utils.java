package rs117.hd.opengl;

import com.google.inject.Provider;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

import static org.lwjgl.opengl.GL11C.GL_INVALID_ENUM;
import static org.lwjgl.opengl.GL11C.GL_INVALID_OPERATION;
import static org.lwjgl.opengl.GL11C.GL_INVALID_VALUE;
import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11C.GL_STACK_OVERFLOW;
import static org.lwjgl.opengl.GL11C.GL_STACK_UNDERFLOW;
import static org.lwjgl.opengl.GL11C.glGetError;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30C.GL_INVALID_FRAMEBUFFER_OPERATION;
import static org.lwjgl.opengl.GL30C.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL43C.glObjectLabel;
import static rs117.hd.HdPlugin.GL_CAPS;

@Slf4j
public final class Utils {
	public static boolean SKIP_GL_ERROR_CHECKS;

	public static void labelObject(int type, int object, String label) {
		if (GL_CAPS.OpenGL43)
			glObjectLabel(type, object, label);
	}

	public static void checkFramebufferComplete(int framebuffer) {
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
			throw new IllegalStateException("Framebuffer " + framebuffer + " is incomplete: 0x" + Integer.toHexString(status));
	}

	@SuppressWarnings("StatementWithEmptyBody")
	public static void clearGLErrors() {
		// @formatter:off
		while (glGetError() != GL_NO_ERROR);
		// @formatter:on
	}

	public static boolean checkGLErrors() {
		return checkGLErrors(null);
	}

	public static boolean checkGLErrors(@Nullable Provider<String> contextProvider) {
		if (SKIP_GL_ERROR_CHECKS)
			return false;

		boolean hasGLError = false;
		String context = null;
		while (true) {
			int err = glGetError();
			if (err == GL_NO_ERROR)
				return hasGLError;

			String errStr;
			switch (err) {
				case GL_INVALID_ENUM:
					errStr = "INVALID_ENUM";
					break;
				case GL_INVALID_VALUE:
					errStr = "INVALID_VALUE";
					break;
				case GL_STACK_OVERFLOW:
					errStr = "STACK_OVERFLOW";
					break;
				case GL_STACK_UNDERFLOW:
					errStr = "STACK_UNDERFLOW";
					break;
				case GL_INVALID_OPERATION:
					errStr = "INVALID_OPERATION";
					break;
				case GL_INVALID_FRAMEBUFFER_OPERATION:
					errStr = "INVALID_FRAMEBUFFER_OPERATION";
					break;
				default:
					errStr = String.format("Error code: %d", err);
					break;
			}
			if (contextProvider != null && context == null)
				context = contextProvider.get();
			if (context != null) {
				log.debug("glGetError({}):", context, new Exception(errStr));
			} else {
				log.debug("GL error:", new Exception(errStr));
			}
			hasGLError = true;
		}
	}
}
