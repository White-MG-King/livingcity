package com.livingcity.data;

import citysim.PortTrade;
import citysim.PortTrade.Voyage;
import citysim.SeaLanes.Cell;
import com.google.gson.Gson;
import com.livingcity.web.CityMapTerrain;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The main-world adapter uses the same demand, prices, deadlines and voyages as the offline market. */
public final class CityTrade {
    private static final Gson JSON = new Gson();
    private static final long INITIAL_COINS = 100L * PortTrade.COIN;
    public record Site(int x, int z) {}
    public record Port(Site land, Cell berth) {}
    public record Procurement(int units, int deadline, Long localCost, Integer localCycles, String decision) {}
    public record AccountView(Port port, double portWork, boolean operating, long coins, long escrow,
                              int incoming, int reserved, int aboard, Procurement procurement) {}
    public record Sailing(Voyage voyage, Cell position, String phase) {}
    public record State(long nextId, Map<Integer, Long> coins, List<Voyage> voyages, List<Voyage> history) {}
    private record Bid(CityData city, PortTrade.Demand demand) {}

    private final Map<Integer, Long> coins = new LinkedHashMap<>();
    private final Map<Integer, Procurement> procurement = new LinkedHashMap<>();
    private final Map<Long, Voyage> active = new LinkedHashMap<>();
    private final Map<Long, com.livingcity.entity.TradeShipEntity> ships = new LinkedHashMap<>();
    private final ArrayDeque<Voyage> history = new ArrayDeque<>();
    private long nextId = 1;

    private long balance(int city) { return coins.getOrDefault(city, INITIAL_COINS); }
    private Voyage vessel(int city) {
        return active.values().stream().filter(v -> v.buyer() == city || v.seller() == city).findFirst().orElse(null);
    }

    public void advance(ServerWorld world, CityRegistry registry) {
        long now = registry.worldCycle();
        var arrivals = active.values().iterator();
        while (arrivals.hasNext()) {
            var voyage = arrivals.next();
            var buyer = registry.byId(voyage.buyer());
            var seller = registry.byId(voyage.seller());
            if (!buyer.land.portOperating(buyer) || !seller.land.portOperating(seller) || Boolean.FALSE.equals(loadedWater(world, voyage.position(now)))) {
                buyer.harness().city.incomingStone = 0;
                seller.harness().city.stone += voyage.units();
                coins.put(buyer.id, balance(buyer.id) + voyage.price() + voyage.freight());
                buyer.harness().city.chronicle.log(buyer.harness().city.dayCount, "港口或航道受阻 · 航次撤销并退回货款与货物");
                arrivals.remove();
            } else if (now >= voyage.arrival()) {
                buyer.harness().city.stone += voyage.units();
                buyer.harness().city.incomingStone = 0;
                coins.put(seller.id, balance(seller.id) + voyage.price());
                buyer.harness().city.chronicle.log(buyer.harness().city.dayCount, "航次 #" + voyage.id() + " · 石材到港");
                history.addFirst(voyage);
                if (history.size() > 16) history.removeLast();
                arrivals.remove();
            }
        }
        procurement.clear();
        var demands = new ArrayList<Bid>();
        var ports = registry.cities().stream().filter(city -> city.isFounded() && city.land.portOperating(city)).toList();
        for (var city : ports) {
            var demand = PortTrade.request(city.harness().city);
            if (demand != null) demands.add(new Bid(city, demand));
        }
        demands.sort(Comparator.comparingInt((Bid b) -> b.demand().priority()).thenComparingInt(b -> b.demand().deadline())
                .thenComparingInt(b -> b.city().id));
        int routeRequests = 0;
        for (var bid : demands) {
            var buyer = bid.city();
            var demand = bid.demand();
            var offers = new ArrayList<PortTrade.Supply>();
            boolean busy = vessel(buyer.id) != null, pending = false;
            if (!busy) for (var seller : ports) {
                if (buyer == seller || vessel(seller.id) != null) continue;
                int available = PortTrade.exportableStone(seller.harness().city);
                if (available < demand.units()) continue;
                var from = buyer.land.portSite().berth();
                var to = seller.land.portSite().berth();
                if (Math.abs(from.x() - to.x()) + Math.abs(from.z() - to.z()) >= PortTrade.MAX_ROUTE_TILES) continue;
                if (routeRequests++ >= 16) { pending = true; break; }
                var route = CityMapTerrain.seaRoute(world, from, to);
                if (route == null) { pending = true; continue; }
                if (!route.isEmpty()) offers.add(new PortTrade.Supply(seller.id, available, PortTrade.reservePrice(demand.units(), available), route));
            }
            var quote = PortTrade.choose(demand.units(), Math.min(PortTrade.ORDER_LIMIT, balance(buyer.id)),
                    demand.replacementBudget(), demand.deadline(), offers);
            String decision;
            if (quote != null && !pending) {
                var voyage = PortTrade.depart(nextId++, buyer.id, demand, quote, now);
                buyer.harness().city.incomingStone = voyage.units();
                registry.byId(voyage.seller()).harness().city.stone -= voyage.units();
                coins.put(buyer.id, balance(buyer.id) - quote.landed());
                active.put(voyage.id(), voyage);
                buyer.harness().city.chronicle.log(buyer.harness().city.dayCount, "采购成交 · 航次 #" + voyage.id());
                decision = "采购成交 · 航次 #" + voyage.id();
            } else decision = busy ? "泊位忙碌" : pending ? "正在预计算航道"
                    : offers.isEmpty() ? "本地开采 · 无连通且有余货的港口" : "本地开采 · 报价或船期超过本地方案";
            procurement.put(buyer.id, new Procurement(demand.units(), demand.deadline(), demand.localCost(), demand.localCycles(), decision));
        }
        registry.markDirty();
    }

