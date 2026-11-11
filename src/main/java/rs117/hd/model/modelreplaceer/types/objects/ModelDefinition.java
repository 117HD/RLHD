package rs117.hd.model.modelreplaceer.types.objects;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;
import java.lang.reflect.Type;
import java.util.*;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Perspective;
import rs117.hd.model.modelreplaceer.ModelStore;
import rs117.hd.model.modelreplaceer.types.ConditionalModel;
import rs117.hd.utils.ColorUtils;

import static rs117.hd.utils.ExpressionParser.asExpression;
import static rs117.hd.utils.ExpressionParser.parseExpression;


public class ModelDefinition {
	private static final int[] ZERO_OFFSETS = new int[4];

	protected final List<ConditionalModel> conditionalModels = new ArrayList<>();
	public int[] modelIds = new int[0];

	public int animationId = -1;
	public int contrast = 0;
	public int ambient = 0;

	@JsonAdapter(OffsetAdapter.class)
	private int[] offsetX = ZERO_OFFSETS;
	@JsonAdapter(OffsetAdapter.class)
	private int[] offsetY = ZERO_OFFSETS;
	@JsonAdapter(OffsetAdapter.class)
	private int[] offsetZ = ZERO_OFFSETS;
	private int[] offsetXByOrientation = ZERO_OFFSETS;

	public int modelSizeX = 128;
	public int modelSizeY = 128;
	public int modelHeight = 128;

	public boolean rotated = false;

	@JsonAdapter(ColorArrayAdapter.class)
	public int[] recolorFrom = new int[0];
	@JsonAdapter(ColorArrayAdapter.class)
	public int[] recolorTo = new int[0];
	public int[] retextureFrom = new int[0];
	public int[] retextureTo = new int[0];

	private static final int[] ROTATE_TYPE_INDICES = {6, 7, 8, 9, 13, 14, 19};

