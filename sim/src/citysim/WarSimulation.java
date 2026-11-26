package citysim;

import citysim.WarTerrain.Ground;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongPredicate;

/** National campaign ledger. Only towns, armies and surface samples participate in a turn. */
public final class WarSimulation {
    public interface World {
        Collection<Town> towns();
        int cityAt(long chunk);
        Ground ground(long chunk);
        int capital(int nation);
        boolean transferCity(int city, int fromNation, int toNation);
    }

    public static final int SIGHT = 4;
    public static final int TRUCE_CYCLES = 80;
    public static final int SECURE_CYCLES = 4;
    private static final int HISTORY_LIMIT = 40;
    private static final int MIN_SORTIE = 2;
    private static final int MAX_ARMY = 512;
    private static final int RECOVERY_CYCLES = 12;
    private static final int RELIEF_CYCLES = 64;
    private static final int REPLANS_PER_TURN = 48;
    private static final int MAX_UNFED = 8;

    public enum Action { STARTED, ENDED, UNKNOWN_NATION, SAME_NATION, ALREADY_AT_WAR, AT_PEACE, TRUCE }
    public enum Order { HOME, MARCH, INTERCEPT, OCCUPY, RETURN, WAIT, SUPPLY }
    public enum Event {
        DECLARED, TRUCE, DISPATCHED, SIGHTED, RELIEF, FIELD, CITY, CAPTURED, RECOVERED, DISBANDED,
        RETURNED, ADVANCING, REFORMED, COUNTRY_FELL
    }
    public record Town(int id, int nation, long hall, Collection<Long> exterior, CityLedger ledger) {}
    public record Relation(int a, int b, long since, long until) {
        public boolean active() { return until == 0; }
    }
    public record Control(int city, int nation, int progress) {}
    public record Loss(int city, int nation, long until) {}
    public record Cooldown(int city, long until) {}
    public record Report(long cycle, Event event, int nation, int opponent, int city, int other,
                         int x, int z, WarCombat.Result fight) {}
    public record Guard(int city, int soldiers) {}
    public record ArmyView(int city, int nation, int x, int z, int soldiers, int organization, double food,
                           int target, int chasing, Order order, Integer nextX, Integer nextZ) {}
    public record View(List<Relation> relations, List<ArmyView> armies, List<Control> occupations,
                       List<Guard> guards, List<Report> reports, List<OfficerBook.Marker> officers, int sight) {}
    public record ArmyState(int city, int nation, long position, int target, int chasing, int soldiers,
                            int organization, double food, double supply, Order order, int unfed,
                            long retryAt, List<Long> route) {}
    public record State(List<Relation> relations, List<ArmyState> armies, List<Control> occupations,
                         List<Cooldown> cooldowns, List<Report> reports, OfficerBook.State officers, List<Loss> recentLosses) {
        public State(List<Relation> relations, List<ArmyState> armies, List<Control> occupations,
                     List<Cooldown> cooldowns, List<Report> reports) {
            this(relations, armies, occupations, cooldowns, reports, null, null);
        }
    }

    private static final class Army {
        final int city;
        final int nation;
        final ArrayDeque<Long> route;
        long position;
        Order order;
        int target, chasing, soldiers, organization, unfed;
        double food, supply;
        long retryAt;

        Army(ArmyState state) {
            city = state.city(); nation = state.nation(); position = state.position();
            target = state.target(); chasing = state.chasing(); soldiers = state.soldiers();
            organization = state.organization(); food = state.food(); supply = state.supply();
            order = state.order(); unfed = state.unfed(); retryAt = state.retryAt();
            route = new ArrayDeque<>(state.route());
        }

        ArmyState save() {
            return new ArmyState(city, nation, position, target, chasing, soldiers, organization, food, supply, order,
                    unfed, retryAt, List.copyOf(route));
        }

        double readiness() { return WarCombat.readiness(organization, supply); }
    }

    private record Move(Army army, long from, long to) {}
    private record Plan(Town target, List<Long> route) {}
    private record Objective(Town town, int tier, int direct) {}
    private record Battle(int city, int nation) {}

    private final Map<Long, Relation> relations = new TreeMap<>();
    private final Map<Integer, Army> armies = new TreeMap<>();
    private final Map<Integer, Control> occupations = new TreeMap<>();
    private final Map<Integer, Loss> recentLosses = new TreeMap<>();
    private final Map<Integer, Long> readyAt = new TreeMap<>();
    private final Map<Integer, Integer> guards = new TreeMap<>();
    private final ArrayDeque<Report> reports = new ArrayDeque<>();
    private final OfficerBook officerBook = new OfficerBook();

    public void reconcile(World world, long cycle) {
        var turn = new Turn(world, cycle);
        syncRoster(turn);
        updateDuties(turn);
    }

    public boolean hostile(int a, int b) {
        Relation relation = relations.get(pair(a, b));
        return relation != null && relation.active();
    }

