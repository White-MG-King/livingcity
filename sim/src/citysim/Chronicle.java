package citysim;

import java.util.ArrayList;
import java.util.List;

/**
 * 城市编年史（缺口对照 #11，V1）。每个硬事件、落成、移民潮、死亡追加一行带时间戳记录。
 * 无头端只存文本行；MC 侧由市政厅 GUI 滚动展示（M4）。
 */
public class Chronicle {
    public static class Entry {
        public final int day;
        public final String text;
        Entry(int day, String text) { this.day = day; this.text = text; }
        @Override public String toString() { return "第 " + day + " 天，" + text; }
    }

    private final List<Entry> entries = new ArrayList<>();

    public void log(int day, String text) {
        entries.add(new Entry(day, text));
    }

    public List<Entry> entries() { return entries; }

    public int size() { return entries.size(); }

    /** 是否含指定子串的记录（回归用例断言用）。 */
    public boolean contains(String substring) {
        for (Entry e : entries) if (e.text.contains(substring)) return true;
        return false;
    }
}
