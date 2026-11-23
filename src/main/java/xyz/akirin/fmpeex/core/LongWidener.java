package xyz.akirin.fmpeex.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Rewrites a class that was compiled against {@code int} members which have since become {@code long}.
 * <p>
 * Every reference to such a member is retargeted to the {@code long} one. The {@code long} is then carried as far as
 * the surrounding code allows: through arithmetic, comparisons, conversions to float/double, string conversion and
 * local variables. Where the value reaches code that can only take an {@code int} (a method argument, an int field,
 * a return value, ...) it is narrowed with {@link xyz.akirin.fmpeex.rt.Sat#toInt(long)}, which clamps.
 */
public final class LongWidener implements Opcodes {

    private static final String SAT_OWNER = "xyz/akirin/fmpeex/rt/Sat";

    /** Collects what was done to one class. */
    public static final class Report {

        public int retargeted;
        public int saturated;
        public final List<String> lines = new ArrayList<String>();
    }

    private final ClassIndex index;

    public LongWidener(ClassIndex index) {
        this.index = index;
    }

    /** @return the patched class, or null when nothing had to change */
    public byte[] transform(byte[] classBytes, Report report) throws AnalyzerException {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, ClassReader.EXPAND_FRAMES);
        boolean changed = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions.size() > 0) {
                changed |= new MethodPass(cn.name, mn, report).run();
            }
        }
        if (!changed) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------------------

    /** One operand popped by one instruction. */
    private static final class Use {

        final AbstractInsnNode insn;
        final int index;
        final int count;
        final SourceValue value;

        Use(AbstractInsnNode insn, int index, int count, SourceValue value) {
            this.insn = insn;
            this.index = index;
            this.count = count;
            this.value = value;
        }
    }

    private static final class UnionFind {

        private final Map<Object, Object> parent = new IdentityHashMap<Object, Object>();

        Object find(Object o) {
            Object p = parent.get(o);
            if (p == null) {
                parent.put(o, o);
                return o;
            }
            if (p == o) {
                return o;
            }
            Object root = find(p);
            parent.put(o, root);
            return root;
        }

        void union(Object a, Object b) {
            Object ra = find(a);
            Object rb = find(b);
            if (ra != rb) {
                parent.put(ra, rb);
            }
        }

        boolean contains(Object o) {
            return parent.containsKey(o);
        }

        Set<Object> elements() {
            return parent.keySet();
        }
    }

    /** Replays single instructions on analysed frames to learn which values each instruction pops. */
    private static final class Recorder extends SourceInterpreter {

        final List<Use> stackUses = new ArrayList<Use>();
        /** ILOAD / IINC instruction -> the definitions of the local variable that reach it. */
        final Map<AbstractInsnNode, SourceValue> localReads = new LinkedHashMap<AbstractInsnNode, SourceValue>();

        Recorder() {
            super(ASM5);
        }

        @Override
        public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) {
            int op = insn.getOpcode();
            if (op >= ILOAD && op <= ALOAD) {
                localReads.put(insn, value);
            } else {
                // xSTORE, DUP*, SWAP: several calls per instruction are possible, the exact index is irrelevant
                stackUses.add(new Use(insn, 0, 1, value));
            }
            return super.copyOperation(insn, value);
        }

        @Override
        public SourceValue unaryOperation(AbstractInsnNode insn, SourceValue value) {
            if (insn.getOpcode() == IINC) {
                localReads.put(insn, value);
            } else {
                stackUses.add(new Use(insn, 0, 1, value));
            }
            return super.unaryOperation(insn, value);
        }

        @Override
        public SourceValue binaryOperation(AbstractInsnNode insn, SourceValue value1, SourceValue value2) {
            stackUses.add(new Use(insn, 0, 2, value1));
            stackUses.add(new Use(insn, 1, 2, value2));
            return super.binaryOperation(insn, value1, value2);
        }

        @Override
        public SourceValue ternaryOperation(AbstractInsnNode insn, SourceValue value1, SourceValue value2,
            SourceValue value3) {
            stackUses.add(new Use(insn, 0, 3, value1));
            stackUses.add(new Use(insn, 1, 3, value2));
            stackUses.add(new Use(insn, 2, 3, value3));
            return super.ternaryOperation(insn, value1, value2, value3);
        }

        @Override
        public SourceValue naryOperation(AbstractInsnNode insn, List<? extends SourceValue> values) {
            for (int i = 0; i < values.size(); i++) {
                stackUses.add(new Use(insn, i, values.size(), values.get(i)));
            }
            return super.naryOperation(insn, values);
        }
    }

    private final class MethodPass {

        final String owner;
        final MethodNode mn;
        final InsnList insns;
        final Report report;

        /** Retargeted GETFIELD / GETSTATIC / INVOKE* instructions -> new descriptor. */
        final Map<AbstractInsnNode, String> seeds = new LinkedHashMap<AbstractInsnNode, String>();
        final Map<FieldInsnNode, String> putSeeds = new LinkedHashMap<FieldInsnNode, String>();

        /** Values that meet on the operand stack: producers that feed the same operand of some instruction. */
        final UnionFind comps = new UnionFind();
        final Map<Object, List<Use>> compUses = new IdentityHashMap<Object, List<Use>>();
        final Map<Object, List<AbstractInsnNode>> compProducers = new IdentityHashMap<Object, List<AbstractInsnNode>>();

        /** Local variable webs: ISTOREs and the ILOADs (and IINCs) they reach. */
        final UnionFind webs = new UnionFind();
        final Map<Object, List<AbstractInsnNode>> webMembers = new IdentityHashMap<Object, List<AbstractInsnNode>>();
        final Set<Object> invalidWebs = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());

        final Set<Object> bannedComps = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());

        Set<AbstractInsnNode> longProducers;
        Set<Object> wideComps;
        Set<Object> wideWebs;

        MethodPass(String owner, MethodNode mn, Report report) {
            this.owner = owner;
            this.mn = mn;
            this.insns = mn.instructions;
            this.report = report;
        }

        boolean run() throws AnalyzerException {
            findSeeds();
            if (seeds.isEmpty() && putSeeds.isEmpty()) {
                return false;
            }
            for (Map.Entry<FieldInsnNode, String> e : putSeeds.entrySet()) {
                FieldInsnNode put = e.getKey();
                insns.insertBefore(put, new InsnNode(I2L));
                note("write  " + put.owner + '.' + put.name + " int -> long");
                put.desc = e.getValue();
                report.retargeted++;
            }
            if (seeds.isEmpty()) {
                return true;
            }

            AbstractInsnNode[] original = insns.toArray();
            Frame<SourceValue>[] frames = new Analyzer<SourceValue>(new SourceInterpreter()).analyze(owner, mn);
            buildGraph(original, frames);
            decide();
            List<FramePatch> framePatches = planFramePatches(original, frames);
            apply(framePatches);
            return true;
        }

        private void note(String text) {
            report.lines.add(owner + '.' + mn.name + mn.desc + ": " + text);
        }

        private void findSeeds() {
            for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode f = (FieldInsnNode) insn;
                    String widened = index.resolveWidened(true, f.owner, f.name, f.desc);
                    if (widened != null) {
                        if (f.getOpcode() == GETFIELD || f.getOpcode() == GETSTATIC) {
                            seeds.put(f, widened);
                        } else {
                            putSeeds.put(f, widened);
                        }
                    }
                } else if (insn instanceof MethodInsnNode) {
                    MethodInsnNode m = (MethodInsnNode) insn;
                    String widened = index.resolveWidened(false, m.owner, m.name, m.desc);
                    if (widened != null) {
                        seeds.put(m, widened);
                    }
                }
            }
        }

        // ----- analysis --------------------------------------------------------------------------------------

        private void buildGraph(AbstractInsnNode[] original, Frame<SourceValue>[] frames) throws AnalyzerException {
            Recorder recorder = new Recorder();
            for (int i = 0; i < original.length; i++) {
                AbstractInsnNode insn = original[i];
                Frame<SourceValue> frame = frames[i];
                if (frame == null || insn.getOpcode() < 0) {
                    continue;
                }
                int op = insn.getOpcode();
                if (op == POP || op == POP2) {
                    // Frame.execute does not report these to the interpreter
                    int top = frame.getStackSize() - 1;
                    SourceValue v = frame.getStack(top);
                    if (op == POP || v.getSize() == 2) {
                        recorder.stackUses.add(new Use(insn, 0, 1, v));
                    } else {
                        recorder.stackUses.add(new Use(insn, 0, 2, frame.getStack(top - 1)));
                        recorder.stackUses.add(new Use(insn, 1, 2, v));
                    }
                    continue;
                }
                new Frame<SourceValue>(frame).execute(insn, recorder);
            }

            for (AbstractInsnNode seed : seeds.keySet()) {
                comps.find(seed);
            }
            for (Use use : recorder.stackUses) {
                AbstractInsnNode first = null;
                for (AbstractInsnNode producer : use.value.insns) {
                    if (first == null) {
                        first = producer;
                        comps.find(first);
                    } else {
                        comps.union(first, producer);
                    }
                }
            }
            for (Object producer : new ArrayList<Object>(comps.elements())) {
                Object rep = comps.find(producer);
                List<AbstractInsnNode> list = compProducers.get(rep);
                if (list == null) {
                    compProducers.put(rep, list = new ArrayList<AbstractInsnNode>());
                }
                list.add((AbstractInsnNode) producer);
            }
            for (Use use : recorder.stackUses) {
                if (use.value.insns.isEmpty()) {
                    continue;
                }
                Object rep = comps.find(
                    use.value.insns.iterator()
                        .next());
                List<Use> list = compUses.get(rep);
                if (list == null) {
                    compUses.put(rep, list = new ArrayList<Use>());
                }
                list.add(use);
            }

            int firstFreeSlot = (mn.access & ACC_STATIC) != 0 ? 0 : 1;
            for (Type arg : Type.getArgumentTypes(mn.desc)) {
                firstFreeSlot += arg.getSize();
            }
            for (int i = 0; i < original.length; i++) {
                if (frames[i] != null && original[i].getOpcode() == ISTORE) {
                    webs.find(original[i]);
                }
            }
            for (Map.Entry<AbstractInsnNode, SourceValue> read : recorder.localReads.entrySet()) {
                AbstractInsnNode reader = read.getKey();
                if (reader.getOpcode() != ILOAD && reader.getOpcode() != IINC) {
                    continue;
                }
                webs.find(reader);
                for (AbstractInsnNode def : read.getValue().insns) {
                    webs.union(reader, def);
                }
            }
            for (Object member : new ArrayList<Object>(webs.elements())) {
                Object rep = webs.find(member);
                List<AbstractInsnNode> list = webMembers.get(rep);
                if (list == null) {
                    webMembers.put(rep, list = new ArrayList<AbstractInsnNode>());
                }
                list.add((AbstractInsnNode) member);
            }
            for (Map.Entry<Object, List<AbstractInsnNode>> web : webMembers.entrySet()) {
                boolean hasStore = false;
                boolean ok = true;
                for (AbstractInsnNode member : web.getValue()) {
                    int op = member.getOpcode();
                    if (op == ISTORE) {
                        hasStore = true;
                    } else if (op != ILOAD) {
                        ok = false;
                    }
                    if (member instanceof VarInsnNode && ((VarInsnNode) member).var < firstFreeSlot) {
                        ok = false;
                    }
                }
                if (!ok || !hasStore) {
                    invalidWebs.add(web.getKey());
                }
            }
        }

        private Object compOf(AbstractInsnNode producer) {
            return comps.find(producer);
        }

        private Object compOf(SourceValue value) {
            return value.insns.isEmpty() ? null
                : comps.find(
                    value.insns.iterator()
                        .next());
        }

        private Object webOf(AbstractInsnNode storeOrLoad) {
            return webs.contains(storeOrLoad) ? webs.find(storeOrLoad) : null;
        }

        private List<Use> usesOf(Object comp) {
            List<Use> uses = compUses.get(comp);
            return uses == null ? Collections.<Use>emptyList() : uses;
        }

        private boolean isArithmetic(int op) {
            return op == IADD || op == ISUB || op == IMUL || op == IDIV || op == IREM || op == INEG;
        }

        private boolean isIntCompare(int op) {
            return op >= IF_ICMPEQ && op <= IF_ICMPLE;
        }

        /** Methods that have an identical overload taking a long in the same position. */
        private boolean hasLongOverload(MethodInsnNode m) {
            String o = m.owner;
            String n = m.name;
            String d = m.desc;
            if (o.equals("java/lang/Integer") && n.equals("toString") && d.equals("(I)Ljava/lang/String;")) {
                return true;
            }
            if (o.equals("java/lang/String") && n.equals("valueOf") && d.equals("(I)Ljava/lang/String;")) {
                return true;
            }
            if ((o.equals("java/lang/StringBuilder") || o.equals("java/lang/StringBuffer")) && n.equals("append")
                && d.startsWith("(I)")) {
                return true;
            }
            return o.equals("java/io/PrintStream") && (n.equals("print") || n.equals("println")) && d.equals("(I)V");
        }

        /**
         * Finds the largest consistent set of stack values and local variables that can become long. Starts from the
         * assumption that everything fed by a retargeted member can, and backs off one value at a time.
         */
        private void decide() {
            while (true) {
                longProducers = new LinkedHashSet<AbstractInsnNode>(seeds.keySet());
                wideComps = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
                wideWebs = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
                boolean grew = true;
                while (grew) {
                    grew = false;
                    for (AbstractInsnNode producer : new ArrayList<AbstractInsnNode>(longProducers)) {
                        Object comp = compOf(producer);
                        if (!bannedComps.contains(comp)) {
                            grew |= wideComps.add(comp);
                        }
                    }
                    for (Object comp : new ArrayList<Object>(wideComps)) {
                        for (Use use : usesOf(comp)) {
                            int op = use.insn.getOpcode();
                            if (isArithmetic(op)) {
                                grew |= longProducers.add(use.insn);
                            }
                            if (use.count == 2 && (isArithmetic(op) || isIntCompare(op))) {
                                // the other operand has to become long as well
                                for (Use other : usesOfInsn(use.insn)) {
                                    Object otherComp = compOf(other.value);
                                    if (otherComp != null && !bannedComps.contains(otherComp)) {
                                        grew |= wideComps.add(otherComp);
                                    }
                                }
                            }
                            if (op == ISTORE) {
                                Object web = webOf(use.insn);
                                if (web != null && !invalidWebs.contains(web)) {
                                    grew |= wideWebs.add(web);
                                }
                            }
                        }
                    }
                    for (Object web : wideWebs) {
                        for (AbstractInsnNode member : webMembers.get(web)) {
                            if (member.getOpcode() == ILOAD) {
                                grew |= longProducers.add(member);
                            }
                        }
                    }
                }
                Object offender = null;
                for (Object comp : wideComps) {
                    if (!acceptable(comp)) {
                        offender = comp;
                        break;
                    }
                }
                if (offender == null) {
                    return;
                }
                bannedComps.add(offender);
            }
        }

        private final Map<AbstractInsnNode, List<Use>> usesByInsn = new IdentityHashMap<AbstractInsnNode, List<Use>>();

        private List<Use> usesOfInsn(AbstractInsnNode insn) {
            if (usesByInsn.isEmpty()) {
                for (List<Use> list : compUses.values()) {
                    for (Use use : list) {
                        List<Use> l = usesByInsn.get(use.insn);
                        if (l == null) {
                            usesByInsn.put(use.insn, l = new ArrayList<Use>());
                        }
                        l.add(use);
                    }
                }
            }
            List<Use> l = usesByInsn.get(insn);
            return l == null ? Collections.<Use>emptyList() : l;
        }

        private boolean acceptable(Object comp) {
            List<AbstractInsnNode> producers = compProducers.get(comp);
            if (producers == null) {
                return false; // a value nothing was recorded for: leave it alone
            }
            for (AbstractInsnNode producer : producers) {
                int op = producer.getOpcode();
                // these leave the value they "produce" below the top of the stack, so it cannot be converted in place
                if (op == DUP_X1 || op == DUP_X2 || op == DUP2 || op == DUP2_X1 || op == DUP2_X2 || op == SWAP) {
                    return false;
                }
            }
            for (Use use : usesOf(comp)) {
                if (!acceptable(use)) {
                    return false;
                }
            }
            return true;
        }

        private boolean acceptable(Use use) {
            AbstractInsnNode insn = use.insn;
            int op = insn.getOpcode();
            switch (op) {
                case I2L:
                case I2F:
                case I2D:
                case POP:
                case INEG:
                case IFEQ:
                case IFNE:
                case IFLT:
                case IFGE:
                case IFGT:
                case IFLE:
                    return true;
                case ISTORE: {
                    Object web = webOf(insn);
                    return web != null && wideWebs.contains(web);
                }
                default:
                    break;
            }
            if (use.count == 2 && (isArithmetic(op) || isIntCompare(op))) {
                for (Use operand : usesOfInsn(insn)) {
                    Object c = compOf(operand.value);
                    if (c == null || !wideComps.contains(c)) {
                        return false;
                    }
                }
                return true;
            }
            if (insn instanceof MethodInsnNode) {
                return use.index == use.count - 1 && hasLongOverload((MethodInsnNode) insn);
            }
            return false;
        }

        // ----- stack map frames ------------------------------------------------------------------------------

        private final class FramePatch {

            final FrameNode node;
            final List<Integer> longStackEntries = new ArrayList<Integer>();
            final List<Integer> clearedSlots = new ArrayList<Integer>();

            FramePatch(FrameNode node) {
                this.node = node;
            }
        }

        private List<FramePatch> planFramePatches(AbstractInsnNode[] original, Frame<SourceValue>[] frames) {
            List<FramePatch> patches = new ArrayList<FramePatch>();
            for (int i = 0; i < original.length; i++) {
                if (!(original[i] instanceof FrameNode)) {
                    continue;
                }
                FramePatch patch = new FramePatch((FrameNode) original[i]);
                patches.add(patch);
                Frame<SourceValue> frame = frames[i];
                if (frame == null) {
                    continue;
                }
                for (int s = 0; s < frame.getStackSize(); s++) {
                    Object comp = compOf(frame.getStack(s));
                    if (comp != null && wideComps.contains(comp)) {
                        patch.longStackEntries.add(s);
                    }
                }
                for (int slot = 0; slot < frame.getLocals(); slot++) {
                    for (AbstractInsnNode def : frame.getLocal(slot).insns) {
                        Object web = def.getOpcode() == ISTORE ? webOf(def) : null;
                        if (web != null && wideWebs.contains(web)) {
                            patch.clearedSlots.add(slot);
                            break;
                        }
                    }
                }
            }
            return patches;
        }

        private void patchFrame(FramePatch patch, List<Integer> newLongSlots) {
            FrameNode node = patch.node;
            if (node.type != F_NEW && node.type != F_FULL) {
                throw new IllegalStateException("frames were not expanded");
            }
            if (node.stack != null) {
                for (int entry : patch.longStackEntries) {
                    node.stack.set(entry, LONG);
                }
            }
            // one element per slot; the second half of a long/double is a null placeholder
            List<Object> slots = new ArrayList<Object>();
            if (node.local != null) {
                for (Object type : node.local) {
                    slots.add(type);
                    if (type == LONG || type == DOUBLE) {
                        slots.add(null);
                    }
                }
            }
            for (int slot : patch.clearedSlots) {
                if (slot < slots.size()) {
                    slots.set(slot, TOP);
                }
            }
            for (int slot : newLongSlots) {
                while (slots.size() < slot) {
                    slots.add(TOP);
                }
                slots.add(LONG);
                slots.add(null);
            }
            List<Object> compact = new ArrayList<Object>();
            for (Object type : slots) {
                if (type != null) {
                    compact.add(type);
                }
            }
            node.local = compact;
        }

        // ----- rewriting -------------------------------------------------------------------------------------

        private void apply(List<FramePatch> framePatches) {
            Map<AbstractInsnNode, AbstractInsnNode> replaced = new IdentityHashMap<AbstractInsnNode, AbstractInsnNode>();

            for (Map.Entry<AbstractInsnNode, String> seed : seeds.entrySet()) {
                AbstractInsnNode insn = seed.getKey();
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode f = (FieldInsnNode) insn;
                    note("read   " + f.owner + '.' + f.name + " int -> long");
                    f.desc = seed.getValue();
                } else {
                    MethodInsnNode m = (MethodInsnNode) insn;
                    note("call   " + m.owner + '.' + m.name + m.desc + " -> " + seed.getValue());
                    m.desc = seed.getValue();
                }
                report.retargeted++;
            }

            // local variables: a fresh long slot per widened web
            Map<Object, Integer> webSlot = new IdentityHashMap<Object, Integer>();
            List<Integer> newLongSlots = new ArrayList<Integer>();
            Set<Integer> retiredIntSlots = new HashSet<Integer>();
            // in code order, so that the same input always gives the same output
            final Map<Object, Integer> webPosition = new IdentityHashMap<Object, Integer>();
            for (Object web : wideWebs) {
                int first = Integer.MAX_VALUE;
                for (AbstractInsnNode member : webMembers.get(web)) {
                    first = Math.min(first, insns.indexOf(member));
                }
                webPosition.put(web, first);
            }
            List<Object> orderedWebs = new ArrayList<Object>(wideWebs);
            Collections.sort(orderedWebs, new java.util.Comparator<Object>() {

                @Override
                public int compare(Object a, Object b) {
                    return webPosition.get(a)
                        .compareTo(webPosition.get(b));
                }
            });
            for (Object web : orderedWebs) {
                int slot = mn.maxLocals;
                mn.maxLocals += 2;
                webSlot.put(web, slot);
                newLongSlots.add(slot);
            }

            // consumers of widened stack values
            Set<AbstractInsnNode> done = Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode, Boolean>());
            for (Object comp : wideComps) {
                for (Use use : usesOf(comp)) {
                    AbstractInsnNode insn = use.insn;
                    if (!done.add(insn)) {
                        continue;
                    }
                    int op = insn.getOpcode();
                    switch (op) {
                        case I2L:
                            insns.remove(insn);
                            break;
                        case I2F:
                            replace(replaced, insn, L2F);
                            break;
                        case I2D:
                            replace(replaced, insn, L2D);
                            break;
                        case POP:
                            replace(replaced, insn, POP2);
                            break;
                        case INEG:
                            replace(replaced, insn, LNEG);
                            break;
                        case IADD:
                            replace(replaced, insn, LADD);
                            break;
                        case ISUB:
                            replace(replaced, insn, LSUB);
                            break;
                        case IMUL:
                            replace(replaced, insn, LMUL);
                            break;
                        case IDIV:
                            replace(replaced, insn, LDIV);
                            break;
                        case IREM:
                            replace(replaced, insn, LREM);
                            break;
                        case IFEQ:
                        case IFNE:
                        case IFLT:
                        case IFGE:
                        case IFGT:
                        case IFLE:
                            insns.insertBefore(insn, new InsnNode(LCONST_0));
                            insns.insertBefore(insn, new InsnNode(LCMP));
                            break;
                        case IF_ICMPEQ:
                        case IF_ICMPNE:
                        case IF_ICMPLT:
                        case IF_ICMPGE:
                        case IF_ICMPGT:
                        case IF_ICMPLE:
                            insns.insertBefore(insn, new InsnNode(LCMP));
                            ((JumpInsnNode) insn).setOpcode(op - IF_ICMPEQ + IFEQ);
                            break;
                        case ISTORE:
                            break; // handled with its web below
                        default: {
                            MethodInsnNode m = (MethodInsnNode) insn;
                            if (m.owner.equals("java/lang/Integer")) {
                                m.owner = "java/lang/Long";
                            }
                            m.desc = "(J" + m.desc.substring(2);
                            break;
                        }
                    }
                }
            }

            for (Object web : orderedWebs) {
                int slot = webSlot.get(web);
                for (AbstractInsnNode member : webMembers.get(web)) {
                    VarInsnNode var = (VarInsnNode) member;
                    retiredIntSlots.add(var.var);
                    if (var.getOpcode() == ISTORE) {
                        Use stored = usesOfInsn(var).isEmpty() ? null : usesOfInsn(var).get(0);
                        Object comp = stored == null ? null : compOf(stored.value);
                        if (comp == null || !wideComps.contains(comp)) {
                            insns.insertBefore(var, new InsnNode(I2L));
                        }
                        var.setOpcode(LSTORE);
                    } else {
                        var.setOpcode(LLOAD);
                    }
                    var.var = slot;
                }
                note("local  int -> long (slot " + slot + ")");
            }

            // producers: convert where what they push differs from what their consumers now expect
            int saturatedHere = 0;
            for (Object producerObj : new ArrayList<Object>(comps.elements())) {
                AbstractInsnNode producer = (AbstractInsnNode) producerObj;
                boolean pushesLong = longProducers.contains(producer);
                boolean wantLong = wideComps.contains(compOf(producer));
                if (pushesLong == wantLong) {
                    continue;
                }
                AbstractInsnNode at = replaced.containsKey(producer) ? replaced.get(producer) : producer;
                if (pushesLong) {
                    insns.insert(at, new MethodInsnNode(INVOKESTATIC, SAT_OWNER, "toInt", "(J)I", false));
                    saturatedHere++;
                } else {
                    insns.insert(at, new InsnNode(I2L));
                }
            }
            if (saturatedHere > 0) {
                report.saturated += saturatedHere;
                note("narrowed to int (clamped) at " + saturatedHere + " place(s)");
            }

            if (!newLongSlots.isEmpty()) {
                // definitely assigned on every path, so every frame may declare them
                InsnList init = new InsnList();
                for (int slot : newLongSlots) {
                    init.add(new InsnNode(LCONST_0));
                    init.add(new VarInsnNode(LSTORE, slot));
                }
                insns.insert(init);
                if (mn.localVariables != null) {
                    for (Iterator<LocalVariableNode> it = mn.localVariables.iterator(); it.hasNext();) {
                        LocalVariableNode local = it.next();
                        if (retiredIntSlots.contains(local.index) && "I".equals(local.desc)) {
                            it.remove();
                        }
                    }
                }
            }
            for (FramePatch patch : framePatches) {
                patchFrame(patch, newLongSlots);
            }
        }

        private void replace(Map<AbstractInsnNode, AbstractInsnNode> replaced, AbstractInsnNode old, int opcode) {
            AbstractInsnNode fresh = new InsnNode(opcode);
            insns.set(old, fresh);
            replaced.put(old, fresh);
        }
    }
}
