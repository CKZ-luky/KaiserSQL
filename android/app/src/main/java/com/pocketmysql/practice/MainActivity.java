package com.pocketmysql.practice;

import com.getcapacitor.BridgeActivity;
import android.os.Bundle;
import android.webkit.WebSettings;

public class MainActivity extends BridgeActivity {
    @Override public void onCreate(Bundle savedInstanceState) {
        registerPlugin(OfflineLabPlugin.class);
        super.onCreate(savedInstanceState);
        if ("offline".equals(BuildConfig.FLAVOR)) {
            getWindow().getDecorView().setBackgroundColor(0xfffffdf4);getBridge().getWebView().setBackgroundColor(0xfffffdf4);
            if(android.os.Build.VERSION.SDK_INT<35){getWindow().setStatusBarColor(0xfffffdf4);getWindow().setNavigationBarColor(0xfffffdf4);}
            androidx.core.view.WindowInsetsControllerCompat bars=androidx.core.view.WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView());
            bars.setAppearanceLightStatusBars(true);bars.setAppearanceLightNavigationBars(true);
        }
        else if (BuildConfig.DEBUG) getBridge().getWebView().getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
    }
}
