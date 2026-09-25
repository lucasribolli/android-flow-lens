package dev.flowlens.gradle;

import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import com.android.build.api.variant.ScopedArtifacts;
import java.util.Locale;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/** Android application plugin using AGP's public variant and artifact APIs. */
public final class FlowLensPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        FlowLensExtension extension = project.getExtensions().create("flowLens", FlowLensExtension.class);
        extension.getMaxFindings().convention(20);

        project.getPluginManager().withPlugin("com.android.application", ignored -> {
            ApplicationAndroidComponentsExtension androidComponents =
                    project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
            androidComponents.onVariants(androidComponents.selector().all(), (Action<ApplicationVariant>) variant -> {
                String variantName = variant.getName();
                String taskName = "analyze" + variantName.substring(0, 1).toUpperCase(Locale.ROOT)
                        + variantName.substring(1) + "Flow";
                TaskProvider<AnalyzeFlowTask> task = project.getTasks().register(taskName, AnalyzeFlowTask.class, analysis -> {
                    analysis.setGroup("verification");
                    analysis.setDescription("Analyze compiled code flow and candidate taint paths for " + variantName);
                    analysis.getMaxFindings().convention(extension.getMaxFindings());
                    analysis.getRulesFile().convention(extension.getRulesFile());
                    analysis.getOutputDirectory().convention(
                            project.getLayout().getBuildDirectory().dir("reports/flowlens/" + variantName));
                });
                variant.getArtifacts().forScope(ScopedArtifacts.Scope.ALL).use(task).toGet(
                        ScopedArtifact.CLASSES.INSTANCE,
                        AnalyzeFlowTask::getInputJars,
                        AnalyzeFlowTask::getInputDirectories);
            });
        });
    }
}
