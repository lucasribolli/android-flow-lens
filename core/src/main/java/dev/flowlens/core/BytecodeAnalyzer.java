package dev.flowlens.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;
import org.objectweb.asm.util.Printer;

/**
 * Read-only, intraprocedural bytecode inventory and explicit-value taint prototype.
 * Input classes are parsed as data and are never loaded or executed.
 */
public final class BytecodeAnalyzer {
    private static final List<String> LIMITATIONS = List.of(
            "Intraprocedural only: callees are not analyzed in calling context; all input methods are analyzed independently.",
            "No Android entry-point reachability, lifecycle, callback or inter-component communication model.",
            "No heap, field contents, array contents, object alias or receiver-mutation tracking.",
            "No reflection target resolution, JNI/native analysis or dynamically loaded code analysis.",
            "No implicit/control-dependence taint, branch feasibility proof or sanitizer models.",
            "No virtual dispatch resolution: call records are bytecode-declared targets, not a resolved call graph.",
            "No dedicated coroutine, Compose, Flow/Rx or framework/library semantic models.",
            "Unknown call returns conservatively inherit receiver/argument taint; this can produce false positives.",
            "Multi-release JAR version overlays are excluded; only base .class entries are analyzed.",
            "Rules match exact JVM owner/name/descriptor; inherited declarations and overloads need their own rules.",
            "Missing debug information leaves source files or line numbers unavailable; input obfuscation is not reversed.");

