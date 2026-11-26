package rs117.hd.model.modelreplaceer.types.objects.impl;

import net.runelite.api.ModelData;
import rs117.hd.model.modelreplaceer.types.objects.ModelDefinition;

public class XmasStatue extends ModelDefinition {
	private static final int SANTA_HAT_MODEL = 189;
	private static final int BRIM_CENTER_X = 3;
	private static final int BRIM_CENTER_Z = -7;

	private final boolean mirrored;
	private final int hatX;
	private final int hatY;
	private final int hatZ;
	private final boolean turnHat;

	public XmasStatue(int bodyModel, int detailModel, int contrast, boolean mirrored, int hatX, int hatY, int hatZ, boolean turnHat) {
		modelIds = new int[] { bodyModel, detailModel, SANTA_HAT_MODEL };
		ambient = 20;
		this.contrast = contrast;
		this.mirrored = mirrored;
		this.hatX = hatX;
		this.hatY = hatY;
		this.hatZ = hatZ;
		this.turnHat = turnHat;
	}

	@Override
	protected ModelData prepareModelPart(int modelId, ModelData modelData) {
		if (modelId != SANTA_HAT_MODEL)
			return modelData;

		ModelData hat = modelData.shallowCopy().cloneVertices();
		if (turnHat) {
			hat.translate(-BRIM_CENTER_X, 0, -BRIM_CENTER_Z)
				.rotateY90Ccw()
				.translate(BRIM_CENTER_X, 0, BRIM_CENTER_Z);
		}
		return hat.translate(hatX, hatY, hatZ);
	}

	@Override
	protected ModelData prepareMergedModel(ModelData modelData) {
		if (!mirrored)
			return modelData;

		// The merged mesh owns its face indices. Reverse the winding after reflecting it.
		float[] verticesZ = modelData.getVerticesZ();
		for (int i = 0; i < modelData.getVerticesCount(); i++)
			verticesZ[i] = -verticesZ[i];

		int[] first = modelData.getFaceIndices1();
		int[] third = modelData.getFaceIndices3();
		for (int i = 0; i < modelData.getFaceCount(); i++) {
			int index = first[i];
			first[i] = third[i];
			third[i] = index;
		}
		return modelData;
	}
}
