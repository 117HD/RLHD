package rs117.hd.resourcepacks.data;

import lombok.Data;

import java.util.ArrayList;

@Data
public class Manifest {
    private boolean hasIcon = false;
    private String internalName = "";
    private ArrayList<String> tags = new ArrayList<>();
    private String commit = "";

    private String support = "";
    private String author = "";
    private String description = "";
    private String link = "";

    public Manifest(String name,String description, String author) {
        this.internalName = name.toLowerCase().replace(" ", "_");
        this.author = author;
        this.description = description;
    }

    public Manifest() {}

    private String version = "";
    private Boolean dev = false;

    public boolean isHasIcon() {
        return hasIcon;
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

    public Boolean getDev() {
        return dev;
    }


}