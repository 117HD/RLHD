package rs117.hd.scene.objects;

import com.google.gson.annotations.JsonAdapter;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.AABB;

import java.util.HashSet;

public class LocationInfo {
    @JsonAdapter(Light.ObjectIDAdapter.class)
    public HashSet<Integer> objectIds = new HashSet<>();
    @JsonAdapter(AABB.JsonAdapter.class)
    public AABB[] aabbs = {};
}