	private static final int DATA_CACHE_SIZE = 240;
	private static final Map<Integer, ModelData> modelDataCache = new LinkedHashMap<>(DATA_CACHE_SIZE, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, ModelData> eldest) {
			return size() > DATA_CACHE_SIZE;
		}
	};

	private final transient Map<Long, Model> rotatedModelCache = new LinkedHashMap<>(DATA_CACHE_SIZE, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Model> eldest) {
			return size() > DATA_CACHE_SIZE;
		}
	};

	public static void release() {
		modelDataCache.clear();
		ModelStore.clearCachedModels();
	}

	public final void clearCachedModels() {
		rotatedModelCache.clear();
	}

	public void validate(int textureCount) {
		if (modelIds == null || modelIds.length == 0 || Arrays.stream(modelIds).anyMatch(id -> id < 0))
			throw new IllegalArgumentException("modelIds must contain at least one nonnegative model ID");
		if (conditionalModels == null || conditionalModels.stream().anyMatch(part ->
			part == null || part.modelId < 0 || part.getInclusionChancePercent() < 0 || part.getInclusionChancePercent() > 100))
			throw new IllegalArgumentException("Each conditional model needs a nonnegative modelId and a chance from 0 to 100");

		requirePairs("recolor", recolorFrom, recolorTo);
		requirePairs("retexture", retextureFrom, retextureTo);
		int maxTextureId = Math.min(textureCount - 1, Short.MAX_VALUE);
		validateTextureIds("retextureFrom", retextureFrom, maxTextureId);
		validateTextureIds("retextureTo", retextureTo, maxTextureId);

		if (offsetX == null || offsetY == null || offsetZ == null)
			throw new IllegalArgumentException("Offsets must be integers or expressions");
		if (offsetXByOrientation == null || offsetXByOrientation.length != 4)
			throw new IllegalArgumentException("offsetXByOrientation must contain four offsets");
		for (int ori = 0; ori < 4; ori++)
			Math.addExact(offsetX[ori], offsetXByOrientation[ori]);
	}

	private static void requirePairs(String field, int[] from, int[] to) {
		if (from == null || to == null || from.length != to.length)
			throw new IllegalArgumentException(field + "From and " + field + "To must have the same length");
	}

	private static void validateTextureIds(String field, int[] ids, int maxTextureId) {
		for (int i = 0; i < ids.length; i++) {
			if (ids[i] < 0 || ids[i] > maxTextureId)
				throw new IllegalArgumentException(field + "[" + i + "] must be in 0.." + maxTextureId);
		}
	}

	private static class ColorArrayAdapter implements JsonDeserializer<int[]> {
		@Override
		public int[] deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
			if (!json.isJsonArray())
				throw new JsonParseException("Expected an array of colors");
			var array = json.getAsJsonArray();
			var colors = new int[array.size()];
			for (int i = 0; i < colors.length; i++) {
				try {
					colors[i] = readColor(array.get(i));
				} catch (RuntimeException ex) {
					throw new JsonParseException("Invalid color at index " + i + ": " + array.get(i), ex);
				}
			}
			return colors;
		}

		private static int readColor(JsonElement color) {
			var value = color.getAsJsonPrimitive();
			if (value.isString()) {
				String hex = value.getAsString();
				if (!hex.matches("(?:#|0[xX])[0-9a-fA-F]{6}"))
					throw new IllegalArgumentException("Expected #RRGGBB or 0xRRGGBB");
				return ColorUtils.hsl("#" + hex.substring(hex.length() - 6));
			}
			if (!value.isNumber())
				throw new IllegalArgumentException("Expected a packed HSL integer");
			int packed = value.getAsBigDecimal().intValueExact();
			if (packed < 0 || packed > 0xFFFF)
				throw new IllegalArgumentException("Expected a packed HSL integer in 0..65535");
			return packed;
		}
	}

	private static class OffsetAdapter implements JsonDeserializer<int[]> {
		@Override
		public int[] deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
			var value = json.getAsJsonPrimitive();
			var offsets = new int[4];
			if (value.isNumber()) {
				Arrays.fill(offsets, value.getAsBigDecimal().intValueExact());
				return offsets;
			}
			if (!value.isString())
				throw new JsonParseException("Expected an integer or offset expression");

			var expression = asExpression(parseExpression(value.getAsString()));
			if (expression.variables.stream().anyMatch(name -> !name.equals("ori")))
				throw new JsonParseException("Offset expressions only support the variable ori");
			var evaluate = expression.toFunction();
			for (int ori = 0; ori < offsets.length; ori++) {
				int orientation = ori;
				Object result = evaluate.apply(name -> orientation);
				if (!(result instanceof Number))
					throw new JsonParseException("Offset expression must produce an integer for ori " + ori);
				double offset = ((Number) result).doubleValue();
				if (offset != Math.rint(offset) || offset < Integer.MIN_VALUE || offset > Integer.MAX_VALUE)
					throw new JsonParseException("Offset expression must produce an integer for ori " + ori);
				offsets[ori] = (int) offset;
			}
			return offsets;
		}
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
						| (orientation & 0xF);

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
				model = model.shallowCopy().cloneVertices().rotateY90Ccw();
			}
			datas[i] = prepareModelPart(modelId, model);
		}

		ModelData result = prepareMergedModel(client.mergeModels(datas));
		if (datas.length == 1)
			result = result.shallowCopy().cloneVertices();

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

		result = postProcessModel(result,type,orientation);

		if (recolorFrom.length != 0) {
			result = result.cloneColors();
			for (int i = 0; i < recolorFrom.length; i++)
				result.recolor((short) recolorFrom[i], (short) recolorTo[i]);
		}

		if (retextureFrom.length != 0) {
			result = result.cloneTextures();
			for (int i = 0; i < retextureFrom.length; i++)
				result.retexture((short) retextureFrom[i], (short) retextureTo[i]);
		}

		if (modelSizeX != 128 || modelHeight != 128 || modelSizeY != 128) {
			result.scale(modelSizeX, modelHeight, modelSizeY);
		}

		int ori = orientation & 3;
		int x = offsetX[ori] + offsetXByOrientation[ori];
		int y = offsetY[ori];
		int z = offsetZ[ori];
		if (x != 0 || y != 0 || z != 0)
			result.translate(x, z, y);

		return result;
	}

	// These hooks run in model space, before applying the object's orientation.
	protected ModelData prepareModelPart(int modelId, ModelData modelData) {
		return modelData;
	}

	protected ModelData prepareMergedModel(ModelData modelData) {
		return modelData;
	}

	/**
	 * Hook method to allow subclasses to apply additional transformations or modifications
	 * to the {@link ModelData} after initial orientation-based rotation but before recoloring,
	 * retexturing, scaling, and translation.
	 *
	 * <p>This method is invoked immediately after applying rotation based on the object's
	 * orientation. It can be overridden to apply extra effects such as model part replacements,
	 * offsets, or other geometry changes.</p>
	 *
	 * @param modelData the current {@link ModelData} after rotation
	 * @param type an identifier representing the object or model type
	 * @param ori the object's orientation (0–3), used earlier to determine rotation
	 * @return the potentially modified {@link ModelData}; the default implementation returns it unchanged
	 */
	protected ModelData postProcessModel(ModelData modelData, int type, int ori) {
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
