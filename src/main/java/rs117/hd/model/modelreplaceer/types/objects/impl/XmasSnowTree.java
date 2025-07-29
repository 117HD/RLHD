package rs117.hd.model.modelreplaceer.types.objects.impl;

import net.runelite.api.*;
import rs117.hd.model.modelreplaceer.types.ConditionalModel;
import rs117.hd.model.modelreplaceer.types.objects.ModelDefinition;

public class XmasSnowTree extends ModelDefinition {

	public XmasSnowTree() {
		modelIds = new int[]{1637, 55839};
		conditionalModels.add(new ConditionalModel(43082, 70));

		contrast = 10;
		ambient = 20;
		recolorFrom = new int[]{6257, 6261, 6241, 127, 3470};
		recolorTo = new int[]{37999, 38003, 37987, 38003, 5665};
		retextureFrom = new short[]{8, 60};
		retextureTo = new short[]{127, 128};
	}

}