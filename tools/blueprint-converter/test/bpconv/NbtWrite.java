package bpconv;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 测试专用：把 Map/List/基本类型模型序列化为 NBT 字节（与 NbtReader 的模型互为镜像）。
 * 用于手工构造三种格式的测试样本——不依赖任何游戏或第三方工具。
 */
public final class NbtWrite {

    public static byte[] root(String name, Map<String, Object> compound) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bos);
        d.writeByte(10);
        d.writeUTF(name);
        writeCompoundBody(d, compound);
        d.flush();
        return bos.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream d, Object v) throws IOException {
        if (v instanceof Byte) d.writeByte((Byte) v);
        else if (v instanceof Short) d.writeShort((Short) v);
        else if (v instanceof Integer) d.writeInt((Integer) v);
        else if (v instanceof Long) d.writeLong((Long) v);
        else if (v instanceof Float) d.writeFloat((Float) v);
        else if (v instanceof Double) d.writeDouble((Double) v);
        else if (v instanceof String) d.writeUTF((String) v);
        else if (v instanceof byte[]) { byte[] a = (byte[]) v; d.writeInt(a.length); d.write(a); }
        else if (v instanceof int[]) { int[] a = (int[]) v; d.writeInt(a.length); for (int i : a) d.writeInt(i); }
        else if (v instanceof long[]) { long[] a = (long[]) v; d.writeInt(a.length); for (long l : a) d.writeLong(l); }
        else if (v instanceof Map) writeCompoundBody(d, (Map<String, Object>) v);
        else if (v instanceof List) {
            List<Object> list = (List<Object>) v;
            int elemType = list.isEmpty() ? 0 : typeOf(list.get(0));
            d.writeByte(elemType);
            d.writeInt(list.size());
            for (Object e : list) writePayload(d, e);
        }
        else throw new IOException("不支持的类型: " + v.getClass());
    }

    private static void writeNamed(DataOutputStream d, String name, Object v) throws IOException {
        d.writeByte(typeOf(v));
        d.writeUTF(name);
        writePayload(d, v);
    }

    private static void writeCompoundBody(DataOutputStream d, Map<String, Object> m) throws IOException {
        for (Map.Entry<String, Object> e : m.entrySet()) writeNamed(d, e.getKey(), e.getValue());
        d.writeByte(0); // TAG_End
    }

    private static int typeOf(Object v) {
        if (v instanceof Byte) return 1;
        if (v instanceof Short) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof Float) return 5;
        if (v instanceof Double) return 6;
        if (v instanceof byte[]) return 7;
        if (v instanceof String) return 8;
        if (v instanceof List) return 9;
        if (v instanceof Map) return 10;
        if (v instanceof int[]) return 11;
        if (v instanceof long[]) return 12;
        if (v == null) return 0;
        throw new IllegalStateException("不支持的类型: " + v.getClass());
    }

    // ---- 构造助手 ----

    public static List<Object> ints(int... vals) {
        List<Object> l = new java.util.ArrayList<>();
        for (int v : vals) l.add(v);
        return l;
    }

    public static Map<String, Object> compound() { return new java.util.LinkedHashMap<>(); }

    public static Map<String, Object> paletteEntry(String name, String... kvPairs) {
        Map<String, Object> e = compound();
        e.put("Name", name);
        if (kvPairs.length > 0) {
            Map<String, Object> props = compound();
            for (int i = 0; i + 1 < kvPairs.length; i += 2) props.put(kvPairs[i], kvPairs[i + 1]);
            e.put("Properties", props);
        }
        return e;
    }
}
