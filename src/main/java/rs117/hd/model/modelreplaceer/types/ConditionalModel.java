package rs117.hd.model.modelreplaceer.types;

import java.util.Random;
import rs117.hd.utils.HDUtils;

public class ConditionalModel {

	public final int modelId;
	public final int numerator;

	public ConditionalModel(int modelId, int numerator) {
		this.modelId = modelId;
		this.numerator = numerator;
	}

	public boolean shouldInclude() {
		return HDUtils.rand.nextInt(100) < numerator;
	}
}