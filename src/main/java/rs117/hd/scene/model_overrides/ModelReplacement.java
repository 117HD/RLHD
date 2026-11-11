package rs117.hd.scene.model_overrides;

import java.util.HashSet;
import java.util.Set;
import lombok.Data;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.Model;
import rs117.hd.model.modelreplaceer.types.objects.ModelDefinition;

@Data
public class ModelReplacement {
	public String model;
	public transient ModelDefinition definition;
	public Set<String> themes = new HashSet<>();

	public Key key(int objectId, int config) {
		return new Key(definition, objectId, config & 0xFF);
	}

	@Value
	public static class Key {
		ModelDefinition definition;
		int objectId;
		int config;

		public Model load(Client client) {
			return definition.getModel(client, objectId, config & 0x3F, config >> 6);
		}
	}
}