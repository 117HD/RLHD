package rs117.hd.tests;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import rs117.hd.config.CpuUsageLimit;
import rs117.hd.utils.jobs.GenericJob;
import rs117.hd.utils.jobs.JobSystem;

@Slf4j
public class JobSystemTests {
	private static JobSystem JOB_SYSTEM;

	@BeforeClass
	public static void beforeAll() {
		JOB_SYSTEM = new JobSystem();
		JOB_SYSTEM.startUp(CpuUsageLimit.MAX);
	}

	@AfterClass
	public static void afterAll() {
		JOB_SYSTEM.shutDown();
	}

	@Test
	public void testQueueAndCompletion() {
		GenericJob task = GenericJob
			.build(
				"testQueueAndCompletion",
				(t) -> busyWork(t, 5000)
			)
			.queue(false);

		task.waitForCompletion();
		Assert.assertTrue(task.ranToCompletion());
	}

	@Test
	public void testQueueWithMultipleDependencies() {
		List<String> order = new CopyOnWriteArrayList<>();

		GenericJob tA = GenericJob
			.build(
				"A", t -> {
					busyWork(t, 200);
					order.add("A");
				}
			)
			.queue();

		GenericJob tB = GenericJob
			.build(
				"B", t -> {
					busyWork(t, 300);
					order.add("B");
				}
			)
			.queue(tA);

		GenericJob tC = GenericJob
			.build(
				"C", t -> {
					busyWork(t, 300);
					order.add("C");
				}
			)
			.queue(tA, tB);

		// Wait for dependent
		tC.waitForCompletion();

		Assert.assertTrue(tA.isDone());
		Assert.assertTrue(tB.isDone());
		Assert.assertTrue(tC.isDone());

		int idxA = order.indexOf("A");
		int idxB = order.indexOf("B");
		int idxC = order.indexOf("C");

		Assert.assertTrue(idxA >= 0 && idxB >= 0 && idxC >= 0);
		Assert.assertTrue(idxC > idxA && idxC > idxB);
		Assert.assertTrue(tA.ranToCompletion() && tB.ranToCompletion() && tC.ranToCompletion());
	}

	@Test
	public void testCancelTask() throws Exception {
		GenericJob longTask = GenericJob
			.build("long", (t) -> busyWork(t, 10000))
			.queue();

		Thread.sleep(50); // let it start
		longTask.cancel();

		Assert.assertFalse(longTask.ranToCompletion());
	}

	@Test
	public void testTasksWideParallel() {
		int taskCount = 1000;

		List<GenericJob> tasks = new CopyOnWriteArrayList<>();

		// Queue all tasks with no dependencies
		for (int i = 0; i < taskCount; i++) {
			tasks.add(GenericJob
				.build(
					"Task" + i,
					t -> {
						busyWork(t, 100); // short work for speed
					}
				)
				.queue());
		}

		// Wait for all tasks to complete
		for (GenericJob task : tasks)
			task.waitForCompletion();

		// Verify all tasks ran
		for (int i = 0; i < taskCount; i++)
			Assert.assertTrue("Task" + i + " should have executed", tasks.get(i).ranToCompletion());
	}

	@Test
	public void testManyDependenciesStress() {
		int count = 500;
		List<GenericJob> tasks = new CopyOnWriteArrayList<>();

		GenericJob prev = null;

		for (int i = 0; i < count; i++) {
			int idx = i;
			GenericJob task = GenericJob
				.build(
					"T" + idx, t -> {
						log.debug("[TASK {}] Start", idx);
						busyWork(t, 10);
					}
				)
				.queue(prev);

			tasks.add(task);
			prev = task;
		}

		prev.waitForCompletion();

		for (int i = 0; i < count; i++)
			Assert.assertTrue("Task T" + i + " should complete", tasks.get(i).ranToCompletion());
	}

