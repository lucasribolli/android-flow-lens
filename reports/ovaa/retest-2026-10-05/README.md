# Reteste do Flow Lens no OVAA — 2026-10-05

**Resultado: aprovado no build Android real e no teste de abertura do aplicativo no dispositivo conectado.**

Projeto, atualmente em `/home/lucas/Documents/repositories/ovaa`. Ambiente: Gradle 8.7, AGP 8.5.1, JDK 21, SDK/target 34, NDK 27.0.11902837. O build nativo CMake foi mantido e compilou para arm64-v8a, armeabi-v7a, x86 e x86_64. O plugin foi integrado via `includeBuild` em `settings.gradle` e aplicado ao módulo `app`. As versões do projeto não foram alteradas.

## Problema encontrado e corrigido

A primeira execução falhou com `OutOfMemoryError: Java heap space` em `Reports.write`: a serialização criava uma string com todo o JSON antes da escrita. O relatório de todas as dependências tem 363.928.498 bytes, causando cópias grandes em memória.

O plugin agora usa Gson com `BufferedWriter`, escrevendo o JSON diretamente no arquivo. O build passou em 35 segundos com o mesmo `org.gradle.jvmargs=-Xmx1536m`. O analisador ainda mantém o grafo e as evidências estruturadas na memória; esta correção não transforma a análise inteira em um processo de memória constante.

O empacotamento do JAR também foi corrigido: o uso de `runtimeClasspath` incluía APIs do próprio Gradle, Groovy e Kotlin, gerando um artefato de 85.714.413 bytes. Uma configuração dedicada agora inclui apenas o plugin, o core, ASM e Gson. O JAR final tem **655.416 bytes**, com o descritor do plugin presente e sem as bibliotecas fornecidas pelo host.

## Resultado da análise

| Medida | Resultado |
| --- | ---: |
| Classes, incluindo dependências | 5.557 |
| Métodos inventariados | 41.951 |
| Métodos com bytecode analisados | 40.135 |
| Métodos pertencentes ao pacote do OVAA | 98 |
| Métodos sem corpo de bytecode | 1.816 |
| Falhas de análise de métodos | 0 |
| Fluxos candidatos | 1 |
| JSON completo | 363.928.498 bytes |
| Resumo para LLM | 2.532 caracteres / bytes UTF-8 |

O único fluxo das regras padrão é `Intent.getStringExtra(String)` → `WebView.loadUrl(String)`, em `WebViewActivity.onCreate`, linha 20. A chamada constante `Runtime.exec("logcat -d")` permanece no inventário, sem ser classificada como fluxo contaminado.

O resumo é uma seleção de evidências orientada ao achado, não uma representação equivalente de todos os métodos. A quantidade de tokens de um tokenizer específico não foi medida. `maxFindings = 100` afeta apenas a apresentação compacta; o JSON preserva todas as evidências.

## Verificações

- `:app:analyzeDebugFlow :app:assembleDebug`: sucesso.
- `:core:test :gradle-plugin:test`: 20 testes, zero falhas, erros ou testes ignorados. Inclui a nova verificação de preservação do JSON e das evidências omitidas no resumo compacto.
- `:gradle-plugin:validatePlugins`: sucesso.
- `:core:standaloneJar :gradle-plugin:standaloneJar`: ambos os JARs atualizados.
- O JAR distribuível foi carregado isoladamente em uma cópia do OVAA por `buildscript classpath files` e `apply plugin`, sem `includeBuild`. A análise passou em 14 segundos; tanto o JSON completo quanto o resumo foram **idênticos byte a byte** aos relatórios da integração por código-fonte.
- O comando CLI `explain` leu o relatório completo com `-Xmx2g` e extraiu `WebViewActivity.onCreate`; fonte, sink, linha e status foram verificados.
- O APK gerado foi instalado com `adb install -r` no Moto G8 Play com Android 10.
- A abertura de `EntranceActivity` chegou à tela de login, com `Status: ok`.
- O deeplink `oversecured://ovaa/webview?url=https%3A%2F%2Fexample.com` abriu `WebViewActivity`, com `Status: ok`; a página foi verificada visualmente.
- O processo permaneceu ativo e o log coletado desse processo não apresentou `FATAL EXCEPTION`.
- O Mobile MCP confirmou o OVAA em primeiro plano e o conteúdo da página na hierarquia da interface.
- O Frida MCP anexou ao processo, mas o runtime não disponibilizava a ponte Java. Nenhum hook Java foi executado. A sessão foi desconectada sem encerrar o aplicativo.

O teste no dispositivo demonstra a execução desse caminho com uma URL de exemplo; não demonstra exploração. A análise continua intraprocedural: não prova o caminho completo entre deeplink, Intent e lifecycle, não cobre JNI e não identifica todas as vulnerabilidades intencionais do OVAA.

## Reproduzir

```bash
cd /home/lucas/Documents/source-codes/ovaa
export JAVA_HOME=/home/lucas/Documents/Codex/2026-09-17/x20/work/toolchains/jdk
./gradlew :app:analyzeDebugFlow :app:assembleDebug --console=plain
```

Os relatórios são gerados em `app/build/reports/flowlens/debug/`. A integração já está aplicada; `ovaa-integration.patch` registra as alterações, sem necessidade de reaplicá-lo.

```bash
java -Xmx2g -jar /home/lucas/Documents/repositories/android-flow-lens/core/build/libs/flow-lens-cli-0.1.0-SNAPSHOT-all.jar explain \
  --report /home/lucas/Documents/repositories/ovaa/app/build/reports/flowlens/debug/analysis.json \
  --method 'oversecured/ovaa/activities/WebViewActivity#onCreate(Landroid/os/Bundle;)V'
```

## Evidências salvas

- [Resumo para LLM](llm.md)
- [Evidência expandida da WebView](webview-evidence.json)
- JSON completo, aproximadamente 364 MB: `analysis.json` fica disponível somente na cópia local e foi excluído do Git por exceder o limite de tamanho do GitHub. Gere-o novamente com o comando acima quando necessário.
- [Métricas e hashes dos artefatos](summary.json)
- [Build Android](gradle-debug.log), [testes e validação](gradle-tests.log), [falha anterior à correção](gradle-before-fix.log)
- [Teste do JAR isolado](gradle-standalone-jar.log), [empacotamento](gradle-packaging.log), [conteúdo do JAR](jar-content-check.json), [conferência do inventário](inventory-check.json)
- [Resultado no dispositivo](device-result.json), [abertura inicial](device-launch.txt), [deeplink](device-deeplink.txt), [log do processo](device-app-logcat.txt)
- [Validação pelos MCPs](mcp-validation.json)
- [Integração aplicada ao OVAA](ovaa-integration.patch)
- [Captura da WebView no aparelho](device-webview.png)
