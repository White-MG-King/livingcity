package citysim;

import citysim.WarSimulation.ArmyState;
import citysim.WarSimulation.Event;
import citysim.WarSimulation.Order;
import citysim.WarSimulation.Town;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static citysim.CityFrontier.key;
import static citysim.RegressionTests.check;

final class WarRegression {
    private static final WarTerrain.Ground PLAINS = WarTerrain.sample(i -> new WarTerrain.Sample(65, "plains", false));
    private static final WarSimulation.Relation WAR = new WarSimulation.Relation(1, 2, 0, 0);

    static void run() {
        terrainAndCombat();
        interception();
        campaignRestore();
        consolidation();
        relief();
        advanceFront();
        legacyOccupation();
    }

    private static void terrainAndCombat() {
        var northBank = WarTerrain.sample(i -> new WarTerrain.Sample(65, "plains", i / 4 >= 2));
        var southBank = WarTerrain.sample(i -> new WarTerrain.Sample(65, "plains", i / 4 < 2));
        var peaks = WarTerrain.sample(i -> new WarTerrain.Sample(145, "jagged_peaks", false));
        var cliff = WarTerrain.sample(i -> new WarTerrain.Sample(77, "plains", false));
        check(!WarTerrain.crosses(key(0, 0), key(1, 0), k -> k == 0 ? northBank : southBank)
                        && !peaks.land() && !WarTerrain.crosses(0, key(1, 0), k -> k == 0 ? PLAINS : cliff),
                "L1 河岸断口、高峰和陡崖阻断陆路", "行军可以穿过极端地形或错开的岸线");
        var dry = new WarCombat.Force(80, WarCombat.readiness(75, 0.2));
        var supplied = new WarCombat.Force(40, WarCombat.readiness(85, 1));
        var result = WarCombat.resolve(dry, supplied, 24, 1, 1.25);
        check(result.committedA() == 24 && result.committedB() == 24 && result.winner() < 0,
                "L2 参战宽度、补给、组织度与地形共同决定战力", "只按军队总人数结算");
    }

    private static void interception() {
        var board = pair();
        var infantry = formation(1, 1, 2, 0, 2, 24, key(3, 0), key(4, 0));
        var enemy = formation(2, 2, 2, 3, 1, 16, key(1, 3));
        var war = seed(board, List.of(infantry, enemy), List.of());
        war.step(board, 1);
        var advance = army(war, 1);
        check(advance.order() == Order.INTERCEPT && advance.chasing() == 2 && advance.target() == 2
                        && advance.x() == 2 && advance.z() == 1,
                "L3 无主地目视敌军后改变原攻城路线", "仍向东走，或原任务目标丢失");
        int drafted = board.towns.get(0).ledger().population;
        war.step(board, 2);
        var fight = war.view().reports().stream().filter(r -> r.event() == Event.FIELD).toList();
        check(fight.size() == 1 && fight.get(0).fight().winner() == 1 && army(war, 2).order() == Order.RETURN
                        && board.towns.get(0).ledger().population == drafted - fight.get(0).fight().lossA(),
                "L4 同回合反向跨边只结算一次交战和人口战损", "两军擦肩而过或重复扣人口");
        war.step(board, 3);
        check(army(war, 1).chasing() == 0 && army(war, 1).target() == 2 && army(war, 1).order() == Order.MARCH,
                "L5 封锁解除后回到原攻城任务", "胜军永久停在拦截路线上");

        var farBoard = pair();
        var far = seed(farBoard, List.of(infantry, formation(2, 2, 2, 5, 1, 16, key(1, 5))), List.of());
        far.step(farBoard, 1);
        check(army(far, 1).chasing() == 0 && army(far, 1).x() == 3 && army(far, 1).z() == 0,
                "L6 视野之外继续原路线", "隔地图发现了超出视野的敌军");

        var woodedBoard = pair();
        woodedBoard.terrain.put(key(5, 0), WarTerrain.sample(i -> new WarTerrain.Sample(65, "forest", false)));
        var wooded = seed(woodedBoard, List.of(
                formation(1, 1, 4, 0, 2, 20, key(5, 0)),
                formation(2, 2, 5, 0, 1, 20, key(4, 0))), List.of());
        wooded.step(woodedBoard, 1);
        var clash = wooded.view().reports().stream().filter(r -> r.event() == Event.FIELD).findFirst().orElseThrow();
        check(clash.fight().winner() == -1,
                "L16 同兵力对冲时，森林一侧有地形优势", "野外交战没有计入地形");
    }

