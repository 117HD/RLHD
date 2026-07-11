package rs117.hd.renderer.zone;

public class FrameContext {
	public static final int VAO_OPAQUE = 0;
	public static final int VAO_ALPHA = 1;
	public static final int VAO_PLAYER = 2;
	public static final int VAO_SHADOW = 3;
	public static final int VAO_PRESCENE = 4;
	public static final int VAO_COUNT = 5;

	final DynamicModelVAO[] dynamicModelVaos = new DynamicModelVAO[VAO_COUNT];

	void initBuffers() {
		for (int i = 0; i < VAO_COUNT; i++) {
			dynamicModelVaos[i] = new DynamicModelVAO("DynamicModelVAO_" + i, true);
			if (dynamicModelVaos[i].getVao() == 0)
				dynamicModelVaos[i].initialize();
		}
	}

	void destroy() {
		for (int i = 0; i < VAO_COUNT; i++) {
			if(dynamicModelVaos[i] != null)
				dynamicModelVaos[i].destroy();
			dynamicModelVaos[i] = null;
		}
	}

	void map() {
		for (int i = 0; i < VAO_COUNT; i++)
			dynamicModelVaos[i].map();
	}

	int obtainDrawIndex(int type) {
		return dynamicModelVaos[type].obtainDrawIndex();
	}

	void unmap() {
		for (int i = 0; i < VAO_COUNT; i++)
			dynamicModelVaos[i].unmap();
	}
}
