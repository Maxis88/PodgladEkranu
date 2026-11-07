package pl.artgarage.podglad;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ViewerActivity extends Activity {

    private static final String[] TRYBY = {"Ręcznie", "co 10 s", "co 3 s", "co 1 s", "Na żywo"};
    private static final int[] INTERWAL = {0, 10000, 3000, 1000, 250};
    private static final String[] JAKOSCI = {"Oszczędna", "Średnia", "Dobra", "Pełna"};
    private static final float[] SKALA = {0.4f, 0.6f, 0.85f, 1f};
    private static final int[] JPEG = {40, 55, 70, 85};

    private String baza, haslo, hash = "";
    private int tryb = 2, jakosc = 1, monitor = 1, monitorow = 1;
    private long bajty = 0;
    private volatile boolean trwa = false, aktywny = false;

    private ZoomImageView obraz;
    private LinearLayout pasek;
    private TextView status;
    private Spinner spMon;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    private final Runnable petla = new Runnable() {
        @Override public void run() {
            if (!aktywny) return;
            pobierz(false);
            int t = INTERWAL[tryb];
            if (t > 0) ui.postDelayed(this, t);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        tryb = prefs.getInt("tryb", 2);
        jakosc = prefs.getInt("jakosc", 1);
        monitor = prefs.getInt("monitor", 1);

        baza = "http://" + getIntent().getStringExtra("host") + ":" + getIntent().getStringExtra("port");
        haslo = getIntent().getStringExtra("haslo");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        MainActivity.wstawki(root);

        obraz = new ZoomImageView(this);
        obraz.setOnTap(() -> pasek.setVisibility(pasek.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        root.addView(obraz, new FrameLayout.LayoutParams(-1, -1));

        pasek = new LinearLayout(this);
        pasek.setOrientation(LinearLayout.VERTICAL);
        pasek.setBackgroundColor(Color.parseColor("#CC1E1E1E"));
        int p = MainActivity.dp(this, 4);
        pasek.setPadding(p, p, p, p);

        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        Button wstecz = new Button(this);
        wstecz.setText("✕");
        wstecz.setOnClickListener(v -> finish());
        r1.addView(wstecz, new LinearLayout.LayoutParams(-2, -2));
        TextView nazwa = new TextView(this);
        nazwa.setText(getIntent().getStringExtra("nazwa"));
        nazwa.setTextColor(Color.WHITE);
        nazwa.setTextSize(16);
        nazwa.setPadding(p * 2, 0, 0, 0);
        r1.addView(nazwa, new LinearLayout.LayoutParams(0, -2, 1));
        Button odsw = new Button(this);
        odsw.setText("⟳ Zrzut");
        odsw.setOnClickListener(v -> pobierz(true));
        r1.addView(odsw, new LinearLayout.LayoutParams(-2, -2));
        pasek.addView(r1);

        LinearLayout r2 = new LinearLayout(this);
        r2.addView(spinner(TRYBY, tryb, i -> { tryb = i; zapisz(); restart(); }), new LinearLayout.LayoutParams(0, -2, 1));
        r2.addView(spinner(JAKOSCI, jakosc, i -> { jakosc = i; zapisz(); pobierz(true); }), new LinearLayout.LayoutParams(0, -2, 1));
        spMon = spinner(new String[]{"Monitor 1"}, 0, i -> {
            if (spMon.getAdapter().getCount() < 2) return;
            int nowy = (i < monitorow) ? i + 1 : 0;
            if (nowy != monitor) { monitor = nowy; zapisz(); pobierz(true); }
        });
        r2.addView(spMon, new LinearLayout.LayoutParams(0, -2, 1));
        pasek.addView(r2);

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(12);
        status.setPadding(p * 2, 0, 0, 0);
        status.setText("Stuknij obraz, aby ukryć/pokazać pasek · 2× stuknięcie = powiększ");
        pasek.addView(status);

        root.addView(pasek, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        setContentView(root);
    }

    interface Wybor { void wybrano(int i); }

    private Spinner spinner(String[] opcje, int start, Wybor w) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, opcje);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(Math.min(start, opcje.length - 1), false);
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) { w.wybrano(i); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        return s;
    }

    private void ustawMonitory(int ile) {
        if (ile == monitorow || ile < 1) return;
        monitorow = ile;
        String[] o = new String[ile > 1 ? ile + 1 : 1];
        for (int i = 0; i < ile; i++) o[i] = "Monitor " + (i + 1);
        if (ile > 1) o[ile] = "Wszystkie";
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, o);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMon.setAdapter(a);
        spMon.setSelection(monitor == 0 ? Math.min(ile, o.length - 1) : Math.min(monitor - 1, o.length - 1), false);
    }

    private void zapisz() {
        prefs.edit().putInt("tryb", tryb).putInt("jakosc", jakosc).putInt("monitor", monitor).apply();
    }

    private void restart() {
        ui.removeCallbacks(petla);
        if (aktywny) ui.post(petla);
    }

    private void pobierz(boolean wymus) {
        if (trwa) return;
        trwa = true;
        final String url = baza + "/shot?m=" + monitor + "&s=" + SKALA[jakosc] + "&q=" + JPEG[jakosc]
                + "&h=" + (wymus ? "" : hash);
        ex.execute(() -> {
            String blad = null;
            Bitmap bmp = null;
            int ileMon = -1;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(15000);
                c.setRequestProperty("X-Key", haslo);
                int kod = c.getResponseCode();
                String m = c.getHeaderField("X-Monitors");
                if (m != null) ileMon = Integer.parseInt(m);
                if (kod == 401) blad = "HASLO";
                else if (kod == 200) {
                    String h = c.getHeaderField("X-Hash");
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    byte[] dane = bo.toByteArray();
                    bajty += dane.length;
                    bmp = BitmapFactory.decodeByteArray(dane, 0, dane.length);
                    if (h != null) hash = h;
                } else if (kod != 204) blad = "Błąd serwera " + kod;
                c.disconnect();
            } catch (Exception e) {
                blad = "Brak połączenia";
            }
            final String fb = blad;
            final Bitmap fbmp = bmp;
            final int fm = ileMon;
            ui.post(() -> {
                trwa = false;
                if ("HASLO".equals(fb)) {
                    Toast.makeText(this, "Złe hasło", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                if (fm > 0) ustawMonitory(fm);
                if (fbmp != null) obraz.ustaw(fbmp);
                String czas = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                status.setText((fb != null ? "⚠ " + fb + " · " : "") + czas + " · pobrano "
                        + String.format(Locale.getDefault(), "%.1f MB", bajty / 1048576.0));
            });
        });
    }

    @Override protected void onResume() {
        super.onResume();
        aktywny = true;
        if (INTERWAL[tryb] == 0) pobierz(true);
        restart();
    }

    @Override protected void onPause() {
        super.onPause();
        aktywny = false;
        ui.removeCallbacks(petla);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ex.shutdownNow();
    }
}
