package rs117.hd.utils.jobs;

import com.google.inject.Injector;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.config.CpuUsageLimit;
import rs117.hd.overlays.FrameTimer;

import static rs117.hd.HdPlugin.PROCESSOR_COUNT;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public final class JobSystem {
	public static final boolean VALIDATE = false;

	@Inject
	public Injector injector;

	@Inject
	public Client client;

	@Inject
	public ClientThread clientThread;

	@Inject
	public HdPlugin plugin;

	@Inject
	public FrameTimer frametimer;

	@Getter
	boolean active;

	private int workerCount;

	private final ArrayDeque<ClientCallbackJob> clientCallbacks = new ArrayDeque<>();
	private final HashMap<Thread, Worker> threadToWorker = new HashMap<>();
	private final AtomicInteger roundRobinIdx = new AtomicInteger();
	private final AtomicBoolean clientInvokeScheduled = new AtomicBoolean();

	Worker[] workers;

	public void startUp(CpuUsageLimit cpuUsageLimit) {
		workerCount = max(1, ceil((PROCESSOR_COUNT - 1) * cpuUsageLimit.threadRatio));
		workers = new Worker[workerCount];
		active = true;

		for (int i = 0; i < workerCount; i++) {
			Worker worker = workers[i] = new Worker(this, i);
			worker.thread = new Thread(worker::run);
			worker.thread.setPriority(Thread.NORM_PRIORITY + 1);
			worker.thread.setName("117HD - Worker " + i);
			threadToWorker.put(worker.thread, worker);
		}

		Job.JOB_SYSTEM = this;
		JobHandle.JOB_SYSTEM = this;

		for (int i = 0; i < workerCount; i++)
			workers[i].thread.start();

		log.debug("Initialized JobSystem with {} workers", workerCount);
	}

	void pushWork(JobHandle handle) {
		// Simple RR with randomization fallback for contention spreading
		int idx = Math.floorMod(
			roundRobinIdx.getAndIncrement() + ThreadLocalRandom.current().nextInt(workerCount),
			workerCount
		);

		Worker best = workers[idx];
		int bestDepth = best.queueDepth.get();

		// Small scan window for lower contention + decent balancing
		for (int i = 1; i < min(workerCount, 3); i++) {
			Worker worker = workers[(idx + i) % workerCount];
			int depth = worker.queueDepth.get();

			if (depth < bestDepth) {
				best = worker;
				bestDepth = depth;
			}
		}

		best.push(handle);
	}

	private void cancelAllWork(ArrayDeque<JobHandle> queue) {
		JobHandle handle;
		while ((handle = queue.pollFirst()) != null) {
			try {
				handle.cancel(false);
				handle.setCompleted();
			} catch (InterruptedException e) {
				log.warn("Interrupted while shutting down worker", e);
				throw new RuntimeException(e);
			}
		}
	}

	public void shutDown() {
		active = false;

		for (Worker worker : workers) {
			cancelAllWork(worker.localWorkQueue);
			if (worker.handle != null) {
				try {
					worker.handle.cancel(true);
				} catch (InterruptedException e) {
					log.warn("Interrupted while shutting down worker", e);
					throw new RuntimeException(e);
				}
			}
			worker.thread.interrupt();
		}

		int workerShutdownCount = 0;
		for (Worker worker : workers) {
			if (!worker.thread.isAlive()) {
				workerShutdownCount++;
				continue;
			}

			try {
				worker.thread.join(1000);
			} catch (InterruptedException e) {
				log.warn("Interrupted while waiting for worker shutdown", e);
			}

			if (worker.thread.isAlive()) {
				log.warn("Worker {} didn't shutdown within a timely manner", worker.thread.getName());
				worker.printState();
			} else {
				workerShutdownCount++;
			}
		}

		if (workerShutdownCount == workerCount)
			log.debug("All workers shutdown successfully");

		threadToWorker.clear();
		workers = null;
	}

	public boolean isWorker() {
		return threadToWorker.containsKey(Thread.currentThread());
	}

	public void printWorkersState() {
		for (Worker worker : workers)
			worker.printState();
	}

	void queue(Job item, boolean highPriority, Job... dependencies) {
		if (!item.executeAsync) {
			try {
				item.queued.set(true);
				item.onRun();
				item.ranToCompletion.set(true);
			} catch (Throwable ex) {
				if (item.wasCancelled()) {
					log.debug("Encountered an error whilst processing: {}", item.hashCode(), ex);
				} else {
					log.warn("Encountered an error whilst processing: {}", item.hashCode(), ex);
				}
			} finally {
				item.done.set(true);
			}
			return;
		}

		JobHandle newHandle = item.handle = JobHandle.obtain();
		newHandle.highPriority = highPriority;
		newHandle.item = item;

		boolean shouldQueue = true;
		for (Job dep : dependencies) {
			if (dep == null || dep.handle == null) continue;
			if (dep.handle.addDependant(newHandle)) {
				shouldQueue = false;
			}
		}

		item.queued.set(true);
		item.done.set(false);
		item.wasCancelled.set(false);
		item.encounteredError.set(false);
		item.ranToCompletion.set(false);

		if (shouldQueue) {
			newHandle.setInQueue();
			if (VALIDATE) log.debug("Handle [{}] Added to queue", newHandle);
			pushWork(newHandle);
		}
	}

	void invokeClientCallback(Runnable callback) throws InterruptedException {
		if (client.isClientThread()) {
			callback.run();
			processPendingClientCallbacks();
			return;
		}

		final ClientCallbackJob clientCallback = ClientCallbackJob.current();
		clientCallback.callback = callback;

		synchronized (clientCallbacks) {
			clientCallbacks.add(clientCallback);
		}

		if (clientInvokeScheduled.compareAndSet(false, true)) {
			clientThread.invoke(() -> {
				processPendingClientCallbacks();
				clientInvokeScheduled.set(false);
			});
		}

		try {
			clientCallback.semaphore.acquire();
		} catch (InterruptedException e) {
			synchronized (clientCallbacks) {
				clientCallbacks.remove(clientCallback);
				throw new InterruptedException();
			}
		}
	}

	public void processPendingClientCallbacks() {
		synchronized (clientCallbacks) {
			ClientCallbackJob pair;
			while ((pair = clientCallbacks.poll()) != null) {
				try {
					pair.callback.run();
				} catch (Throwable ex) {
					log.warn("Encountered exception whilst processing client callback", ex);
				} finally {
					pair.semaphore.release();
				}
			}
		}

	}
}