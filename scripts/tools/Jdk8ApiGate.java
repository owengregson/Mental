/*
 * Jdk8ApiGate — the campaign H1 "closed-world" static gate over the DOWNGRADED mega-jar BASE tree.
 *
 * Why this exists (full-range campaign, hazard H1/H2): JVMDowngrader lowers the LANGUAGE (records, sealed,
 * switch-expr, string-concat indy) and shims the COMMON post-8 stdlib calls (List.of, Optional.isEmpty,
 * String.isBlank/strip/repeat, Stream.toList, some NIO). It does NOT shim everything — a stray un-shimmable
 * JDK-9+ API compiles, downgrades and shades GREEN, then NoSuchMethodErrors on a real Java-8 server
 * (StarEnchants' real-world catch: ThreadLocalRandom.nextDouble(double)). The clientless suite only drives a
 * subset of paths, so a hazard on an unexercised path (the fast-path blind spot) ships unseen. This gate is the
 * static net: every JDK reference in the downgraded base bytecode must resolve against a REAL Java-8 rt.jar.
 *
 * Adapted from StarEnchants' scripts/tools/Jdk8ApiGate.java with two Mental-specific additions:
 *   1. Server-provided IGNORE prefixes (--ignore): org/bukkit, net/minecraft, io/netty, … are provided by
 *      the running server, never bundled, and are neither validated nor required in-jar.
 *   2. Every OTHER (non-JDK, non-ignored) reference MUST resolve IN-JAR. This subsumes the H2 trap (a jvmdg
 *      util/* runtime helper the shade forgot to bundle is an un-relocated-prefix class ref that resolves
 *      nowhere → a hard miss) and any stub-pruning gap: a reference to a class the mega-jar neither carries
 *      nor the server provides would NoClassDefFoundError on a real 1.8 server.
 *
 * Scanned scope: the BASE tree only (META-INF/versions/* is skipped — only the base v52 tree runs on Java 8;
 * a versioned overlay would otherwise shadow a base ClassInfo and corrupt hierarchy resolution). It sees
 * super/interfaces, field/method descriptors + exceptions, every instruction's type/member reference,
 * invokedynamic bootstrap handles/args (incl. ConstantDynamic), and annotation element values. It does NOT
 * see reflective string-named lookups (Class.forName("…"), Lookup.find*) — an inherent limit of any scanner.
 *
 * Severity: java/ javax/ org/w3c/ org/xml/sax/ org/ietf/jgss/ org/omg/ org/jcp/ -> HARD (class OR member
 * miss blocks). jdk/ sun/ com/sun/ -> class miss blocks, member miss WARNs (--strict-internal to enforce).
 * A --ignore-prefixed owner is never checked. Anything else must exist in-jar (class-level) or it is a HARD
 * miss. The anchored-prefix allowlist (--allow) starts EMPTY: a genuine miss is fixed, not allowlisted.
 * An allow entry may append " FROM <referrer-prefix>" to scope the exception to references made BY classes
 * under that prefix — the escape hatch stays pinned to the vetted namespace (e.g. a shaded library's own
 * guarded optional probe) and the closed world stays fully strict for every other class, first-party above all.
 */
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class Jdk8ApiGate {

    /** Which namespace tier a referenced owner falls in (drives severity + whether it must resolve in-jar). */
    enum Tier { HARD, INTERNAL, IGNORE, UNKNOWN }

    static final class ClassInfo {
        final String superName;
        final String[] interfaces;
        final Set<String> methods = new HashSet<String>(); // name+descriptor
        final Set<String> fields = new HashSet<String>();  // name+descriptor
        ClassInfo(String superName, String[] interfaces) {
            this.superName = superName;
            this.interfaces = interfaces == null ? new String[0] : interfaces;
        }
    }

    static final class Violation {
        final Tier tier;
        final boolean classMiss; // true = missing class, false = missing member on an existing class
        final String key;        // stable, dedup + allowlist key
        final String exampleSrc; // one class that references it
        Violation(Tier tier, boolean classMiss, String key, String exampleSrc) {
            this.tier = tier; this.classMiss = classMiss; this.key = key; this.exampleSrc = exampleSrc;
        }
        boolean isHard(boolean strictInternal) {
            switch (tier) {
                case HARD: return true;                            // public JDK surface: class OR member miss blocks
                case UNKNOWN: return true;                         // non-JDK, non-server: must exist in-jar
                case INTERNAL: return classMiss || strictInternal; // internal: class miss blocks, member miss warns
                default: return false;                             // IGNORE never records
            }
        }
    }

    /** One allowlist entry: an anchored key prefix, optionally scoped to referrers under fromPrefix. */
    static final class AllowEntry {
        final String keyPrefix;
        final String fromPrefix; // null = any referrer (the original unscoped form)
        AllowEntry(String keyPrefix, String fromPrefix) {
            this.keyPrefix = keyPrefix; this.fromPrefix = fromPrefix;
        }
    }

    static final Map<String, ClassInfo> baseline = new HashMap<String, ClassInfo>();
    static final Map<String, ClassInfo> underTest = new HashMap<String, ClassInfo>();
    static final Map<String, Violation> violations = new LinkedHashMap<String, Violation>();
    static final List<AllowEntry> allow = new ArrayList<AllowEntry>();
    static final List<String> ignorePrefixes = new ArrayList<String>();

    static boolean strictInternal = false;
    static int allowlisted = 0;

    // Public JDK surface (a miss here is unambiguous): the java.* core plus the JDK-shipped javax.*/org.* trees.
    static final String[] HARD_PREFIXES =
        { "java/", "javax/", "org/w3c/", "org/xml/sax/", "org/ietf/jgss/", "org/omg/", "org/jcp/" };
    // JDK-internal surface (inherently volatile): a class miss still blocks, a member miss warns by default.
    static final String[] INTERNAL_PREFIXES = { "jdk/", "sun/", "com/sun/" };

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: Jdk8ApiGate <jarToCheck> <jdk8Home> [--strict-internal] "
                + "[--allow <file>] [--ignore <prefix>]...");
            System.exit(2);
        }
        File jar = new File(args[0]);
        File jdk8 = new File(args[1]);
        String allowFile = null;
        for (int i = 2; i < args.length; i++) {
            if ("--strict-internal".equals(args[i])) strictInternal = true;
            else if ("--allow".equals(args[i]) && i + 1 < args.length) allowFile = args[++i];
            else if ("--ignore".equals(args[i]) && i + 1 < args.length) ignorePrefixes.add(args[++i]);
        }
        if (!jar.isFile()) { System.err.println("FATAL: jar not found: " + jar); System.exit(2); }
        loadAllow(allowFile);

        // 1. Index the real Java-8 API surface from a genuine JDK 8 (rt.jar + the rest of jre/lib + ext + tools.jar).
        List<File> baseJars = new ArrayList<File>();
        collectJars(new File(jdk8, "jre/lib"), baseJars);
        collectJars(new File(jdk8, "jre/lib/ext"), baseJars);
        collectJars(new File(jdk8, "lib"), baseJars);      // JDK tools.jar lives here; also the JRE-style rt.jar
        collectJars(new File(jdk8, "lib/ext"), baseJars);
        if (baseJars.isEmpty()) {
            System.err.println("FATAL: no Java-8 baseline jars under " + jdk8 + " (need a real JDK 8 rt.jar)");
            System.exit(2);
        }
        for (File f : baseJars) index(f, baseline, false);
        if (baseline.get("java/lang/Object") == null) {
            System.err.println("FATAL: baseline does not contain java.lang.Object — is " + jdk8 + " a JDK 8?");
            System.exit(2);
        }

        // 2. Index the jar-under-test's BASE tree (skip META-INF/versions/*), so an in-jar type resolves its
        //    hierarchy AND a non-JDK reference can be proved present in-jar.
        index(jar, underTest, true);

        // 3. Walk every reference in the downgraded BASE bytecode.
        scan(jar);

        // 4. Report + verdict.
        System.exit(report(jar, baseJars.size()));
    }

    // ── indexing ────────────────────────────────────────────────────────────────────────────────────
    static void index(File jarFile, final Map<String, ClassInfo> into, final boolean baseTreeOnly)
            throws IOException {
        JarFile jf = new JarFile(jarFile);
        try {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".class") || n.equals("module-info.class")) continue;
                // The base v52 tree is the only one that runs on Java 8; a versioned overlay must not
                // overwrite a base ClassInfo (that would corrupt hierarchy resolution). Baseline jars carry
                // their own versions/ dirs too — skip them everywhere.
                if (n.startsWith("META-INF/")) continue;
                byte[] bytes = readAll(jf.getInputStream(e));
                try {
                    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                        ClassInfo cur;
                        @Override public void visit(int v, int a, String name, String sig, String sup, String[] itf) {
                            cur = new ClassInfo(sup, itf);
                            into.put(name, cur);
                        }
                        @Override public MethodVisitor visitMethod(int a, String mn, String md, String s, String[] x) {
                            if (cur != null) cur.methods.add(mn + md);
                            return null;
                        }
                        @Override public FieldVisitor visitField(int a, String fn, String fd, String s, Object val) {
                            if (cur != null) cur.fields.add(fn + fd);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (Throwable t) {
                    System.err.println("[jdk8-gate] warn: could not index " + n + " (" + t + ")");
                }
            }
        } finally {
            jf.close();
        }
    }

    // ── scanning the jar under test (BASE tree only) ────────────────────────────────────────────────────
    static void scan(File jarFile) throws IOException {
        JarFile jf = new JarFile(jarFile);
        try {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".class")) continue;
                if (n.startsWith("META-INF/") || n.equals("module-info.class")) continue;
                byte[] bytes = readAll(jf.getInputStream(e));
                try {
                    new ClassReader(bytes).accept(new ScanClassVisitor(), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (Throwable t) {
                    record(Tier.HARD, false, "UNPARSEABLE " + n + " — " + t, n);
                }
            }
        } finally {
            jf.close();
        }
    }

    static final class ScanClassVisitor extends ClassVisitor {
        String src = "?";
        ScanClassVisitor() { super(Opcodes.ASM9); }
        @Override public void visit(int v, int a, String name, String sig, String sup, String[] itf) {
            src = name;
            if (sup != null) checkType(sup, src);
            if (itf != null) for (String i : itf) checkType(i, src);
        }
        @Override public AnnotationVisitor visitAnnotation(String desc, boolean vis) { return ann(desc, src); }
        @Override public AnnotationVisitor visitTypeAnnotation(int tr, TypePath tp, String desc, boolean vis) { return ann(desc, src); }
        @Override public FieldVisitor visitField(int a, final String n, final String d, String s, Object val) {
            noteDesc(d, src);
            final String fsrc = src;
            return new FieldVisitor(Opcodes.ASM9) {
                @Override public AnnotationVisitor visitAnnotation(String desc, boolean vis) { return ann(desc, fsrc); }
                @Override public AnnotationVisitor visitTypeAnnotation(int tr, TypePath tp, String desc, boolean vis) { return ann(desc, fsrc); }
            };
        }
        @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] exc) {
            noteDesc(d, src);
            if (exc != null) for (String ex : exc) checkType(ex, src);
            return new ScanMethodVisitor(src);
        }
    }

    static final class ScanMethodVisitor extends MethodVisitor {
        final String src;
        ScanMethodVisitor(String src) { super(Opcodes.ASM9); this.src = src; }
        @Override public void visitTypeInsn(int op, String type) { checkTypeOperand(type, src); }
        @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
            noteDesc(desc, src);
            checkMember(owner, name, desc, false, src);
        }
        @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
            noteDesc(desc, src);
            checkMember(owner, name, desc, true, src);
        }
        @Override public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
            noteDesc(desc, src);
            checkHandle(bsm, src);
            for (Object o : bsmArgs) noteConst(o, src);
        }
        @Override public void visitLdcInsn(Object cst) { noteConst(cst, src); }
        @Override public void visitMultiANewArrayInsn(String desc, int dims) { noteDesc(desc, src); }
        @Override public void visitTryCatchBlock(Label s, Label e, Label h, String type) {
            if (type != null) checkType(type, src);
        }
        @Override public AnnotationVisitor visitAnnotation(String desc, boolean vis) { return ann(desc, src); }
        @Override public AnnotationVisitor visitParameterAnnotation(int p, String desc, boolean vis) { return ann(desc, src); }
        @Override public AnnotationVisitor visitTypeAnnotation(int tr, TypePath tp, String desc, boolean vis) { return ann(desc, src); }
        @Override public AnnotationVisitor visitInsnAnnotation(int tr, TypePath tp, String desc, boolean vis) { return ann(desc, src); }
        @Override public AnnotationVisitor visitTryCatchAnnotation(int tr, TypePath tp, String desc, boolean vis) { return ann(desc, src); }
        @Override public AnnotationVisitor visitLocalVariableAnnotation(int tr, TypePath tp, Label[] st, Label[] en, int[] idx, String desc, boolean vis) { return ann(desc, src); }
        @Override public AnnotationVisitor visitAnnotationDefault() { return new AnnScanner(src); }
    }

    /** Scans annotation element values: Class-literal types, enum constants, and nested annotations. */
    static final class AnnScanner extends AnnotationVisitor {
        final String src;
        AnnScanner(String src) { super(Opcodes.ASM9); this.src = src; }
        @Override public void visit(String name, Object value) { if (value instanceof Type) noteType((Type) value, src); }
        @Override public void visitEnum(String name, String desc, String value) { noteDesc(desc, src); }
        @Override public AnnotationVisitor visitAnnotation(String name, String desc) { noteDesc(desc, src); return this; }
        @Override public AnnotationVisitor visitArray(String name) { return this; }
    }

    static AnnotationVisitor ann(String desc, String src) {
        noteDesc(desc, src);
        return new AnnScanner(src);
    }

    static void noteConst(Object o, String src) {
        if (o instanceof Type) noteType((Type) o, src);
        else if (o instanceof Handle) checkHandle((Handle) o, src);
        else if (o instanceof ConstantDynamic) {
            ConstantDynamic cd = (ConstantDynamic) o;
            noteDesc(cd.getDescriptor(), src);
            checkHandle(cd.getBootstrapMethod(), src);
            for (int i = 0; i < cd.getBootstrapMethodArgumentCount(); i++) noteConst(cd.getBootstrapMethodArgument(i), src);
        }
    }

    static void checkHandle(Handle h, String src) {
        int tag = h.getTag();
        boolean method = tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKESTATIC
            || tag == Opcodes.H_INVOKESPECIAL || tag == Opcodes.H_NEWINVOKESPECIAL
            || tag == Opcodes.H_INVOKEINTERFACE;
        noteDesc(h.getDesc(), src);
        checkMember(h.getOwner(), h.getName(), h.getDesc(), method, src);
    }

    // ── reference checks ──────────────────────────────────────────────────────────────────────────────
    static void noteDesc(String desc, String src) {
        if (desc == null || desc.isEmpty()) return;
        if (desc.charAt(0) == '(') {
            Type mt = Type.getMethodType(desc);
            for (Type a : mt.getArgumentTypes()) noteType(a, src);
            noteType(mt.getReturnType(), src);
        } else {
            noteType(Type.getType(desc), src);
        }
    }

    static void noteType(Type t, String src) {
        if (t == null) return;
        if (t.getSort() == Type.ARRAY) t = t.getElementType();
        if (t.getSort() == Type.OBJECT) checkType(t.getInternalName(), src);
        else if (t.getSort() == Type.METHOD) noteDesc(t.getDescriptor(), src);
    }

    static void checkTypeOperand(String operand, String src) {
        if (operand == null || operand.isEmpty()) return;
        if (operand.charAt(0) == '[') noteType(Type.getType(operand), src);
        else checkType(operand, src);
    }

    /** Class-existence check for a referenced type. */
    static void checkType(String internalName, String src) {
        Tier tier = tierOf(internalName);
        if (tier == Tier.IGNORE) return;
        if (tier == Tier.HARD || tier == Tier.INTERNAL) {
            if (baseline.containsKey(internalName) || underTest.containsKey(internalName)) return;
            record(tier, true, "CLASS " + internalName, src);
            return;
        }
        // UNKNOWN: non-JDK, non-server. It must be carried in the jar (a shaded runtime, our own code) — a
        // reference resolving nowhere would NoClassDefFoundError on a real 1.8 server (subsumes the H2 gap).
        if (underTest.containsKey(internalName)) return;
        record(Tier.UNKNOWN, true, "CLASS " + internalName, src);
    }

    /** Member-existence check for a referenced method/field, with full hierarchy resolution. */
    static void checkMember(String owner, String name, String desc, boolean method, String src) {
        if (owner == null) return;
        if (owner.charAt(0) == '[') {
            // Arrays expose java.lang.Object's members; clone() is a synthetic every array has on every JDK.
            if ("clone".equals(name)) return;
            owner = "java/lang/Object";
        }
        // Signature-polymorphic methods carry the call-site descriptor, not a declared one — never a real miss.
        if (method && "java/lang/invoke/MethodHandle".equals(owner)
            && ("invoke".equals(name) || "invokeExact".equals(name))) return;
        Tier tier = tierOf(owner);
        if (tier == Tier.IGNORE) return;
        if (tier == Tier.UNKNOWN) {
            // Only the owning class must exist in-jar; its members are javac-consistent by construction, and
            // hierarchy that escapes into an ignored server supertype is inconclusive (never a real miss).
            if (!underTest.containsKey(owner)) record(Tier.UNKNOWN, true, "CLASS " + owner, src);
            return;
        }
        if (lookup(owner) == null) {
            record(tier, true, "CLASS " + owner, src); // owner class itself absent from Java 8
            return;
        }
        if (resolveMember(owner, name + desc, method, new HashSet<String>())) return;
        record(tier, false, (method ? "METHOD " : "FIELD ") + owner + "#" + name + " " + desc, src);
    }

    static boolean resolveMember(String owner, String key, boolean method, Set<String> seen) {
        if (owner == null || !seen.add(owner)) return false;
        ClassInfo ci = lookup(owner);
        if (ci == null) return false; // hierarchy escaped into an unindexed type — inconclusive => not found
        if (method ? ci.methods.contains(key) : ci.fields.contains(key)) return true;
        for (String itf : ci.interfaces) if (resolveMember(itf, key, method, seen)) return true;
        return resolveMember(ci.superName, key, method, seen);
    }

    /**
     * Resolve a class by name. For a JDK (HARD-tier) owner, the real baseline is authoritative — never let a
     * same-named class bundled in the jar-under-test shadow it (that could hide real JDK-8 members). For any
     * other owner, a bundled copy wins, since that copy is what actually runs.
     */
    static ClassInfo lookup(String name) {
        if (tierOf(name) == Tier.HARD) {
            ClassInfo b = baseline.get(name);
            return b != null ? b : underTest.get(name);
        }
        ClassInfo c = underTest.get(name);
        return c != null ? c : baseline.get(name);
    }

    static Tier tierOf(String internalName) {
        if (internalName == null || internalName.isEmpty()) return Tier.IGNORE;
        // JDK prefixes are always HARD/INTERNAL — never shadowable by an --ignore entry.
        for (String p : HARD_PREFIXES) if (internalName.startsWith(p)) return Tier.HARD;
        for (String p : INTERNAL_PREFIXES) if (internalName.startsWith(p)) return Tier.INTERNAL;
        for (String p : ignorePrefixes) if (internalName.startsWith(p)) return Tier.IGNORE;
        return Tier.UNKNOWN;
    }

    static void record(Tier tier, boolean classMiss, String key, String src) {
        // Anchored match: an allow entry must be a PREFIX of the key (so it includes the METHOD/FIELD/CLASS tag
        // and owner, and ideally the descriptor) — never an unanchored substring that could swallow unrelated misses.
        // A FROM-scoped entry additionally requires the REFERENCING class to sit under its referrer prefix, so a
        // vetted shaded-library probe never grants the same miss to first-party code: the check runs per reference,
        // and an out-of-scope referrer of the same key still records a violation here.
        for (AllowEntry a : allow) {
            if ((key.equals(a.keyPrefix) || key.startsWith(a.keyPrefix))
                    && (a.fromPrefix == null || src.startsWith(a.fromPrefix))) {
                allowlisted++;
                return;
            }
        }
        if (!violations.containsKey(key)) violations.put(key, new Violation(tier, classMiss, key, src));
    }

    // ── reporting ───────────────────────────────────────────────────────────────────────────────────
    static int report(File jar, int baseJarCount) {
        List<Violation> hard = new ArrayList<Violation>();
        List<Violation> warn = new ArrayList<Violation>();
        for (Violation v : violations.values()) (v.isHard(strictInternal) ? hard : warn).add(v);

        System.out.println("[jdk8-gate] scanned " + jar.getName()
            + " (base tree) against a Java-8 baseline of " + baseline.size() + " classes (" + baseJarCount + " jars)"
            + "; " + underTest.size() + " in-jar classes; " + ignorePrefixes.size() + " server-provided ignore prefix(es)"
            + (strictInternal ? " [strict-internal]" : ""));
        if (allowlisted > 0) System.out.println("[jdk8-gate] " + allowlisted + " reference(s) allow-listed");

        if (!warn.isEmpty()) {
            System.out.println("[jdk8-gate] " + warn.size() + " WARNING(s) — JDK-internal member absent from Java 8 (not blocking; --strict-internal to enforce):");
            for (Violation v : warn) System.out.println("    WARN  " + v.key + "   (e.g. in " + v.exampleSrc + ")");
        }
        if (!hard.isEmpty()) {
            System.out.println("[jdk8-gate] " + hard.size() + " VIOLATION(s) — reference resolves neither in Java 8, in-jar, nor a server-provided package:");
            for (Violation v : hard) System.out.println("    FAIL  " + v.key + "   (e.g. in " + v.exampleSrc + ")");
            System.out.println();
            System.out.println("[jdk8-gate] Each FAIL would NoSuchMethodError/NoClassDefFoundError on a real 1.8 server.");
            System.out.println("[jdk8-gate] Fix: use a Java-8-available alternative (or one jvmdg shims); shade the missing");
            System.out.println("[jdk8-gate] runtime; add a documented --ignore prefix if the server truly provides it; or, if a");
            System.out.println("[jdk8-gate] reference is provably safe, add a matching prefix to the --allow file.");
            return 1;
        }
        System.out.println("[jdk8-gate] OK — every reference in the downgraded base tree resolves in Java 8, in-jar, or a server package.");
        return 0;
    }

    // ── io helpers ──────────────────────────────────────────────────────────────────────────────────
    static void collectJars(File dir, List<File> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) if (f.isFile() && f.getName().endsWith(".jar")) out.add(f);
    }

    static void loadAllow(String allowFile) throws IOException {
        if (allowFile == null) return;
        File f = new File(allowFile);
        if (!f.isFile()) return;
        for (String line : Files.readAllLines(f.toPath())) {
            String s = line.trim();
            if (s.isEmpty() || s.charAt(0) == '#') continue;
            // " FROM " splits the key prefix from the optional referrer scope. The token cannot occur inside
            // a key: owners/descriptors are internal names (no spaces), so a key holds at most two spaces —
            // after the tag and after the member name — neither of which precedes the word FROM.
            int from = s.indexOf(" FROM ");
            if (from >= 0) {
                allow.add(new AllowEntry(s.substring(0, from).trim(), s.substring(from + 6).trim()));
            } else {
                allow.add(new AllowEntry(s, null));
            }
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private Jdk8ApiGate() {}
}
