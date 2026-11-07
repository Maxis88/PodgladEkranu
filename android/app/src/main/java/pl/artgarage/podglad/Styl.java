package pl.artgarage.podglad;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Wspólne kolory i elementy interfejsu. */
final class Styl {
    static final int TLO = Color.parseColor("#0F1115");
    static final int KARTA = Color.parseColor("#1A1D24");
    static final int KARTA2 = Color.parseColor("#232733");
    static final int AKCENT = Color.parseColor("#4F8CFF");
    static final int TEKST = Color.parseColor("#F2F4F8");
    static final int SZARY = Color.parseColor("#9AA3B2");
    static final int ZIELONY = Color.parseColor("#34C759");
    static final int CZERWONY = Color.parseColor("#FF6B6B");
    static final int NIEAKTYWNY = Color.parseColor("#4A505C");

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable ksztalt(int kolor, float promienDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(kolor);
        g.setCornerRadius(dp(c, promienDp));
        return g;
    }

    static Drawable klikalne(int kolor, float promienDp, Context c) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), ksztalt(kolor, promienDp, c),
                ksztalt(Color.WHITE, promienDp, c));
    }

    static TextView przycisk(Context c, String tekst, boolean glowny) {
        TextView t = new TextView(c);
        t.setText(tekst);
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(glowny ? Color.WHITE : TEKST);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 18), dp(c, 13), dp(c, 18), dp(c, 13));
        t.setBackground(klikalne(glowny ? AKCENT : KARTA2, 14, c));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    static TextView tekst(Context c, String s, float rozmiar, int kolor, boolean pogrubiony) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(rozmiar);
        t.setTextColor(kolor);
        if (pogrubiony) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static EditText pole(Context c, String podpowiedz, int typ) {
        EditText e = new EditText(c);
        e.setHint(podpowiedz);
        e.setHintTextColor(NIEAKTYWNY);
        e.setTextColor(TEKST);
        e.setInputType(typ);
        e.setSingleLine(true);
        e.setTextSize(16);
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        e.setBackground(ksztalt(KARTA2, 12, c));
        return e;
    }

    static EditText haslo(Context c) {
        return pole(c, "Hasło", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    }

    static LinearLayout.LayoutParams odstep(Context c, int gora) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, gora);
        return lp;
    }

    static View kropka(Context c, int kolor) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(kolor);
        v.setBackground(g);
        return v;
    }

    static void wstawki(View root) {
        root.setOnApplyWindowInsetsListener((v, in) -> {
            v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                    in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
            return in.consumeSystemWindowInsets();
        });
    }

    /** Szerokość treści: pełna na telefonie, maks. 560dp (wyśrodkowana) na tablecie. */
    static int szerokoscTresci(Activity a) {
        int ekran = a.getResources().getDisplayMetrics().widthPixels;
        return Math.min(ekran, dp(a, 560));
    }

    private Styl() {}
}
