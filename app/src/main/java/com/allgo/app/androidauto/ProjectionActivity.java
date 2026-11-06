package com.allgo.app.androidauto;

import android.app.Activity;
import android.os.Bundle;

/** AllGo's Android Auto screen, opened by MG4 Dash and other launchers: shows DiAuto instead. */
public class ProjectionActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiAutoLink.openScreen(this);
        finish();
    }
}