    private boolean atWar(int nation) {
        return relations.values().stream().anyMatch(p -> p.active() && (p.a() == nation || p.b() == nation));
    }

    public Action declare(World world, int a, int b, long cycle) {
        if (a == b) return Action.SAME_NATION;
        var towns = world.towns();
        if (!hasNation(towns, a) || !hasNation(towns, b)) return Action.UNKNOWN_NATION;
        Relation before = relations.get(pair(a, b));
        if (before != null && before.active()) return Action.ALREADY_AT_WAR;
        if (before != null && before.until() > cycle) return Action.TRUCE;
        relations.put(pair(a, b), new Relation(Math.min(a, b), Math.max(a, b), cycle, 0));
        log(cycle, Event.DECLARED, a, b, 0, 0, 0, null);
        return Action.STARTED;
    }

    public Action truce(World world, int a, int b, long cycle) {
        if (a == b) return Action.SAME_NATION;
        var towns = world.towns();
        if (!hasNation(towns, a) || !hasNation(towns, b)) return Action.UNKNOWN_NATION;
        if (!hostile(a, b)) return Action.AT_PEACE;
        relations.put(pair(a, b), new Relation(Math.min(a, b), Math.max(a, b), cycle, cycle + TRUCE_CYCLES));
        var frame = new Turn(world, cycle);
        occupations.values().removeIf(o -> !hostile(frame.towns.get(o.city()).nation(), o.nation()));
        recentLosses.values().removeIf(loss -> !hostile(loss.nation(), frame.towns.get(loss.city()).nation()));
        for (Army army : armies.values()) {
            if (army.order == Order.HOME || army.order == Order.RETURN) continue;
            var goal = frame.towns.get(army.target);
            if (!atWar(army.nation) || (goal != null && goal.nation() != army.nation
                    && !hostile(army.nation, goal.nation()))) sendHome(army, cycle);
        }
        updateDuties(frame);
        log(cycle, Event.TRUCE, a, b, 0, 0, 0, null);
        return Action.ENDED;
    }

    public void step(World world, long cycle) {
        relations.values().removeIf(p -> !p.active() && p.until() <= cycle);
        readyAt.values().removeIf(until -> until <= cycle);
        recentLosses.values().removeIf(loss -> loss.until() <= cycle);
        var turn = new Turn(world, cycle);
        syncRoster(turn);
        // The first release persisted fully secured cities without transferring them.
        var previouslySettled = occupations.values().stream().filter(c -> c.progress() >= SECURE_CYCLES).toList();
        if (!previouslySettled.isEmpty()) {
            previouslySettled.forEach(c -> occupations.remove(c.city()));
            settleCaptures(turn, previouslySettled);
            turn = new Turn(world, cycle);
            syncRoster(turn);
        }
        restStandingArmies(turn);
        updateDuties(turn);
        if (turn.fighting.isEmpty() && armies.values().stream().allMatch(a -> a.order == Order.HOME)) return;
        // Spread planning failures and recruitment attempts across turns in large wars.
        for (Town town : turn.towns.values()) {
            Army army = armies.get(town.id());
            if (army.order != Order.HOME || !turn.fighting.contains(town.nation()) || frontController(town) != town.nation()
                    || cycle < readyAt.getOrDefault(town.id(), 0L)) continue;
            if (!dispatch(turn, town, army)) readyAt.put(town.id(), cycle + 3 + town.id() % 4);
        }
        for (Army army : armies.values()) if (army.order != Order.HOME) feed(turn, army);
        var positions = new HashMap<Long, List<Army>>();
        for (Army army : armies.values()) {
            if (army.order != Order.RETURN && army.order != Order.HOME && army.soldiers > 0) add(positions, army.position, army);
        }
        var moves = new ArrayList<Move>();
        for (Army army : armies.values()) {
            if (army.soldiers == 0) continue;
            long next = army.order == Order.HOME ? army.position : destination(turn, army, positions);
            moves.add(new Move(army, army.position, next));
        }
        var committed = new HashSet<Integer>();
        encounters(turn, moves, committed);
        siege(turn, moves, committed);
        for (Move move : moves) {
            if (!committed.contains(move.army().city)) move(move);
        }
        var captures = new ArrayList<Control>();
        for (var it = occupations.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            Control next = advanceControl(turn, entry.getValue());
            if (next == null) it.remove();
            else if (next.progress() >= SECURE_CYCLES) {
                captures.add(next);
                it.remove();
            }
            else entry.setValue(next);
        }
        if (!captures.isEmpty()) {
            settleCaptures(turn, captures);
            turn = new Turn(world, cycle);
            syncRoster(turn);
        }
        for (Army army : armies.values()) finishReturn(turn, army);
        updateDuties(turn);
    }

    private void syncRoster(Turn turn) {
        for (Town town : turn.towns.values()) {
            armies.computeIfAbsent(town.id(), id ->
                    formation(town, frontController(town) == town.nation() ? standingSize(town.ledger()) : 0, 100));
        }
        officerBook.reconcile(turn.world, turn.towns.values(), turn.cycle);
    }

