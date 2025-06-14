package rs117.hd.data;

public enum GameValType {
	NPC("npcs.json"),
	OBJECT("objects.json"),
	ANIM("anims.json"),
	SPOTANIM("spotanim.json");

	private final String fileName;

	GameValType(String fileName) {
		this.fileName = fileName;
	}

	public String getFileName() {
		return fileName;
	}
}
