package rs117.hd.model.modelreplaceer;

import rs117.hd.model.modelreplaceer.types.objects.ModelDefinition;
import rs117.hd.model.modelreplaceer.types.objects.impl.Torch;
import rs117.hd.model.modelreplaceer.types.objects.impl.XmasBush;
import rs117.hd.model.modelreplaceer.types.objects.impl.XmasHolly;
import rs117.hd.model.modelreplaceer.types.objects.impl.XmasSnowOak;
import rs117.hd.model.modelreplaceer.types.objects.impl.XmasSnowTree;
import rs117.hd.model.modelreplaceer.types.objects.impl.XmasStatue;
import rs117.hd.model.modelreplaceer.types.objects.impl.XmasTree;

public enum ModelStore {
	HOLLY(new XmasHolly()),
	XMAS_TREE(new XmasTree()),
	TORCH(new Torch()),
	XMAS_BUSH(new XmasBush()),
	XMAS_SNOW_TREE(new XmasSnowTree()),
	XMAS_SNOW_OAK(new XmasSnowOak()),
	XMAS_LUMBRIDGE_STATUE(new XmasStatue(5575, 1452, 30, false, -10, -100, -6, false)),
	XMAS_GARDEN_QUEEN_STATUE(new XmasStatue(9134, 9135, 0, true, -2, -110, 4, true));


	public final ModelDefinition definition;

	ModelStore(ModelDefinition def) {
		this.definition = def;
	}

}