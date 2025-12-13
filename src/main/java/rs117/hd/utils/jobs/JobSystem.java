package rs117.hd.utils.jobs;

import com.google.inject.Injector;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
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

	private final int workerCount = max(2, PROCESSOR_COUNT - 1);

	final BlockingDeque<JobHandle> workQueue = new LinkedBlockingDeque<>();
	private final ArrayBlockingQueue<ClientCallbackJob> clientCallbacks = new ArrayBlockingQueue<>(workerCount);

	private final HashMap<Thread, Worker> threadToWorker = new HashMap<>();
	Worker[] workers;
	Semaphore workerSemaphore = new Semaphore(workerCount);

	private boolean clientInvokeScheduled;

	public void initialize() {
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
	}

	public int getInflightWorkerCount() {
		int inflightCount = 0;
		for (int i = 0; i < workerCount; i++) {
			if (workers[i].inflight.get())
				inflightCount++;
		}
		return inflightCount;
	}

	void signalWorkAvailable(int workCount) {
		int availPermits = workerSemaphore.availablePermits();
		if (availPermits >= workCount)
			return;
		workerSemaphore.release(min(workCount, workCount - availPermits));
	}

	public int getWorkQueueSize() {
		return workQueue.size();
	}

	private void cancelAllWork(BlockingDeque<JobHandle> queue) {
		JobHandle handle;
		while ((handle = queue.poll()) != null) {
			try {
				handle.cancel(false);
				handle.setCompleted();
			} catch (InterruptedException e) {
				log.warn("Interrupted while shutting down worker", e);
				throw new RuntimeException(e);
			}
		}
	}

	public void destroy() {
		active = false;
		cancelAllWork(workQueue);

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

	public boolean hasIdleWorkers() {
		for (Worker worker : workers) {
			if (!worker.inflight.get())
				return true;
		}
		return false;
	}

	public void printWorkersState() {
		log.debug("WorkQueue Size: {}", workQueue.size());
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
				log.warn("Encountered an error whilst processing: {}", item.hashCode(), ex);
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
			if (VALIDATE) log.debug("Handle [{}] Added to queue (Dep Count: {{}})", newHandle, dependencies);
			if (highPriority) {
				workQueue.addFirst(newHandle);
			} else {
				workQueue.addLast(newHandle);
			}
		}

		signalWorkAvailable(1);
	}

	void invokeClientCallback(Runnable callback) throws InterruptedException {
		if (client.isClientThread()) {
			callback.run();
			processPendingClientCallbacks();
			return;
		}

		final ClientCallbackJob clientCallback = ClientCallbackJob.current();
		clientCallback.callback = callback;

		clientCallbacks.add(clientCallback);

		if (!clientInvokeScheduled) {
			clientInvokeScheduled = true;
			clientThread.invoke(() -> {
				clientInvokeScheduled = false;
				processPendingClientCallbacks();
			});
		}

		try {
			clientCallback.semaphore.acquire();
		} catch (InterruptedException e) {
			clientCallbacks.remove(clientCallback);
			throw new InterruptedException();
		}
	}

	public void processPendingClientCallbacks() {
		int size = clientCallbacks.size();
		if (size == 0)
			return;

		ClientCallbackJob pair;
		while (size-- > 0 && (pair = clientCallbacks.poll()) != null) {
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
