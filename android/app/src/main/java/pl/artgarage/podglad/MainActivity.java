package pl.artgarage.podglad;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {

    private EditText eHost, ePort, eHaslo;
    private TextView tStatus;
    private LinearLayout znalezione;
    private Button bPolacz, bSzukaj;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    static void wstawki(View root) {
        root.setOnApplyWindowInsetsListener((v, in) -> {
            v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                    in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
            return in.consumeSystemWindowInsets();
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#121212"));
        wstawki(sv);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(this, 20);
        l.setPadding(p, p, p, p);
        sv.addView(l);

        TextView tytul = new TextView(this);
        tytul.setText("Podgląd Ekranu");
        tytul.setTextSize(26);
        tytul.setTextColor(Color.WHITE);
        l.addView(tytul);

        TextView opis = new TextView(this);
        opis.setText("W domu: naciśnij Szukaj albo wpisz adres IP komputera.\nPoza domem: wpisz adres pliku podglad.php, np. https://3dartgarage.pl/podglad.php");
        opis.setTextColor(Color.LTGRAY);
        opis.setPadding(0, dp(this, 6), 0, dp(this, 16));
        l.addView(opis);

        bSzukaj = new Button(this);
        bSzukaj.setText("🔍  Szukaj komputera w sieci");
        bSzukaj.setOnClickListener(v -> szukaj());
        l.addView(bSzukaj);

        znalezione = new LinearLayout(this);
        znalezione.setOrientation(LinearLayout.VERTICAL);
        l.addView(znalezione);

        eHost = pole(l, "Adres komputera (IP) lub https://…/podglad.php", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ePort = pole(l, "Port", InputType.TYPE_CLASS_NUMBER);
        eHaslo = pole(l, "Hasło", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        CheckBox pokaz = new CheckBox(this);
        pokaz.setText("pokaż hasło");
        pokaz.setTextColor(Color.LTGRAY);
        pokaz.setOnCheckedChangeListener((c, on) -> {
            eHaslo.setInputType(InputType.TYPE_CLASS_TEXT | (on ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            eHaslo.setSelection(eHaslo.length());
        });
        l.addView(pokaz);

        eHost.setText(prefs.getString("host", ""));
        ePort.setText(prefs.getString("port", "8765"));
        eHaslo.setText(prefs.getString("haslo", ""));

        bPolacz = new Button(this);
        bPolacz.setText("▶  Połącz");
        bPolacz.setOnClickListener(v -> polacz());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(this, 12);
        l.addView(bPolacz, lp);

        tStatus = new TextView(this);
        tStatus.setTextColor(Color.parseColor("#FFB74D"));
        tStatus.setPadding(0, dp(this, 12), 0, 0);
        l.addView(tStatus);

        setContentView(sv);
    }

    private EditText pole(LinearLayout l, String podp, int typ) {
        TextView t = new TextView(this);
        t.setText(podp);
        t.setTextColor(Color.LTGRAY);
        t.setPadding(0, dp(this, 12), 0, 0);
        l.addView(t);
        EditText e = new EditText(this);
        e.setInputType(typ);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        l.addView(e);
        return e;
    }

    private void szukaj() {
        bSzukaj.setEnabled(false);
        znalezione.removeAllViews();
        tStatus.setText("Szukam…");
        new Thread(() -> {
            Set<String> wyniki = new HashSet<>();
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                s.setSoTimeout(600);
                byte[] q = "PODGLAD?".getBytes("UTF-8");
                for (int i = 0; i < 3; i++) {
                    s.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), 8766));
                    long koniec = System.currentTimeMillis() + 700;
                    while (System.currentTimeMillis() < koniec) {
                        byte[] buf = new byte[256];
                        DatagramPacket pk = new DatagramPacket(buf, buf.length);
                        try { s.receive(pk); } catch (SocketTimeoutException e) { break; }
                        String odp = new String(pk.getData(), 0, pk.getLength(), "UTF-8");
                        if (!odp.startsWith("PODGLAD!")) continue;
                        String[] cz = odp.split("!", 3);
                        String ip = pk.getAddress().getHostAddress();
                        String klucz = ip + ":" + cz[1];
                        if (wyniki.add(klucz)) {
                            String nazwa = cz.length > 2 ? cz[2] : ip;
                            ui.post(() -> dodajWynik(nazwa, ip, cz[1]));
                        }
                    }
                }
            } catch (Exception e) {
                ui.post(() -> tStatus.setText("Błąd wyszukiwania: " + e.getMessage()));
            }
            ui.post(() -> {
                bSzukaj.setEnabled(true);
                tStatus.setText(wyniki.isEmpty()
                        ? "Nie znaleziono. Sprawdź, czy serwer działa i czy telefon jest w tym samym Wi-Fi. Możesz wpisać adres ręcznie."
                        : "Wybierz komputer z listy.");
            });
        }).start();
    }

    private void dodajWynik(String nazwa, String ip, String port) {
        Button b = new Button(this);
        b.setText("🖥  " + nazwa + "  (" + ip + ")");
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(v -> {
            eHost.setText(ip);
            ePort.setText(port);
            if (eHaslo.length() > 0) polacz(); else eHaslo.requestFocus();
        });
        znalezione.addView(b);
    }

    private void polacz() {
        String host = eHost.getText().toString().trim();
        String port = ePort.getText().toString().trim();
        String haslo = eHaslo.getText().toString();
        final boolean relay = host.startsWith("http://") || host.startsWith("https://");
        final String baza = relay ? host.replaceAll("/+$", "") : "http://" + host + ":" + port;
        final String pingUrl = relay ? baza + "?a=ping" : baza + "/ping";
        if (host.isEmpty() || (!relay && port.isEmpty())) {
            tStatus.setText("Podaj adres i port.");
            return;
        }
        prefs.edit().putString("host", host).putString("port", port).putString("haslo", haslo).apply();
        bPolacz.setEnabled(false);
        tStatus.setText("Łączę…");
        new Thread(() -> {
            String blad = null, nazwa = host;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(pingUrl).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(6000);
                c.setRequestProperty("X-Key", haslo);
                int kod = c.getResponseCode();
                if (kod == 401) blad = "Złe hasło.";
                else if (kod == 503) blad = "Komputer jest offline (serwer na Windows nie działa lub brak adresu podglad.php w jego ustawieniach).";
                else if (kod != 200) blad = "Serwer odpowiedział kodem " + kod;
                else {
                    InputStream in = c.getInputStream();
                    byte[] buf = new byte[256];
                    int n = in.read(buf);
                    if (n > 0) nazwa = new String(buf, 0, n, "UTF-8");
                }
                c.disconnect();
            } catch (Exception e) {
                blad = "Brak połączenia: " + e.getMessage();
            }
            final String fb = blad, fn = nazwa;
            ui.post(() -> {
                bPolacz.setEnabled(true);
                if (fb != null) { tStatus.setText(fb); return; }
                tStatus.setText("");
                Intent i = new Intent(this, ViewerActivity.class);
                i.putExtra("baza", baza);
                i.putExtra("relay", relay);
                i.putExtra("haslo", haslo);
                i.putExtra("nazwa", fn);
                startActivity(i);
            });
        }).start();
    }
}
