package rs117.hd.tests;

import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Polygon;


public class PolygonAABBPerformanceTest {
	private static final int TEST_ITERATIONS = 10_000_000;
	private static final int WARMUP_ITERATIONS = 1_000_000;

	// Test polygon points (same as the main test)
	private static final WorldPoint[] POLYGON_POINTS = {
		new WorldPoint(2548, 2833, 0),
		new WorldPoint(2568, 2830, 0),
		new WorldPoint(2575, 2832, 0),
		new WorldPoint(2580, 2831, 0),
		new WorldPoint(2563, 2805, 0),
		new WorldPoint(2553, 2799, 0),
		new WorldPoint(2544, 2789, 0),
		new WorldPoint(2539, 2770, 0),
		new WorldPoint(2528, 2747, 0),
		new WorldPoint(2510, 2732, 0),
		new WorldPoint(2507, 2723, 0),
		new WorldPoint(2510, 2707, 0),
		new WorldPoint(2511, 2693, 0),
		new WorldPoint(2505, 2687, 0),
		new WorldPoint(2497, 2685, 0),
		new WorldPoint(2487, 2684, 0),
		new WorldPoint(2485, 2688, 0),
		new WorldPoint(2495, 2692, 0),
		new WorldPoint(2498, 2696, 0),
		new WorldPoint(2500, 2701, 0),
		new WorldPoint(2499, 2710, 0),
		new WorldPoint(2497, 2716, 0),
		new WorldPoint(2495, 2721, 0),
		new WorldPoint(2493, 2725, 0),
		new WorldPoint(2490, 2730, 0),
		new WorldPoint(2490, 2742, 0),
		new WorldPoint(2484, 2747, 0),
		new WorldPoint(2472, 2747, 0),
		new WorldPoint(2465, 2741, 0),
		new WorldPoint(2464, 2736, 0),
		new WorldPoint(2464, 2730, 0),
		new WorldPoint(2457, 2727, 0),
		new WorldPoint(2454, 2722, 0),
		new WorldPoint(2455, 2717, 0),
		new WorldPoint(2455, 2707, 0),
		new WorldPoint(2456, 2695, 0),
		new WorldPoint(2463, 2691, 0),
		new WorldPoint(2472, 2690, 0),
		new WorldPoint(2481, 2689, 0),
		new WorldPoint(2483, 2686, 0),
		new WorldPoint(2460, 2687, 0),
		new WorldPoint(2452, 2698, 0),
		new WorldPoint(2447, 2719, 0),
		new WorldPoint(2427, 2730, 0),
		new WorldPoint(2408, 2735, 0),
		new WorldPoint(2390, 2749, 0),
		new WorldPoint(2367, 2754, 0),
		new WorldPoint(2356, 2754, 0),
		new WorldPoint(2331, 2748, 0),
		new WorldPoint(2315, 2761, 0),
		new WorldPoint(2310, 2772, 0),
		new WorldPoint(2301, 2775, 0),
		new WorldPoint(2298, 2779, 0),
		new WorldPoint(2308, 2777, 0),
		new WorldPoint(2311, 2775, 0),
		new WorldPoint(2317, 2763, 0),
		new WorldPoint(2322, 2757, 0),
		new WorldPoint(2330, 2754, 0),
		new WorldPoint(2348, 2754, 0),
		new WorldPoint(2351, 2761, 0),
		new WorldPoint(2354, 2768, 0),
		new WorldPoint(2347, 2782, 0),
		new WorldPoint(2327, 2787, 0),
		new WorldPoint(2315, 2790, 0),
		new WorldPoint(2305, 2796, 0),
		new WorldPoint(2301, 2802, 0),
		new WorldPoint(2297, 2812, 0),
		new WorldPoint(2294, 2818, 0),
		new WorldPoint(2292, 2830, 0),
		new WorldPoint(2295, 2842, 0),
		new WorldPoint(2326, 2856, 0),
		new WorldPoint(2336, 2859, 0),
		new WorldPoint(2341, 2878, 0),
		new WorldPoint(2346, 2889, 0),
		new WorldPoint(2352, 2907, 0),
		new WorldPoint(2352, 2919, 0),
		new WorldPoint(2347, 2929, 0),
		new WorldPoint(2341, 2940, 0),
		new WorldPoint(2334, 2952, 0),
		new WorldPoint(2327, 2971, 0),
		new WorldPoint(2313, 2975, 0),
		new WorldPoint(2273, 2990, 0),
		new WorldPoint(2260, 2995, 0),
		new WorldPoint(2244, 2991, 0),
		new WorldPoint(2229, 2997, 0),
		new WorldPoint(2212, 3013, 0),
		new WorldPoint(2189, 3014, 0),
		new WorldPoint(2185, 3010, 0),
		new WorldPoint(2176, 3006, 0),
		new WorldPoint(2165, 3013, 0),
		new WorldPoint(2156, 3027, 0),
		new WorldPoint(2157, 3032, 0),
		new WorldPoint(2162, 3044, 0),
		new WorldPoint(2165, 3046, 0),
		new WorldPoint(2182, 3037, 0),
		new WorldPoint(2190, 3032, 0),
		new WorldPoint(2204, 3023, 0),
		new WorldPoint(2220, 3018, 0),
		new WorldPoint(2238, 3019, 0),
		new WorldPoint(2256, 3021, 0),
		new WorldPoint(2271, 3027, 0),
		new WorldPoint(2292, 3028, 0),
		new WorldPoint(2300, 3032, 0),
		new WorldPoint(2306, 3041, 0),
		new WorldPoint(2314, 3050, 0),
		new WorldPoint(2320, 3048, 0),
		new WorldPoint(2326, 3029, 0),
		new WorldPoint(2333, 3025, 0),
		new WorldPoint(2351, 3024, 0),
		new WorldPoint(2364, 3026, 0),
		new WorldPoint(2364, 3027, 0),
		new WorldPoint(2376, 3020, 0),
		new WorldPoint(2403, 3019, 0),
		new WorldPoint(2408, 3025, 0),
		new WorldPoint(2415, 3027, 0),
		new WorldPoint(2433, 3025, 0),
		new WorldPoint(2458, 3010, 0),
		new WorldPoint(2473, 2991, 0),
		new WorldPoint(2465, 2976, 0),
		new WorldPoint(2468, 2963, 0),
		new WorldPoint(2473, 2956, 0),
		new WorldPoint(2462, 2938, 0),
		new WorldPoint(2463, 2928, 0),
		new WorldPoint(2466, 2922, 0),
		new WorldPoint(2462, 2916, 0),
		new WorldPoint(2458, 2905, 0),
		new WorldPoint(2453, 2899, 0),
		new WorldPoint(2452, 2889, 0),
		new WorldPoint(2452, 2882, 0),
		new WorldPoint(2449, 2879, 0),
		new WorldPoint(2439, 2872, 0),
		new WorldPoint(2432, 2867, 0),
		new WorldPoint(2414, 2878, 0),
		new WorldPoint(2422, 2866, 0),
		new WorldPoint(2430, 2857, 0),
		new WorldPoint(2432, 2850, 0),
		new WorldPoint(2429, 2844, 0),
		new WorldPoint(2430, 2829, 0),
		new WorldPoint(2432, 2812, 0),
		new WorldPoint(2437, 2808, 0),
		new WorldPoint(2454, 2805, 0),
		new WorldPoint(2464, 2822, 0),
		new WorldPoint(2476, 2830, 0),
		new WorldPoint(2481, 2833, 0),
		new WorldPoint(2504, 2826, 0),
		new WorldPoint(2515, 2824, 0),
		new WorldPoint(2535, 2821, 0),
		new WorldPoint(2538, 2823, 0),
		new WorldPoint(2543, 2827, 0),
		new WorldPoint(2548, 2833, 0)
	};

