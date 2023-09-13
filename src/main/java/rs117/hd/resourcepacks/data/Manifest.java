package rs117.hd.resourcepacks.data;

import java.util.ArrayList;
import lombok.Data;

@Data
public class Manifest {
	private boolean hasIcon = false;
	private String displayName = "";
	private String internalName = "";
	private ArrayList<String> tags = new ArrayList<>();
	private String commit = "";

	private String support = "";
	private String author = "";
	private String description = "";
	private String link = "";

	public Manifest(String name, String description, String author) {
		this.displayName = name;
		this.internalName = name.toLowerCase().replace(" ", "_");
		this.author = author;
		this.description = description;
	}

	private String version = "";
	private Boolean dev = false;

	public boolean hasIcon() {
		return hasIcon;
	}

	public String getDisplayName() {
		if (displayName == null || displayName.isEmpty())
			return getInternalName();
		return displayName;
	}

	public String getInternalName() {
		return internalName;
	}

	public ArrayList<String> getTags() {
		return tags;
	}

	public String getCommit() {
		return commit;
	}

	public String getSupport() {
		return support;
	}

	public String getAuthor() {
		return author;
	}

	public String getDescription() {
		return description;
	}

	public String getLink() {
		return link;
	}

	public String getVersion() {
		return version;
	}

	public Boolean isDevelopmentPack() {
		return dev;
	}
}