    private void restStandingArmies(Turn turn) {
        for (Town town : turn.towns.values()) {
            Army army = armies.get(town.id());
            if (army.order != Order.HOME || frontController(town) != town.nation()) continue;
            army.position = town.hall();
            int capacity = standingSize(town.ledger());
            int recruited = Math.max(1, capacity / 8);
            army.soldiers = Math.min(capacity, army.soldiers + recruited);
            army.organization = Math.min(100, army.organization + 6);
            army.supply = 1;
        }
    }

    private static int standingSize(CityLedger city) {
        return Math.min(MAX_ARMY, city.population / 4);
    }

    private static Army formation(Town town, int soldiers, int organization) {
        return new Army(new ArmyState(town.id(), town.nation(), town.hall(), 0, 0, soldiers,
                organization, 0, 1, Order.HOME, 0, 0, List.of()));
    }

    private final class Turn {
        final World world;
        final long cycle;
        final Map<Integer, Town> towns = new TreeMap<>();
        final Map<Integer, Set<Integer>> neighbors = new HashMap<>();
        final Map<Integer, Set<Long>> entrances = new HashMap<>();
        final Set<Integer> fighting = new HashSet<>();
        int searches = REPLANS_PER_TURN;

        Turn(World world, long cycle) {
            this.world = world;
            this.cycle = cycle;
            for (Town town : world.towns()) towns.put(town.id(), town);
            for (Relation p : relations.values()) {
                if (!p.active()) continue;
                fighting.add(p.a());
                fighting.add(p.b());
            }
        }

        Town at(long key) { return towns.get(world.cityAt(key)); }

        Set<Integer> border(Town town) {
            return neighbors.computeIfAbsent(town.id(), id -> {
                var result = new HashSet<Integer>();
                for (long key : town.exterior()) {
                    Town next = at(key);
                    result.add(next == null ? 0 : controller(next));
                }
                return result;
            });
        }

        Set<Long> entries(Town town) {
            return entrances.computeIfAbsent(town.id(), id -> {
                var edges = new HashSet<Long>();
                for (long exterior : town.exterior()) {
                    for (long next : CityFrontier.neighbors(exterior)) {
                        if (world.cityAt(next) == id) edges.add(next);
                    }
                }
                if (edges.isEmpty()) edges.add(town.hall());
                return edges;
            });
        }

        List<Long> path(Army army, long finish, int limit, int terminalCity) {
            if (limit > SIGHT + 2 && searches-- <= 0) return null;
            return WarTerrain.route(army.position, finish, limit, walkable(army, terminalCity), world::ground);
        }

        List<Long> attackRoute(Army army, Town target) {
            if (world.cityAt(army.position) == target.id()) return List.of();
            if (searches-- <= 0) return null;
            return WarTerrain.route(army.position, entries(target), WarTerrain.MAX_MARCH,
                    walkable(army, target.id()), world::ground);
        }

        LongPredicate walkable(Army army, int terminalCity) {
            int leaving = army.order == Order.RETURN ? world.cityAt(army.position) : 0;
            return key -> {
                Town target = at(key);
                return target == null || frontController(target) == army.nation
                        || target.id() == terminalCity || target.id() == leaving;
            };
        }
    }

    private void updateDuties(Turn turn) {
        guards.clear();
        for (Town town : turn.towns.values()) {
            Army army = armies.get(town.id());
            CityLedger ledger = town.ledger();
            if (army != null) army.soldiers = Math.min(army.soldiers, ledger.population);
            int standing = army == null ? 0 : army.soldiers;
            int keep = 0;
            if (turn.fighting.contains(town.nation()) && frontController(town) == town.nation()
                    && turn.border(town).stream().anyMatch(n -> n != town.nation())) {
                keep = Math.min(Math.max(0, ledger.population / 2 - standing), Math.max(1, ledger.population / 8));
                if (keep > 0) guards.put(town.id(), keep);
            }
            ledger.assignMilitary(standing, keep, army != null && army.order != Order.HOME);
        }
    }

    private boolean dispatch(Turn turn, Town town, Army army) {
        var city = town.ledger();
        if (army.soldiers < MIN_SORTIE || army.organization < 75
                || city.foodPoints <= reserve(city) + army.soldiers * ration(city) * 8) return false;
        Plan plan = choose(turn, army);
        if (plan == null || plan.route().isEmpty()) return false;
        if (!provision(turn, army, town, plan.route().size())) return false;
        assign(army, plan);
        log(turn.cycle, Event.DISPATCHED, army.nation, turn.towns.get(army.target).nation(),
                army.city, army.target, army.position, null);
        return true;
    }

