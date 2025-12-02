package rs117.hd.utils.jobs;

import com.google.inject.Injector;
import java.util.HashMap;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimer;

import static rs117.hd.HdPlugin.PROCESSOR_COUNT;
import static rs117.hd.utils.MathUtils.*;

@Singleton
@Slf4j
public final class JobSystem {
	public static final boolean VALIDATE = false;

	public static JobSystem INSTANCE;

	@Inject
	public HdPlugin plugin;

	@Inject
	public Client client;

	@Inject
	public ClientThread clientThread;

	@Inject
	public FrameTimer frametimer;

	@Inject
	public Injector injector;

	@Getter
	protected boolean active;

	protected final BlockingDeque<JobHandle> workQueue = new LinkedBlockingDeque<>();
	private final BlockingDeque<JobClientCallback> clientCallbacks = new LinkedBlockingDeque<>();

	private final int workerCount = max(2, PROCESSOR_COUNT - 1);
	private final HashMap<Thread, JobWorker> threadToWorker = new HashMap<>();
	protected JobWorker[] workers;

	private boolean clientInvokeScheduled;

	public void initialize() {
		INSTANCE = this;
		workers = new JobWorker[workerCount];
		active = true;

		for(int i = 0; i < workerCount; i++) {
			JobWorker newWorker = workers[i] = new JobWorker(i);
			newWorker.thread = new Thread(newWorker::run);
			newWorker.thread.setPriority(Thread.NORM_PRIORITY + 1);
			newWorker.thread.setName("117HD - Worker " + i);
			threadToWorker.put(newWorker.thread, newWorker);
		}

		for(int i = 0; i < workerCount; i++)
			workers[i].thread.start();
	}

	public int getInflightWorkerCount() {
		int inflightCount = 0;
		for(int i = 0; i < workerCount; i++) {
			if(workers[i].inflight.get())
				inflightCount++;
		}
		return inflightCount;
	}

	public int getWorkQueueSize() {
		return workQueue.size();
	}

	@SneakyThrows
	public void shutdown() {
		active = false;
		workQueue.clear();

		for(JobWorker worker : workers) {
			worker.localWorkQueue.clear();
			worker.thread.interrupt();
			if(worker.handle != null)
				worker.handle.cancel(true);
		}

		int workerShutdownCount = 0;
		for(JobWorker worker : workers) {
			if(!worker.thread.isAlive()) {
				workerShutdownCount++;
				continue;
			}

			worker.thread.join(1000);

			if (worker.thread.isAlive()) {
				log.warn("Worker {} didn't shutdown within a timely manner", worker.thread.getName());
				worker.printState();
			} else {
				workerShutdownCount++;
			}
		}

		if(workerShutdownCount == workerCount)
			log.debug("All workers shutdown successfully");

		threadToWorker.clear();
		workers = null;
	}

	public boolean isWorker() {
		return threadToWorker.containsKey(Thread.currentThread());
	}

	public boolean hasIdleWorkers() {
		for(JobWorker worker : workers) {
			if(!worker.inflight.get())
				return true;
		}
		return false;
	}

	public void printWorkersState() {
		log.debug("WorkQueue Size: {}", workQueue.size());
		for(JobWorker worker : workers)
			worker.printState();
	}

	protected void queue(JobWork item, boolean highPriority, JobWork... dependencies) {
		if(!item.executeAsync) {
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
		for(JobWork dep : dependencies) {
			if(dep == null || dep.handle == null) continue;
			if(dep.handle.addDependant(newHandle)) {
				shouldQueue = false;
			}
		}

		item.queued.set(true);
		item.done.set(false);
		item.wasCancelled.set(false);
		item.ranToCompletion.set(false);

		if(shouldQueue) {
			newHandle.setInQueue();
			if(VALIDATE) log.debug("Handle [{}] Added to queue (Dep Count: {{}})", newHandle, dependencies);
			if (highPriority) {
				workQueue.addFirst(newHandle);
			} else {
				workQueue.addLast(newHandle);
			}
		}
	}

	protected void queueClientCallback(boolean immediate, Runnable callback) throws InterruptedException {
		if(client.isClientThread()) {
			callback.run();
			processPendingClientCallbacks(false);
			return;
		}

		final JobClientCallback newItem = JobClientCallback.obtain();
		newItem.callback = callback;
		newItem.immediate = immediate;

		if(immediate)
			clientCallbacks.addFirst(newItem);
		else
			clientCallbacks.addLast(newItem);

		if(!clientInvokeScheduled) {
			clientInvokeScheduled = true;
			clientThread.invoke(() -> {
				clientInvokeScheduled = false;
				processPendingClientCallbacks(false);
			});
		}

		try {
			newItem.sema.acquire();
		}catch (InterruptedException e) {
			clientCallbacks.remove(newItem);
			throw new InterruptedException();
		} finally {
			newItem.release();
		}
	}

	public void processPendingClientCallbacks() {
		processPendingClientCallbacks(true);
	}

	public void processPendingClientCallbacks(boolean immediateOnly) {
		int size = clientCallbacks.size();
		if (size == 0)
			return;

		JobClientCallback pair;
		while(size-- > 0 && (pair = clientCallbacks.poll()) != null) {
			if(!pair.immediate && immediateOnly) {
				clientCallbacks.addLast(pair); // Add it back onto the end
				continue;
			}

			try {
				pair.callback.run();
			} catch (Throwable ex) {
				log.warn("Encountered exception whilst processing client callback", ex);
			} finally {
				pair.sema.release();
			}
		}
	}
}