	// Bounding box for the polygon (min/max from the points)
	private static final AABB[] BOUNDING_BOX = {
		new AABB( 2167, 3011, 2205, 3041),
		new AABB( 2176, 3006, 2205, 3010),
		new AABB( 2171, 3008, 2175, 3010),
		new AABB( 2163, 3014, 2166, 3045),
		new AABB( 2160, 3021, 2162, 3041),
		new AABB( 2167, 3042, 2197, 3046),
		new AABB( 2198, 3042, 2204, 3042),
		new AABB( 2200, 3043, 2203, 3043),
		new AABB( 2173, 3047, 2189, 3055),
		new AABB( 2169, 3047, 2172, 3051),
		new AABB( 2167, 3047, 2168, 3048),
		new AABB( 2165, 3046, 2166, 3047),
		new AABB( 2206, 3005, 2259, 3035),
		new AABB( 2260, 2996, 2342, 3030),
		new AABB( 2288, 3031, 2326, 3038),
		new AABB( 2300, 3039, 2323, 3045),
		new AABB( 2307, 3046, 2320, 3052),
		new AABB( 2217, 3001, 2259, 3004),
		new AABB( 2223, 2992, 2259, 3000),
		new AABB( 2260, 2989, 2342, 2995),
		new AABB( 2274, 2982, 2342, 2988),
		new AABB( 2292, 2976, 2342, 2981),
		new AABB( 2284, 2978, 2291, 2981),
		new AABB( 2270, 2986, 2273, 2988),
		new AABB( 2251, 2989, 2259, 2991),
		new AABB( 2343, 2976, 2444, 3026),
		new AABB( 2343, 3027, 2350, 3029),
		new AABB( 2351, 3027, 2361, 3028),
		new AABB( 2445, 2976, 2457, 3020),
		new AABB( 2458, 2976, 2468, 3016),
		new AABB( 2445, 3021, 2448, 3026),
		new AABB( 2449, 3021, 2454, 3023),
		new AABB( 2455, 3021, 2456, 3022),
		new AABB( 2404, 3027, 2416, 3031),
		new AABB( 2409, 3032, 2420, 3035),
		new AABB( 2417, 3027, 2444, 3031),
		new AABB( 2445, 3027, 2447, 3029),
		new AABB( 2445, 3030, 2445, 3033),
		new AABB( 2421, 3032, 2444, 3032),
		new AABB( 2421, 3033, 2435, 3035),
		new AABB( 2436, 3033, 2436, 3034),
		new AABB( 2437, 3033, 2438, 3033),
		new AABB( 2442, 3033, 2444, 3033),
		new AABB( 2442, 3034, 2444, 3034),
		new AABB( 2428, 3036, 2433, 3036),
		new AABB( 2429, 3037, 2432, 3037),
		new AABB( 2410, 3036, 2423, 3036),
		new AABB( 2419, 3037, 2421, 3037),
		new AABB( 2411, 3037, 2416, 3037),
		new AABB( 2412, 3038, 2414, 3038),
		new AABB( 2420, 3038, 2420, 3038),
		new AABB( 2458, 3017, 2460, 3018),
		new AABB( 2469, 2976, 2473, 3008),
		new AABB( 2344, 2923, 2469, 2975),
		new AABB( 2316, 2969, 2343, 2975),
		new AABB( 2327, 2955, 2343, 2968),
		new AABB( 2332, 2940, 2343, 2954),
		new AABB( 2338, 2934, 2343, 2939),
		new AABB( 2335, 2937, 2337, 2939),
		new AABB( 2341, 2930, 2343, 2933),
		new AABB( 2329, 2951, 2331, 2954),
		new AABB( 2322, 2964, 2326, 2968),
		new AABB( 2310, 2972, 2315, 2975),
		new AABB( 2240, 2989, 2250, 2991),
		new AABB( 2240, 2986, 2269, 2988),
		new AABB( 2271, 2984, 2273, 2985),
		new AABB( 2274, 2978, 2283, 2981),
		new AABB( 2281, 2975, 2291, 2977),
		new AABB( 2292, 2972, 2309, 2975),
		new AABB( 2303, 2969, 2315, 2971),
		new AABB( 2314, 2966, 2321, 2968),
		new AABB( 2318, 2964, 2321, 2965),
		new AABB( 2324, 2955, 2326, 2963),
		new AABB( 2322, 2962, 2323, 2963),
		new AABB( 2292, 3039, 2299, 3042),
		new AABB( 2302, 3046, 2306, 3049),
		new AABB( 2280, 3031, 2287, 3034),
		new AABB( 2260, 3031, 2279, 3034),
		new AABB( 2327, 3031, 2335, 3035),
		new AABB( 2336, 3031, 2340, 3033),
		new AABB( 2470, 2951, 2473, 2975),
		new AABB( 2474, 2949, 2479, 2964),
		new AABB( 2470, 2944, 2473, 2950),
		new AABB( 2470, 2939, 2471, 2943),
		new AABB( 2474, 2946, 2475, 2948),
		new AABB( 2474, 2965, 2476, 2969),
		new AABB( 2474, 2970, 2474, 2972),
		new AABB( 2345, 2914, 2469, 2922),
		new AABB( 2346, 2882, 2452, 2913),
		new AABB( 2453, 2893, 2462, 2913),
		new AABB( 2463, 2904, 2465, 2913),
		new AABB( 2466, 2908, 2468, 2913),
		new AABB( 2396, 2810, 2433, 2881),
		new AABB( 2434, 2867, 2436, 2881),
		new AABB( 2437, 2874, 2446, 2881),
		new AABB( 2437, 2870, 2439, 2873),
		new AABB( 2447, 2878, 2449, 2881)
	};

