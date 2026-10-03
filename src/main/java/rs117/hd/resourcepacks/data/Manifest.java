package rs117.hd.resourcepacks.data;

import java.util.ArrayList;
import lombok.Data;

@Data
public class Manifest {
	private boolean hasIcon = false;
	private boolean hasCompactIcon = false;
	private boolean hasSettings = false;
	private String displayName = "";
	private String internalName = "";
	private ArrayList<String> tags = new ArrayList<>();
	private String commit = "";

	private String support = "";
	private String author;
	private String description;
	private String link = "";
	private String sha256 = "";

	public Manifest(String name, String description, String author) {
		this.displayName = name;
		this.author = author;
		this.description = description;
	}

	private String version = "";
	private Boolean dev = false;
	private Long fileSize = null;

	public boolean hasIcon() {
		return hasIcon;
	}

	public String getDisplayName() {
		if (displayName == null || displayName.isEmpty())
			return getInternalName();
		return displayName;
	}

	public Boolean isDevelopmentPack() {
		return dev;
	}

	public boolean hasSha256() {
		return sha256 != null && !sha256.isEmpty();
	}

}
