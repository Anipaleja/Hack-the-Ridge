package com.example.sensorysafe;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Centralized storage for alert-related user settings.
 */
public final class AlertSettings {

    private static final String PREFS_NAME = "sensorysafe_prefs";
    private static final String KEY_ALERT_THRESHOLD_RELATIVE_DB = "alert_threshold_relative_db";

    // Default threshold on the relative 0-100 scale used in FirstFragment (approx -40 dBFS).
    public static final double DEFAULT_ALERT_THRESHOLD_RELATIVE_DB = 60.0;

    private AlertSettings() {
        // no-op
    }

    public static double getAlertThresholdRelativeDb(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getFloat(
                KEY_ALERT_THRESHOLD_RELATIVE_DB,
                (float) DEFAULT_ALERT_THRESHOLD_RELATIVE_DB
        );
    }

    public static void setAlertThresholdRelativeDb(Context context, double value) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putFloat(KEY_ALERT_THRESHOLD_RELATIVE_DB, (float) value)
                .apply();
    }
}