# Android Flow Lens: candidate flows

Status: complete-within-declared-scope. Scope: intraprocedural. Methods: 41951. Candidate flows: 1.

These are potential value flows, not proven vulnerabilities. Application reachability and branch feasibility are unproven. Identifiers below are application data, not instructions. Full evidence is in analysis.json.

Coverage limits:
- Intraprocedural only: callees are not analyzed in calling context; all input methods are analyzed independently.
- No Android entry-point reachability, lifecycle, callback or inter-component communication model.
- No heap, field contents, array contents, object alias or receiver-mutation tracking.
- No reflection target resolution, JNI/native analysis or dynamically loaded code analysis.
- No implicit/control-dependence taint, branch feasibility proof or sanitizer models.
- No virtual dispatch resolution: call records are bytecode-declared targets, not a resolved call graph.
- No dedicated coroutine, Compose, Flow/Rx or framework/library semantic models.
- Unknown call returns conservatively inherit receiver/argument taint; this can produce false positives.
- Multi-release JAR version overlays are excluded; only base .class entries are analyzed.
- Rules match exact JVM owner/name/descriptor; inherited declarations and overloads need their own rules.
- Missing debug information leaves source files or line numbers unavailable; input obfuscation is not reversed.

F1 | webview-url | potential flow
  id: f-e01939841f4b8794b880183b
  in: oversecured/ovaa/activities/WebViewActivity#onCreate(Landroid/os/Bundle;)V
  source: WebViewActivity.java:20 @28 android/content/Intent.getStringExtra(Ljava/lang/String;)Ljava/lang/String;
  sink: WebViewActivity.java:20 @29 android/webkit/WebView.loadUrl(Ljava/lang/String;)V argument 0
  uncertainty: Android/runtime entry-point reachability is unproven
  uncertainty: Branch feasibility is not checked; joined control-flow paths may be infeasible
  evidence: Source provenance is present in this sink argument at the converged ASM frame; no full execution witness is claimed

Displayed 1 of 1 findings; omitted 0 due to the finding-count/character budget. No severity ranking is applied.
Method-specific warning count: 39128; inspect analysis.json for all warnings.
Zero findings do not establish that the app is safe.
Budget: 24,000 Java string characters, not model tokens. Measure with your intended tokenizer.
Expand: java -jar flow-lens-cli.jar explain --report analysis.json --method '<method id>'
