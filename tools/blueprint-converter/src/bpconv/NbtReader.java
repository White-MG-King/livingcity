package bpconv;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * T1：手写 NBT 二进制解析（零第三方依赖，NBT 规范：大端序、根为具名 TAG_Compound）。
 * gzip 魔数 0x1F8B 自动检测解压。
 * 标签模型：Compound=Map&lt;String,Object&gt;，List=List&lt;Object&gt;，
 * 数值=Byte/Short/Integer/Long/Float/Double，数组=byte[]/int[]/long[]，字符串=String。
 */
public final class NbtReader {

    public static Map<String, Object> readRoot(byte[] raw) throws IOException {
        InputStream in = new ByteArrayInputStream(raw);
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B)
            in = new GZIPInputStream(in);
        DataInputStream d = new DataInputStream(in);
        int type = d.readUnsignedByte();
        if (type != 10) throw new IOException("NBT 根标签不是 TAG_Compound: type=" + type);
        d.readUTF(); // 根标签名（通常为空串）
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) readPayload(d, 10);
        return root;
    }

    private static Object readPayload(DataInputStream d, int type) throws IOException {
        switch (type) {
            case 1: return d.readByte();
            case 2: return d.readShort();
            case 3: return d.readInt();
            case 4: return d.readLong();
            case 5: return d.readFloat();
            case 6: return d.readDouble();
            case 7: { int n = d.readInt(); byte[] a = new byte[n]; d.readFully(a); return a; }
            case 8: return d.readUTF();
            case 9: {
                int elemType = d.readUnsignedByte();
                int n = d.readInt();
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(readPayload(d, elemType));
                return list;
            }
            case 10: {
                Map<String, Object> m = new LinkedHashMap<>();
                while (true) {
                    int t = d.readUnsignedByte();
                    if (t == 0) break;
                    String name = d.readUTF();
                    m.put(name, readPayload(d, t));
                }
                return m;
            }
            case 11: { int n = d.readInt(); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = d.readInt(); return a; }
            case 12: { int n = d.readInt(); long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = d.readLong(); return a; }
            default: throw new IOException("未知 NBT 标签类型: " + type);
        }
    }

    // ---- 类型安全取值助手（缺键抛带路径的 IOException，方便定位格式差异） ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Object o, String path) throws IOException {
        if (!(o instanceof Map)) throw new IOException("期望 Compound，实际 " + typeName(o) + "，路径: " + path);
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o, String path) throws IOException {
        if (!(o instanceof List)) throw new IOException("期望 List，实际 " + typeName(o) + "，路径: " + path);
        return (List<Object>) o;
    }

    public static Object require(Map<String, Object> m, String key, String path) throws IOException {
        Object v = m.get(key);
        if (v == null) throw new IOException("缺少键 '" + key + "'，路径: " + path);
        return v;
    }

    public static int intOf(Object o, String path) throws IOException {
        if (o instanceof Number) return ((Number) o).intValue();
        throw new IOException("期望数值，实际 " + typeName(o) + "，路径: " + path);
    }

    public static long longOf(Object o, String path) throws IOException {
        if (o instanceof Number) return ((Number) o).longValue();
        throw new IOException("期望数值，实际 " + typeName(o) + "，路径: " + path);
    }

    public static String stringOf(Object o, String path) throws IOException {
        if (o instanceof String) return (String) o;
        throw new IOException("期望 String，实际 " + typeName(o) + "，路径: " + path);
    }

    public static long[] longArrayOf(Object o, String path) throws IOException {
        if (o instanceof long[]) return (long[]) o;
        throw new IOException("期望 LongArray，实际 " + typeName(o) + "，路径: " + path);
    }

    public static byte[] byteArrayOf(Object o, String path) throws IOException {
        if (o instanceof byte[]) return (byte[]) o;
        throw new IOException("期望 ByteArray，实际 " + typeName(o) + "，路径: " + path);
    }

    /** List<Int> 三元素 → int[3]（size/pos 通用）。 */
    public static int[] intTriple(Object o, String path) throws IOException {
        List<Object> l = list(o, path);
        if (l.size() != 3) throw new IOException("期望 3 元素 List，实际 " + l.size() + "，路径: " + path);
        return new int[]{intOf(l.get(0), path + "[0]"), intOf(l.get(1), path + "[1]"), intOf(l.get(2), path + "[2]")};
    }

    /**
     * 兼容多种写法的三元组 → int[3]：Compound{x,y,z}（真实 Litematica 的 Position/Size，
     * 小写键，2026-11-10 经真实素材验证；Size 可为负 = 圈选方向）、Compound{X,Y,Z}
     * 或 List<Int>[3]（部分第三方工具的写法）。
     */
    public static int[] intTripleEither(Object o, String path) throws IOException {
        if (o instanceof Map) {
            Map<String, Object> m = compound(o, path);
            return new int[]{axisOf(m, "X", path), axisOf(m, "Y", path), axisOf(m, "Z", path)};
        }
        return intTriple(o, path);
    }

    /** Compound 里取一个坐标轴：大写键优先，回落小写（真实 Litematica 用小写）。 */
    private static int axisOf(Map<String, Object> m, String upper, String path) throws IOException {
        Object v = m.get(upper);
        if (v == null) v = m.get(upper.toLowerCase());
        if (v == null) throw new IOException("缺少键 '" + upper + "/" + upper.toLowerCase() + "'，路径: " + path);
        return intOf(v, path + "." + upper);
    }

    private static String typeName(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }
}