    public Map<String, Object> analyze(List<Path> inputs, List<Rule> rules) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(rules, "rules");
        if (inputs.isEmpty()) throw new IllegalArgumentException("At least one class, JAR or class directory is required");
        Set<String> ids = new HashSet<>();
        for (Rule rule : rules) {
            if (!ids.add(rule.id())) throw new IllegalArgumentException("Duplicate rule id: " + rule.id());
        }
        SortedSet<String> warnings = new TreeSet<>();
        SortedMap<String, ClassNode> classes = readClasses(inputs, warnings);
        List<Map<String, Object>> methods = new ArrayList<>();
        List<Map<String, Object>> calls = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        int analyzed = 0;
        int failed = 0;
        for (ClassNode clazz : classes.values()) {
            List<MethodNode> ordered = new ArrayList<>(clazz.methods);
            ordered.sort(Comparator.comparing((MethodNode m) -> m.name).thenComparing(m -> m.desc));
            for (MethodNode method : ordered) {
                String id = methodId(clazz.name, method.name, method.desc);
                int[] lines = lines(method);
                List<Map<String, Object>> instructionList = new ArrayList<>();
                for (int i = 0; i < method.instructions.size(); i++) {
                    AbstractInsnNode insn = method.instructions.get(i);
                    instructionList.add(instruction(method, insn, i, lines[i]));
                    if (insn instanceof MethodInsnNode invoke) {
                        calls.add(map("caller", id, "instruction", i, "line", lines[i],
                                "owner", invoke.owner, "name", invoke.name, "descriptor", invoke.desc,
                                "opcode", opcode(insn), "resolution", "declared-target-only"));
                        if (invoke.name.equals("<init>")) {
                            warnings.add(id + ": constructor/receiver mutation is not modeled");
                        }
                    } else if (insn instanceof InvokeDynamicInsnNode invoke) {
                        calls.add(map("caller", id, "instruction", i, "line", lines[i],
                                "name", invoke.name, "descriptor", invoke.desc, "opcode", opcode(insn),
                                "bootstrapOwner", invoke.bsm.getOwner(), "bootstrapName", invoke.bsm.getName(),
                                "resolution", "dynamic-target-unresolved"));
                        warnings.add(id + ": invokedynamic target is not resolved");
                    }
                    if (insn instanceof FieldInsnNode) {
                        warnings.add(id + ": field contents are not tracked");
                    }
                    if (isArrayAccess(insn.getOpcode())) {
                        warnings.add(id + ": array contents are not tracked");
                    }
                }
                Map<String, Object> record = map("id", id, "owner", clazz.name, "name", method.name,
                        "descriptor", method.desc, "sourceFile", clazz.sourceFile,
                        "access", method.access, "instructions", instructionList,
                        "instructionIndexMeaning", "ASM instruction-list index, including labels and line-number nodes");
                methods.add(record);
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    record.put("analysisStatus", "no-bytecode-body");
                    record.put("cfgEdges", List.of());
                    if ((method.access & Opcodes.ACC_NATIVE) != 0) warnings.add(id + ": native body unavailable");
                    continue;
                }
                TaintInterpreter interpreter = new TaintInterpreter(clazz, method, lines, rules);
                SortedSet<Edge> edges = new TreeSet<>();
                Analyzer<TaintValue> analyzer = new Analyzer<>(interpreter) {
                    @Override protected void newControlFlowEdge(int from, int to) {
                        edges.add(new Edge(from, to, "normal"));
                    }
                    @Override protected boolean newControlFlowExceptionEdge(int from, int to) {
                        edges.add(new Edge(from, to, "exception"));
                        return true;
                    }
                };
                try {
                    Frame<TaintValue>[] frames = analyzer.analyze(clazz.name, method);
                    List<Map<String, Object>> methodFindings = new ArrayList<>();
                    collectFindings(clazz, method, lines, frames, rules, methodFindings);
                    findings.addAll(methodFindings);
                    record.put("analysisStatus", "analyzed");
                    analyzed++;
                } catch (AnalyzerException | RuntimeException error) {
                    failed++;
                    record.put("analysisStatus", "failed");
                    record.put("analysisError", error.getClass().getSimpleName() + ": " + error.getMessage());
                    warnings.add(id + ": analysis failed; findings for this method are omitted");
                }
                record.put("cfgEdges", edges.stream().map(e -> map("from", e.from, "to", e.to, "kind", e.kind)).toList());
            }
        }
        if (classes.isEmpty()) warnings.add("No .class files were found in the supplied inputs");
        Map<String, Object> analysis = map("engine", "asm-explicit-value-taint", "status",
                failed > 0 || classes.isEmpty() ? "partial" : "complete-within-declared-scope",
                "scope", "intraprocedural", "classCount", classes.size(), "methodCount", methods.size(),
                "analyzedMethodCount", analyzed, "failedMethodCount", failed, "findingCount", findings.size(),
                "reachability", "all-input-methods; Android/runtime reachability unproven",
                "findingMeaning", "potential explicit-value flow, not proof of exploitability",
                "coverage", LIMITATIONS,
                "rules", rules.stream().sorted(Comparator.comparing(Rule::id))
                        .map(r -> map("id", r.id(), "kind", r.kind(), "owner", r.owner(), "name", r.name(),
                                "descriptor", r.descriptor(), "argument", r.argument())).toList());
        return map("schemaVersion", "1.0", "analysis", analysis, "methods", methods,
                "calls", calls, "findings", findings, "warnings", new ArrayList<>(warnings));
    }

    private static void collectFindings(ClassNode clazz, MethodNode method, int[] lines,
            Frame<TaintValue>[] frames, List<Rule> rules, List<Map<String, Object>> findings) {
        String methodId = methodId(clazz.name, method.name, method.desc);
        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode insn = method.instructions.get(index);
            if (!(insn instanceof MethodInsnNode call) || frames[index] == null) continue;
            int argumentCount = Type.getArgumentTypes(call.desc).length;
            for (Rule rule : rules.stream().sorted(Comparator.comparing(Rule::id)).toList()) {
                if (!rule.kind().equals("sink") || !rule.matches(call.owner, call.name, call.desc)) continue;
                Frame<TaintValue> frame = frames[index];
                TaintValue argument = frame.getStack(frame.getStackSize() - argumentCount + rule.argument());
                for (Trace trace : argument.traces.values()) {
                    SortedSet<String> uncertainty = new TreeSet<>(trace.assumptions);
                    uncertainty.add("Android/runtime entry-point reachability is unproven");
                    uncertainty.add("Branch feasibility is not checked; joined control-flow paths may be infeasible");
                    Map<String, Object> sink = map("rule", rule.id(), "method", methodId, "instruction", index,
                            "sourceFile", clazz.sourceFile, "line", lines[index], "argument", rule.argument(),
                            "owner", call.owner, "name", call.name, "descriptor", call.desc);
                    findings.add(map("id", findingId(trace.origin.id + "->" + methodId + "@" + index + ":" + rule.id()),
                            "rule", rule.id(), "kind", "potential-taint-flow", "source", trace.origin.location,
                            "sink", sink, "valueTaintOrigin", trace.origin.id,
                            "uncertainty", new ArrayList<>(uncertainty),
                            "evidence", "Source provenance is present in this sink argument at the converged ASM frame; no full execution witness is claimed"));
                }
            }
        }
    }

    private static SortedMap<String, ClassNode> readClasses(List<Path> inputs, SortedSet<String> warnings) {
        SortedMap<String, ClassNode> result = new TreeMap<>();
        Set<Path> visited = new HashSet<>();
        try {
            for (Path input : inputs.stream().map(p -> p.toAbsolutePath().normalize()).sorted().toList()) {
                if (!Files.exists(input)) throw new IllegalArgumentException("Input does not exist: " + input);
                if (Files.isDirectory(input)) {
                    try (Stream<Path> files = Files.walk(input)) {
                        for (Path file : files.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                            if (visited.add(file.toRealPath())) addClass(Files.readAllBytes(file), file.toString(), result);
                        }
                    }
                } else if (visited.add(input.toRealPath())) {
                    if (input.toString().endsWith(".class")) {
                        addClass(Files.readAllBytes(input), input.toString(), result);
                    } else if (input.toString().endsWith(".jar")) {
                        try (JarFile jar = new JarFile(input.toFile())) {
                            long overlays = jar.stream().filter(e -> !e.isDirectory())
                                    .filter(e -> e.getName().startsWith("META-INF/versions/") && e.getName().endsWith(".class")).count();
                            if (overlays > 0) warnings.add("JAR " + input.getFileName() + ": skipped " + overlays
                                    + " multi-release version overlay classes; only base entries are analyzed");
                            for (JarEntry entry : jar.stream().filter(e -> !e.isDirectory())
                                    .filter(e -> e.getName().endsWith(".class"))
                                    .filter(e -> !e.getName().startsWith("META-INF/versions/"))
                                    .sorted(Comparator.comparing(JarEntry::getName)).toList()) {
                                try (InputStream stream = jar.getInputStream(entry)) {
                                    addClass(stream.readAllBytes(), input + "!/" + entry.getName(), result);
                                }
                            }
                        }
                    } else throw new IllegalArgumentException("Expected .class, .jar or class directory: " + input);
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Unable to read analysis inputs", error);
        }
        return result;
    }

    private static void addClass(byte[] bytes, String input, Map<String, ClassNode> classes) {
        ClassNode clazz = new ClassNode(Opcodes.ASM9);
        try {
            new ClassReader(bytes).accept(clazz, ClassReader.SKIP_FRAMES);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Cannot parse class from " + input, error);
        }
        if (clazz.name.equals("module-info")) return;
        if (classes.putIfAbsent(clazz.name, clazz) != null) {
            throw new IllegalArgumentException("Duplicate class " + clazz.name + " in " + input
                    + "; resolve duplicate inputs so method identities remain unambiguous");
        }
    }

    private static int[] lines(MethodNode method) {
        int[] result = new int[method.instructions.size()];
        int line = -1;
        for (int i = 0; i < result.length; i++) {
            if (method.instructions.get(i) instanceof LineNumberNode node) line = node.line;
            result[i] = line;
        }
        return result;
    }

    private static boolean isArrayAccess(int opcode) {
        return (opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD)
                || (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE);
    }

    private static String methodId(String owner, String name, String descriptor) {
        return owner + "#" + name + descriptor;
    }

    private static String opcode(AbstractInsnNode insn) { return Printer.OPCODES[insn.getOpcode()]; }

    private static String findingId(String key) {
        try {
            return "f-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 unavailable", impossible);
        }
    }

    private static Map<String, Object> instruction(MethodNode method, AbstractInsnNode insn, int index, int line) {
        String operation = insn.getOpcode() >= 0 ? opcode(insn)
                : insn instanceof LabelNode ? "LABEL" : insn instanceof LineNumberNode ? "LINE" : "METADATA";
        Map<String, Object> result = map("index", index, "opcode", operation, "line", line);
        if (insn instanceof VarInsnNode variable) result.put("local", variable.var);
        if (insn instanceof IincInsnNode increment) {
            result.put("local", increment.var);
            result.put("increment", increment.incr);
        }
        if (insn instanceof MethodInsnNode call) result.put("callee", methodId(call.owner, call.name, call.desc));
        if (insn instanceof FieldInsnNode field) result.put("field", field.owner + "#" + field.name + ":" + field.desc);
        if (insn instanceof TypeInsnNode type) result.put("type", type.desc);
        if (insn instanceof LdcInsnNode constant) result.put("constantType", constant.cst.getClass().getSimpleName());
        if (insn instanceof JumpInsnNode jump) result.put("target", method.instructions.indexOf(jump.label));
        if (insn instanceof TableSwitchInsnNode table) {
            result.put("defaultTarget", method.instructions.indexOf(table.dflt));
            result.put("targets", table.labels.stream().map(method.instructions::indexOf).toList());
        }
        if (insn instanceof LookupSwitchInsnNode lookup) {
            result.put("defaultTarget", method.instructions.indexOf(lookup.dflt));
            result.put("targets", lookup.labels.stream().map(method.instructions::indexOf).toList());
        }
        return result;
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private record Edge(int from, int to, String kind) implements Comparable<Edge> {
        @Override public int compareTo(Edge other) {
            int value = Integer.compare(from, other.from);
            if (value == 0) value = Integer.compare(to, other.to);
            return value == 0 ? kind.compareTo(other.kind) : value;
        }
    }

    private record Origin(String id, Map<String, Object> location) { }
    private record Trace(Origin origin, SortedSet<String> assumptions) { }

    private static final class TaintValue implements Value {
        final BasicValue basic;
        final SortedMap<String, Trace> traces;
        TaintValue(BasicValue basic, Map<String, Trace> traces) {
            this.basic = basic;
            this.traces = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(traces));
        }
        @Override public int getSize() { return basic.getSize(); }
        @Override public boolean equals(Object object) {
            return object instanceof TaintValue other && basic.equals(other.basic) && traces.equals(other.traces);
        }
        @Override public int hashCode() { return Objects.hash(basic, traces); }
    }

    private static final class TaintInterpreter extends Interpreter<TaintValue> {
        private final BasicInterpreter basic = new BasicInterpreter();
        private final ClassNode clazz;
        private final MethodNode method;
        private final int[] lines;
        private final List<Rule> rules;

        TaintInterpreter(ClassNode clazz, MethodNode method, int[] lines, List<Rule> rules) {
            super(Opcodes.ASM9);
            this.clazz = clazz;
            this.method = method;
            this.lines = lines;
            this.rules = rules;
        }

        private TaintValue value(BasicValue type, TaintValue... from) {
            return type == null ? null : new TaintValue(type, traces(List.of(from), null));
        }

        private static SortedMap<String, Trace> traces(Collection<? extends TaintValue> values, String assumption) {
            SortedMap<String, Trace> result = new TreeMap<>();
            for (TaintValue value : values) {
                for (Trace trace : value.traces.values()) {
                    SortedSet<String> merged = new TreeSet<>(trace.assumptions);
                    Trace previous = result.get(trace.origin.id);
                    if (previous != null) merged.addAll(previous.assumptions);
                    if (assumption != null) merged.add(assumption);
                    result.put(trace.origin.id, new Trace(trace.origin, java.util.Collections.unmodifiableSortedSet(merged)));
                }
            }
            return result;
        }

        @Override public TaintValue newValue(Type type) { return value(basic.newValue(type)); }

        @Override public TaintValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
            return value(basic.newOperation(insn));
        }

        @Override public TaintValue copyOperation(AbstractInsnNode insn, TaintValue v) throws AnalyzerException {
            return value(basic.copyOperation(insn, v.basic), v);
        }

        @Override public TaintValue unaryOperation(AbstractInsnNode insn, TaintValue v) throws AnalyzerException {
            BasicValue type = basic.unaryOperation(insn, v.basic);
            // Field contents and new array contents are not represented by receiver/dimension taint.
            if (insn.getOpcode() == Opcodes.GETFIELD || insn.getOpcode() == Opcodes.NEWARRAY
                    || insn.getOpcode() == Opcodes.ANEWARRAY) return value(type);
            return value(type, v);
        }

        @Override public TaintValue binaryOperation(AbstractInsnNode insn, TaintValue a, TaintValue b)
                throws AnalyzerException {
            BasicValue type = basic.binaryOperation(insn, a.basic, b.basic);
            if (isArrayAccess(insn.getOpcode())) return value(type);
            return value(type, a, b);
        }

        @Override public TaintValue ternaryOperation(AbstractInsnNode insn, TaintValue a, TaintValue b, TaintValue c)
                throws AnalyzerException {
            return value(basic.ternaryOperation(insn, a.basic, b.basic, c.basic));
        }

        @Override public TaintValue naryOperation(AbstractInsnNode insn, List<? extends TaintValue> values)
                throws AnalyzerException {
            BasicValue type = basic.naryOperation(insn, values.stream().map(v -> v.basic).toList());
            if (type == null) return null;
            int index = method.instructions.indexOf(insn);
            String methodId = methodId(clazz.name, method.name, method.desc);
            if (insn instanceof MethodInsnNode call) {
                SortedMap<String, Trace> result = traces(values,
                        "Assumed unknown-call propagation from receiver/arguments to return at " + methodId + "@" + index
                                + " (" + methodId(call.owner, call.name, call.desc) + ")");
                for (Rule rule : rules) {
                    if (rule.kind().equals("source") && rule.matches(call.owner, call.name, call.desc)) {
                        String originId = rule.id() + ":" + methodId + "@" + index;
                        Origin origin = new Origin(originId, map("rule", rule.id(), "method", methodId,
                                "instruction", index, "sourceFile", clazz.sourceFile, "line", lines[index],
                                "owner", call.owner, "name", call.name, "descriptor", call.desc));
                        result.put(originId, new Trace(origin, java.util.Collections.emptySortedSet()));
                    }
                }
                return new TaintValue(type, result);
            }
            if (insn instanceof InvokeDynamicInsnNode) {
                return new TaintValue(type, traces(values,
                        "Assumed invokedynamic argument-to-return propagation at " + methodId + "@" + index));
            }
            return value(type); // MULTIANEWARRAY: contents and dimension-to-element flows are unmodeled.
        }

        @Override public void returnOperation(AbstractInsnNode insn, TaintValue value, TaintValue expected)
                throws AnalyzerException {
            basic.returnOperation(insn, value.basic, expected.basic);
        }

        @Override public TaintValue merge(TaintValue a, TaintValue b) {
            if (a.equals(b)) return a;
            return new TaintValue(basic.merge(a.basic, b.basic), traces(List.of(a, b), null));
        }
    }
}
