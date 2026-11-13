package com.max.pod;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Opened from Android's Settings > Apps > Pod ("Additional settings in the app"), and from the
 * Music Folder item in Pod's own Settings menu. Chooses where Pod looks for music.
 */
public class SettingsActivity extends Activity {
    static final int PICK = 1;
    static final String STORAGE = "com.android.externalstorage.documents";
    TextView folder;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Pod settings");
        int pad = dp(20);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, pad, pad, pad);

        TextView heading = new TextView(this);
        heading.setText("Music folder");
        heading.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
        list.addView(heading);

        folder = new TextView(this);
        folder.setTextAppearance(android.R.style.TextAppearance_Material_Title);
        folder.setPadding(0, dp(4), 0, dp(4));
        list.addView(folder);

        TextView help = new TextView(this);
        help.setText("Pod lists the songs in this folder and in every folder inside it.");
        help.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        help.setPadding(0, 0, 0, dp(16));
        list.addView(help);

        Button choose = new Button(this);
        choose.setText("Choose folder");
        choose.setOnClickListener(v -> pick());
        list.addView(choose);

        Button reset = new Button(this);
        reset.setText("Use all music on this phone");
        reset.setOnClickListener(v -> { Folder.clear(this); show(); });
        list.addView(reset);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        setContentView(scroll);
        show();
    }

    int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    void show() {
        String label = Folder.label(this);
        folder.setText(label == null ? "All music on this phone" : label);
    }

    void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        String vol = Folder.volume(this);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(STORAGE,
                (vol == null ? "primary" : vol) + ":" + (vol == null ? "Music" : Folder.path(this))));
        try {
            startActivityForResult(i, PICK);
        } catch (Exception e) {
            Toast.makeText(this, "No folder picker on this phone", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (code != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        String id = STORAGE.equals(tree.getAuthority()) ? DocumentsContract.getTreeDocumentId(tree) : null;
        int colon = id == null ? -1 : id.indexOf(':');
        if (colon <= 0) {
            Toast.makeText(this, "Pick a folder on the phone's storage or SD card", Toast.LENGTH_LONG).show();
            return;
        }
        String path = id.substring(colon + 1);
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        Folder.set(this, id.substring(0, colon), path);
        show();
    }
}