    private static void campaignRestore() {
        var board = pair();
        board.add(3, 2, 240, 0, 4);
        var war = new WarSimulation();
        check(war.declare(board, 1, 1, 0) == WarSimulation.Action.SAME_NATION
                        && war.declare(board, 1, 2, 0) == WarSimulation.Action.STARTED,
                "L7 发起与记录不同国家间的战争", "接受了内战或漏记战争");
        war.step(board, 1);
        check(war.view().armies().stream().filter(a -> a.order() != Order.HOME).count() == 2 && war.view().guards().size() == 3
                        && board.towns.get(0).ledger().workforce() == 96 - 24 - 12
                        && board.towns.get(0).ledger().mouthsAtHome() == 96 - 24,
                "L8 先留驻防并从同一人口与粮仓动员", "军役未占用工时或军粮重复计算");
        var copy = pair();
        copy.add(3, 2, 240, 0, 4);
        for (int i = 0; i < 2; i++) {
            var book = copy.towns.get(i).ledger();
            book.population = board.towns.get(i).ledger().population;
            book.foodPoints = board.towns.get(i).ledger().foodPoints;
        }
        var loaded = WarSimulation.restore(copy, 1, war.save());
        check(loaded.view().equals(war.view()) && copy.towns.get(0).ledger().workforce() == board.towns.get(0).ledger().workforce(),
                "L15 恢复时立即还原驻防和军役", "需要先推进一回合才找回驻防名单");
        for (int cycle = 2; cycle <= 22; cycle++) {
            loaded.step(copy, cycle);
            war.step(board, cycle);
        }
        check(war.save().equals(loaded.save()) && copy.towns.get(0).ledger().population == board.towns.get(0).ledger().population
                        && copy.towns.get(1).ledger().foodPoints == board.towns.get(1).ledger().foodPoints,
                "L9 途中存档与连续推进产生同一结果", "改道、战损或粮草重载后分叉");
        check(war.truce(board, 1, 2, 22) == WarSimulation.Action.ENDED
                        && war.declare(board, 2, 1, 23) == WarSimulation.Action.TRUCE && war.view().occupations().isEmpty(),
                "L10 停战撤军并阻止当回合反向宣战", "战区或停战时限未生效");
        for (int cycle = 23; cycle <= 45; cycle++) war.step(board, cycle);
        check(war.view().armies().size() == 3 && war.view().armies().stream().allMatch(a -> a.order() == Order.HOME)
                        && war.view().guards().isEmpty()
                        && board.towns.stream().allMatch(t -> t.ledger().mouthsAtHome() == t.ledger().population),
                "L11 停战后常备军归队，由本城供养", "停止战争后编制丢失或口粮重复扣除");
    }

    private static void consolidation() {
        var board = new Board();
        board.add(1, 1, -4, 0, 80);
        board.add(2, 2, 0, 0, 15);
        board.add(3, 1, 4, 0, 80);
        board.towns.get(1).ledger().foodPoints = 0;
        var war = seed(board, List.of(
                formation(1, 1, -1, 0, 2, 20, key(0, 0)),
                formation(3, 1, 1, 0, 2, 20, key(0, 0))), List.of());
        war.step(board, 1);
        var fights = war.view().reports().stream().filter(r -> r.event() == Event.CITY).toList();
        check(fights.size() == 1 && fights.get(0).fight().committedA() == 40 && fights.get(0).fight().committedB() > 0
                        && war.view().occupations().get(0).progress() == 1,
                "L12 同轮抵达同一城市的友军合并进攻", "多个入境动作重复消耗守军或直接易主");
        for (int cycle = 2; cycle <= WarSimulation.SECURE_CYCLES; cycle++) war.step(board, cycle);
        check(war.view().occupations().isEmpty() && board.towns.get(1).nation() == 1
                        && war.view().reports().stream().filter(r -> r.event() == Event.CAPTURED).count() == 1,
                "L13 持续驻扎才完成一次易主", "没有占领倒计时或没有转交城池");
    }

    private static void relief() {
        var board = new Board();
        board.add(1, 1, 0, 0, 80);
        board.add(2, 2, 10, 0, 10);
        board.add(3, 1, 2, 3, 35);
        var war = seed(board, List.of(formation(1, 1, 1, 0, 2, 20, key(2, 0), key(3, 0))),
                List.of(new WarSimulation.Control(3, 2, WarSimulation.SECURE_CYCLES)));
        war.step(board, 1);
        check(army(war, 1).target() == 3 && war.view().reports().stream().anyMatch(r -> r.event() == Event.RELIEF),
                "L14 失守城市让正在出征的军队改道回援", "仍优先向旧敌城前进");
    }

