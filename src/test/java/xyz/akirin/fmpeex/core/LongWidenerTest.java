package xyz.akirin.fmpeex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Patches a small generated class that was "compiled" against an {@code int} API, links it against a {@code long}
 * API and runs it.
 */
class LongWidenerTest implements Opcodes {

    private static final long BIG = 3000000000L;

    /** {@code t/Lib} with {@code static value()} and {@code static field}, typed {@code int} or {@code long}. */
    private static byte[] lib(boolean wide) {
        String type = wide ? "J" : "I";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_7, ACC_PUBLIC, "t/Lib", null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "field", type, null, null)
            .visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()" + type, null, null);
        mv.visitCode();
        if (wide) {
            mv.visitLdcInsn(BIG);
            mv.visitInsn(LRETURN);
        } else {
            mv.visitLdcInsn(7);
            mv.visitInsn(IRETURN);
        }
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code t/Client}, written the way javac would compile it against the {@code int} flavour of {@code t/Lib}. */
    private static byte[] client() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_7, ACC_PUBLIC, "t/Client", null, "java/lang/Object", null);

        // static String scaled(int n) { int v = Lib.value(); int m = v > n ? v : n; return Integer.toString(m * n); }
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "scaled", "(I)Ljava/lang/String;", null, null);
        mv.visitCode();
        Label takeN = new Label();
        Label join = new Label();
        mv.visitMethodInsn(INVOKESTATIC, "t/Lib", "value", "()I", false);
        mv.visitVarInsn(ISTORE, 1);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitVarInsn(ILOAD, 0);
        mv.visitJumpInsn(IF_ICMPLE, takeN);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitJumpInsn(GOTO, join);
        mv.visitLabel(takeN);
        mv.visitVarInsn(ILOAD, 0);
        mv.visitLabel(join);
        mv.visitVarInsn(ISTORE, 2);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ILOAD, 0);
        mv.visitInsn(IMUL);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // static int narrow() { return Lib.value(); }
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "narrow", "()I", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, "t/Lib", "value", "()I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // static double roundTrip(int x) { Lib.field = x; return (double) Lib.field; }
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "roundTrip", "(I)D", null, null);
        mv.visitCode();
        mv.visitVarInsn(ILOAD, 0);
        mv.visitFieldInsn(PUTSTATIC, "t/Lib", "field", "I");
        mv.visitFieldInsn(GETSTATIC, "t/Lib", "field", "I");
        mv.visitInsn(I2D);
        mv.visitInsn(DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static LongWidener widenerFor(final byte[] libBytes) {
        return new LongWidener(new ClassIndex(new ClassIndex.Source() {

            @Override
            public byte[] get(String internalName) {
                return "t/Lib".equals(internalName) ? libBytes : null;
            }
        }));
    }

    private static Class<?> load(final byte[] libBytes, final byte[] clientBytes) throws ClassNotFoundException {
        final Map<String, byte[]> classes = new HashMap<String, byte[]>();
        classes.put("t.Lib", libBytes);
        classes.put("t.Client", clientBytes);
        ClassLoader loader = new ClassLoader(LongWidenerTest.class.getClassLoader()) {

            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        return loader.loadClass("t.Client");
    }

    @Test
    void leavesClassAloneWhenTheIntMembersExist() throws Exception {
        assertNull(widenerFor(lib(false)).transform(client(), new LongWidener.Report()));
    }

    @Test
    void carriesLongThroughLocalsBranchesAndArithmetic() throws Exception {
        byte[] lib = lib(true);
        LongWidener.Report report = new LongWidener.Report();
        byte[] patched = widenerFor(lib).transform(client(), report);
        assertNotNull(patched);
        Class<?> client = load(lib, patched);

        // max(3_000_000_000, 2) * 2 does not fit in an int
        assertEquals(
            "6000000000",
            client.getMethod("scaled", int.class)
                .invoke(null, 2));
        // a value that has to stay an int is clamped, not wrapped
        assertEquals(
            Integer.MAX_VALUE,
            client.getMethod("narrow")
                .invoke(null));
        assertEquals(
            42.0,
            client.getMethod("roundTrip", int.class)
                .invoke(null, 42));
        assertEquals(4, report.retargeted);
        assertEquals(1, report.saturated);
    }
}