    private boolean provision(Turn turn, Army army, Town store, int steps) {
        double ration = army.soldiers * ration(turn.towns.get(army.city).ledger());
        double needed = ration * Math.max(12, steps * 2 + 8);
        double available = Math.max(0, store.ledger().foodPoints - reserve(store.ledger()));
        double take = Math.min(Math.max(0, needed - army.food), available);
        if (army.food + take < needed) return false;
        store.ledger().foodPoints -= take;
        army.food += take;
        return true;
    }

    private Plan choose(Turn turn, Army army) {
        var candidates = new ArrayList<Objective>();
        for (Town town : turn.towns.values()) {
            int controller = frontController(town);
            if (!hostile(army.nation, controller)) continue;
            int direct = turn.world.cityAt(army.position) == town.id() ? 0 : turn.entries(town).stream()
                    .mapToInt(entry -> WarTerrain.distance(army.position, entry)).min().orElse(WarTerrain.MAX_MARCH + 1);
            if (direct > WarTerrain.MAX_MARCH) continue;
            int tier = reliefTarget(town, army.nation) ? 0 : turn.border(town).contains(army.nation) ? 1 : 2;
            candidates.add(new Objective(town, tier, direct));
        }
        candidates.sort(Comparator.comparingInt(Objective::tier).thenComparingInt(Objective::direct)
                .thenComparingInt(candidate -> candidate.town().id()));
        Plan best = null;
        int tier = 3;
        for (Objective candidate : candidates) {
            if (turn.searches <= 0) break;
            if (best != null && (candidate.tier() > tier || candidate.direct() >= best.route().size())) break;
            var town = candidate.town();
            List<Long> path = turn.attackRoute(army, town);
            if (path != null) {
                best = new Plan(town, path);
                tier = candidate.tier();
            }
        }
        return best;
    }

    private void feed(Turn turn, Army army) {
        var town = turn.towns.get(army.city);
        double cost = army.soldiers * ration(town.ledger());
        if (cost <= 0) return;
        Town camp = turn.at(army.position);
        if (army.food < cost * 3 && camp != null && camp.nation() == army.nation && frontController(camp) == army.nation) {
            provision(turn, army, camp, army.order == Order.RETURN ? army.route.size() : 0);
        }
        army.supply = Math.min(1, army.food / cost);
        army.food = Math.max(0, army.food - cost);
        if (army.supply < 0.99) {
            army.unfed++;
            army.organization = Math.max(0, army.organization - 10);
        } else {
            army.unfed = 0;
            army.organization = Math.min(100, army.organization + 2);
        }
        if (army.order != Order.RETURN && (army.organization < 25 || army.food < cost * 2)) sendHome(army, turn.cycle);
        // After their provisions run out, stranded troops also lose soldiers during the retreat.
        if (army.unfed >= MAX_UNFED) lose(turn, army, Math.max(1, army.soldiers / 8));
    }

    private long destination(Turn turn, Army army, Map<Long, List<Army>> positions) {
        var current = turn.at(army.position);
        if (army.order == Order.RETURN) {
            if (current != null && current.id() == army.city && frontController(current) == army.nation) return army.position;
            if (army.route.isEmpty() && turn.cycle >= army.retryAt) {
                chooseRetreat(turn, army);
                army.retryAt = turn.cycle + 3;
            }
            return follow(turn, army, 0);
        }
        if (!turn.fighting.contains(army.nation)) {
            sendHome(army, turn.cycle);
            return army.position;
        }
        if (army.order == Order.OCCUPY && current != null && occupations.containsKey(current.id())
                && frontController(current) == army.nation) return army.position;
        if (current == null && army.organization >= 25) {
            var seen = visibleEnemies(army, positions);
            for (Army enemy : seen) {
                if (enemy.soldiers == 0 || enemy.order == Order.RETURN) continue;
                var town = turn.at(enemy.position);
                List<Long> path = turn.path(army, enemy.position, SIGHT + 2, town == null ? 0 : town.id());
                if (path == null) continue;
                if (army.chasing != enemy.city) {
                    log(turn.cycle, Event.SIGHTED, army.nation, enemy.nation, army.city, enemy.city, enemy.position, null);
                }
                army.chasing = enemy.city;
                army.route.clear();
                army.order = Order.INTERCEPT;
                return path.isEmpty() ? army.position : path.get(0);
            }
        }
        boolean resuming = army.chasing != 0;
        if (resuming) {
            army.chasing = 0;
            army.retryAt = turn.cycle;
        }
        Town target = turn.towns.get(army.target);
        boolean needsRelief = (target == null || !reliefTarget(target, army.nation))
                && (occupations.values().stream().anyMatch(o -> reliefTarget(turn.towns.get(o.city()), army.nation))
                || recentLosses.values().stream().anyMatch(o -> reliefTarget(turn.towns.get(o.city()), army.nation)));
        boolean validTarget = target != null && hostile(army.nation, frontController(target));
        if (!validTarget) army.route.clear();
        if (validTarget && needsRelief && !army.route.isEmpty() && turn.cycle >= army.retryAt) {
            Plan relief = choose(turn, army);
            army.retryAt = turn.cycle + 5;
            if (relief != null && reliefTarget(relief.target(), army.nation) && army.target != relief.target().id()) {
                assign(army, relief);
                log(turn.cycle, Event.RELIEF, army.nation, frontController(relief.target()), army.city,
                        army.target, army.position, null);
            }
        }
        if (army.route.isEmpty() && turn.cycle >= army.retryAt) {
            List<Long> original = resuming && validTarget && !needsRelief
                    ? turn.attackRoute(army, target) : null;
            Plan plan = original == null ? choose(turn, army) : new Plan(target, original);
            if (plan != null) {
                if (current != null && current.nation() == army.nation && frontController(current) == army.nation
                        && !provision(turn, army, current, plan.route().size())) {
                    army.order = Order.SUPPLY;
                    army.retryAt = turn.cycle + 3;
                    return army.position;
                }
                if (army.target == 0 && current != null) {
                    log(turn.cycle, Event.ADVANCING, army.nation, frontController(plan.target()),
                            army.city, plan.target().id(), army.position, null);
                }
                assign(army, plan);
            } else army.retryAt = turn.cycle + 3;
        }
        if (!army.route.isEmpty()) army.order = Order.MARCH;
        else if (army.order != Order.SUPPLY) army.order = Order.WAIT;
        return follow(turn, army, army.target);
    }

