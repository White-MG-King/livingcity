package citysim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Price and timing rules shared by headless adapters. Money uses hundredths of a standard work hour. */
public final class PortTrade {
    public static final int COIN = 100;
    public static final int RESERVE = CityLedger.BUILD_STONE;
    public static final long ORDER_LIMIT = 60L * COIN;
    private static final int HANDLING = 4;
    private static final int MIN_DELIVERY_WINDOW = 8;
    public static final int MAX_ROUTE_TILES = ((int) (ORDER_LIMIT / COIN) - HANDLING) * 2;

    public record Demand(int units, int priority, int deadline, Long localCost, Integer localCycles) {
        public long replacementBudget() {
            return localCycles != null && localCycles <= deadline ? localCost : ORDER_LIMIT;
        }
    }

    public record Supply(int seller, int units, long floor, List<SeaLanes.Cell> route) {
        public int legCycles() { return Math.max(1, (route.size() + 2) / 4); }
        public int deliveryCycles() { return legCycles() * 2 + 1; }
        public long freight() { return (HANDLING + route.size() / 2L) * COIN; }
    }

    public record Quote(Supply supply, long ceiling, long price, int rivals) {
        public long landed() { return price + supply.freight(); }
    }

    public record Voyage(long id, int buyer, int seller, int units, long price, long freight, long floor, long ceiling,
                         int rivals, long departure, long pickup, long arrival, long latest, Long localCost,
                         List<SeaLanes.Cell> route) {
        public SeaLanes.Cell position(long now) {
            if (now >= arrival) return route.get(0);
            if (now >= pickup) {
                long traveled = (now - pickup) * (route.size() - 1) / (arrival - pickup);
                return route.get(route.size() - 1 - (int) traveled);
            }
            return route.get((int) ((now - departure) * (route.size() - 1) / (pickup - departure)));
        }
    }

    private PortTrade() {}

    public static Demand request(CityLedger city) {
        CityLedger.Project project = city.activeProject;
        if (!city.portActive() || city.population <= 0 || city.incomingStone > 0 || city.stone >= CityLedger.BUILD_STONE
                || project == null || project.paused || (city.hardEvent != null && city.hardEvent.layer == LayerId.FOOD)
                || city.timber < CityLedger.BUILD_TIMBER && !city.forestAvailable) return null;
        int units = (int) Math.ceil(CityLedger.BUILD_STONE - city.stone);
        double workToNextBuilding = (project.delivered + 1) * project.kind.buildHours(city.config) - project.paidHours;
        int deadline = Math.max(MIN_DELIVERY_WINDOW, (int) Math.ceil(workToNextBuilding / city.config.sliceCap));
        boolean operating = city.quarryActive();
        double setup = operating ? 0 : Math.max(0, CityLedger.QUARRY_HOURS - city.quarryWork);
        double woodToGather = operating ? 0 : Math.max(0, CityLedger.QUARRY_TIMBER - city.timber);
        double production = units * CityLedger.QUARRY_HOURS_PER_STONE;
        double cost = setup + production + (operating ? 0 : CityLedger.QUARRY_TIMBER);
        var snapshot = Thermometer.read(city);
        double upkeep = city.buildings.stream().filter(b -> b.state == BuildingEntry.State.ACTIVE)
                .mapToDouble(b -> b.kind.maintPerCycle(city.config)).sum();
        double capacity = Math.min(CityLedger.INDUSTRY_HOUR_LIMIT,
                (snapshot.laborPool - snapshot.farmHoursNeeded - city.config.minLightHours - upkeep) * .5);
        boolean canMine = capacity > 0 && (woodToGather == 0 || city.forestAvailable);
        double scheduledHours = setup + units * CityLedger.QUARRY_CAPACITY_DIVISOR + woodToGather;
        return new Demand(units, project.kind.primaryLayer(), deadline, canMine ? Math.round(cost * COIN) : null,
                canMine ? (int) Math.ceil(scheduledHours / capacity) : null);
    }

    public static int exportableStone(CityLedger city) {
        if (!city.portActive() || city.population <= 0) return 0;
        int ownProject = city.activeProject == null ? 0 : CityLedger.BUILD_STONE;
        return Math.max(0, (int) Math.floor(city.stone) - RESERVE - ownProject);
    }

    public static long reservePrice(int units, int exportable) {
        double floor = units * CityLedger.QUARRY_HOURS_PER_STONE + 1
                + Math.max(0, RESERVE - (exportable - units)) * .25;
        return Math.round(floor * COIN);
    }

    public static Quote choose(int quantity, long funds, long localCost, int deadline, List<Supply> offers) {
        List<Supply> feasible = new ArrayList<>();
        for (Supply offer : offers) {
            long ceiling = Math.min(funds, localCost) - offer.freight();
            if (offer.units() >= quantity && !offer.route().isEmpty() && offer.deliveryCycles() <= deadline
                    && ceiling - offer.floor() >= 2) feasible.add(offer);
        }
        return feasible.stream().map(offer -> {
            long ceiling = Math.min(funds, localCost) - offer.freight();
            long surplus = ceiling - offer.floor();
            int rivals = feasible.size() - 1;
            long margin = Math.max(1, Math.min(surplus - 1, Math.round(surplus * (.25 + .40 / (1 + rivals)))));
            return new Quote(offer, ceiling, offer.floor() + margin, rivals);
        }).min(Comparator.comparingLong(Quote::landed).thenComparingInt(q -> q.supply().deliveryCycles())
                .thenComparingInt(q -> q.supply().seller())).orElse(null);
    }

    public static Voyage depart(long id, int buyer, Demand demand, Quote quote, long now) {
        Supply supply = quote.supply();
        return new Voyage(id, buyer, supply.seller(), demand.units(), quote.price(), supply.freight(), supply.floor(),
                quote.ceiling(), quote.rivals(), now, now + supply.legCycles() + 1,
                now + supply.deliveryCycles(), now + demand.deadline(), demand.localCost(), supply.route());
    }
}
