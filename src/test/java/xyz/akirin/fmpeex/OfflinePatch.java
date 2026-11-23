package xyz.akirin.fmpeex;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import xyz.akirin.fmpeex.core.ClassIndex;
import xyz.akirin.fmpeex.core.LongWidener;

/**
 * Runs the patcher outside the game, for inspecting what it does to a mod.
 * <p>
 * Usage: {@code OfflinePatch <dir with the extracted target classes> <output dir> <library jar or dir>...}
 * <p>
 * Every class of the target is written to the output directory, patched or not, and each patched method is checked
 * for operand type consistency.
 */
public final class OfflinePatch {

    public static void main(String[] args) throws Exception {
        final File targetDir = new File(args[0]);
        File outDir = new File(args[1]);
        final List<File> libs = new ArrayList<File>();
        libs.add(targetDir);
        for (int i = 2; i < args.length; i++) {
            libs.add(new File(args[i]));
        }
        final List<ZipFile> zips = new ArrayList<ZipFile>();
        final List<File> dirs = new ArrayList<File>();
        for (File lib : libs) {
            if (lib.isDirectory()) {
                dirs.add(lib);
            } else {
                zips.add(new ZipFile(lib));
            }
        }
        ClassIndex index = new ClassIndex(new ClassIndex.Source() {

            @Override
            public byte[] get(String internalName) {
                try {
                    for (File dir : dirs) {
                        File f = new File(dir, internalName + ".class");
                        if (f.isFile()) {
                            return read(new FileInputStream(f));
                        }
                    }
                    for (ZipFile zip : zips) {
                        ZipEntry entry = zip.getEntry(internalName + ".class");
                        if (entry != null) {
                            return read(zip.getInputStream(entry));
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return null;
            }
        });
        LongWidener widener = new LongWidener(index);

        List<File> classFiles = new ArrayList<File>();
        collect(targetDir, classFiles);
        int patched = 0;
        int retargeted = 0;
        int saturated = 0;
        int failures = 0;
        for (File classFile : classFiles) {
            byte[] in = read(new FileInputStream(classFile));
            LongWidener.Report report = new LongWidener.Report();
            byte[] out = widener.transform(in, report);
            String relative = targetDir.toURI()
                .relativize(classFile.toURI())
                .getPath();
            File target = new File(outDir, relative);
            target.getParentFile()
                .mkdirs();
            FileOutputStream os = new FileOutputStream(target);
            try {
                os.write(out == null ? in : out);
            } finally {
                os.close();
            }
            if (out == null) {
                continue;
            }
            patched++;
            retargeted += report.retargeted;
            saturated += report.saturated;
            for (String line : report.lines) {
                System.out.println(line);
            }
            failures += verify(out);
        }
        System.out.println();
        System.out.println(
            "classes: " + classFiles.size()
                + ", patched: "
                + patched
                + ", retargeted refs: "
                + retargeted
                + ", clamped narrowings: "
                + saturated
                + ", verify failures: "
                + failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static int verify(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        int failures = 0;
        for (MethodNode mn : cn.methods) {
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(cn.name, mn);
            } catch (AnalyzerException e) {
                failures++;
                System.out.println("VERIFY FAILED " + cn.name + '.' + mn.name + mn.desc + ": " + e.getMessage());
            }
        }
        return failures;
    }

    private static void collect(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, out);
            } else if (child.getName()
                .endsWith(".class")) {
                    out.add(child);
                }
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                bos.write(buffer, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }
}