	@Test
	public void testDependencyChain() {
		List<String> order = new CopyOnWriteArrayList<>();

		GenericJob tA = GenericJob
			.build(
				"A", t -> {
					log.debug("[TASK A] Start");
					busyWork(t, 1000);
					order.add("A");
				}
			)
			.queue();

		GenericJob tB = GenericJob
			.build(
				"B", t -> {
					log.debug("[TASK B] Start");
					busyWork(t, 200);
					order.add("B");
				}
			)
			.queue(tA);

		GenericJob tC = GenericJob
			.build(
				"C", t -> {
					log.debug("[TASK C] Start");
					busyWork(t, 2000);
					order.add("C");
				}
			)
			.queue(tB);

		tC.waitForCompletion();

		Assert.assertEquals(List.of("A", "B", "C"), order);
		Assert.assertTrue(tA.ranToCompletion() && tB.ranToCompletion() && tC.ranToCompletion());
	}

	@Test
	public void testBranchingDependencies() {
		List<String> order = new CopyOnWriteArrayList<>();

		GenericJob tA = GenericJob.build(
			"A", t -> {
				log.debug("[TASK A] Start");
				busyWork(t, 100);
				order.add("A");
			}
		).queue();

		GenericJob tB = GenericJob.build(
			"B", t -> {
				log.debug("[TASK B] Start");
				busyWork(t, 200);
				order.add("B");
			}
		).queue(tA);

		GenericJob tC = GenericJob.build(
			"C", t -> {
				log.debug("[TASK C] Start");
				busyWork(t, 150);
				order.add("C");
			}
		).queue(tA);

		GenericJob tD = GenericJob.build(
			"D", t -> {
				log.debug("[TASK D] Start");
				busyWork(t, 100);
				order.add("D");
			}
		).queue(tB, tC);

		tD.waitForCompletion();

		Assert.assertTrue(order.indexOf("A") < order.indexOf("B"));
		Assert.assertTrue(order.indexOf("A") < order.indexOf("C"));
		Assert.assertTrue(order.indexOf("B") < order.indexOf("D"));
		Assert.assertTrue(order.indexOf("C") < order.indexOf("D"));
	}

	@Test
	public void testDiamondDependencyGraph() throws Exception {
		List<String> order = new CopyOnWriteArrayList<>();

		GenericJob tA = GenericJob.build(
			"A", t -> {
				log.debug("[TASK A] Start");
				busyWork(t, 120);
				order.add("A");
			}
		).queue();

		GenericJob tB = GenericJob.build(
			"B", t -> {
				log.debug("[TASK B] Start");
				busyWork(t, 140);
				order.add("B");
			}
		).queue();

		GenericJob tC = GenericJob.build(
			"C", t -> {
				log.debug("[TASK C] Start");
				busyWork(t, 200);
				order.add("C");
			}
		).queue(tA, tB);

		GenericJob tD = GenericJob.build(
			"D", t -> {
				log.debug("[TASK D] Start");
				busyWork(t, 180);
				order.add("D");
			}
		).queue(tA, tB);

		GenericJob tE = GenericJob.build(
			"E", t -> {
				log.debug("[TASK E] Start");
				busyWork(t, 80);
				order.add("E");
			}
		).queue(tC, tD);

		tE.waitForCompletion();

		Assert.assertTrue(order.indexOf("A") < order.indexOf("C"));
		Assert.assertTrue(order.indexOf("B") < order.indexOf("C"));
		Assert.assertTrue(order.indexOf("A") < order.indexOf("D"));
		Assert.assertTrue(order.indexOf("B") < order.indexOf("D"));

		Assert.assertTrue(order.indexOf("C") < order.indexOf("E"));
		Assert.assertTrue(order.indexOf("D") < order.indexOf("E"));
	}

