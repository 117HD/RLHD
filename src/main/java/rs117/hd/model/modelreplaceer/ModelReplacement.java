package rs117.hd.model.modelreplaceer;

import com.google.gson.annotations.JsonAdapter;
import java.util.HashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.GamevalManager;

@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class ModelReplacement
{
	public static final ModelReplacement NONE = new ModelReplacement();
	private static final Set<Integer> EMPTY = new HashSet<>();
	public String description = "UNKNOWN";
	public ModelStore model;
	public Set<String> themes = new HashSet<>();
	public String time = "";
	@JsonAdapter(GamevalManager.ObjectAdapter.class)
	public Set<Integer> objectIds = EMPTY;
}
