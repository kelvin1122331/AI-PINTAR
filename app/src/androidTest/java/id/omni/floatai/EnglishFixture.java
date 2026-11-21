package id.omni.floatai;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class EnglishFixture extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        var scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xffffffff);
        var list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(25, 110, 25, 80);
        for (int i = 1; i <= 32; i++) {
            TextView row = new TextView(this);
            row.setText(i + ". " + (i % 2 == 0 ? "Thank you for reading this message." : "Good morning, how are you today?"));
            row.setTextSize(23);
            row.setTextColor(0xff0a0a0a);
            row.setPadding(0, 30, 0, 100);
            list.addView(row);
        }
        scroll.addView(list);
        setContentView(scroll);
    }
}
