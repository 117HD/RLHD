# GadScape's 117 HD data

Files here (`areas.json`, `environments.json`, `lights.json`, `tile_overrides.json`, `model_overrides.json`, same
format as 117 HD's files one folder up) are loaded on top of 117 HD's own (`rs117.hd.scene.GadScapeData`): their
entries come first, and an area, tile override (same name) or environment (same area) here replaces 117 HD's.
117 HD's own files stay as upstream has them, so updates from 117HD/RLHD merge cleanly.

The GadScape map editor's Tools > 117 HD window writes them. Object and NPC ids can be numbers (GadScape's custom
objects have no gameval names).
