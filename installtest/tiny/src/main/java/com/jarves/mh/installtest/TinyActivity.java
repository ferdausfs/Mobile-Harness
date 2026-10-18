package com.jarves.mh.installtest;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Minimal install probe. If you can open this, the phone accepted a
 * targetSdk-28 APK signed with the Mobile Harness release key.
 */
public class TinyActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        TextView title = new TextView(this);
        title.setText("Install test OK");
        title.setTextSize(22f);
        title.setGravity(Gravity.CENTER);
        TextView body = new TextView(this);
        body.setText("com.jarves.mh.installtest (tiny)\n\nThe phone installs targetSdk-28 APKs\nsigned with the Mobile Harness release key.\n\nYou can uninstall this app now.");
        body.setTextSize(14f);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, 32, 0, 0);
        root.addView(title);
        root.addView(body);
        setContentView(root);
    }
}
