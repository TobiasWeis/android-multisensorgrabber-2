package de.weis.multisensor_grabber;

import android.app.Activity;
import android.os.Bundle;
import android.text.Html;
import android.widget.TextView;

/**
 * Created by weis on 27.12.16.
 */

public class AboutActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        TextView tv = (TextView) findViewById(R.id.textView_about);
        tv.setText(
                Html.fromHtml("<h1>Multisensor Grabber</h1><br>" +
                        "<p>Developer: Tobias Weis (<a href=\"mailto:mail@tobias-weis.de\">mail@tobias-weis.de</a>), " +
                        "<a href=\"http://www.tobias-weis.de\">www.tobias-weis.de</a><br><br>" +
                        "<b>DISCLAIMER:</b><br>Some features (e.g. exposure-control) are not available on specific phones. This app is using the camera2 API<br><br>" +
                        "This app has been written to grab geo-annotated sequences for computer-vision experiments." +
                        "Feel free to modify the code and use it for your work (no paper or report yet to cite):<br>" +
                        "<a href=\"https://github.com/TobiasWeis/multisensorgrabber\">https://github.com/TobiasWeis/multisensorgrabber</a></p>")
        );
    }
}