	@Test
	public void testContainmentPerformance() {
		List<WorldPoint> pointsList = Arrays.asList(POLYGON_POINTS);
		Polygon polygonAABB = new Polygon(pointsList, 0); // level 0

		int testX = 2308;
		int testY = 3025;
		int testZ = 0;

		// Warmup
		System.out.println("Warming up...");
		for (int i = 0; i < WARMUP_ITERATIONS; i++) {
			polygonAABB.contains(testX, testY, testZ);
			containsPoint(testX, testY, testZ);
		}

		// Test PolygonAABB
		System.out.println("Running PolygonAABB containment test...");
		long polygonStart = System.nanoTime();
		for (int i = 0; i < TEST_ITERATIONS; i++) {
			polygonAABB.contains(testX, testY, testZ);
		}
		long polygonTime = System.nanoTime() - polygonStart;
		double polygonTimeMs = polygonTime / 1_000_000.0;
		double polygonOpsPerMs = TEST_ITERATIONS / polygonTimeMs;

		// Test AABB array
		System.out.println("Running AABB array containment test...");
		long aabbStart = System.nanoTime();
		for (int i = 0; i < TEST_ITERATIONS; i++) {
			containsPoint(testX, testY, testZ);
		}
		long aabbTime = System.nanoTime() - aabbStart;
		double aabbTimeMs = aabbTime / 1_000_000.0;
		double aabbOpsPerMs = TEST_ITERATIONS / aabbTimeMs;

		// Results
		System.out.println("\n=== Performance Results ===");
		System.out.printf("Test point: (%d, %d, %d)\n", testX, testY, testZ);
		System.out.printf("PolygonAABB:    %d iterations in %.2f ms (%.2f ops/ms)\n",
			TEST_ITERATIONS, polygonTimeMs, polygonOpsPerMs);
		System.out.printf("AABB array:     %d iterations in %.2f ms (%.2f ops/ms)\n",
			TEST_ITERATIONS, aabbTimeMs, aabbOpsPerMs);
		System.out.printf("Speed ratio:    %.2fx %s\n",
			Math.max(polygonTimeMs, aabbTimeMs) / Math.min(polygonTimeMs, aabbTimeMs),
			polygonTimeMs < aabbTimeMs ? "(PolygonAABB faster)" : "(AABB array faster)");

		Assert.assertTrue("Point (2308, 3025, 0) should be inside the polygon",
			polygonAABB.contains(2308, 3025, 0));
		Assert.assertTrue("Point (2308, 3025, 0) should be inside the polygon (WorldPoint)",
			polygonAABB.contains(new WorldPoint(2308, 3025, 0)));
		Assert.assertFalse("Point (2902, 2981, 0) should be outside the polygon (WorldPoint)",
			polygonAABB.contains(new WorldPoint(2902, 2981, 0)));

	}

	public boolean containsPoint(int... worldPoint) {
		for (var aabb : BOUNDING_BOX)
			if (aabb.contains(worldPoint))
				return true;
		return false;
	}

}

