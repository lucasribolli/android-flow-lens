# Android Flow Lens: architecture and analysis contract

Android Flow Lens is a Gradle plugin packaged as a JAR that reads compiled application classes and produces evidence for security review. The first version analyzes an Android Gradle project that can be built. “White-box, pre-obfuscation analysis” describes the intended result: preserve the application's available symbols and explain data movement before release optimization. It does not regenerate original source code or guarantee that every application behavior is represented.

The integration is a build-time bytecode analysis step, immediately downstream of Java/Kotlin compilation. It is not a compiler frontend extension. JVM bytecode provides a shared representation for both languages, while a future Kotlin metadata/source-mapping layer can improve how generated methods, suspend functions, and inline functions appear to reviewers.

```text
Java / Kotlin + generated sources
              |
       variant compilation
              |
    AGP scoped CLASSES artifacts ---------> D8 / R8 --> APK / AAB
              |
       read-only analysis task
              |
    ASM bytecode + source locations
              |
    method CFGs + declared call sites
              |
    configured intra-method taint rules
              |
    complete JSON report --> compact LLM evidence export
```

The prototype targets Java 17, the public Android Gradle Plugin 8.7.3 API, and ASM 9.7.1. These are explicit compatibility pins, not claims about the newest releases. The adapter registers variant tasks through `androidComponents.onVariants`, obtains `ScopedArtifact.CLASSES` with `ScopedArtifacts.Scope.ALL`, and consumes those inputs through `toGet`. It reads artifacts without rewriting the application. The public scoped artifact API is preferable to the removed Transform API; Google documents both its replacement APIs and the distinction between project-only and all-class scopes. [Android Gradle plugin API guidance](https://developer.android.com/build/releases/gradle-plugin-api-updates)

Select the release variant when release-specific code is the security target. Its class artifacts are captured before that application's R8 optimization, retaining available names in the current module. Debug and release can have different source sets, constants, dependencies, and generated code. Dependencies already distributed in obfuscated form remain obfuscated; collecting them before the application's R8 step cannot recover their original symbols. Source filenames and line numbers are also conditional on the metadata present. R8 can remove, inline, merge, and rename code, which is why the capture boundary matters. [R8 optimization overview](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)

Three kinds of evidence must remain distinct:

| Representation | Question answered | Prototype boundary |
| --- | --- | --- |
| Control-flow graph (CFG) | Which instructions can follow another instruction within this method? | Bytecode control flow; not a proof that a branch is feasible at runtime. |
| Call information | Which method does this instruction declare it invokes? | Declared call sites, not a fully resolved whole-application call graph. Virtual dispatch and Android entry points need additional modeling. |
| Taint result | Can a configured source label reach a configured sink argument under the analysis model? | Flow-sensitive explicit taint inside one method, with uncertain propagation across unknown calls. |

A source-to-sink match is a candidate for investigation. It does not independently establish attacker control, reachability from an exported component, an exploitable vulnerability, or a particular severity. Absence of findings does not establish absence of vulnerabilities.

The core uses ASM to inspect classes and propagate abstract values over method control flow. Labels attached to stack/local values are merged at joins. This supports local assignments and overwrites while combining alternatives from different branches. It is flow-sensitive, but not path-sensitive: mutually incompatible branches may still contribute to a reported flow. ASM supplies bytecode facilities; the security semantics are supplied by this project's rules and transfer logic. [ASM project](https://asm.ow2.io/)

The initial rule contract is deliberately narrow:

1. Match methods by exact JVM owner, method name, and descriptor. Overloads are separate rules. The owner is the bytecode-declared owner; a superclass rule does not imply hierarchy-based matching.
2. A source introduces taint on its returned value. This does not model callback parameters, out-parameters, fields, or implicit sources automatically.
3. A sink checks configured zero-based declared argument positions. The receiver is not argument zero. Rules must distinguish, for example, a logging tag from a logged message.
4. Sanitizer rules are not implemented. A future sanitizer should be an explicitly trusted return-value summary with a specific taint category, security context, and conditions under which the return becomes safe. Sanitization must never be inferred from an API's name, and clearing a return must not silently clear the original argument or aliases.
5. An unmatched call can heuristically propagate input taint to its return. That edge must remain marked uncertain. This heuristic cannot represent arbitrary callee behavior or side effects, and may produce both false positives and false negatives.

The current rule fields are `id`, `kind`, `owner`, `name`, `descriptor`, and `argument`: `kind` is `source` or `sink`, sources use `argument=-1` for the return, and sinks use a declared argument index. Category-specific labels are future work. Rule sets should be versioned and reviewed with the code they describe. URL encoding, hashing, encryption, escaping, and validation are not universal sanitizers: their safety depends on the sink and threat model. A production schema should reject invalid argument indexes, void-return source/sanitizer rules, and ambiguous conflicts instead of silently weakening analysis.

The first version does not claim interprocedural, alias/heap, lifecycle, callback, intent/IPC, reflection, JNI, dynamic-loading, coroutine, or implicit-control-flow coverage. Any locally approximated field or call behavior is not a substitute for those models. Methods are inspected independently, including code that may never execute. Exception edges and parse failures should be retained in analysis diagnostics; incomplete analysis must not be presented as a successful security check.

The complete `analysis.json` report is the auditable store. The compact `llm.md` export is a smaller review input containing findings and the relevant method, source, sink, and location references. The CLI supports method-ID-based explanation. Compact output preserves uncertainty and analysis limitations; it is not a lossless encoding of application behavior, and a full bytecode CFG is not necessarily smaller than source text.

For production, extend this into retrieval over a versioned schema. This is a proposed contract, not a description of every field currently implemented:

```text
run: schema, tool/rule versions, variant, input digest, coverage, diagnostics
method: id, JVM signature, source location, class origin
call: caller id, instruction id, declared target, resolution status
finding: id, rule id, source ref, sink ref, evidence refs, uncertainty
slice: finding id, bounded relevant methods/edges, omitted counts
```

Use stable IDs to avoid repeating long signatures; keep a dictionary for expansion. Return a small index first, then retrieve a finding's evidence slice or a method's source neighborhood on demand. Deterministic ordering and input hashes enable caching and review diffs. Keep original identifiers available for human navigation. Avoid including string constants unless they explain the finding; redact sensitive values before any external model submission.

The prototype bounds the compact export by finding count and character budget (24,000 characters by default); this is not a token limit. A future token budget should bound complete evidence records rather than cut JSON in the middle of a finding. Report omitted finding counts and how to retrieve them. Measure tokens with the actual target model's tokenizer, comparing source, full JSON, and compact export for the same review task. Bytes or characters divided by four are only estimates. No universal percentage of token savings is established. Evaluate whether compact input preserves useful review results alongside the reduction.

The next development stages should prioritize analysis validity:

1. **Integration and provenance.** Test release/debug/flavor and multi-module builds, generated classes, library dependencies, configuration caching, and AGP compatibility. Attach input hashes, rule versions, task completion status, missing dependencies, and per-method failures to reports. Verify that the analysis task sees original application symbols when release shrinking is enabled.
2. **Interprocedural data flow.** Build a class hierarchy and resolve candidate dispatch targets, then introduce method summaries and an IFDS-style solver for explicit source labels. Add access paths and alias analysis for fields, arrays, containers, and mutation. Bound context/access-path depth explicitly and report any resulting approximation. IFDS alone does not supply an accurate call graph, aliasing, or Android framework semantics.
3. **Android semantics.** Add merged-manifest entry points, component exposure, lifecycle transitions, listeners, asynchronous callbacks, intents, and relevant framework/library summaries. Add Kotlin metadata mapping and coroutine-aware models with dedicated fixtures. Export unresolved edges as uncertainty instead of inventing source-level behavior.
4. **Security review integration.** Add category-specific sanitizers, validated rule packs, evidence retrieval, SARIF, baselines, and CI policies. Gate only on well-defined confidence/severity criteria; keep unsupported analysis visible.

For a production Android analyzer, evaluate an optional FlowDroid backend before implementing all Android semantics from scratch. Its maintained project supports Android/Java static data flow, configurable sources/sinks, library summaries, and an APK analysis path. A practical integration would build a separate unminified analysis APK with release-equivalent inputs, run FlowDroid with the matching Android platform libraries, and normalize its evidence into the same report schema. This path uses post-compilation APK/DEX rather than the prototype's pre-R8 class representation; reconcile source identities and variant differences explicitly. FlowDroid is not integrated in this prototype, and it also requires configuration and validation. [FlowDroid documentation](https://github.com/secure-software-engineering/FlowDroid)

Validation needs positive and negative cases: direct source-to-sink flow, clean constants, overwritten locals, branch joins, overload/argument mismatches, unknown-return propagation, exceptions, and deterministic exports. Future coverage should add sanitizer contexts, cross-method calls, dispatch, aliases, field updates, callbacks, IPC, reflection, and Kotlin cases with known expected outcomes. Use DroidBench's Android-specific microbenchmarks as an external evaluation set, recording supported, unsupported, and timed-out cases separately. [DroidBench](https://github.com/secure-software-engineering/DroidBench)

Report precision/recall against labeled cases, analysis time, peak memory, report size, and measured tokens. Record source/rule/backend versions with every benchmark. A passing bytecode fixture proves the tested transfer behavior; it does not replace an end-to-end Android Gradle build or establish whole-application security coverage.
