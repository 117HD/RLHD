package rs117.hd.opengl;

import java.util.ArrayDeque;
import lombok.RequiredArgsConstructor;
import rs117.hd.utils.opengl.texture.GLFrameBuffer;
import rs117.hd.utils.opengl.texture.GLTexture;

public class GLRenderState {
	public static final BindStack<GLFrameBuffer> frameBuffer = new BindStack<>(GLFrameBuffer::bind);
	public static final BindStack<GLTexture> texture = new BindStack<>(GLTexture::bind);

	@FunctionalInterface
	public interface BinderInterface<T> {
		void bind(T val);
	}

	@RequiredArgsConstructor
	public static class BindStack<T> {
		private T active;
		private final ArrayDeque<T> previouslyActive = new ArrayDeque<>();
		private final BinderInterface<T> binderFunction;

		public boolean isActive(T val) { return active == val;}

		public void push(T val){
			if(val == null) {
				active = null;
				return;
			}

			if(active != null) {
				previouslyActive.push(active);
			}

			previouslyActive.remove(val);
			active = val;
		}

		public void pop() {
			if(previouslyActive.isEmpty()) {
				active = null;
				return;
			}

			T newActive = previouslyActive.pop();
			binderFunction.bind(newActive);
			active = newActive;
		}

		public void clear() {
			previouslyActive.clear();
		}
	}
}
