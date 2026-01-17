package rs117.hd.renderer.zone;

import com.google.inject.Injector;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AsyncUploadData {
	public static final int BUFFER_COUNT = 4;

	@Inject
	SceneUploader sceneUploader;

	@Inject
	FacePrioritySorter facePrioritySorter;

	public final ReentrantLock syncLock = new ReentrantLock();
	public final FacePrioritySorter.SortedFaces sortedFaces = new FacePrioritySorter.SortedFaces();
	public final FacePrioritySorter.SortedFaces unsortedFaces = new FacePrioritySorter.SortedFaces();

	private final AsyncCachedModel[] models;
	public final ConcurrentLinkedDeque<AsyncCachedModel> freeModels = new ConcurrentLinkedDeque<>();

	AsyncUploadData(boolean createModelCache, Injector injector) {
		if(createModelCache) {
			models = new AsyncCachedModel[BUFFER_COUNT];
			for (int i = 0; i < BUFFER_COUNT; i++) {
				models[i] = new AsyncCachedModel(this);
				freeModels.add(models[i]);
			}
		} else {
			models = null;
		}

		injector.injectMembers(this);
	}

	public void waitForCompletion() {
		for(int i = 0; i < models.length; i++)
			models[i].waitForCompletion();
	}
}
