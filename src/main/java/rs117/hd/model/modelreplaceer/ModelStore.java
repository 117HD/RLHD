package rs117.hd.model.modelreplaceer;

import java.util.function.Function;
import java.util.function.Supplier;
import net.runelite.api.*;

import java.util.EnumMap;
import java.util.Map;
import rs117.hd.model.modelreplaceer.types.objects.ModelDefinition;
import rs117.hd.model.modelreplaceer.types.objects.impl.Torch;
import rs117.hd.model.modelreplaceer.types.objects.impl.XmasBush;
import rs117.hd.model.modelreplaceer.types.objects.impl.XmasHolly;
import rs117.hd.model.modelreplaceer.types.objects.impl.XmasSnowTree;
import rs117.hd.model.modelreplaceer.types.objects.impl.XmasTree;

public enum ModelStore {
	HOLLY(new XmasHolly()),
	XMAS_TREE(new XmasTree()),
	TORCH(new Torch()),
	XMAS_BUSH(new XmasBush()),
	XMAS_SNOW_TREE(new XmasSnowTree());


	public final ModelDefinition definition;

	ModelStore(ModelDefinition def) {
		this.definition = def;
	}

}