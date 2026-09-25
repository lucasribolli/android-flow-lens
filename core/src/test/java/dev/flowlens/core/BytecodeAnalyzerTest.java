package dev.flowlens.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

class BytecodeAnalyzerTest {
    @TempDir Path directory;
    private static final String OWNER = "fixtures/Api";
    private static final List<Rule> RULES = List.of(
            new Rule("source", "source", OWNER, "source", "()Ljava/lang/String;", -1),
            new Rule("sink", "sink", OWNER, "sink", "(Ljava/lang/String;)V", 0));

    @Test void tracksSourceThroughLocalsAndProducesSourceLocationsAndCfg() throws IOException {
        Map<String, Object> result = analyze("direct", "()V", mv -> {
            Label start = new Label();
            mv.visitLabel(start);
            mv.visitLineNumber(12, start);
            source(mv);
            mv.visitVarInsn(ASTORE, 0);
            mv.visitVarInsn(ALOAD, 0);
            sink(mv);
            mv.visitInsn(RETURN);
        });
        assertEquals(1, list(result, "findings").size());
        Map<String, Object> finding = list(result, "findings").get(0);
        assertEquals(12, object(finding, "source").get("line"));
        assertEquals("Fixture.java", object(finding, "source").get("sourceFile"));
        assertEquals("fixtures/Test#direct()V", object(finding, "sink").get("method"));
        assertFalse(list(list(result, "methods").get(0), "cfgEdges").isEmpty());
    }

    @Test void overwritingALocalRemovesTaint() throws IOException {
        Map<String, Object> result = analyze("overwrite", "()V", mv -> {
            source(mv);
            mv.visitVarInsn(ASTORE, 0);
            mv.visitLdcInsn("safe");
            mv.visitVarInsn(ASTORE, 0);
            mv.visitVarInsn(ALOAD, 0);
            sink(mv);
            mv.visitInsn(RETURN);
        });
        assertTrue(list(result, "findings").isEmpty());
    }

    @Test void exactSinkArgumentIsUsedIncludingWideArguments() throws IOException {
        List<Rule> rules = List.of(RULES.get(0),
                new Rule("second", "sink", OWNER, "wide", "(JLjava/lang/String;Ljava/lang/String;)V", 1));
        Map<String, Object> result = analyze("differentArgument", "()V", mv -> {
            mv.visitInsn(LCONST_0);
            mv.visitLdcInsn("safe");
            source(mv);
            mv.visitMethodInsn(INVOKESTATIC, OWNER, "wide", "(JLjava/lang/String;Ljava/lang/String;)V", false);
            mv.visitInsn(RETURN);
        }, rules);
        assertTrue(list(result, "findings").isEmpty());
    }

    @Test void taintOnEitherBranchSurvivesTheJoin() throws IOException {
        Map<String, Object> result = analyze("branch", "(Z)V", mv -> {
            Label safe = new Label();
            Label join = new Label();
            mv.visitVarInsn(ILOAD, 0);
            mv.visitJumpInsn(IFEQ, safe);
            source(mv);
            mv.visitJumpInsn(GOTO, join);
            mv.visitLabel(safe);
            mv.visitLdcInsn("safe");
            mv.visitLabel(join);
            sink(mv);
            mv.visitInsn(RETURN);
        });
        assertEquals(1, list(result, "findings").size());
        assertTrue(list(result, "findings").get(0).get("uncertainty").toString().contains("Branch feasibility"));
    }

    @Test void unknownCallPropagationIsClearlyMarked() throws IOException {
        Map<String, Object> result = analyze("unknown", "()V", mv -> {
            source(mv);
            mv.visitMethodInsn(INVOKESTATIC, OWNER, "maybeTransform", "(Ljava/lang/String;)Ljava/lang/String;", false);
            sink(mv);
            mv.visitInsn(RETURN);
        });
        assertEquals(1, list(result, "findings").size());
        assertTrue(list(result, "findings").get(0).get("uncertainty").toString().contains("unknown-call propagation"));
    }

