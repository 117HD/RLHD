package rs117.hd.tests;

import java.util.LinkedHashMap;
import org.junit.Assert;
import org.junit.Test;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.utils.VariableSupplier;

import static rs117.hd.utils.ExpressionParser.parseExpression;
import static rs117.hd.utils.ExpressionParser.parseFunction;
import static rs117.hd.utils.ExpressionParser.parsePredicate;

public class ExpressionParserTest {
	@Test
	public void testExpressionParser() {
		final VariableSupplier vars = new VariableSupplier() {
			@Override
			public Object get(String name) { return null; }

			@Override
			public int getInt(String name) {
				switch (name) {
					case "h":
						return 5;
					case "s":
						return 10;
					case "l":
						return 5;
				}
				throw new UnsupportedOperationException(name + " is not an int var");
			}

			@Override
			public boolean getBoolean(String name) {
				switch (name) {
					case "blending":
						return true;
					case "textures":
						return false;
				}
				throw new UnsupportedOperationException(name + " is not an boolean var");
			}
		};

		Assert.assertEquals(5, parseExpression("5"));
		Assert.assertEquals(-5, parseExpression("-5"));
		Assert.assertEquals(-2.5f, parseExpression("-2.5"));
		Assert.assertEquals(-.5f, parseExpression("-0.5"));
		Assert.assertEquals(-.5f, parseExpression("-.5"));
		Assert.assertEquals(.5f, parseExpression(".5"));
		Assert.assertEquals(.5f, parseExpression("+.5"));
		Assert.assertEquals(.5f, parseExpression("++ +.5"));
		Assert.assertEquals(1, parseExpression("--1"));
		Assert.assertEquals(.5f, parseExpression("+-++-.5"));
		Assert.assertEquals(17, parseFunction("5 + 12").apply(null));
		Assert.assertEquals(16, parseExpression("8 / 2 * (2 + 2)"));
		Assert.assertEquals(32, parseExpression("2 * 8 / 2 * (2 + 2)"));
		Assert.assertEquals(3, parseExpression("2 * 3 / 2"));
		Assert.assertEquals(0, parseExpression("2 * 8 - 4 * 4"));
		Assert.assertEquals(29, parseExpression("2 + 3 * (8 + 5 / 5)"));
		Assert.assertEquals(40, parseExpression("(8 - 1 + 3) * 6 - ((3 + 7) * 2)"));
		Assert.assertEquals(21, parseExpression("(1 + 2) * (3 + 4)"));
		Assert.assertFalse(parsePredicate("!( blending )").test(vars));
		Assert.assertEquals(false, parseExpression("!true"));
		Assert.assertEquals(true, parseExpression("SUMMER == 1", name -> SeasonalTheme.valueOf(name).ordinal()));

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
			System.out.println("\u001B[32m" + "Case: Threw as expected: " + ex);
			return;
		}
		Assert.fail("Didn't throw an exception");
	}
}
