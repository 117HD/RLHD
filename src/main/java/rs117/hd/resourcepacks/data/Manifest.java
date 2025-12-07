package rs117.hd.resourcepacks.data;

import com.google.common.html.HtmlEscapers;
import java.util.ArrayList;
import lombok.Data;
import org.apache.commons.text.WordUtils;

@Data
public class Manifest {
	private boolean hasIcon = false;
	private boolean hasCompactIcon = false;
	private String displayName = "";
	private String internalName = "";
	private ArrayList<String> tags = new ArrayList<>();
	private String commit = "";

	private String support = "";
	private String author;
	private String description;
	private String link = "";
	private PackType packType;

	public Manifest(String name, String description, String author) {
		this.displayName = name;
		this.internalName = name.toLowerCase().replace(" ", "_");
		this.author = author;
		this.description = description;
		this.packType = PackType.RESOURCE;
	}

	private String version = "";
	private Boolean dev = false;
	private Long fileSize = null;

	private transient String renderDescription = null;

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

	public PackType getPackType() {
		return packType != null ? packType : PackType.RESOURCE;
	}

	public boolean isResourcePack() {
		return getPackType() == PackType.RESOURCE;
	}

	public boolean isAddonPack() {
		return getPackType() == PackType.ADDON;
	}

	public String getTooltipText() {
		if (renderDescription != null) {
			return renderDescription;
		}

		if (description == null || description.isEmpty()) {
			return null;
		}

		String plain = HtmlEscapers.htmlEscaper().escape(description).trim();
		if (plain.isEmpty()) {
			return null;
		}

		String wrapped = WordUtils.wrap(plain, 40, "<br>", true);

		renderDescription = "<html>" + wrapped + "</html>";
		return renderDescription;
	}
}