	@Test
	public void testCancelUpstreamDependencyPreventsDownstreamExecution() throws Exception {
		GenericJob hA = GenericJob.build(
			"A", t -> {
				log.debug("[TASK A] Start");
				busyWork(t, 5000);
			}
		).queue();

		GenericJob hB = GenericJob.build(
			"B", t -> {
				log.debug("[TASK B] Start (This shouldn't happen)");
				busyWork(t, 100);
			}
		).queue(hA);

		Thread.sleep(50);
		hA.cancel();

		Assert.assertTrue(hA.isDone());
		Assert.assertTrue(hB.isDone());

		Assert.assertFalse(hA.ranToCompletion());
		Assert.assertFalse(hB.ranToCompletion());
	}

	@Test
	public void testCircularDependencyDetection() {
		// Create 4 fresh handles
		GenericJob A = GenericJob.build("A", t -> busyWork(t, 1000));
		GenericJob B = GenericJob.build("B", t -> busyWork(t, 1000));
		GenericJob C = GenericJob.build("C", t -> busyWork(t, 1000));
		GenericJob D = GenericJob.build("D", t -> busyWork(t, 1000));

		// ----------------------------
		// Case 1: Valid chain A -> B -> C -> D
		// Should NOT throw
		// ----------------------------
		try {
			A.queue(); // A -> B
			B.queue(A); // B -> C
			C.queue(B); // C -> D
		} catch (Exception ex) {
			Assert.fail("Valid dependency chain threw unexpectedly: " + ex);
		}

		A.waitForCompletion();
		B.waitForCompletion();
		C.waitForCompletion();

		// ----------------------------
		// Case 2: Create a cycle by adding D -> A
		// A -> B -> C -> D -> A  (cycle)
		// Should throw IllegalStateException
		// ----------------------------
		boolean cycleThrown = false;
		try {
			A.queue(); // A -> D
			B.queue(A); // B -> C
			C.queue(B); // C -> B
			D.queue(D); // D -> A - should throw
		} catch (IllegalStateException expected) {
			cycleThrown = true;
		}
		Assert.assertTrue("Cycle A -> B -> C -> D -> A should throw IllegalStateException", cycleThrown);

		A.waitForCompletion();
		B.waitForCompletion();
		C.waitForCompletion();

		// ----------------------------
		// Case 3: Self-cycle A -> A
		// Should throw IllegalStateException
		// ----------------------------
		boolean selfCycleThrown = false;
		try {
			A.queue(A);
		} catch (IllegalStateException expected) {
			selfCycleThrown = true;
		}
		Assert.assertTrue("Self-cycle A -> A should throw IllegalStateException", selfCycleThrown);
	}

	@Test
	public void testGroupedDependencyGraph() throws Exception {
		List<String> order = new CopyOnWriteArrayList<>();

		// ----- A -----
		GenericJob tA = GenericJob.build(
			"A", t -> {
				log.debug("[TASK A] Start");
				busyWork(t, 100);
				order.add("A");
			}
		).queue();

		// ----- B -----
		GenericJob tB = GenericJob.build(
			"B", t -> {
				log.debug("[TASK B] Start");
				busyWork(t, 120);
				order.add("B");
			}
		).queue();

		// ----- C depends on A & B -----
		GenericJob hC = GenericJob.build(
			"C", t -> {
				log.debug("[TASK C] Start");
				busyWork(t, 150);
				order.add("C");
			}
		).queue(tA, tB);

		// ----- D depends on C -----
		GenericJob tD = GenericJob.build(
			"D", t -> {
				log.debug("[TASK D] Start");
				busyWork(t, 80);
				order.add("D");
			}
		).queue(hC);

		// ----- E depends on C -----
		GenericJob tE = GenericJob.build(
			"E", t -> {
				log.debug("[TASK E] Start");
				busyWork(t, 90);
				order.add("E");
			}
		).queue(hC);

		// Wait for the final downstream tasks
		tD.waitForCompletion();
		tE.waitForCompletion();

		// ----- Assertions -----
		Assert.assertTrue(order.contains("A"));
		Assert.assertTrue(order.contains("B"));
		Assert.assertTrue(order.contains("C"));
		Assert.assertTrue(order.contains("D"));
		Assert.assertTrue(order.contains("E"));

		// C must run after both A and B
		Assert.assertTrue(order.indexOf("C") > order.indexOf("A"));
		Assert.assertTrue(order.indexOf("C") > order.indexOf("B"));

		// D & E must run after C
		Assert.assertTrue(order.indexOf("D") > order.indexOf("C"));
		Assert.assertTrue(order.indexOf("E") > order.indexOf("C"));

		// Flags must be set
		Assert.assertTrue(tA.ranToCompletion());
		Assert.assertTrue(tB.ranToCompletion());
		Assert.assertTrue(hC.ranToCompletion());
		Assert.assertTrue(tD.ranToCompletion());
		Assert.assertTrue(tE.ranToCompletion());
	}

