package rs117.hd.model.modelreplaceer.types.objects;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Perspective;
import rs117.hd.model.modelreplaceer.types.ConditionalModel;

import java.util.*;

@Slf4j
public abstract class ModelDefinition {

	protected final List<ConditionalModel> conditionalModels = new ArrayList<>();
	public int[] modelIds = new int[0];
	public int[] models = null;

	public int animationId = -1;
	public int contrast = 0;
	public int ambient = 0;

	public int offsetX = 0;
	public int offsetY = 0;
	public int offsetZ = 0;

	public int modelSizeX = 128;
	public int modelSizeY = 128;
	public int modelHeight = 128;

	public boolean rotated = false;

	public int[] recolorFrom;
	public int[] recolorTo;
	public short[] retextureFrom;
	public short[] retextureTo;

	private static final int[] ROTATE_TYPE_INDICES = {6, 7, 8, 9, 13, 14, 19};

	// Cache for model data and rotated models with LRU eviction
	private static final int DATA_CACHE_SIZE = 240;
	private static final Map<Integer, ModelData> modelDataCache = new LinkedHashMap<>(DATA_CACHE_SIZE, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, ModelData> eldest) {
			return size() > DATA_CACHE_SIZE;
		}
	};

	private static final Map<Long, Model> rotatedModelCache = new LinkedHashMap<>(DATA_CACHE_SIZE, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Model> eldest) {
			return size() > DATA_CACHE_SIZE;
		}
	};

	public static void release() {
		modelDataCache.clear();
		rotatedModelCache.clear();
	}

	public static boolean shouldRotateType(int config) {
		int type = config & 0x3F;
		for (int rotateType : ROTATE_TYPE_INDICES) {
			if (type == rotateType) {
				return true;
			}
		}
		return false;
	}

	public final Model getModel(Client client, int id, int type, int orientation) {
		boolean rotate45 = shouldRotateType(type);

		long cacheKey = ((long) id << 12)
						| ((type & 0xFF) << 4)
						| (orientation & 0xF)
						| (rotate45 ? 1L << 16 : 0);

		Model cached = rotatedModelCache.get(cacheKey);
		if (cached != null) {
			return cached;
		}

		ModelData data = loadAndMergeWithRotation(client, type, orientation, rotate45);
		if (data == null) {
			return null;
		}

		Model model = data.shallowCopy().light(ambient + 64, contrast + 768, -50, -10, -50);

		rotatedModelCache.put(cacheKey, model);
		return model;
	}

	protected ModelData loadAndMergeWithRotation(Client client, int type, int orientation, boolean rotate45) {
		if (modelIds.length == 0) {
			return null;
		}

		List<Integer> modelsToLoad = new ArrayList<>(modelIds.length + conditionalModels.size());
		for (int id : modelIds) {
			modelsToLoad.add(id);
		}

		for (ConditionalModel cm : conditionalModels) {
			if (cm.shouldInclude()) {
				modelsToLoad.add(cm.modelId);
			}
		}

		ModelData[] datas = new ModelData[modelsToLoad.size()];
		boolean rotate = this.rotated;

		if (type == 2 && orientation > 3) {
			rotate = !rotate;
		}

		for (int i = 0; i < datas.length; i++) {
			int modelId = modelsToLoad.get(i);
			ModelData model = modelDataCache.get(modelId);
			if (model == null) {
				model = client.loadModelData(modelId);
				if (model != null) {
					modelDataCache.put(modelId, model);
				}
			}
			if (model == null) {
				return null;
			}
			if (rotate) {
				model.rotateY90Ccw();
			}
			datas[i] = model;
		}

		ModelData result = client.mergeModels(datas);

		if (rotate45) {
			result = rotate(result, 256).translate(45, 0, -45);
		}

		switch (orientation & 3) {
			case 1:
				result.rotateY90Ccw();
				break;
			case 2:
				result.rotateY180Ccw();
				break;
			case 3:
				result.rotateY270Ccw();
				break;
		}

		if (recolorFrom != null && recolorTo != null) {
			if (recolorFrom.length != recolorTo.length) {
				log.error("{}: Mismatched recolor arrays: from={}, to={}", getClass().getSimpleName(), recolorFrom.length, recolorTo.length);
			} else {
				for (int i = 0; i < recolorFrom.length; i++) {
					result.recolor((short) recolorFrom[i], (short) recolorTo[i]);
				}
			}
		}

		if (retextureFrom != null && retextureTo != null) {
			if (retextureFrom.length != retextureTo.length) {
				log.error("{}: Mismatched retexture arrays: from={}, to={}", getClass().getSimpleName(), retextureFrom.length, retextureTo.length);
			} else {
				for (int i = 0; i < retextureFrom.length; i++) {
					result.retexture(retextureFrom[i], retextureTo[i]);
				}
			}
		}

		if (modelSizeX != 128 || modelHeight != 128 || modelSizeY != 128) {
			result.scale(modelSizeX, modelHeight, modelSizeY);
		}

		if (offsetX != 0 || offsetY != 0 || offsetZ != 0) {
			result.translate(offsetX, offsetZ, offsetY);
		}

		return postProcessModel(result);
	}

	protected ModelData postProcessModel(ModelData modelData) {
		return modelData;
	}

	private ModelData rotate(ModelData model, int angle) {
		int sin = Perspective.SINE[angle];
		int cos = Perspective.COSINE[angle];

		float[] xVerts = model.getVerticesX();
		float[] zVerts = model.getVerticesZ();

		for (int i = 0; i < xVerts.length; i++) {
			float x = xVerts[i];
			float z = zVerts[i];
			xVerts[i] = (cos * x + sin * z) / 65536.0f;
			zVerts[i] = (cos * z - sin * x) / 65536.0f;
		}

		return model;
	}
}
