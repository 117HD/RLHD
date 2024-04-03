package rs117.hd.utils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assert;
import rs117.hd.config.SeasonalTheme;

import static rs117.hd.utils.ExpressionParser.parseExpression;
import static rs117.hd.utils.ExpressionParser.parseFunction;
import static rs117.hd.utils.ExpressionParser.parsePredicate;

public class ExpressionParserTest {
	public static void main(String... args) {
		Map<String, Object> constants = new HashMap<>();
		Enum<?>[][] enums = {
			SeasonalTheme.values()
		};
		for (var anEnum : enums)
			for (var e : anEnum)
				constants.put(e.name(), e.ordinal());

		VariableSupplier vars = name -> {
			switch (name) {
				case "h":
					return 5;
				case "s":
					return 10;
				case "l":
					return 5;
				case "blending":
					return true;
				case "textures":
					return false;
			}
			return null;
		};

		Assert.assertEquals(5.f, parseExpression("5"));
		Assert.assertEquals(-5.f, parseExpression("-5"));
		Assert.assertEquals(-2.5f, parseExpression("-2.5"));
		Assert.assertEquals(-.5f, parseExpression("-0.5"));
		Assert.assertEquals(-.5f, parseExpression("-.5"));
		Assert.assertEquals(.5f, parseExpression(".5"));
		Assert.assertEquals(.5f, parseExpression("+.5"));
		Assert.assertEquals(.5f, parseExpression("++ +.5"));
		Assert.assertEquals(1f, parseExpression("--1"));
		Assert.assertEquals(.5f, parseExpression("+-++-.5"));
		Assert.assertEquals(17.f, parseFunction("5 + 12").apply(null));
		Assert.assertEquals(16.f, parseExpression("8 / 2 * (2 + 2)"));
		Assert.assertEquals(32.f, parseExpression("2 * 8 / 2 * (2 + 2)"));
		Assert.assertEquals(3.f, parseExpression("2 * 3 / 2"));
		Assert.assertEquals(0.f, parseExpression("2 * 8 - 4 * 4"));
		Assert.assertEquals(29.f, parseExpression("2 + 3 * (8 + 5 / 5)"));
		Assert.assertEquals(40.f, parseExpression("(8 - 1 + 3) * 6 - ((3 + 7) * 2)"));
		Assert.assertEquals(21.f, parseExpression("(1 + 2) * (3 + 4)"));
		Assert.assertFalse(parsePredicate("!( blending )").test(vars));
		Assert.assertEquals(false, parseExpression("!true"));
		Assert.assertEquals(true, parseExpression("SUMMER == 1", constants));

		System.out.println("Intentional errors:");
		assertThrows(() -> parseExpression("unexpected ( indeed"));
		assertThrows(() -> parseExpression("(5 + ( missing paren)"));

		LinkedHashMap<String, Boolean> testCases = new LinkedHashMap<>();
		testCases.put("h != 0", true);
		testCases.put("s == 0 || h <= 10 && s < 2", false);
		testCases.put("h == 8 && (s == 3 || s == 4) && l >= 20", false);
		testCases.put("h > 3 && s < 15 && l < 21", true);
		testCases.put("h < 3 && s < 15 && l < 21", false);
		testCases.put("h > 3 && (s < 9 || l < 19)", true);
		testCases.put("h == 5 ? s > 3 : s > 15", true);
		testCases.put("h == s || h == l", true);
		testCases.put("blending || textures", true);

		for (var entry : testCases.entrySet()) {
			var predicate = parsePredicate(entry.getKey());
			var result = predicate.test(vars);
			var passed = entry.getValue() == result;
			System.out.println(
				(passed ? "\u001B[32m" : "\u001B[31m") +
				"Case: " + entry.getKey() + " " + (passed ? "passed" : "failed") + ". Expected: " + entry.getValue() + ", got: " + result);
		}
	}

	private static void assertThrows(Runnable runnable) {
		try {
			runnable.run();
		} catch (Throwable ex) {
			System.out.println(ex);
			return;
		}
		Assert.fail("Didn't throw an exception");
	}
}
