package dev.flowlens.gradle;

import dev.flowlens.core.BytecodeAnalyzer;
import dev.flowlens.core.Reports;
import dev.flowlens.core.Rule;
import dev.flowlens.core.Rules;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Consumes AGP's class artifacts without changing the compiled application. */
@DisableCachingByDefault(because = "Prototype reports may contain local artifact paths")
public abstract class AnalyzeFlowTask extends DefaultTask {
    @Classpath
    public abstract ListProperty<RegularFile> getInputJars();

    @Classpath
    public abstract ListProperty<Directory> getInputDirectories();

    @Input
    public abstract Property<Integer> getMaxFindings();

    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getRulesFile();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void analyze() {
        int maxFindings = getMaxFindings().get();
        if (maxFindings < 1) {
            throw new GradleException("flowLens.maxFindings must be at least 1");
        }

        Set<Path> artifacts = new LinkedHashSet<>();
        for (RegularFile jar : getInputJars().get()) {
            artifacts.add(jar.getAsFile().toPath());
        }
        for (Directory directory : getInputDirectories().get()) {
            artifacts.add(directory.getAsFile().toPath());
        }

        Path outputDirectory = getOutputDirectory().get().getAsFile().toPath();
        Map<String, Object> report;
        try {
            List<Rule> rules = getRulesFile().isPresent()
                    ? Rules.load(getRulesFile().get().getAsFile().toPath())
                    : Rule.defaults();
            report = new BytecodeAnalyzer().analyze(new ArrayList<>(artifacts), rules);
            Reports.write(report, outputDirectory, maxFindings);
        } catch (Exception exception) {
            throw new GradleException("Flow Lens analysis failed for " + getPath(), exception);
        }
        getLogger().lifecycle("Flow Lens reports: {}", outputDirectory);
        if (report.get("analysis") instanceof Map<?, ?> analysis
                && "partial".equals(analysis.get("status"))) {
            throw new GradleException("Flow Lens analysis is partial; inspect the preserved reports at "
                    + outputDirectory);
        }
    }
}