    private List<Army> visibleEnemies(Army army, Map<Long, List<Army>> positions) {
        var result = new ArrayList<Army>();
        int x = CityFrontier.x(army.position), z = CityFrontier.z(army.position);
        for (int dx = -SIGHT; dx <= SIGHT; dx++) {
            for (int dz = -SIGHT + Math.abs(dx); dz <= SIGHT - Math.abs(dx); dz++) {
                var nearby = positions.get(CityFrontier.key(x + dx, z + dz));
                if (nearby == null) continue;
                for (Army other : nearby) if (hostile(army.nation, other.nation)) result.add(other);
            }
        }
        result.sort(Comparator.comparingInt((Army other) -> WarTerrain.distance(army.position, other.position))
                .thenComparingInt(other -> other.city));
        return result;
    }

    private void chooseRetreat(Turn turn, Army army) {
        Town home = turn.towns.get(army.city);
        var path = turn.path(army, home.hall(), WarTerrain.MAX_MARCH, 0);
        if (path != null) army.route.addAll(path);
    }

    private long follow(Turn turn, Army army, int terminalCity) {
        Long next = army.route.peek();
        if (next == null) return army.position;
        if (turn.world.ground(army.position) == WarTerrain.UNKNOWN
                || turn.world.ground(next) == WarTerrain.UNKNOWN) return army.position;
        if (turn.walkable(army, terminalCity).test(next) && WarTerrain.crosses(army.position, next, turn.world::ground)) return next;
        army.route.clear();
        return army.position;
    }

    private void encounters(Turn turn, List<Move> moves, Set<Integer> committed) {
        var endings = new HashMap<Long, List<Move>>();
        for (Move move : moves) add(endings, move.to(), move);
        for (Move left : moves) {
            if (committed.contains(left.army().city) || left.army().order == Order.RETURN || turn.at(left.to()) != null) continue;
            Move right = enemyOf(left, endings.get(left.to()), committed, false);
            if (right == null) right = enemyOf(left, endings.get(left.from()), committed, true);
            if (right == null) continue;
            int defenders = right.army().nation;
            var a = new ArrayList<Army>();
            var b = new ArrayList<Army>();
            a.add(left.army());
            b.add(right.army());
            for (Move join : endings.get(left.to())) {
                Army army = join.army();
                if (join == left || join == right || army.order == Order.RETURN || committed.contains(army.city)) continue;
                if (army.nation == left.army().nation) a.add(army);
                if (army.nation == defenders) b.add(army);
            }
            var ground = turn.world.ground(left.to());
            int width = Math.max(1, ground.width());
            double coverA = left.from() == left.to() ? ground.cover() : 1;
            double coverB = right.from() == right.to() ? ground.cover() : 1;
            if (left.from() == right.to() && left.to() == right.from() && left.from() != left.to()) {
                var opposite = turn.world.ground(right.to());
                width = Math.max(1, Math.min(width, opposite.width()));
                coverA = opposite.cover();
                coverB = ground.cover();
            }
            var result = WarCombat.resolve(force(a, 0, 0), force(b, 0, 0), width, coverA, coverB);
            fight(turn, a, 0, null, result.lossA(), result.fatigueA(), result.winner() <= 0, committed);
            fight(turn, b, 0, null, result.lossB(), result.fatigueB(), result.winner() >= 0, committed);
            log(turn.cycle, Event.FIELD, left.army().nation, defenders, left.army().city, right.army().city, left.to(), result);
        }
    }

