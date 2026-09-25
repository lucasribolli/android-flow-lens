package dev.flowlens.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** SDK-independent task integration; does not simulate AGP's variant pipeline. */
class FlowLensPluginTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void registersExtensionWithExpectedDefaults() {
        Project project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build();
        project.getPluginManager().apply(FlowLensPlugin.class);

        FlowLensExtension extension = project.getExtensions().findByType(FlowLensExtension.class);
        assertNotNull(extension);
        assertEquals(20, extension.getMaxFindings().get());
        assertFalse(extension.getRulesFile().isPresent());
    }

    @Test
    void taskWritesFullAndCompactReportsFromCompiledClasses() throws IOException {
        Project project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build();
        project.getPluginManager().apply(FlowLensPlugin.class);
        Path classes = temporaryDirectory.resolve("classes");
        Files.createDirectories(classes.resolve("demo"));
        Files.write(classes.resolve("demo/PluginFixture.class"), fixtureBytecode());

        FlowLensExtension extension = project.getExtensions().getByType(FlowLensExtension.class);
        AnalyzeFlowTask task = project.getTasks().create("analyzeFixtureFlow", AnalyzeFlowTask.class);
        task.getInputJars().set(List.of());
        task.getInputDirectories().set(List.of(project.getLayout().getProjectDirectory().dir("classes")));
        task.getMaxFindings().set(extension.getMaxFindings());
        task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("reports/flowlens/fixture"));

        task.analyze();

        Path reports = task.getOutputDirectory().get().getAsFile().toPath();
        Path fullReport = reports.resolve("analysis.json");
        Path compactReport = reports.resolve("llm.md");
        assertTrue(Files.isRegularFile(fullReport));
        assertTrue(Files.isRegularFile(compactReport));
        String analysis = Files.readString(fullReport);
        String compact = Files.readString(compactReport);
        assertTrue(analysis.contains("getStringExtra"), "The full report must retain the source call");
        assertTrue(analysis.contains("exec"), "The full report must retain the sink call");
        assertTrue(compact.contains("getStringExtra"), "The compact finding must retain source evidence");
        assertTrue(compact.contains("exec"), "The compact finding must retain sink evidence");
    }

    private static byte[] fixtureBytecode() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/PluginFixture", null, "java/lang/Object", null);
        writer.visitSource("PluginFixture.java", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run",
                "(Landroid/content/Intent;)V",
                null,
                new String[]{"java/io/IOException"});
        method.visitCode();
        Label start = new Label();
        method.visitLabel(start);
        method.visitLineNumber(12, start);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime", "()Ljava/lang/Runtime;", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn("input");
        method.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "android/content/Intent",
                "getStringExtra",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        method.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Runtime",
                "exec",
                "(Ljava/lang/String;)Ljava/lang/Process;",
                false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(3, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
