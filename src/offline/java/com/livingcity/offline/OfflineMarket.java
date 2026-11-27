package com.livingcity.offline;

import citysim.CityLedger;
import citysim.PortTrade;
import citysim.PortTrade.Demand;
import citysim.PortTrade.Supply;
import citysim.PortTrade.Voyage;
import citysim.SeaLanes.Cell;
import com.livingcity.offline.OfflineWorld.Port;
import com.livingcity.offline.OfflineWorld.Town;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OfflineMarket {
    static final long INITIAL_COINS = 100 * PortTrade.COIN;
    static final int HISTORY_LIMIT = 16;

    record Procurement(int units, int deadline, Long localCost, Integer localCycles, String decision) {}
    record AccountView(Port port, double portWork, boolean operating, long coins, long escrow,
                       int incoming, int reserved, int aboard, Procurement procurement) {}
    record Sailing(Voyage voyage, Cell position, String phase) {}

    private record Bid(Town buyer, Demand demand) {}

    final Map<Long, Voyage> active = new LinkedHashMap<>();
    final ArrayDeque<Voyage> history = new ArrayDeque<>();
    long nextId = 1;

    void importMain(OfflineWorld world, com.livingcity.data.CityTrade.State saved) {
        nextId = saved.nextId();
        for (var town : world.towns) {
            town.coins = saved.coins().getOrDefault(town.id, INITIAL_COINS);
            if (town.coins < 0) throw new IllegalArgumentException("贸易账户无效");
        }
        var occupied = new java.util.HashSet<Integer>();
        for (var voyage : saved.voyages()) {
            if (!com.livingcity.data.CityTrade.validVoyage(voyage, nextId) || town(world, voyage.buyer()).port == null
                    || town(world, voyage.seller()).port == null || !occupied.add(voyage.buyer()) || !occupied.add(voyage.seller())) {
                throw new IllegalArgumentException("贸易航次无效");
            }
            active.put(voyage.id(), voyage);
        }
        for (var voyage : saved.history()) {
            if (!com.livingcity.data.CityTrade.validVoyage(voyage, nextId)) throw new IllegalArgumentException("贸易历史无效");
            history.add(voyage);
        }
    }

    void advance(OfflineWorld world) {
        var arrivals = active.values().iterator();
        while (arrivals.hasNext()) {
            Voyage voyage = arrivals.next();
            if (voyage.arrival() > world.cycle) continue;
            Town buyer = town(world, voyage.buyer());
            Town seller = town(world, voyage.seller());
            buyer.sim.city.stone += voyage.units();
            buyer.sim.city.incomingStone = 0;
            seller.coins += voyage.price();
            buyer.sim.city.chronicle.log(buyer.sim.city.dayCount, "航次 #" + voyage.id() + " 石材到港");
            history.addFirst(voyage);
            if (history.size() > HISTORY_LIMIT) history.removeLast();
            arrivals.remove();
        }

        List<Bid> demands = new ArrayList<>();
        for (Town town : world.towns) {
            town.procurement = null;
            Demand demand = PortTrade.request(town.sim.city);
            if (demand != null) demands.add(new Bid(town, demand));
        }
        demands.sort(Comparator.comparingInt((Bid bid) -> bid.demand().priority()).thenComparingInt(bid -> bid.demand().deadline())
                .thenComparingInt(bid -> bid.buyer().id));
        for (Bid bid : demands) match(world, bid.buyer(), bid.demand());
    }

    private void match(OfflineWorld world, Town buyer, Demand demand) {
        List<Supply> offers = new ArrayList<>();
        boolean busy = vessel(buyer.id) != null;
        if (!busy) {
            for (Town seller : world.towns) {
                if (seller == buyer || vessel(seller.id) != null) continue;
                int available = PortTrade.exportableStone(seller.sim.city);
                if (available < demand.units()) continue;
                List<Cell> route = world.sea().route(buyer.port.berth(), seller.port.berth(), PortTrade.MAX_ROUTE_TILES);
                if (route.isEmpty()) continue;
                offers.add(new Supply(seller.id, available, PortTrade.reservePrice(demand.units(), available), route));
            }
        }
        var quote = PortTrade.choose(demand.units(), Math.min(PortTrade.ORDER_LIMIT, buyer.coins),
                demand.replacementBudget(), demand.deadline(), offers);
        String decision;
        if (quote != null) {
            Voyage voyage = PortTrade.depart(nextId++, buyer.id, demand, quote, world.cycle);
            Town seller = town(world, voyage.seller());
            buyer.sim.city.incomingStone = voyage.units();
            buyer.coins -= quote.landed();
            seller.sim.city.stone -= voyage.units();
            active.put(voyage.id(), voyage);
            decision = "采购成交 · 航次 #" + voyage.id();
        } else {
            String reason = busy ? "泊位忙碌" : offers.isEmpty() ? "无连通且有余货的卖家"
                    : offers.stream().allMatch(o -> o.deliveryCycles() > demand.deadline()) ? "船期超过用料期限"
                    : "报价超过预算或本地方案";
            decision = (demand.localCost() == null ? "待料" : "本地开采") + " · " + reason;
        }
        buyer.procurement = new Procurement(demand.units(), demand.deadline(), demand.localCost(), demand.localCycles(), decision);
    }

    private Voyage vessel(int id) {
        for (Voyage v : active.values()) {
            if (id == v.buyer() || id == v.seller()) return v;
        }
        return null;
    }

    AccountView view(Town town, long now) {
        CityLedger city = town.sim.city;
        Voyage v = vessel(town.id);
        boolean buying = v != null && v.buyer() == town.id;
        boolean selling = v != null && !buying;
        return new AccountView(town.port, city.portWork, city.portActive(), town.coins,
                buying ? v.price() + v.freight() : 0, city.incomingStone, selling && now < v.pickup() ? v.units() : 0,
                selling && now >= v.pickup() ? v.units() : 0, town.procurement);
    }

    List<Sailing> voyages(long now) {
        List<Sailing> result = new ArrayList<>();
        for (Voyage v : active.values()) result.add(new Sailing(v, v.position(now), now < v.pickup() ? "outbound" : "returning"));
        for (Voyage v : history) result.add(new Sailing(v, null, "delivered"));
        return result;
    }

    static Town town(OfflineWorld world, int id) {
        return world.towns.stream().filter(town -> town.id == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("找不到城市 #" + id));
    }
}
