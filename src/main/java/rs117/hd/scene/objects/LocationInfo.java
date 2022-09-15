package rs117.hd.scene.objects;

import com.google.gson.annotations.JsonAdapter;
import rs117.hd.data.ObjectID;
import rs117.hd.utils.AABB;

import java.util.HashSet;

public class LocationInfo {
    @JsonAdapter(ObjectID.JsonAdapter.class)
    public HashSet<Integer> objectIds = new HashSet<>();
    @JsonAdapter(AABB.JsonAdapter.class)
    public AABB[] aabbs = {};
}
