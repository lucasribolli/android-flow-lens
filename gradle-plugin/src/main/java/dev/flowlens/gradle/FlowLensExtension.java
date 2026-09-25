package dev.flowlens.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/** User configuration shared by the application's variant analysis tasks. */
public abstract class FlowLensExtension {
    /** Maximum findings included in the compact LLM context report. */
    public abstract Property<Integer> getMaxFindings();

    /** Optional JSON rule list; when absent the core's bundled rules are used. */
    public abstract RegularFileProperty getRulesFile();
}
