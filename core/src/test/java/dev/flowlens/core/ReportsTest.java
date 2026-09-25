package dev.flowlens.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportsTest {
    @TempDir Path temporary;
    @Test void limitsPresentationWithoutDroppingFullEvidence() throws Exception {
        Map<String,Object> report = report(3, "unknown-return-summary");
        Reports.write(report, temporary, 1);
        String compact = Files.readString(temporary.resolve("llm.md"));
        String full = Files.readString(temporary.resolve("analysis.json"));
        assertTrue(compact.contains("Displayed 1 of 3 findings; omitted 2"));
        assertTrue(compact.contains("unknown-return-summary"));
        assertTrue(full.contains("id-2"));
        assertFalse(compact.contains("id-2"));
        assertTrue(compact.contains("not proven vulnerabilities"));
    }
    @Test void longEvidenceIsOmittedWholeAndTheBudgetIsHonored() throws Exception {
        Map<String,Object> report = report(2, "x".repeat(30_000));
        Reports.write(report, temporary, 20);
        String compact = Files.readString(temporary.resolve("llm.md"));
        assertTrue(compact.length() <= Reports.CHARACTER_BUDGET);
        assertTrue(compact.contains("Displayed 0 of 2 findings; omitted 2"));
        assertFalse(compact.contains("id-0"));
    }
    @Test void strictRulesRejectTyposAndFractionalArgument() throws Exception {
        String valid = "[{\"id\":\"s\",\"kind\":\"sink\",\"owner\":\"a/B\",\"name\":\"sink\",\"descriptor\":\"(Ljava/lang/String;)V\",\"argument\":0}]";
        Path file = temporary.resolve("rules.json");
        Files.writeString(file, valid);
        assertEquals(1, Rules.load(file).size());
        Files.writeString(file, valid.replace("\"argument\":0", "\"argument\":0.5"));
        assertThrows(IllegalArgumentException.class, () -> Rules.load(file));
        Files.writeString(file, valid.replace("\"kind\"", "\"knd\""));
        assertThrows(IllegalArgumentException.class, () -> Rules.load(file));
    }
    @Test void explainRetrievesTheRequestedMethodAndRejectsUnknownIds() {
        Map<String,Object> report = report(2, "uncertain");
        report.put("methods", List.of(Map.of("id", "a/B#m()V", "instructions", List.of())));
        report.put("calls", List.of(Map.of("caller", "a/B#m()V", "name", "sink")));
        assertEquals(2, Reports.objects(Reports.explain(report,"a/B#m()V").get("findings")).size());
        assertThrows(IllegalArgumentException.class, () -> Reports.explain(report, "missing"));
    }
    private static Map<String,Object> report(int count, String uncertainty) {
        Map<String,Object> site = Map.of("method", "a/B#m()V", "sourceFile", "B.java", "line", 10,
                "instruction", 2, "owner", "a/B", "name", "sink", "descriptor", "(Ljava/lang/String;)V", "argument", 0);
        List<Map<String,Object>> findings = new ArrayList<>();
        for (int i=0;i<count;i++) findings.add(Map.of("id", "id-"+i, "rule", "rule", "source", site,
                "sink", site, "uncertainty", List.of(uncertainty), "evidence", "value provenance"));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("analysis", Map.of("status", "partial", "scope", "intraprocedural", "methodCount", 1,
                "coverage", List.of("No interprocedural analysis")));
        result.put("findings", findings);
        result.put("warnings", List.of("a/B#m()V: field content not tracked"));
        return result;
    }
}
