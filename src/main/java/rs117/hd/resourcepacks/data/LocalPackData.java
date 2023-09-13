package rs117.hd.resourcepacks.data;

import lombok.Data;

@Data
public class LocalPackData {
	private String commitHash;
	private String internalName;

	public LocalPackData(String commit, String internalName, int i) {
		this.commitHash = commit;
		this.internalName = internalName;
	}
}