	@Test
	public void testCancelUpstreamCascadesDownstream() throws Exception {
		List<String> order = new CopyOnWriteArrayList<>();

		// ----- A: Will be cancelled -----
		GenericJob tA = GenericJob.build(
			"A", t -> {
				log.debug("[TASK A] Start");
				busyWork(t, 3000);  // long-running, gives us time to cancel
				order.add("A");
			}
		).queue();

		// ----- B: Allowed to run -----
		GenericJob tB = GenericJob.build(
			"B", t -> {
				log.debug("[TASK B] Start");
				busyWork(t, 100);
				order.add("B");
			}
		).queue();

		// ----- C depends on A & B -----
		GenericJob tC = GenericJob.build(
			"C", t -> {
				log.debug("[TASK C] Should NOT execute");
				busyWork(t, 50);
				order.add("C");
			}
		).queue(tA, tB);

		// ----- D depends on C -----
		GenericJob tD = GenericJob.build(
			"D", t -> {
				log.debug("[TASK D] Should NOT execute");
				busyWork(t, 50);
				order.add("D");
			}
		).queue(tC);

		// ----- E depends on C -----
		GenericJob tE = GenericJob.build(
			"E", t -> {
				log.debug("[TASK E] Should NOT execute");
				busyWork(t, 50);
				order.add("E");
			}
		).queue(tC);

		// Let A start
		Thread.sleep(50);
		tA.cancel(); // cancel upstream root

		// Wait for final tasks
		tE.waitForCompletion();

		// ----- Assertions -----

		// B is not cancelled, should run
		tB.waitForCompletion();
		Assert.assertTrue(tB.ranToCompletion());

		// A is cancelled
		Assert.assertFalse(tA.ranToCompletion());

		// C, D, E must never execute because they depend on A
		Assert.assertFalse(tC.ranToCompletion());
		Assert.assertFalse(tD.ranToCompletion());
		Assert.assertFalse(tE.ranToCompletion());

		// Order should contain only B (A is cancelled before completing)
		Assert.assertTrue(order.contains("B"));
		Assert.assertEquals(1, order.size());
	}

	private static void busyWork(GenericJob task, long millis) throws InterruptedException {
		final long start = System.nanoTime();
		final long durationNanos = millis * 1_000_000L;
		long nextLogTime = System.nanoTime() + 1000 * 1_000_000L; // 1 Second

		long number = 2;
		long primesFound = 0;

		while (System.nanoTime() - start < durationNanos) {
			if (isPrime(number))
				primesFound++;

			number++;

			task.workerHandleCancel();

			long now = System.nanoTime();
			if (now >= nextLogTime) {
				log.debug("busyWork: checked={} primes={}", number, primesFound);
				nextLogTime = now + 1000 * 1_000_000L;
			}
		}
	}

	private static boolean isPrime(long n) {
		if (n < 2) return false;
		if (n % 2 == 0) return n == 2;

		long limit = (long) Math.sqrt(n);
		for (long i = 3; i <= limit; i += 2) {
			if (n % i == 0)
				return false;
		}
		return true;
	}
}
