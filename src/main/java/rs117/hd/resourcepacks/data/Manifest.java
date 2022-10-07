package rs117.hd.resourcepacks.data;

import lombok.Data;

import java.util.ArrayList;

@Data
public class Manifest {
    boolean hasIcon = false;
    String internalName = "";
    ArrayList<String> tags = new ArrayList<>();
    String commit = "";
    String support = "";
    String author = "";
    String description = "";
    String link = "";
    String version = "";
}