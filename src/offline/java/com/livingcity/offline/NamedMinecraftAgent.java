package com.livingcity.offline;

import net.fabricmc.loader.impl.transformer.PackageAccessFixer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/** Applies Fabric's development package-access fix to the offline JVM's named vanilla classes. */
public final class NamedMinecraftAgent {
    public static void premain(String options, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                               ProtectionDomain domain, byte[] bytes) {
                if (name == null || !name.startsWith("net/minecraft/")) return null;
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(reader, 0);
                reader.accept(new PackageAccessFixer(Opcodes.ASM9, writer), 0);
                return writer.toByteArray();
            }
        });
    }
}
