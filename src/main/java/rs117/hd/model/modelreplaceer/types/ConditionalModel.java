package rs117.hd.model.modelreplaceer.types;

import lombok.Getter;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.MathUtils;

/**
 * Represents a model with a conditional chance to be included.
 * <p>
 * The chance is expressed as a percentage from 0 to 100.
 * When checked, it randomly decides if the model should be included
 * based on the given chance.
 */
public class ConditionalModel {

	/**
	 * The ID of the model.
	 */
	public final int modelId;

	/**
	 * The inclusion chance as an integer percentage (0-100).
	 * For example, 25 means a 25% chance to include.
	 * -- GETTER --
	 *  Gets the inclusion chance percentage for this model.
	 *
	 * @return the inclusion chance as an integer between 0 and 100.

	 */
	@Getter
	private final int inclusionChancePercent;

	/**
	 * Constructs a ConditionalModel with a given model ID and inclusion chance.
	 *
	 * @param modelId The ID of the model.
	 * @param inclusionChancePercent The chance (0-100) that this model should be included.
	 *                              Values outside this range will be clamped.
	 */
	public ConditionalModel(int modelId, int inclusionChancePercent) {
		this.modelId = modelId;
		if (inclusionChancePercent < 0) {
			inclusionChancePercent = 0;
		} else if (inclusionChancePercent > 100) {
			inclusionChancePercent = 100;
		}
		this.inclusionChancePercent = inclusionChancePercent;
	}

	/**
	 * Determines whether this model should be included based on a random chance.
	 *
	 * @return true if the model is included, false otherwise.
	 */
	public boolean shouldInclude() {
		return MathUtils.RAND.nextInt(100) < inclusionChancePercent;
	}

}