    private Move enemyOf(Move left, List<Move> right, Set<Integer> committed, boolean swapping) {
        if (right == null) return null;
        for (Move move : right) {
            Army enemy = move.army();
            if (!committed.contains(enemy.city) && enemy.order != Order.RETURN && hostile(left.army().nation, enemy.nation)
                    && (!swapping || (move.from() == left.to() && move.to() != move.from()))) return move;
        }
        return null;
    }

    private void siege(Turn turn, List<Move> moves, Set<Integer> committed) {
        var attacks = new TreeMap<Battle, List<Move>>(Comparator.comparingInt(Battle::city).thenComparingInt(Battle::nation));
        var defenders = new HashMap<Integer, List<Army>>();
        for (Move move : moves) {
            if (committed.contains(move.army().city) || move.army().order == Order.RETURN) continue;
            Town target = turn.at(move.to());
            if (target == null) continue;
            int nation = move.army().nation;
            if (hostile(nation, frontController(target))) {
                add(attacks, new Battle(target.id(), nation), move);
            } else if (frontController(target) == nation && turn.world.cityAt(move.from()) == target.id()) {
                add(defenders, target.id(), move.army());
            }
        }
        var contested = new HashSet<Integer>();
        for (var entry : attacks.entrySet()) {
            Battle id = entry.getKey();
            var town = turn.towns.get(id.city());
            var attacking = entry.getValue().stream().filter(m -> !committed.contains(m.army().city)).toList();
            if (attacking.isEmpty()) continue;
            if (!contested.add(town.id())) {
                attacking.forEach(m -> committed.add(m.army().city));
                continue;
            }
            int controller = frontController(town);
            var a = attacking.stream().map(Move::army).toList();
            var b = defenders.getOrDefault(town.id(), List.of()).stream()
                    .filter(army -> army.nation == controller && !committed.contains(army.city)).toList();
            int garrison = controller == town.nation() ? guards.getOrDefault(town.id(), 0) : 0;
            double homeSupply = Math.min(1, town.ledger().foodPoints / Math.max(1, town.ledger().population * ration(town.ledger())));
            var entries = attacking.stream().map(Move::to).distinct().map(turn.world::ground).toList();
            int width = Math.max(1, entries.stream().mapToInt(Ground::width).sum());
            double cover = entries.stream().mapToDouble(Ground::cover).average().orElse(1) * 1.12;
            var result = WarCombat.resolve(force(a, 0, 0), force(b, garrison, WarCombat.readiness(100, homeSupply)), width, 1, cover);
            boolean won = result.winner() > 0;
            fight(turn, a, 0, null, result.lossA(), result.fatigueA(), !won, committed);
            fight(turn, b, garrison, town, result.lossB(), result.fatigueB(), won, committed);
            log(turn.cycle, Event.CITY, id.nation(), controller, town.id(), 0, attacking.get(0).to(), result);
            if (!won || a.stream().noneMatch(army -> army.soldiers > 0 && army.order != Order.RETURN)) continue;
            if (town.nation() == id.nation()) {
                occupations.remove(town.id());
                log(turn.cycle, Event.RECOVERED, id.nation(), controller, town.id(), 0, town.hall(), null);
            } else {
                occupations.put(town.id(), new Control(town.id(), id.nation(), 0));
            }
            for (Move advance : attacking) {
                var army = advance.army();
                if (army.soldiers <= 0 || army.order == Order.RETURN) continue;
                move(advance);
                army.order = town.nation() == id.nation() ? Order.MARCH : Order.OCCUPY;
                army.target = town.id();
                army.route.clear();
                army.chasing = 0;
            }
        }
    }

    private Control advanceControl(Turn turn, Control occupation) {
        if (occupation.progress() >= SECURE_CYCLES) return occupation;
        boolean held = armies.values().stream().anyMatch(army -> army.nation == occupation.nation() && army.soldiers > 0
                && army.order == Order.OCCUPY && army.target == occupation.city()
                && turn.world.cityAt(army.position) == occupation.city());
        if (!held) return null;
        return new Control(occupation.city(), occupation.nation(), occupation.progress() + 1);
    }

