package citysim;

import java.util.ArrayList;
import java.util.List;

/** Read-only evaluation for city inspectors. Uses the planner's current scoring rules. */
public record CityNeedsReport(int priority, String candidateKind, int candidateCount, List<Need> layers) {
    public record Need(int id, double satisfaction, double gap, double weight, boolean tracked) {}

    public static CityNeedsReport read(CityLedger city) {
        var snapshot = Thermometer.read(city);
        var winner = MeansSelection.selectWinner(city, snapshot);
        int priority = snapshot.hardLayer >= 0 ? snapshot.hardLayer : winner == null ? -1 : winner.layer;
        List<Need> needs = new ArrayList<>(LayerId.COUNT);
        for (int id = 0; id < LayerId.COUNT; id++) {
            boolean tracked = id != LayerId.SAFETY && id != LayerId.ORDER
                    && (id != LayerId.SPIRIT || city.config.spiritFromPlazaRatio > 0);
            double weight = tracked && !LayerId.isHard(id) && snapshot.gap[id] > 0 ? MeansSelection.weight(city, id) : 0;
            needs.add(new Need(id, city.satisfaction[id], snapshot.gap[id], weight, tracked));
        }
        return new CityNeedsReport(priority, winner == null ? "" : winner.kind.name(),
                winner == null ? 0 : winner.n, List.copyOf(needs));
    }
}
