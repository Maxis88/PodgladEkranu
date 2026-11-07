package pl.artgarage.podglad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ViewerActivity extends Activity {

    private static final float[] SKALA = {0.5f, 0.75f, 1f, 1f};
    private static final int[] JPEG = {50, 65, 80, 92};

    private String baza, haslo, pc, hash = "";
    private boolean relay, sterowanie = false;
    private LinearLayout rzadSter;
    private TextView bSter;
    private final ExecutorService exIn = Executors.newSingleThreadExecutor();
    private int interwal = 3000, jakosc = 2, monitor = 1, monitorow = 1;
    private long bajty = 0;
    private volatile boolean trwa = false, aktywny = false;

    private ZoomImageView obraz;
    private View pasek;
    private TextView status, bCzas, bJakosc, bMonitor;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    private final Runnable petla = new Runnable() {
        @Override public void run() {
            if (!aktywny) return;
            pobierz(false);
            if (interwal > 0) ui.postDelayed(this, interwal);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        interwal = prefs.getInt("interwal", 3000);
        jakosc = Math.min(3, prefs.getInt("jakosc2", 2));

        baza = getIntent().getStringExtra("baza");
        relay = getIntent().getBooleanExtra("relay", false);
        pc = getIntent().getStringExtra("pc");
        haslo = getIntent().getStringExtra("haslo");
        // monitor zapamiętywany osobno dla każdego komputera
        monitor = prefs.getInt("monitor|" + baza + "|" + pc, 1);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        Styl.wstawki(root);

        obraz = new ZoomImageView(this);
        obraz.setOnTap(() -> pasek.setVisibility(pasek.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        obraz.setOnKlik((nx, ny, typ) -> {
            try {
                JSONObject e = new JSONObject().put("t", "click").put("x", nx).put("y", ny);
                if (typ == 1) e.put("n", 2);
                if (typ == 2) e.put("b", "right");
                wyslij(new JSONArray().put(e));
            } catch (Exception ignored) {}
        });
        root.addView(obraz, new FrameLayout.LayoutParams(-1, -1));

        // pływający pasek – wyśrodkowany, o ograniczonej szerokości na tabletach
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        int d = Styl.dp(this, 10);
        p.setPadding(d, d, d, d);
        p.setBackground(Styl.ksztalt(Color.parseColor("#E61A1D24"), 18, this));
        pasek = p;

        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        TextView wstecz = maly("←");
        wstecz.setOnClickListener(v -> finish());
        r1.addView(wstecz);
        LinearLayout tyt = new LinearLayout(this);
        tyt.setOrientation(LinearLayout.VERTICAL);
        tyt.setPadding(Styl.dp(this, 12), 0, Styl.dp(this, 8), 0);
        TextView nazwa = Styl.tekst(this, getIntent().getStringExtra("nazwa"), 16, Styl.TEKST, true);
        nazwa.setSingleLine(true);
        tyt.addView(nazwa);
        status = Styl.tekst(this, "Łączę…", 12, Styl.SZARY, false);
        status.setSingleLine(true);
        tyt.addView(status);
        r1.addView(tyt, new LinearLayout.LayoutParams(0, -2, 1));
        TextView odsw = Styl.przycisk(this, "⟳", true);
        odsw.setTextSize(18);
        odsw.setPadding(Styl.dp(this, 16), Styl.dp(this, 8), Styl.dp(this, 16), Styl.dp(this, 8));
        odsw.setOnClickListener(v -> pobierz(true));
        bSter = Styl.przycisk(this, "🖱", false);
        bSter.setTextSize(18);
        bSter.setPadding(Styl.dp(this, 16), Styl.dp(this, 8), Styl.dp(this, 16), Styl.dp(this, 8));
        bSter.setOnClickListener(v -> przelaczSter(!sterowanie));
        LinearLayout.LayoutParams lps = new LinearLayout.LayoutParams(-2, -2);
        lps.rightMargin = Styl.dp(this, 8);
        r1.addView(bSter, lps);
        r1.addView(odsw);
        p.addView(r1);

        LinearLayout r2 = new LinearLayout(this);
        bCzas = maly("");
        bCzas.setOnClickListener(v -> wybierzCzas());
        bJakosc = maly("");
        bJakosc.setOnClickListener(v -> wybierzJakosc());
        bMonitor = maly("");
        bMonitor.setOnClickListener(v -> wybierzMonitor());
        for (TextView t : new TextView[]{bCzas, bJakosc, bMonitor}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (t != bMonitor) lp.rightMargin = Styl.dp(this, 8);
            r2.addView(t, lp);
        }
        p.addView(r2, Styl.odstep(this, 10));

        // rząd sterowania
        rzadSter = new LinearLayout(this);
        String[][] kl = {{"⌨ Pisz", ""}, {"▲", "su"}, {"▼", "sd"}, {"Enter", "enter"}, {"⌫", "backspace"}, {"Esc", "esc"}, {"⊞", "win"}};
        for (int i = 0; i < kl.length; i++) {
            final String kod = kl[i][1];
            TextView t = maly(kl[i][0]);
            t.setPadding(Styl.dp(this, 4), Styl.dp(this, 9), Styl.dp(this, 4), Styl.dp(this, 9));
            t.setOnClickListener(v -> klawisz(kod));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, i == 0 ? 1.8f : 1f);
            if (i < kl.length - 1) lp.rightMargin = Styl.dp(this, 6);
            rzadSter.addView(t, lp);
        }
        rzadSter.setVisibility(View.GONE);
        p.addView(rzadSter, Styl.odstep(this, 8));
        opisy();

        FrameLayout.LayoutParams lpp = new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - Styl.dp(this, 16), Styl.dp(this, 620)),
                -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lpp.topMargin = Styl.dp(this, 8);
        root.addView(pasek, lpp);
        setContentView(root);
    }

    private TextView maly(String s) {
        TextView t = Styl.przycisk(this, s, false);
        t.setTextSize(13);
        t.setPadding(Styl.dp(this, 12), Styl.dp(this, 9), Styl.dp(this, 12), Styl.dp(this, 9));
        t.setSingleLine(true);
        return t;
    }

    private void opisy() {
        bCzas.setText("⏱ " + MainActivity.nazwaCzasu(interwal));
        bJakosc.setText("◐ " + MainActivity.JAKOSCI[jakosc]);
        bMonitor.setText("🖵 " + (monitor == 0 ? "Wszystkie" : "Monitor " + monitor));
    }

    private void zapisz() {
        prefs.edit().putInt("interwal", interwal).putInt("jakosc2", jakosc)
                .putInt("monitor|" + baza + "|" + pc, monitor).apply();
        opisy();
    }

    private void wybierzCzas() {
        String[] opcje = new String[MainActivity.CZASY_NAZWY.length + 1];
        System.arraycopy(MainActivity.CZASY_NAZWY, 0, opcje, 0, MainActivity.CZASY_NAZWY.length);
        opcje[opcje.length - 1] = "Własny…";
        new AlertDialog.Builder(this).setTitle("Odświeżanie").setItems(opcje, (d, w) -> {
            if (w < MainActivity.CZASY_MS.length) { interwal = MainActivity.CZASY_MS[w]; zapisz(); restart(); return; }
            EditText e = Styl.pole(this, "Sekundy (np. 7 lub 0,5)",
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            LinearLayout l = new LinearLayout(this);
            int p = Styl.dp(this, 22);
            l.setPadding(p, Styl.dp(this, 8), p, 0);
            l.addView(e, new LinearLayout.LayoutParams(-1, -2));
            new AlertDialog.Builder(this).setTitle("Własny czas").setView(l)
                    .setPositiveButton("OK", (d2, x) -> {
                        try {
                            float s = Float.parseFloat(e.getText().toString().trim().replace(',', '.'));
                            interwal = Math.max(250, Math.min(Math.round(s * 1000), 3600000));
                            zapisz(); restart();
                        } catch (Exception ignored) {}
                    }).setNegativeButton("Anuluj", null).show();
        }).show();
    }

    private void wybierzJakosc() {
        new AlertDialog.Builder(this).setTitle("Jakość").setItems(MainActivity.JAKOSCI, (d, w) -> {
            jakosc = w; zapisz(); pobierz(true);
        }).show();
    }

    private void wybierzMonitor() {
        String[] o = new String[monitorow > 1 ? monitorow + 1 : 1];
        for (int i = 0; i < monitorow; i++) o[i] = "Monitor " + (i + 1);
        if (monitorow > 1) o[monitorow] = "Wszystkie";
        new AlertDialog.Builder(this).setTitle("Monitor").setItems(o, (d, w) -> {
            monitor = w < monitorow ? w + 1 : 0; zapisz(); pobierz(true);
        }).show();
    }

    private void przelaczSter(boolean wl) {
        sterowanie = wl;
        obraz.setSterowanie(wl);
        rzadSter.setVisibility(wl ? View.VISIBLE : View.GONE);
        bSter.setBackground(Styl.klikalne(wl ? Styl.AKCENT : Styl.KARTA2, 14, this));
        if (wl) Toast.makeText(this, "Sterowanie: stuknij = klik, 2× = podwójny, przytrzymaj = prawy, 2 palce = powiększ/przesuń",
                Toast.LENGTH_LONG).show();
    }

    private void klawisz(String kod) {
        try {
            if (kod.isEmpty()) { pisz(); return; }
            JSONObject e;
            if (kod.equals("su") || kod.equals("sd"))
                e = new JSONObject().put("t", "scroll").put("d", kod.equals("su") ? 3 : -3);
            else e = new JSONObject().put("t", "key").put("k", new JSONArray().put(kod));
            wyslij(new JSONArray().put(e));
        } catch (Exception ignored) {}
    }

    private void pisz() {
        EditText e = Styl.pole(this, "Tekst do wpisania na komputerze", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setSingleLine(false);
        e.setMaxLines(5);
        LinearLayout l = new LinearLayout(this);
        int p = Styl.dp(this, 22);
        l.setPadding(p, Styl.dp(this, 8), p, 0);
        l.addView(e, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Pisz na komputerze").setView(l)
                .setPositiveButton("Wyślij", (x, y) -> wyslijTekst(e.getText().toString(), false))
                .setNeutralButton("Wyślij + Enter", (x, y) -> wyslijTekst(e.getText().toString(), true))
                .setNegativeButton("Anuluj", null).create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        d.show();
        e.requestFocus();
    }

    private void wyslijTekst(String s, boolean enter) {
        try {
            JSONArray a = new JSONArray();
            if (!s.isEmpty()) a.put(new JSONObject().put("t", "text").put("s", s));
            if (enter) a.put(new JSONObject().put("t", "key").put("k", new JSONArray().put("enter")));
            if (a.length() > 0) wyslij(a);
        } catch (Exception ignored) {}
    }

    /** Wysyła polecenia myszy/klawiatury do komputera, potem szybko odświeża obraz. */
    private void wyslij(JSONArray zdarzenia) {
        final String url = baza + (relay ? "?a=input&pc=" + MainActivity.enc(pc) + "&m=" : "/input?m=") + monitor;
        final byte[] dane = zdarzenia.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exIn.execute(() -> {
            String blad = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(10000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("X-Key", haslo);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(dane.length);
                OutputStream o = c.getOutputStream();
                o.write(dane);
                o.close();
                int kod = c.getResponseCode();
                if (kod == 403) blad = "Sterowanie jest wyłączone na komputerze – zaznacz „Zezwalaj na sterowanie” w programie na Windows.";
                else if (kod == 401) blad = "Złe hasło";
                else if (kod != 200) blad = "Błąd sterowania (" + kod + ")";
                c.disconnect();
            } catch (Exception e) {
                blad = "Brak połączenia";
            }
            final String fb = blad;
            ui.post(() -> {
                if (fb != null) {
                    Toast.makeText(this, fb, Toast.LENGTH_LONG).show();
                    if (fb.startsWith("Sterowanie jest")) przelaczSter(false);
                    return;
                }
                ui.postDelayed(() -> pobierz(false), relay ? 1200 : 250);
                ui.postDelayed(() -> pobierz(false), relay ? 2800 : 900);
            });
        });
    }

    private void restart() {
        ui.removeCallbacks(petla);
        if (aktywny) ui.post(petla);
    }

    private void pobierz(boolean wymus) {
        if (trwa) return;
        trwa = true;
        if (wymus && relay) { ui.postDelayed(() -> pobierz(false), 1500); ui.postDelayed(() -> pobierz(false), 3500); }
        final String url = baza + (relay ? "?a=shot&pc=" + MainActivity.enc(pc) + "&m=" : "/shot?m=") + monitor
                + "&s=" + SKALA[jakosc] + "&q=" + JPEG[jakosc] + "&h=" + (wymus ? "" : hash);
        ex.execute(() -> {
            String blad = null;
            Bitmap bmp = null;
            int ileMon = -1;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(20000);
                c.setRequestProperty("X-Key", haslo);
                int kod = c.getResponseCode();
                String m = c.getHeaderField("X-Monitors");
                if (m != null) try { ileMon = Integer.parseInt(m.trim()); } catch (Exception ignored) {}
                if (kod == 401) blad = "HASLO";
                else if (kod == 503) blad = "komputer offline";
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
                } else if (kod != 204) blad = "błąd serwera " + kod;
                c.disconnect();
            } catch (Exception e) {
                blad = "brak połączenia";
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
                if (fm > 0) monitorow = fm;
                if (fbmp != null) obraz.ustaw(fbmp);
                String czas = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                status.setText((fb != null ? "⚠ " + fb + " · " : "● ") + czas + " · "
                        + String.format(Locale.getDefault(), "%.1f MB", bajty / 1048576.0)
                        + (fb == null ? " · stuknij obraz, by ukryć pasek" : ""));
                status.setTextColor(fb != null ? Styl.CZERWONY : Styl.SZARY);
            });
        });
    }

    @Override protected void onResume() {
        super.onResume();
        aktywny = true;
        if (interwal == 0) pobierz(true);
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
        exIn.shutdownNow();
    }
}
