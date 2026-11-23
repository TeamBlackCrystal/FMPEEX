package xyz.akirin.fmpeex.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Patches PEEX while it is being loaded so that it links against FMProjectE, whose EMC values are {@code long}.
 * <p>
 * Nothing about PEEX is hard-coded apart from its package: for every {@code int} member PEEX refers to, the classes
 * that are actually installed are inspected, and the reference is only rewritten when the {@code int} member is gone
 * and a {@code long} member of the same name is there instead. With the original ProjectE nothing is changed.
 * <p>
 * More packages can be added with {@code -Dfmpeex.targets=some.addon.,other.addon.} (dot-terminated prefixes).
 */
public class FMPEEXTransformer implements IClassTransformer {

    private static final Logger LOG = LogManager.getLogger("FMPEEX");

    private final List<String> targets = new ArrayList<String>();
    private final LongWidener widener;

    public FMPEEXTransformer() {
        targets.add("akuto2.peex.");
        String extra = System.getProperty("fmpeex.targets");
        if (extra != null) {
            for (String prefix : extra.split(",")) {
                prefix = prefix.trim();
                if (!prefix.isEmpty()) {
                    targets.add(prefix);
                }
            }
        }
        widener = new LongWidener(new ClassIndex(new ClassIndex.Source() {

            @Override
            public byte[] get(String internalName) {
                try {
                    return Launch.classLoader.getClassBytes(internalName.replace('/', '.'));
                } catch (IOException e) {
                    return null;
                }
            }
        }));
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || transformedName == null || !isTarget(transformedName)) {
            return basicClass;
        }
        try {
            LongWidener.Report report = new LongWidener.Report();
            byte[] patched = widener.transform(basicClass, report);
            if (patched == null) {
                return basicClass;
            }
            LOG.info(
                "Patched {}: {} reference(s) retargeted to long, {} value(s) clamped to int",
                transformedName,
                report.retargeted,
                report.saturated);
            if (LOG.isDebugEnabled()) {
                for (String line : report.lines) {
                    LOG.debug(line);
                }
            }
            return patched;
        } catch (Throwable t) {
            LOG.error("Could not patch " + transformedName + ", leaving it unchanged", t);
            return basicClass;
        }
    }

    private boolean isTarget(String className) {
        for (String prefix : targets) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