    private void settleCaptures(Turn turn, List<Control> captures) {
        var defeated = new TreeMap<Integer, Control>();
        for (Control capture : captures) {
            Town town = turn.towns.get(capture.city());
            int prior = town.nation();
            int nation = capture.nation();
            if (!hostile(prior, nation) || !turn.world.transferCity(town.id(), prior, nation)) continue;
            Army oldArmy = armies.get(town.id());
            town.ledger().foodPoints += oldArmy.food;
            Town transferred = new Town(town.id(), nation, town.hall(), town.exterior(), town.ledger());
            armies.put(town.id(), formation(transferred, 0, 35));
            readyAt.put(town.id(), turn.cycle + RECOVERY_CYCLES);
            recentLosses.put(town.id(), new Loss(town.id(), prior, turn.cycle + RELIEF_CYCLES));
            log(turn.cycle, Event.CAPTURED, nation, prior, town.id(), 0, town.hall(), null);
            defeated.put(prior, capture);
            for (Army army : armies.values()) {
                if (army.order == Order.OCCUPY && army.target == town.id() && army.nation == nation) {
                    army.target = 0;
                    army.order = Order.WAIT;
                    army.retryAt = turn.cycle + 1;
                }
            }
        }
        var roster = turn.world.towns();
        defeated.entrySet().removeIf(entry -> hasNation(roster, entry.getKey()));
        if (defeated.isEmpty()) return;
        relations.values().removeIf(p -> defeated.containsKey(p.a()) || defeated.containsKey(p.b()));
        occupations.values().removeIf(o -> defeated.containsKey(o.nation()) || defeated.containsKey(turn.towns.get(o.city()).nation()));
        recentLosses.values().removeIf(loss -> defeated.containsKey(loss.nation()));
        for (var fallen : defeated.entrySet()) {
            var capture = fallen.getValue();
            log(turn.cycle, Event.COUNTRY_FELL, capture.nation(), fallen.getKey(), capture.city(), 0,
                    turn.towns.get(capture.city()).hall(), null);
        }
    }

    private void fight(Turn turn, List<Army> force, int garrison, Town town, int losses, int fatigue,
                       boolean retreat, Set<Integer> committed) {
        int remaining = garrison + force.stream().mapToInt(army -> army.soldiers).sum();
        for (Army army : force) {
            int troops = army.soldiers;
            int loss = remaining == 0 ? 0 : Math.min(troops, (int) Math.round((double) losses * troops / remaining));
            losses -= loss;
            remaining -= troops;
            lose(turn, army, loss);
            army.organization = Math.max(0, army.organization - fatigue);
            committed.add(army.city);
            if (retreat || army.organization < 25) sendHome(army, turn.cycle);
        }
        if (garrison > 0 && losses > 0) town.ledger().loseResidents(Math.min(losses, garrison), "驻防战损");
    }

    private static WarCombat.Force force(List<Army> armies, int guards, double readiness) {
        int soldiers = guards;
        double sum = guards * readiness;
        for (Army army : armies) {
            soldiers += army.soldiers;
            sum += army.soldiers * army.readiness();
        }
        return new WarCombat.Force(soldiers, soldiers == 0 ? 0 : sum / soldiers);
    }

    private static void lose(Turn turn, Army army, int losses) {
        if (losses == 0) return;
        army.soldiers -= losses;
        turn.towns.get(army.city).ledger().loseResidents(losses, "野战军战损");
    }

    private void finishReturn(Turn turn, Army army) {
        if (army.order == Order.HOME) return;
        Town home = turn.towns.get(army.city);
        boolean arrived = turn.world.cityAt(army.position) == home.id() && frontController(home) == army.nation;
        if (army.soldiers > 0 && (army.order != Order.RETURN || !arrived)) return;
        home.ledger().foodPoints += army.food;
        boolean losses = army.soldiers == 0;
        readyAt.put(army.city, turn.cycle + RECOVERY_CYCLES);
        log(turn.cycle, losses ? Event.REFORMED : Event.RETURNED, army.nation, 0, army.city, 0, home.hall(), null);
        army.position = home.hall();
        army.food = 0;
        army.supply = 1;
        army.order = Order.HOME;
        army.target = 0;
        army.chasing = 0;
        army.unfed = 0;
        army.route.clear();
    }

    private int controller(Town town) {
        var occupying = occupations.get(town.id());
        return occupying == null || occupying.progress() < SECURE_CYCLES ? town.nation() : occupying.nation();
    }

    private int frontController(Town town) {
        var occupying = occupations.get(town.id());
        return occupying == null ? town.nation() : occupying.nation();
    }

    private boolean reliefTarget(Town town, int nation) {
        var loss = recentLosses.get(town.id());
        return (town.nation() == nation || loss != null && loss.nation() == nation) && hostile(nation, frontController(town));
    }

    private static void assign(Army army, Plan plan) {
        army.target = plan.target().id();
        army.route.clear();
        army.route.addAll(plan.route());
        army.order = Order.MARCH;
    }

    private static void move(Move move) {
        Army army = move.army();
        army.position = move.to();
        if (move.from() != move.to() && army.route.peek() != null && army.route.peek() == move.to()) army.route.remove();
    }

    private static void sendHome(Army army, long cycle) {
        army.order = Order.RETURN;
        army.chasing = 0;
        army.route.clear();
        army.retryAt = cycle;
    }

    private static double ration(CityLedger ledger) {
        return (double) ledger.config.satietyPerCitizenPerDay / Math.max(1, ledger.config.cyclesPerDay);
    }

    private static double reserve(CityLedger ledger) {
        return ledger.mouthsAtHome() * (double) ledger.config.satietyPerCitizenPerDay;
    }

    private static long pair(int a, int b) { return CityFrontier.key(Math.min(a, b), Math.max(a, b)); }

