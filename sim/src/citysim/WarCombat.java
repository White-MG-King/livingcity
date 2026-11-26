package citysim;

/** Deterministic combat for equal inputs, regardless of city order or Minecraft tick rate. */
public final class WarCombat {
    public record Force(int soldiers, double readiness) {}
    public record Result(int winner, int committedA, int committedB, double powerA, double powerB,
                         int lossA, int lossB, int fatigueA, int fatigueB) {}

    private WarCombat() {}

    public static double readiness(int organization, double supply) {
        return Math.max(0, Math.min(100, organization)) / 100.0 * (0.35 + 0.65 * Math.max(0, Math.min(1, supply)));
    }

    public static Result resolve(Force a, Force b, int width, double coverA, double coverB) {
        int frontA = Math.min(a.soldiers(), width), frontB = Math.min(b.soldiers(), width);
        double powerA = frontA * a.readiness() * coverA, powerB = frontB * b.readiness() * coverB;
        double total = powerA + powerB;
        double pressureA = total > 0 ? powerB / total : 0;
        double pressureB = total > 0 ? powerA / total : 0;
        int winner = Math.abs(powerA - powerB) < 0.001 ? 0 : (powerA > powerB ? 1 : -1);
        return new Result(winner, frontA, frontB, powerA, powerB, loss(frontA, pressureA), loss(frontB, pressureB),
                (int) Math.round(40 * pressureA), (int) Math.round(40 * pressureB));
    }

    private static int loss(int front, double pressure) {
        if (front == 0 || pressure <= 0) return 0;
        return Math.max(1, (int) Math.round(front * (0.04 + 0.28 * pressure)));
    }
}
