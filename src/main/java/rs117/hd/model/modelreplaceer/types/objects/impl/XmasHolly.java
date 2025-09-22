package rs117.hd.model.modelreplaceer.types.objects.impl;

import net.runelite.api.*;
import rs117.hd.model.modelreplaceer.types.objects.ModelDefinition;

public class XmasHolly extends ModelDefinition {
	public XmasHolly() {
		modelIds = new int[]{43051};
		contrast = 10;
		ambient = 20;
		offsetZ = -40;
	}

	@Override
	protected ModelData postProcessModel(ModelData modelData, int type, int ori) {
		offsetX = (ori == 0) ? -15 : 15;
		return modelData;
	}
}