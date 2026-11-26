package com.livingcity.sensor;

/** 每个存档独立的城市时钟。余数只表示距下一完整周期的进度。 */
public final class CityClock {
    public static final int TICKS_PER_CYCLE = 1200;
    private static final int[] SPEEDS = {1, 5, 10, 1000};
    private static final long BUDGET_NANOS = 2_000_000L;
    private int speed = 1;
    private boolean stepRequested;
    private int remainder;
    private long cooldownTicks;
    private int sampleTicks;
    private int sampleUnits;
    private double effectiveSpeed = 1;
    private boolean sampled;

    public int speed() { return speed; }
    public int remainder() { return remainder; }
    public double effectiveSpeed() { return effectiveSpeed; }
    public boolean sampled() { return sampled; }

    public int nextSpeed() {
        for (int value : SPEEDS) if (value > speed) return value;
        return SPEEDS[0];
    }

    public boolean setSpeed(int requested) {
        if (requested == 0) {
            speed = 0;
            sampleTicks = sampleUnits = 0;
            effectiveSpeed = 0;
            sampled = false;
            return true;
        }
        for (int value : SPEEDS) {
            if (value == requested) {
                if (speed != value) {
                    speed = value;
                    stepRequested = false;
                    sampleTicks = sampleUnits = 0;
                    effectiveSpeed = value;
                    sampled = false;
                }
                return true;
            }
        }
        return false;
    }

    public void restore(int requested, int fraction) {
        if (!setSpeed(requested)) setSpeed(1);
        remainder = Math.max(0, Math.min(TICKS_PER_CYCLE - 1, fraction));
    }

    public boolean requestStep() {
        if (speed != 0 || stepRequested) return false;
        stepRequested = true;
        return true;
    }

    public boolean tick() {
        if (speed == 0) {
            boolean due = stepRequested;
            stepRequested = false;
            return due;
        }
        int units = speed;
        if (cooldownTicks > 0) {
            cooldownTicks--;
            units = 1;
        }
        sampleUnits += units;
        if (++sampleTicks == 20) {
            effectiveSpeed = sampleUnits / (double) sampleTicks;
            sampled = true;
            sampleTicks = sampleUnits = 0;
        }
        // 最高档小于 1200，每 tick 至多一个完整周期；降档不会遗留整周期债务。
        remainder += units;
        if (remainder < TICKS_PER_CYCLE) return false;
        remainder -= TICKS_PER_CYCLE;
        return true;
    }

    public void recordCycleCost(long nanos) {
        if (speed > 1 && nanos > BUDGET_NANOS) {
            cooldownTicks = (nanos - 1) / BUDGET_NANOS;
        }
    }
}
