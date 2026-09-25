package dev.flowlens.core;

import com.google.gson.reflect.TypeToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Standalone entry point; Gradle uses the same core without running application code. */
public final class Main {
    public static void main(String[] args) {
        try { System.exit(run(args)); }
        catch (Exception error) {
            System.err.println("Flow Lens failed: " + error.getMessage());
            System.exit(1);
        }
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0 || List.of(args).contains("--help")) {
            System.out.println("""
                    Flow Lens prototype — Java 17+
                    analyze --input <classes-dir|jar|class> [--input ...] --output <dir>
                            [--rules <rules.json>] [--max-findings 20]
                    explain --report <analysis.json> --method '<owner#name(descriptor)>'
                    Exit codes: 0 completed within declared scope, 1 failure, 2 partial analysis.
                    Findings are candidates; zero findings do not establish safety.
                    """);
            return 0;
        }
        String command = args[0];
        List<Path> inputs = new ArrayList<>();
        Path output = null, rulesFile = null, reportFile = null;
        String methodId = null;
        int maxFindings = 20;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 1; i < args.length; i += 2) {
            String flag = args[i];
            if (i + 1 == args.length) throw new IllegalArgumentException("Missing value for " + flag);
            if (!flag.equals("--input") && !seen.add(flag)) throw new IllegalArgumentException("Repeated option " + flag);
            String value = args[i + 1];
            if (command.equals("analyze")) {
                switch (flag) {
                    case "--input" -> inputs.add(Path.of(value));
                    case "--output" -> output = Path.of(value);
                    case "--rules" -> rulesFile = Path.of(value);
                    case "--max-findings" -> maxFindings = Integer.parseInt(value);
                    default -> throw new IllegalArgumentException("Unknown analyze option: " + flag);
                }
            } else if (command.equals("explain")) {
                switch (flag) {
                    case "--report" -> reportFile = Path.of(value);
                    case "--method" -> methodId = value;
                    default -> throw new IllegalArgumentException("Unknown explain option: " + flag);
                }
            } else throw new IllegalArgumentException("Expected analyze or explain");
        }
        if (command.equals("analyze")) {
            if (inputs.isEmpty() || output == null) throw new IllegalArgumentException("--input and --output are required");
            if (maxFindings < 1) throw new IllegalArgumentException("--max-findings must be positive");
            List<Rule> rules = rulesFile == null ? Rule.defaults() : Rules.load(rulesFile);
            Map<String, Object> report = new BytecodeAnalyzer().analyze(inputs, rules);
            Reports.write(report, output, maxFindings);
            var analysis = Reports.object(report.get("analysis"));
            System.out.println("Status: " + analysis.get("status") + "; candidate flows: " + analysis.get("findingCount"));
            System.out.println("Reports: " + output.toAbsolutePath().normalize());
            return "partial".equals(analysis.get("status")) ? 2 : 0;
        }
        if (!command.equals("explain") || reportFile == null || methodId == null)
            throw new IllegalArgumentException("explain requires --report and --method");
        try (var reader = Files.newBufferedReader(reportFile)) {
            Map<String, Object> report = Reports.JSON.fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());
            System.out.println(Reports.JSON.toJson(Reports.explain(report, methodId)));
        }
        return 0;
    }
}