    @Test void arrayContentsGapIsExplicitAndDoesNotClaimATrack() throws IOException {
        Map<String, Object> result = analyze("array", "()V", mv -> {
            mv.visitInsn(ICONST_1);
            mv.visitTypeInsn(ANEWARRAY, "java/lang/String");
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_0);
            source(mv);
            mv.visitInsn(AASTORE);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(AALOAD);
            sink(mv);
            mv.visitInsn(RETURN);
        });
        assertTrue(list(result, "findings").isEmpty());
        assertTrue(result.get("warnings").toString().contains("array contents are not tracked"));
        assertTrue(object(result, "analysis").get("coverage").toString().contains("No heap"));
    }

    @Test void inputOrderDoesNotChangeReportAndDuplicateIdentityFails() throws IOException {
        Path first = fixture("A", "a", "()V", mv -> mv.visitInsn(RETURN));
        Path second = fixture("B", "b", "()V", mv -> mv.visitInsn(RETURN));
        BytecodeAnalyzer analyzer = new BytecodeAnalyzer();
        assertEquals(analyzer.analyze(List.of(first, second), RULES), analyzer.analyze(List.of(second, first), RULES));
        Path duplicate = directory.resolve("duplicate.class");
        Files.copy(first, duplicate);
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(List.of(first, duplicate), RULES));
    }

    @Test void malformedMethodMarksTheAnalysisPartial() throws IOException {
        Map<String, Object> result = analyze("bad", "()V", mv -> {
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });
        assertEquals("partial", object(result, "analysis").get("status"));
        assertEquals(1, object(result, "analysis").get("failedMethodCount"));
    }

    @Test void invalidRulesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Rule("bad", "source", OWNER, "x", "()V", -1));
        assertThrows(IllegalArgumentException.class,
                () -> new Rule("bad", "sink", OWNER, "x", "(Ljava/lang/String;)V", 1));
        for (String descriptor : List.of("", "I", "()", "()Vtrailing", "(I)Vtrailing", "(V)V", "([V)V", "()L;",
                "()Ljava.lang.String;", "()Lfoo;;", "(Ljava/lang/String)V", "()Q", "((I)V)V")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> new Rule("bad", "sink", OWNER, "x", descriptor, 0), descriptor);
            assertTrue(error.getMessage().contains("Invalid JVM method descriptor"), descriptor);
        }
        assertDoesNotThrow(() -> new Rule("valid", "sink", OWNER, "x", "(J[[Ljava/lang/String;)V", 1));
    }

    @Test void invalidClassFailsExplicitly() throws IOException {
        Path invalid = directory.resolve("invalid.class");
        Files.writeString(invalid, "this is not bytecode");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new BytecodeAnalyzer().analyze(List.of(invalid), RULES));
        assertTrue(error.getMessage().contains("Cannot parse class"));
    }

    @Test void jarInputsAnalyzeBaseEntriesAndWarnAboutMultiReleaseOverlays() throws IOException {
        Path bytecode = fixture("Test", "jarBody", "()V", mv -> {
            source(mv);
            sink(mv);
            mv.visitInsn(RETURN);
        });
        Path jar = directory.resolve("fixture.jar");
        try (JarOutputStream stream = new JarOutputStream(Files.newOutputStream(jar))) {
            stream.putNextEntry(new JarEntry("fixtures/Test.class"));
            stream.write(Files.readAllBytes(bytecode));
            stream.closeEntry();
            stream.putNextEntry(new JarEntry("META-INF/versions/17/fixtures/Test.class"));
            stream.write(Files.readAllBytes(bytecode));
            stream.closeEntry();
        }
        Map<String, Object> result = new BytecodeAnalyzer().analyze(List.of(jar), RULES);
        assertEquals(1, list(result, "findings").size());
        assertTrue(result.get("warnings").toString().contains("skipped 1 multi-release"));
    }

    @Test void unreachableSinkProducesNoFinding() throws IOException {
        Map<String, Object> result = analyze("deadSink", "()V", mv -> {
            source(mv);
            mv.visitVarInsn(ASTORE, 0);
            mv.visitInsn(RETURN);
            mv.visitVarInsn(ALOAD, 0);
            sink(mv);
            mv.visitInsn(RETURN);
        });
        assertTrue(list(result, "findings").isEmpty());
        assertEquals("complete-within-declared-scope", object(result, "analysis").get("status"));
    }

    @Test void unknownInstanceCallTracksTheReceiverWithAnAssumption() throws IOException {
        Map<String, Object> result = analyze("receiver", "()V", mv -> {
            source(mv);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false);
            sink(mv);
            mv.visitInsn(RETURN);
        });
        assertEquals(1, list(result, "findings").size());
        assertTrue(list(result, "findings").get(0).get("uncertainty").toString().contains("receiver/arguments"));
    }

    private Map<String, Object> analyze(String name, String desc, Consumer<MethodVisitor> body) throws IOException {
        return analyze(name, desc, body, RULES);
    }

    private Map<String, Object> analyze(String name, String desc, Consumer<MethodVisitor> body, List<Rule> rules)
            throws IOException {
        return new BytecodeAnalyzer().analyze(List.of(fixture("Test", name, desc, body)), rules);
    }

    private Path fixture(String className, String name, String desc, Consumer<MethodVisitor> body) throws IOException {
        // Explicit generous maxima keep malformed-flow fixtures constructible for the Analyzer failure test.
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, "fixtures/" + className, null, "java/lang/Object", null);
        writer.visitSource("Fixture.java", null);
        MethodVisitor mv = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(16, 8);
        mv.visitEnd();
        writer.visitEnd();
        Path path = directory.resolve(className + ".class");
        Files.write(path, writer.toByteArray());
        return path;
    }

    private static void source(MethodVisitor mv) {
        mv.visitMethodInsn(INVOKESTATIC, OWNER, "source", "()Ljava/lang/String;", false);
    }
    private static void sink(MethodVisitor mv) {
        mv.visitMethodInsn(INVOKESTATIC, OWNER, "sink", "(Ljava/lang/String;)V", false);
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> map, String key) {
        return (Map<String, Object>) map.get(key);
    }
}
