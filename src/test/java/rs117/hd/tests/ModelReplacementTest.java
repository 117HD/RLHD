package rs117.hd.tests;

import com.google.gson.Gson;
import java.time.LocalDate;
import org.junit.Test;
import rs117.hd.model.modelreplaceer.ModelStore;
import rs117.hd.scene.daylight_cycle.SkyState;
import rs117.hd.scene.model_overrides.ModelReplacement;
import rs117.hd.utils.VariableSupplier;

import static org.junit.Assert.*;

public class ModelReplacementTest {
	@Test
	public void selectsOnlyFromDecemberFirstThroughJanuaryFirst() {
		var replacement = parse("{\"model\":\"XMAS_LUMBRIDGE_STATUE\",\"condition\":\"date >= 1201 || date <= 0101\"}");
		var sky = new SkyState();
		replacement.initialize(vars("2026-11-30", sky));

		assertNull(replacement.definition);
		assertEquals(261201, vars("2026-12-01", sky).get("date"));
		assertEquals(270101, vars("2027-01-01", sky).get("date"));
		assertNotNull(replacement.select(vars("2026-12-01", sky)));
		assertNotNull(replacement.select(vars("2026-12-31", sky)));
		assertNotNull(replacement.select(vars("2027-01-01", sky)));
		assertNull(replacement.select(vars("2027-01-02", sky)));
	}

	@Test
	public void selectsFirstMatchingVariantThenFallback() {
		var replacement = parse("{\"model\":\"XMAS_LUMBRIDGE_STATUE\",\"variants\":[" +
			"{\"model\":\"XMAS_GARDEN_QUEEN_STATUE\",\"condition\":\"night\"}," +
			"{\"model\":\"XMAS_LUMBRIDGE_STATUE\",\"condition\":\"sunAltitude < 5\"}]}");
		var sky = new SkyState();
		replacement.initialize(vars("2026-12-01", sky));
		assertSame(ModelStore.get("XMAS_LUMBRIDGE_STATUE"), replacement.definition);

		sky.cycleActive = true;
		sky.sunAltitudeDegrees = -1;
		assertSame(ModelStore.get("XMAS_GARDEN_QUEEN_STATUE"), replacement.select(vars("2026-12-01", sky)));
		sky.sunAltitudeDegrees = 10;
		assertSame(ModelStore.get("XMAS_LUMBRIDGE_STATUE"), replacement.select(vars("2026-12-01", sky)));
	}

	private static ModelReplacement parse(String json) {
		return new Gson().fromJson(json, ModelReplacement.class);
	}

	private static VariableSupplier vars(String date, SkyState sky) {
		return ModelReplacement.variables(name -> null, LocalDate.parse(date), sky);
	}
}