    private static Boolean loadedWater(ServerWorld world, Cell cell) {
        int x = cell.x() * 4 + 2, z = cell.z() * 4 + 2;
        var chunk = world.getChunkManager().getWorldChunk(x >> 4, z >> 4);
        if (chunk == null) return null;
        int y = chunk.sampleHeightmap(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, x & 15, z & 15);
        return chunk.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z)).getFluidState()
                .isIn(net.minecraft.registry.tag.FluidTags.WATER);
    }

    public void syncShips(ServerWorld world, long now) {
        var visible = new java.util.HashSet<Long>();
        for (var voyage : active.values()) {
            if (visible.size() >= 32) break;
            Cell cell = voyage.position(now);
            if (!Boolean.TRUE.equals(loadedWater(world, cell))) continue;
            var ship = ships.get(voyage.id());
            boolean spawn = ship == null || ship.isRemoved();
            if (spawn) {
                ship = new com.livingcity.entity.TradeShipEntity(com.livingcity.entity.LivingCityEntities.TRADE_SHIP, world);
                ships.put(voyage.id(), ship);
            }
            int x = cell.x() * 4 + 2, z = cell.z() * 4 + 2;
            var chunk = world.getChunkManager().getWorldChunk(x >> 4, z >> 4);
            int y = chunk.sampleHeightmap(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, x & 15, z & 15);
            ship.refreshPositionAndAngles(x + .5, y + .65, z + .5, ship.getYaw(), 0);
            ship.setVelocity(0, 0, 0);
            if (spawn && !world.spawnEntity(ship)) {
                ships.remove(voyage.id());
                ship.discard();
                continue;
            }
            visible.add(voyage.id());
        }
        ships.entrySet().removeIf(entry -> {
            if (visible.contains(entry.getKey())) return false;
            entry.getValue().discard();
            return true;
        });
    }

    public AccountView view(CityData city, long now) {
        var site = city.land.portSite();
        var port = site == null ? null : new Port(new Site(site.entrance().getX(), site.entrance().getZ()), site.berth());
        var voyage = vessel(city.id);
        boolean buying = voyage != null && voyage.buyer() == city.id;
        boolean selling = voyage != null && !buying;
        return new AccountView(port, city.harness().city.portWork, city.land.portOperating(city), balance(city.id),
                buying ? voyage.price() + voyage.freight() : 0, city.harness().city.incomingStone,
                selling && now < voyage.pickup() ? voyage.units() : 0, selling && now >= voyage.pickup() ? voyage.units() : 0,
                procurement.get(city.id));
    }

    public List<Sailing> voyages(long now) {
        var result = new ArrayList<Sailing>();
        for (var voyage : active.values()) result.add(new Sailing(voyage, voyage.position(now), now < voyage.pickup() ? "outbound" : "returning"));
        for (var voyage : history) result.add(new Sailing(voyage, null, "delivered"));
        return result;
    }

    void write(NbtCompound root) {
        root.putByteArray("trade", JSON.toJson(new State(nextId, coins, List.copyOf(active.values()), List.copyOf(history)))
                .getBytes(StandardCharsets.UTF_8));
    }

    void read(CityRegistry registry, NbtCompound root) {
        var state = saved(root);
        if (state.voyages().size() > registry.cities().size()) throw new IllegalArgumentException("贸易航次数量无效");
        nextId = state.nextId();
        for (var entry : state.coins().entrySet()) {
            if (registry.byId(entry.getKey()) == null || entry.getValue() < 0) throw new IllegalArgumentException("贸易账户无效");
            coins.put(entry.getKey(), entry.getValue());
        }
        var occupied = new java.util.HashSet<Integer>();
        for (var voyage : state.voyages()) {
            if (!validVoyage(voyage, nextId) || registry.byId(voyage.buyer()) == null || registry.byId(voyage.seller()) == null
                    || !occupied.add(voyage.buyer()) || !occupied.add(voyage.seller()) || active.putIfAbsent(voyage.id(), voyage) != null) {
                throw new IllegalArgumentException("贸易航次无效");
            }
        }
        for (var voyage : state.history()) {
            if (!validVoyage(voyage, nextId)) throw new IllegalArgumentException("贸易历史无效");
            history.add(voyage);
        }
    }

    public static boolean validVoyage(Voyage voyage, long nextId) {
        if (voyage == null || voyage.id() < 1 || voyage.id() >= nextId || voyage.buyer() == voyage.seller()
                || voyage.units() <= 0 || voyage.units() > citysim.CityLedger.BUILD_STONE
                || voyage.price() < 0 || voyage.freight() < 0 || voyage.price() + voyage.freight() > PortTrade.ORDER_LIMIT
                || voyage.route() == null || voyage.route().isEmpty() || voyage.route().size() > PortTrade.MAX_ROUTE_TILES
                || voyage.departure() < 0 || voyage.departure() >= voyage.pickup() || voyage.pickup() >= voyage.arrival()) return false;
        for (int i = 1; i < voyage.route().size(); i++) {
            Cell before = voyage.route().get(i - 1), after = voyage.route().get(i);
            if (before == null || after == null || Math.abs(before.x() - after.x()) + Math.abs(before.z() - after.z()) != 1) return false;
        }
        return voyage.route().get(0) != null;
    }

    public static State saved(NbtCompound root) {
        if (!root.contains("trade", 7)) return new State(1, Map.of(), List.of(), List.of());
        var bytes = root.getByteArray("trade");
        if (bytes.length > 4 * 1024 * 1024) throw new IllegalArgumentException("贸易存档过大");
        var state = JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), State.class);
        if (state == null || state.nextId() < 1 || state.coins() == null || state.voyages() == null || state.history() == null
                || state.history().size() > 16) throw new IllegalArgumentException("贸易存档无效");
        return state;
    }
}
