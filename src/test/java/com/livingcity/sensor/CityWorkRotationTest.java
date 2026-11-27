package com.livingcity.sensor;

public final class CityWorkRotationTest {
    public static void run() {
        for (int size : new int[]{1, 3, 4, 5, 25, 100}) {
            var rotation = new CityWorkRotation();
            var planning = new java.util.HashSet<Integer>();
            var resources = new java.util.HashSet<Integer>();
            for (int tick = 0; tick < size; tick++) {
                planning.add(rotation.nextPlanner(size));
                resources.add(rotation.nextResource(size));
                // A full ring of writer visits must not reset the next planning slot.
                for (int writes = 0; writes < Math.max(4, size); writes++) rotation.nextWriter(size);
            }
            if (planning.size() != size || resources.size() != size) {
                throw new AssertionError("Planning or resource discovery starved with " + size + " cities");
            }
            for (int tick = 0; tick < size; tick++) rotation.nextResource(size);
            if (rotation.nextPlanner(size) != 0) throw new AssertionError("Resource work moved the planning cursor");
        }
        System.out.println("CITY ROTATION PASS: every city receives planning and resources independently of writing and guard pauses");
    }
}