    private static void advanceFront() {
        var board = new Board();
        board.add(1, 1, 0, 0, 100);
        board.add(2, 2, 5, 0, 4);
        board.add(3, 2, 9, 0, 4);
        board.add(4, 2, 240, 0, 4);
        board.land(2, 4, 0);
        board.terrain.put(key(5, 0), WarTerrain.BLOCKED);
        var war = new WarSimulation();
        war.reconcile(board, 0);
        int ruler = officer(war, OfficerBook.Role.KING, 2).id();
        int general = officer(war, OfficerBook.Role.GENERAL, 1).id();
        check(war.view().officers().size() == 10 && army(war, 1).order() == Order.HOME && army(war, 1).soldiers() == 25,
                "L17 和平时也有国王、市长和常备将军", "只有宣战后才出现官员或常备军");
        war.declare(board, 1, 2, 0);
        int cycle = 1;
        for (; cycle <= 60 && board.towns.get(2).nation() != 1; cycle++) war.step(board, cycle);
        var taken = war.view().reports().stream().filter(r -> r.event() == Event.CAPTURED).toList();
        check(taken.stream().map(WarSimulation.Report::city).toList().equals(List.of(2, 3))
                        && war.view().reports().stream().anyMatch(r -> r.event() == Event.ADVANCING && r.other() == 3)
                        && board.towns.get(1).nation() == 1,
                "L18 从可达边界连续攻下两城", "需要走到被封锁的市政厅或占一城后停止");
        var king = officer(war, OfficerBook.Role.KING, 2);
        check(king.id() == ruler && king.city() == 4 && officer(war, OfficerBook.Role.GENERAL, 1).id() == general,
                "L19 失去首都迁都，国王和在役将军保留同一个编号", "迁都或出征刷新了官员");
        var restored = WarSimulation.restore(board, cycle, war.save());
        war.truce(board, 1, 2, cycle);
        check(restored.view().officers().equals(war.view().officers()) && board.towns.get(1).nation() == 1
                        && board.towns.get(2).nation() == 1,
                "L20 官员和实际归属可跨档，停战保留已易主城市", "停战复位成旧国家或档案消失");
    }

    private static void legacyOccupation() {
        var board = pair();
        var war = seed(board, List.of(), List.of(new WarSimulation.Control(2, 1, WarSimulation.SECURE_CYCLES)));
        war.step(board, 1);
        check(board.towns.get(1).nation() == 1 && war.view().relations().isEmpty()
                        && war.view().reports().stream().filter(r -> r.event() == Event.CAPTURED).count() == 1
                        && war.view().officers().stream().noneMatch(p -> p.nation() == 2),
                "L21 迁移旧快照已完成的占领并结束灭国战争", "旧占领仍循环，灭国仍在外交列表");
    }

    private static OfficerBook.Marker officer(WarSimulation model, OfficerBook.Role role, int district) {
        return model.view().officers().stream()
                .filter(p -> p.role() == role && (role == OfficerBook.Role.KING ? p.nation() : p.city()) == district)
                .findFirst().orElseThrow();
    }

    private static WarSimulation.ArmyView army(WarSimulation war, int city) {
        return war.view().armies().stream().filter(a -> a.city() == city).findFirst().orElseThrow();
    }

    private static Board pair() {
        var board = new Board();
        board.add(1, 1, 0, 0, 96);
        board.add(2, 2, 10, 0, 64);
        return board;
    }

    private static ArmyState formation(int city, int nation, int x, int z, int goal, int soldiers, Long... route) {
        return new ArmyState(city, nation, key(x, z), goal, 0, soldiers, 100, soldiers * 30,
                1, Order.MARCH, 0, 0, List.of(route));
    }

    private static WarSimulation seed(Board board, List<ArmyState> armies, List<WarSimulation.Control> occupations) {
        return WarSimulation.restore(board, 0,
                new WarSimulation.State(List.of(WAR), armies, occupations, List.of(), List.of()));
    }

    private static final class Board implements WarSimulation.World {
        final List<Town> towns = new ArrayList<>();
        final Map<Long, Integer> owners = new HashMap<>();
        final Map<Long, WarTerrain.Ground> terrain = new HashMap<>();

        void add(int id, int nation, int x, int z, int population) {
            long hall = key(x, z);
            var ledger = new CityLedger(id, new CityConfig());
            ledger.population = population;
            ledger.foodPoints = 10_000;
            owners.put(hall, id);
            towns.add(new Town(id, nation, hall, Arrays.stream(CityFrontier.neighbors(hall)).boxed().toList(), ledger));
        }

        void land(int id, int x, int z) {
            owners.put(key(x, z), id);
            for (int i = 0; i < towns.size(); i++) {
                Town town = towns.get(i);
                if (town.id() != id) continue;
                Collection<Long> exterior = owners.entrySet().stream().filter(entry -> entry.getValue() == id)
                        .flatMap(entry -> Arrays.stream(CityFrontier.neighbors(entry.getKey())).boxed())
                        .filter(next -> cityAt(next) != id).distinct().toList();
                towns.set(i, new Town(id, town.nation(), town.hall(), exterior, town.ledger()));
                return;
            }
        }

        @Override public Collection<Town> towns() { return towns; }
        @Override public int cityAt(long chunk) { return owners.getOrDefault(chunk, 0); }
        @Override public WarTerrain.Ground ground(long chunk) { return terrain.getOrDefault(chunk, PLAINS); }
        @Override public int capital(int nation) {
            return towns.stream().filter(town -> town.nation() == nation).mapToInt(Town::id).min().orElse(0);
        }
        @Override public boolean transferCity(int city, int from, int to) {
            for (int i = 0; i < towns.size(); i++) {
                Town old = towns.get(i);
                if (old.id() != city || old.nation() != from) continue;
                towns.set(i, new Town(old.id(), to, old.hall(), old.exterior(), old.ledger()));
                return true;
            }
            return false;
        }
    }
}
