package rs117.hd.scene.model_overrides;

import java.util.HashSet;
import java.util.Set;
import lombok.Data;
import rs117.hd.model.modelreplaceer.ModelStore;

@Data
public class ModelReplacement {
	public ModelStore model;
	public Set<String> themes = new HashSet<>();
}