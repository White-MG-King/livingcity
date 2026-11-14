package com.livingcity.sensor;

import citysim.BuildingEntry;
import citysim.BuildingKind;
import citysim.CityLedger;
import citysim.SimEvent;
import citysim.SimHarness;
import com.livingcity.data.CityData;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 传感总线（MC → 账本的唯一合法路径，见《传感层任务单》§0.2）。
 * 回调里只做一件事：把输入结构体 offer 进队列；业务翻译只在周期边界统一执行一次。
 */
public final class CitySensorBus {

	/**
	 * 输入结构体。锚点三元组 (ax,ay,az) 是表演层任务单 P1 的契约扩展：
	 * REGISTER_BUILDING 登记时把执行人站位（或显式 pos 参数）记进 CityData.buildingAnchors；
	 * ax=ay=az=0 约定为「无锚点」（老存档兼容路径，物化时会跳过该建筑并提示重新 register）。
	 */
	public record Input(Type type, int idA, int idB, String text, int ax, int ay, int az) {
		/** 旧四参便捷重载：无锚点。既有 offer 调用点（winter/fire 等）零改动。 */
		public Input(Type type, int idA, int idB, String text) {
			this(type, idA, idB, text, 0, 0, 0);
		}

		public enum Type {
			REGISTER_BUILDING,  // idA 无（返回新 id）, text=档位名, idB=床数, (ax,ay,az)=锚点
			BED_COUNT_CHANGED,  // idA=建筑条目 id, idB=新床数
			CITIZEN_DEATH,      // idA=物化市民 id, text=死因
			BLOCK_DAMAGED,      // idA=建筑条目 id, idB=结构位 index（M3 启用）
			BLOCK_REPAIRED,     // 同上（M3 启用）
			WINTER_INJECT,      // idA=持续周期数
			FIRE_INJECT         // idA=建筑条目 id
		}
	}

	private static final Queue<Input> QUEUE = new ConcurrentLinkedQueue<>();

	private CitySensorBus() {
	}

	public static void offer(Input in) {
		QUEUE.offer(in);
	}

	/**
	 * 周期边界消费队列。除下方翻译表外禁止加任何逻辑（《扩展纪律》§2）。
	 *
	 * @param data MC 侧空间注册表（建筑锚点写这里——账本零空间数据，见表演层任务单 §0.2）
	 * @return 需要回显给玩家的结果行（如登记后回显账本 id）
	 */
	public static List<String> drainAndApply(SimHarness harness, CityData data) {
		List<String> echo = new ArrayList<>();
		CityLedger city = harness.city;
		Input in;
		while ((in = QUEUE.poll()) != null) {
			switch (in.type()) {
				case REGISTER_BUILDING -> {
					BuildingKind kind;
					try {
						kind = BuildingKind.valueOf(in.text());
					} catch (IllegalArgumentException e) {
						continue;
					}
					BuildingEntry entry = city.placeExisting(kind, in.idB());
					boolean anchored = in.ax() != 0 || in.ay() != 0 || in.az() != 0;
					if (anchored) {
						data.setBuildingAnchor(entry.id, new BlockPos(in.ax(), in.ay(), in.az()));
					}
					echo.add("登记" + kind.displayName + " #" + entry.id + "（" + entry.bedCount + " 床"
							+ (anchored ? "，锚点 " + in.ax() + "," + in.ay() + "," + in.az() : "，无锚点") + "）");
				}
				case BED_COUNT_CHANGED -> {
					BuildingEntry entry = find(city, in.idA());
					if (entry != null && entry.kind == BuildingKind.HOUSE) {
						entry.bedCount = Math.max(0, in.idB());
					}
				}
				case CITIZEN_DEATH -> harness.killCitizen(in.idA(), in.text());
				case BLOCK_DAMAGED -> {
					BuildingEntry entry = find(city, in.idA());
					if (entry != null) entry.markMissing(in.idB());
				}
				case BLOCK_REPAIRED -> {
					BuildingEntry entry = find(city, in.idA());
					if (entry != null) entry.clearMissing(in.idB());
				}
				case WINTER_INJECT -> harness.injectEvent(
						SimEvent.harshWinter(in.idA(), city.config.harshWinterFarmEff));
				case FIRE_INJECT -> harness.injectEvent(SimEvent.fire(in.idA()));
			}
		}
		return echo;
	}

	private static BuildingEntry find(CityLedger city, int id) {
		for (BuildingEntry b : city.buildings) {
			if (b.id == id) return b;
		}
		return null;
	}
}
