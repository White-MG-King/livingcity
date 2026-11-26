package citysim;

public class Debug {
    public static void main(String[] args) {
        CityConfig cfg = new CityConfig();
        cfg.birthChance = 0;
        cfg.immigrantChance = 0;
        SimHarness h = new SimHarness(99, cfg);
        CityLedger city = h.city;
        city.population = 30;
        city.placeExisting(BuildingKind.HOUSE, 32);
        city.placeExisting(BuildingKind.WELL, 0);
        city.unlocked.add(BuildingKind.PLAZA);
        city.satisfaction[LayerId.HYGIENE] = 0.9;
        city.foodPoints = 100;
        city.activeProject = new CityLedger.Project(BuildingKind.PLAZA, 1, cfg.plazaBuildHours);
        city.activeProject.paidHours = 200;
        h.injectEvent(SimEvent.harshWinter(100, cfg.harshWinterFarmEff));

        for (int i = 1; i <= 65; i++) {
            PlannerCycle.step(city);
            if (i % 5 == 0 || i <= 3) {
                Thermometer.Snapshot s = Thermometer.read(city);
                CityLedger.Project p = city.activeProject;
                System.out.println("cyc=" + city.cycleCount
                        + " hard=" + (city.hardEvent == null ? "-" : LayerId.name(city.hardEvent.layer))
                        + " snapHard=" + s.hardLayer
                        + " gapFood=" + String.format("%.1f", s.gap[0])
                        + " eff=" + s.farmEff
                        + " events=" + city.activeEvents.size()
                        + " pop=" + city.population
                        + " stock=" + String.format("%.0f", city.foodPoints)
                        + " proj=" + (p == null ? "null" : p.kind + "/paid=" + p.paidHours + "/paused=" + p.paused)
                        + " well=" + city.buildings.get(1).state);
            }
        }
        for (Chronicle.Entry e : city.chronicle.entries()) System.out.println("  史: " + e);
    }
}
