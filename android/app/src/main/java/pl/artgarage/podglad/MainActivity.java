package pl.artgarage.podglad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    /** Zapisany komputer. adres = IP (sieć lokalna) albo https://…/podglad.php (internet). */
    static class Komp {
        String nazwa, adres, port, haslo, pc;
        boolean relay() { return adres.startsWith("http://") || adres.startsWith("https://"); }
        String baza() { return relay() ? adres.replaceAll("/+$", "") : "http://" + adres + ":" + port; }
        String pingUrl() {
            return relay() ? baza() + "?a=ping&pc=" + enc(pc) : baza() + "/ping";
        }
        String opis() { return relay() ? "przez internet" : adres + ":" + port; }
        JSONObject json() throws Exception {
            return new JSONObject().put("nazwa", nazwa).put("adres", adres).put("port", port)
                    .put("haslo", haslo).put("pc", pc == null ? "" : pc);
        }
        static Komp z(JSONObject o) {
            Komp k = new Komp();
            k.nazwa = o.optString("nazwa"); k.adres = o.optString("adres"); k.port = o.optString("port", "8765");
            k.haslo = o.optString("haslo"); k.pc = o.optString("pc");
            return k;
        }
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    static final String[] CZASY_NAZWY = {"Ręcznie", "0,5 s", "1 s", "2 s", "3 s", "5 s", "10 s", "30 s", "1 min"};
    static final int[] CZASY_MS = {0, 500, 1000, 2000, 3000, 5000, 10000, 30000, 60000};
    static final String[] JAKOSCI = {"Oszczędna", "Średnia", "Dobra", "Ostra (tekst)"};

    static String nazwaCzasu(int ms) {
        for (int i = 0; i < CZASY_MS.length; i++) if (CZASY_MS[i] == ms) return CZASY_NAZWY[i];
        return (ms % 1000 == 0 ? String.valueOf(ms / 1000) : String.valueOf(ms / 1000.0).replace('.', ',')) + " s";
    }

    private final List<Komp> komputery = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService ex = Executors.newFixedThreadPool(4);
    private SharedPreferences prefs;
    private LinearLayout lista, znalezione;
    private TextView status, bSzukaj, tUstawienia;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        wczytaj();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Styl.TLO);
        Styl.wstawki(root);
        ScrollView sv = new ScrollView(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout srodek = new FrameLayout(this);
        sv.addView(srodek, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = Styl.dp(this, 20);
        l.setPadding(p, p, p, p);
        srodek.addView(l, new FrameLayout.LayoutParams(Styl.szerokoscTresci(this), -2, Gravity.CENTER_HORIZONTAL));

        // nagłówek
        LinearLayout nag = new LinearLayout(this);
        nag.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tyt = new LinearLayout(this);
        tyt.setOrientation(LinearLayout.VERTICAL);
        tyt.addView(Styl.tekst(this, "Podgląd Ekranu", 28, Styl.TEKST, true));
        tyt.addView(Styl.tekst(this, "Wybierz komputer do podglądu", 14, Styl.SZARY, false));
        nag.addView(tyt, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ust = Styl.przycisk(this, "⚙", false);
        ust.setTextSize(20);
        ust.setPadding(Styl.dp(this, 14), Styl.dp(this, 8), Styl.dp(this, 14), Styl.dp(this, 8));
        ust.setOnClickListener(v -> ustawienia());
        nag.addView(ust);
        l.addView(nag);

        tUstawienia = Styl.tekst(this, "", 13, Styl.SZARY, false);
        tUstawienia.setPadding(0, Styl.dp(this, 6), 0, 0);
        l.addView(tUstawienia);
        odswiezOpisUstawien();

        l.addView(Styl.tekst(this, "TWOJE KOMPUTERY", 12, Styl.SZARY, true), Styl.odstep(this, 24));
        lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        l.addView(lista);

        LinearLayout przyciski = new LinearLayout(this);
        bSzukaj = Styl.przycisk(this, "Szukaj w sieci", false);
        bSzukaj.setOnClickListener(v -> szukaj());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        lp1.rightMargin = Styl.dp(this, 10);
        przyciski.addView(bSzukaj, lp1);
        TextView bDodaj = Styl.przycisk(this, "+ Dodaj", true);
        bDodaj.setOnClickListener(v -> dodajDialog());
        przyciski.addView(bDodaj, new LinearLayout.LayoutParams(0, -2, 1));
        l.addView(przyciski, Styl.odstep(this, 14));

        znalezione = new LinearLayout(this);
        znalezione.setOrientation(LinearLayout.VERTICAL);
        l.addView(znalezione);

        status = Styl.tekst(this, "", 14, Color.parseColor("#FFB74D"), false);
        status.setPadding(0, Styl.dp(this, 12), 0, 0);
        l.addView(status);

        TextView pomoc = Styl.tekst(this,
                "W domu: „Szukaj w sieci” znajdzie komputery z uruchomionym programem.\n"
                        + "Poza domem: „+ Dodaj” i wpisz adres pliku podglad.php (np. https://3dartgarage.pl/podglad.php) "
                        + "– pojawią się wszystkie komputery z tym hasłem.\n"
                        + "Przytrzymaj kartę komputera, aby zmienić nazwę, hasło lub go usunąć.",
                13, Styl.NIEAKTYWNY, false);
        pomoc.setPadding(0, Styl.dp(this, 24), 0, 0);
        l.addView(pomoc);

        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        pokazListe();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ex.shutdownNow();
    }

    // ---------- zapis ----------

    private void wczytaj() {
        komputery.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString("komputery", "[]"));
            for (int i = 0; i < a.length(); i++) komputery.add(Komp.z(a.getJSONObject(i)));
        } catch (Exception ignored) {}
        // migracja z wersji 1.x (jeden komputer)
        if (komputery.isEmpty() && !prefs.getString("host", "").isEmpty()) {
            Komp k = new Komp();
            k.adres = prefs.getString("host", ""); k.port = prefs.getString("port", "8765");
            k.haslo = prefs.getString("haslo", ""); k.nazwa = k.adres; k.pc = "";
            komputery.add(k);
            zapisz();
        }
    }

    private void zapisz() {
        JSONArray a = new JSONArray();
        try { for (Komp k : komputery) a.put(k.json()); } catch (Exception ignored) {}
        prefs.edit().putString("komputery", a.toString()).apply();
    }

    private boolean istnieje(Komp n) {
        for (Komp k : komputery)
            if (k.adres.equals(n.adres) && k.port.equals(n.port) && String.valueOf(k.pc).equals(String.valueOf(n.pc))) return true;
        return false;
    }

    // ---------- lista ----------

    private void pokazListe() {
        lista.removeAllViews();
        if (komputery.isEmpty()) {
            TextView t = Styl.tekst(this, "Brak zapisanych komputerów. Naciśnij „Szukaj w sieci” albo „+ Dodaj”.", 14, Styl.SZARY, false);
            t.setPadding(Styl.dp(this, 16), Styl.dp(this, 18), Styl.dp(this, 16), Styl.dp(this, 18));
            t.setBackground(Styl.ksztalt(Styl.KARTA, 16, this));
            lista.addView(t, Styl.odstep(this, 10));
            return;
        }
        for (Komp k : komputery) lista.addView(karta(k), Styl.odstep(this, 10));
    }

    private View karta(Komp k) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        int p = Styl.dp(this, 16);
        c.setPadding(p, p, p, p);
        c.setBackground(Styl.klikalne(Styl.KARTA, 16, this));
        c.setClickable(true);

        TextView ikona = Styl.tekst(this, k.relay() ? "🌐" : "🖥", 24, Styl.TEKST, false);
        ikona.setGravity(Gravity.CENTER);
        ikona.setBackground(Styl.ksztalt(Styl.KARTA2, 12, this));
        c.addView(ikona, new LinearLayout.LayoutParams(Styl.dp(this, 48), Styl.dp(this, 48)));

        LinearLayout opis = new LinearLayout(this);
        opis.setOrientation(LinearLayout.VERTICAL);
        opis.setPadding(Styl.dp(this, 14), 0, Styl.dp(this, 8), 0);
        opis.addView(Styl.tekst(this, k.nazwa, 17, Styl.TEKST, true));
        TextView stan = Styl.tekst(this, k.opis() + " · sprawdzam…", 13, Styl.SZARY, false);
        opis.addView(stan);
        c.addView(opis, new LinearLayout.LayoutParams(0, -2, 1));

        View kropka = Styl.kropka(this, Styl.NIEAKTYWNY);
        c.addView(kropka, new LinearLayout.LayoutParams(Styl.dp(this, 12), Styl.dp(this, 12)));

        c.setOnClickListener(v -> polacz(k));
        c.setOnLongClickListener(v -> { menuKomputera(k); return true; });

        ex.execute(() -> {
            int kod = ping(k, 2500)[0] == null ? -1 : 200;
            ui.post(() -> {
                boolean on = kod == 200;
                stan.setText(k.opis() + (on ? " · dostępny" : " · niedostępny"));
                ((android.graphics.drawable.GradientDrawable) kropka.getBackground()).setColor(on ? Styl.ZIELONY : Styl.CZERWONY);
            });
        });
        return c;
    }

    private void menuKomputera(Komp k) {
        new AlertDialog.Builder(this)
                .setTitle(k.nazwa)
                .setItems(new String[]{"Zmień nazwę", "Zmień hasło", "Usuń"}, (d, w) -> {
                    if (w == 2) {
                        komputery.remove(k); zapisz(); pokazListe();
                    } else {
                        EditText e = w == 0
                                ? Styl.pole(this, "Nazwa", InputType.TYPE_CLASS_TEXT)
                                : Styl.haslo(this);
                        if (w == 0) e.setText(k.nazwa);
                        new AlertDialog.Builder(this).setTitle(w == 0 ? "Nazwa" : "Nowe hasło")
                                .setView(owin(e))
                                .setPositiveButton("Zapisz", (d2, x) -> {
                                    String s = e.getText().toString();
                                    if (s.trim().isEmpty()) return;
                                    if (w == 0) k.nazwa = s.trim(); else k.haslo = s;
                                    zapisz(); pokazListe();
                                })
                                .setNegativeButton("Anuluj", null).show();
                    }
                }).show();
    }

    private View owin(View... widoki) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = Styl.dp(this, 22);
        l.setPadding(p, Styl.dp(this, 8), p, 0);
        for (View v : widoki) l.addView(v, Styl.odstep(this, 10));
        return l;
    }

    // ---------- sieć ----------

    /** Zwraca {nazwa lub null, komunikat błędu}. */
    private String[] ping(Komp k, int timeout) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(k.pingUrl()).openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout + 3000);
            c.setRequestProperty("X-Key", k.haslo);
            int kod = c.getResponseCode();
            if (kod == 401) return new String[]{null, "Złe hasło."};
            if (kod == 503) return new String[]{null, "Komputer jest offline (program na Windows nie działa)."};
            if (kod != 200) return new String[]{null, "Serwer odpowiedział kodem " + kod};
            String n = czytajTekst(c.getInputStream());
            c.disconnect();
            return new String[]{n.isEmpty() ? k.adres : n, null};
        } catch (Exception e) {
            return new String[]{null, "Brak połączenia z " + k.opis()};
        }
    }

    static String czytajTekst(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8").trim();
    }

    private void polacz(Komp k) {
        status.setText("Łączę z " + k.nazwa + "…");
        ex.execute(() -> {
            String[] w = ping(k, 4000);
            ui.post(() -> {
                if (w[0] == null) { status.setText(w[1]); return; }
                status.setText("");
                Intent i = new Intent(this, ViewerActivity.class);
                i.putExtra("baza", k.baza());
                i.putExtra("relay", k.relay());
                i.putExtra("pc", k.pc);
                i.putExtra("haslo", k.haslo);
                i.putExtra("nazwa", k.nazwa);
                startActivity(i);
            });
        });
    }

    private void szukaj() {
        bSzukaj.setEnabled(false);
        bSzukaj.setText("Szukam…");
        znalezione.removeAllViews();
        status.setText("");
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
                        Komp k = new Komp();
                        k.adres = pk.getAddress().getHostAddress();
                        k.port = cz[1];
                        k.nazwa = cz.length > 2 ? cz[2] : k.adres;
                        k.pc = "";
                        if (wyniki.add(k.adres + ":" + k.port)) ui.post(() -> dodajZnaleziony(k));
                    }
                }
            } catch (Exception e) {
                ui.post(() -> status.setText("Błąd wyszukiwania: " + e.getMessage()));
            }
            ui.post(() -> {
                bSzukaj.setEnabled(true);
                bSzukaj.setText("Szukaj w sieci");
                if (wyniki.isEmpty()) status.setText("Nic nie znaleziono. Sprawdź, czy program działa na komputerze i czy jesteś w tym samym Wi-Fi.");
            });
        }).start();
    }

    private void dodajZnaleziony(Komp k) {
        if (znalezione.getChildCount() == 0)
            znalezione.addView(Styl.tekst(this, "ZNALEZIONE W SIECI", 12, Styl.SZARY, true), Styl.odstep(this, 20));
        boolean jest = istnieje(k);
        TextView t = Styl.przycisk(this, (jest ? "✓  " : "+  ") + k.nazwa + "   (" + k.adres + ")", false);
        t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        t.setOnClickListener(v -> {
            if (istnieje(k)) { toast("Ten komputer jest już na liście"); return; }
            EditText h = Styl.haslo(this);
            h.setText(prefs.getString("ostatnieHaslo", ""));
            new AlertDialog.Builder(this).setTitle("Hasło dla " + k.nazwa).setView(owin(h))
                    .setPositiveButton("Dodaj", (d, x) -> {
                        k.haslo = h.getText().toString();
                        sprawdzIDodaj(k);
                    })
                    .setNegativeButton("Anuluj", null).show();
        });
        znalezione.addView(t, Styl.odstep(this, 8));
    }

    private void sprawdzIDodaj(Komp k) {
        prefs.edit().putString("ostatnieHaslo", k.haslo).apply();
        status.setText("Sprawdzam…");
        ex.execute(() -> {
            String[] w = ping(k, 4000);
            ui.post(() -> {
                if (w[0] == null) { status.setText(w[1]); return; }
                if (k.nazwa == null || k.nazwa.isEmpty() || k.nazwa.equals(k.adres)) k.nazwa = w[0];
                if (!istnieje(k)) komputery.add(k);
                zapisz(); pokazListe();
                status.setText("Dodano: " + k.nazwa);
            });
        });
    }

    private void dodajDialog() {
        EditText adres = Styl.pole(this, "IP komputera lub https://…/podglad.php",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = Styl.pole(this, "Port (tylko w sieci lokalnej)", InputType.TYPE_CLASS_NUMBER);
        port.setText("8765");
        EditText h = Styl.haslo(this);
        h.setText(prefs.getString("ostatnieHaslo", ""));
        new AlertDialog.Builder(this).setTitle("Dodaj komputer").setView(owin(adres, port, h))
                .setPositiveButton("Dodaj", (d, x) -> {
                    String a = adres.getText().toString().trim();
                    if (a.isEmpty()) return;
                    if (a.contains("podglad.php") && !a.startsWith("http")) a = "https://" + a;
                    Komp k = new Komp();
                    k.adres = a; k.port = port.getText().toString().trim(); k.haslo = h.getText().toString();
                    k.pc = ""; k.nazwa = "";
                    if (k.port.isEmpty()) k.port = "8765";
                    if (k.relay()) dodajZInternetu(k); else { k.nazwa = a; sprawdzIDodaj(k); }
                })
                .setNegativeButton("Anuluj", null).show();
    }

    /** Pobiera z podglad.php listę komputerów z tym hasłem i dodaje je wszystkie. */
    private void dodajZInternetu(Komp wzor) {
        prefs.edit().putString("ostatnieHaslo", wzor.haslo).apply();
        status.setText("Pobieram listę komputerów…");
        ex.execute(() -> {
            String blad = null;
            List<Komp> nowe = new ArrayList<>();
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(wzor.baza() + "?a=list").openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(10000);
                c.setRequestProperty("X-Key", wzor.haslo);
                int kod = c.getResponseCode();
                if (kod == 401) blad = "Złe hasło (albo żaden komputer jeszcze nie łączył się z tym hasłem).";
                else if (kod != 200) blad = "Serwer odpowiedział kodem " + kod + ". Sprawdź adres podglad.php.";
                else {
                    JSONArray a = new JSONArray(czytajTekst(c.getInputStream()));
                    for (int i = 0; i < a.length(); i++) {
                        Komp k = new Komp();
                        k.adres = wzor.adres; k.port = wzor.port; k.haslo = wzor.haslo;
                        k.pc = a.getJSONObject(i).getString("name");
                        k.nazwa = k.pc;
                        nowe.add(k);
                    }
                    if (nowe.isEmpty()) blad = "Żaden komputer nie łączył się jeszcze z tym hasłem.";
                }
            } catch (Exception e) {
                blad = "Nie mogę połączyć się z " + wzor.adres;
            }
            final String fb = blad;
            ui.post(() -> {
                if (fb != null) { status.setText(fb); return; }
                int dodane = 0;
                for (Komp k : nowe) if (!istnieje(k)) { komputery.add(k); dodane++; }
                zapisz(); pokazListe();
                status.setText(dodane > 0 ? "Dodano komputerów: " + dodane : "Wszystkie komputery są już na liście.");
            });
        });
    }

    // ---------- ustawienia ----------

    private void odswiezOpisUstawien() {
        tUstawienia.setText("Odświeżanie: " + nazwaCzasu(prefs.getInt("interwal", 3000))
                + "  ·  Jakość: " + JAKOSCI[Math.min(3, prefs.getInt("jakosc2", 2))]);
    }

    private void ustawienia() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = Styl.dp(this, 22);
        l.setPadding(p, Styl.dp(this, 4), p, 0);

        l.addView(Styl.tekst(this, "Domyślny czas odświeżania", 14, Styl.SZARY, true), Styl.odstep(this, 8));
        final int[] wybrany = {prefs.getInt("interwal", 3000)};
        final int[] jak = {prefs.getInt("jakosc2", 2)};
        LinearLayout siatka = new LinearLayout(this);
        siatka.setOrientation(LinearLayout.VERTICAL);
        l.addView(siatka);
        EditText wlasny = Styl.pole(this, "Własny czas w sekundach (np. 7 lub 0,5)",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        l.addView(wlasny, Styl.odstep(this, 10));

        l.addView(Styl.tekst(this, "Domyślna jakość", 14, Styl.SZARY, true), Styl.odstep(this, 18));
        LinearLayout siatkaJ = new LinearLayout(this);
        siatkaJ.setOrientation(LinearLayout.VERTICAL);
        l.addView(siatkaJ);

        Runnable[] rysuj = new Runnable[1];
        rysuj[0] = () -> {
            siatka.removeAllViews();
            LinearLayout wiersz = null;
            for (int i = 0; i < CZASY_MS.length; i++) {
                if (i % 3 == 0) { wiersz = new LinearLayout(this); siatka.addView(wiersz, Styl.odstep(this, 8)); }
                final int ms = CZASY_MS[i];
                TextView t = Styl.przycisk(this, CZASY_NAZWY[i], ms == wybrany[0]);
                t.setPadding(0, Styl.dp(this, 10), 0, Styl.dp(this, 10));
                t.setOnClickListener(v -> { wybrany[0] = ms; wlasny.setText(""); rysuj[0].run(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (i % 3 != 2) lp.rightMargin = Styl.dp(this, 8);
                wiersz.addView(t, lp);
            }
            siatkaJ.removeAllViews();
            LinearLayout w2 = null;
            for (int i = 0; i < JAKOSCI.length; i++) {
                if (i % 2 == 0) { w2 = new LinearLayout(this); siatkaJ.addView(w2, Styl.odstep(this, 8)); }
                final int q = i;
                TextView t = Styl.przycisk(this, JAKOSCI[i], i == jak[0]);
                t.setPadding(0, Styl.dp(this, 10), 0, Styl.dp(this, 10));
                t.setOnClickListener(v -> { jak[0] = q; rysuj[0].run(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (i % 2 == 0) lp.rightMargin = Styl.dp(this, 8);
                w2.addView(t, lp);
            }
        };
        rysuj[0].run();
        boolean znany = false;
        for (int ms : CZASY_MS) if (ms == wybrany[0]) znany = true;
        if (!znany) wlasny.setText(nazwaCzasu(wybrany[0]).replace(" s", ""));

        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        new AlertDialog.Builder(this).setTitle("Ustawienia").setView(sv)
                .setPositiveButton("Zapisz", (d, x) -> {
                    int ms = wybrany[0];
                    String s = wlasny.getText().toString().trim().replace(',', '.');
                    if (!s.isEmpty()) {
                        try { ms = Math.round(Float.parseFloat(s) * 1000); } catch (Exception ignored) {}
                        ms = Math.max(250, Math.min(ms, 3600000));
                    }
                    prefs.edit().putInt("interwal", ms).putInt("jakosc2", jak[0]).apply();
                    odswiezOpisUstawien();
                })
                .setNegativeButton("Anuluj", null).show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