    private static boolean hasNation(Collection<Town> towns, int nation) {
        return towns.stream().anyMatch(town -> town.nation() == nation);
    }

    private static <K,V> void add(Map<K, List<V>> index, K key, V item) {
        index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
    }

    private void log(long cycle, Event event, int nation, int opponent, int city, int other, long key, WarCombat.Result result) {
        reports.add(new Report(cycle, event, nation, opponent, city, other, CityFrontier.x(key), CityFrontier.z(key), result));
        if (reports.size() > HISTORY_LIMIT) reports.remove();
    }

    public View view() {
        var formations = armies.values().stream().map(army -> {
            Long next = army.route.peek();
            return new ArmyView(army.city, army.nation, CityFrontier.x(army.position), CityFrontier.z(army.position),
                    army.soldiers, army.organization, army.food, army.target, army.chasing, army.order,
                    next == null ? null : CityFrontier.x(next), next == null ? null : CityFrontier.z(next));
        }).toList();
        return new View(List.copyOf(relations.values()), formations, List.copyOf(occupations.values()),
                guards.entrySet().stream().map(e -> new Guard(e.getKey(), e.getValue())).toList(), List.copyOf(reports),
                officerBook.markers(city -> {
                    Army army = armies.get(city);
                    return army == null ? null : army.position;
                }), SIGHT);
    }

    public State save() {
        return new State(List.copyOf(relations.values()), armies.values().stream().map(Army::save).toList(),
                List.copyOf(occupations.values()), readyAt.entrySet().stream().map(e -> new Cooldown(e.getKey(), e.getValue())).toList(),
                List.copyOf(reports), officerBook.save(), List.copyOf(recentLosses.values()));
    }

    public static WarSimulation restore(World world, long cycle, State state) {
        var war = new WarSimulation();
        var turn = war.new Turn(world, cycle);
        var nations = new HashSet<Integer>();
        turn.towns.values().forEach(town -> nations.add(town.nation()));
        for (Relation p : state.relations()) {
            require(p.a() < p.b() && nations.contains(p.a()) && nations.contains(p.b()) && p.since() >= 0 && p.since() <= cycle
                    && (p.active() || p.until() > p.since()));
            require(war.relations.putIfAbsent(pair(p.a(), p.b()), p) == null);
        }
        for (ArmyState a : state.armies()) {
            Town town = turn.towns.get(a.city());
            require(town != null && a.nation() == town.nation() && a.soldiers() >= 0 && a.soldiers() <= MAX_ARMY
                    && (a.soldiers() > 0 || a.order() == Order.HOME)
                    && a.organization() >= 0 && a.organization() <= 100 && Double.isFinite(a.food()) && a.food() >= 0
                    && a.supply() >= 0 && a.supply() <= 1 && a.order() != null && a.unfed() >= 0 && a.retryAt() >= 0
                    && a.route().size() <= WarTerrain.MAX_MARCH && (a.target() == 0 || turn.towns.containsKey(a.target()))
                    && (a.chasing() == 0 || turn.towns.containsKey(a.chasing())));
            long previous = a.position();
            require(validPosition(previous));
            for (long next : a.route()) {
                require(validPosition(next) && WarTerrain.distance(previous, next) == 1);
                previous = next;
            }
            require(war.armies.putIfAbsent(a.city(), new Army(a)) == null);
        }
        for (Control o : state.occupations()) {
            Town town = turn.towns.get(o.city());
            require(town != null && war.hostile(town.nation(), o.nation()) && o.progress() >= 0 && o.progress() <= SECURE_CYCLES);
            require(war.occupations.putIfAbsent(o.city(), o) == null);
        }
        for (Cooldown timer : state.cooldowns()) {
            require(turn.towns.containsKey(timer.city()) && timer.until() >= 0);
            require(war.readyAt.putIfAbsent(timer.city(), timer.until()) == null);
        }
        if (state.recentLosses() != null) {
            for (Loss loss : state.recentLosses()) {
                Town town = turn.towns.get(loss.city());
                require(town != null && war.hostile(town.nation(), loss.nation()) && loss.until() > cycle
                        && war.recentLosses.putIfAbsent(loss.city(), loss) == null);
            }
        }
        require(state.reports().size() <= HISTORY_LIMIT);
        for (Report report : state.reports()) {
            require(report.cycle() >= 0 && report.cycle() <= cycle && report.event() != null);
            if (report.event() == Event.FIELD || report.event() == Event.CITY) {
                var fight = report.fight();
                require(fight != null && Double.isFinite(fight.powerA()) && Double.isFinite(fight.powerB()));
            }
            war.reports.add(report);
        }
        war.officerBook.restore(state.officers(), cycle);
        war.reconcile(world, cycle);
        return war;
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("战事存档数据无效");
    }

    private static boolean validPosition(long key) {
        return Math.abs((long) CityFrontier.x(key)) <= 1875000 && Math.abs((long) CityFrontier.z(key)) <= 1875000;
    }
}
