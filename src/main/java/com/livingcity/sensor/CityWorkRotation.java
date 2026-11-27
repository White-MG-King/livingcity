package com.livingcity.sensor;

/** Planning and resource discovery must advance independently of the number of write visits. */
public final class CityWorkRotation {
    private long planner, resource, writer;

    public int nextPlanner(int size) { return (int) Math.floorMod(planner++, size); }
    public int nextResource(int size) { return (int) Math.floorMod(resource++, size); }
    public int nextWriter(int size) { return (int) Math.floorMod(writer++, size); }
}
