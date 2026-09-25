package dev.flowlens.sample;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;

/** Synthetic analysis fixture containing an intentionally unsafe data flow. */
public final class MainActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView browser = new WebView(this);
        setContentView(browser);
        // Intentional source-to-sink fixture. Do not use this in a production app.
        String untrustedUrl = getIntent().getStringExtra("url");
        browser.loadUrl(untrustedUrl);
    }

    /** Negative fixture: overwriting the value removes the explicit taint. */
    public static void overwrittenInput(Intent intent, WebView browser) {
        String value = intent.getStringExtra("url");
        value = "about:blank";
        browser.loadUrl(value);
    }
}
