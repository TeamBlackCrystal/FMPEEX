package xyz.akirin.fmpeex.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Looks up the members that classes actually declare, by reading raw class bytes instead of loading the classes.
 * Used to find references that were compiled against an {@code int} member which is a {@code long} member now.
 */
public final class ClassIndex {

    /** Supplies raw class bytes for an internal name ({@code a/b/C}); returns null when the class is unavailable. */
    public interface Source {

        byte[] get(String internalName);
    }

    private static final class Info {

        String superName;
        String[] interfaces;
        final Set<String> members = new HashSet<String>();
    }

    private static final Info MISSING = new Info();

    private final Source source;
    private final Map<String, Info> cache = new ConcurrentHashMap<String, Info>();

    public ClassIndex(Source source) {
        this.source = source;
    }

    private static String key(boolean field, String name, String desc) {
        return (field ? "F " : "M ") + name + ' ' + desc;
    }

    private Info info(String internalName) {
        Info cached = cache.get(internalName);
        if (cached != null) {
            return cached == MISSING ? null : cached;
        }
        Info info = MISSING;
        if (!internalName.startsWith("java/") && !internalName.startsWith("[")) {
            byte[] bytes = null;
            try {
                bytes = source.get(internalName);
            } catch (RuntimeException ignored) {
                // treated as unavailable
            }
            if (bytes != null) {
                info = read(bytes);
            }
        }
        cache.put(internalName, info);
        return info == MISSING ? null : info;
    }

    private static Info read(byte[] bytes) {
        final Info info = new Info();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {

            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
                info.superName = superName;
                info.interfaces = interfaces;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                info.members.add(key(true, name, desc));
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                String[] exceptions) {
                info.members.add(key(false, name, desc));
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return info;
    }

    /** The {@code long} counterpart of an {@code int} field or {@code int}-returning method descriptor, or null. */
    static String widenedDesc(boolean field, String desc) {
        if (field) {
            return "I".equals(desc) ? "J" : null;
        }
        return desc.endsWith(")I") ? desc.substring(0, desc.length() - 1) + "J" : null;
    }

    /**
     * Resolves a member reference the way the JVM would, over the classes this index can read.
     *
     * @return the descriptor to use instead, when the referenced {@code int} member does not exist anywhere in the
     *         readable part of the hierarchy but its {@code long} counterpart does; otherwise null
     */
    public String resolveWidened(boolean field, String owner, String name, String desc) {
        String widened = widenedDesc(field, desc);
        if (widened == null) {
            return null;
        }
        String exactKey = key(field, name, desc);
        String widenedKey = key(field, name, widened);
        boolean foundWidened = false;
        Set<String> seen = new HashSet<String>();
        Deque<String> queue = new ArrayDeque<String>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            Info info = info(current);
            if (info == null) {
                continue;
            }
            if (info.members.contains(exactKey)) {
                return null;
            }
            if (info.members.contains(widenedKey)) {
                foundWidened = true;
            }
            if (info.superName != null) {
                queue.add(info.superName);
            }
            if (info.interfaces != null) {
                for (String itf : info.interfaces) {
                    queue.add(itf);
                }
            }
        }
        return foundWidened ? widened : null;
    }
}
