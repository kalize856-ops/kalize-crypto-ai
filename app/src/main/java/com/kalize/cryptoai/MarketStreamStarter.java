package com.kalize.cryptoai;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Starts the persistent market stream from a visible app interaction. */
public final class MarketStreamStarter {
    private MarketStreamStarter() { }
    public static void start(Context context) {
        Intent intent = new Intent(context, MarketDataService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    /** Requests an explicit socket reset without stopping the foreground service. */
    public static void reconnect(Context context) {
        Intent intent = new Intent(context, MarketDataService.class);
        intent.setAction(MarketDataService.ACTION_RECONNECT);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }
}
