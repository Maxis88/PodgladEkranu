package pl.artgarage.podglad;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * Obraz z powiększaniem i przesuwaniem.
 * Tryb podglądu: stuknięcie = pokaż/ukryj pasek, 2× = powiększ.
 * Tryb sterowania: stuknięcie = klik, 2× = podwójny klik, przytrzymanie = prawy klik,
 * dwa palce = powiększ/przesuń, jeden palec = przesuń obraz.
 */
public class ZoomImageView extends ImageView {

    public interface OnTap { void tap(); }
    /** typ: 0 = lewy klik, 1 = podwójny, 2 = prawy. nx, ny w zakresie 0–1. */
    public interface OnKlik { void klik(float nx, float ny, int typ); }

    private final Matrix m = new Matrix();
    private final float[] v = new float[9];
    private final ScaleGestureDetector sgd;
    private final GestureDetector gd;
    private int bw, bh;
    private float minSkala = 1f, fx, fy;
    private OnTap onTap;
    private OnKlik onKlik;
    private boolean sterowanie;

    public ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                fx = d.getFocusX(); fy = d.getFocusY();
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector d) {
                float s = skala(), f = d.getScaleFactor();
                float nowa = Math.max(minSkala * 0.8f, Math.min(s * f, minSkala * 12f));
                f = nowa / s;
                m.postScale(f, f, d.getFocusX(), d.getFocusY());
                m.postTranslate(d.getFocusX() - fx, d.getFocusY() - fy);
                fx = d.getFocusX(); fy = d.getFocusY();
                ogranicz();
                return true;
            }
        });
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                m.postTranslate(-dx, -dy);
                ogranicz();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (sterowanie) { klik(e, 1); return true; }
                float s = skala();
                if (s > minSkala * 1.1f) dopasuj();
                else {
                    float f = Math.max(1f, minSkala * 2.5f) / s;
                    m.postScale(f, f, e.getX(), e.getY());
                    ogranicz();
                }
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (sterowanie) klik(e, 0);
                else if (onTap != null) onTap.tap();
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (sterowanie) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    klik(e, 2);
                }
            }
        });
    }

    public void setOnTap(OnTap t) { onTap = t; }
    public void setOnKlik(OnKlik k) { onKlik = k; }
    public void setSterowanie(boolean s) { sterowanie = s; }

    private void klik(MotionEvent e, int typ) {
        if (onKlik == null || bw == 0) return;
        Matrix inv = new Matrix();
        if (!m.invert(inv)) return;
        float[] p = {e.getX(), e.getY()};
        inv.mapPoints(p);
        float nx = p[0] / bw, ny = p[1] / bh;
        if (nx < 0 || ny < 0 || nx > 1 || ny > 1) return;
        onKlik.klik(nx, ny, typ);
    }

    private float skala() { m.getValues(v); return v[Matrix.MSCALE_X]; }

    /** Podmienia klatkę; zachowuje powiększenie, jeśli rozmiar obrazu się nie zmienił. */
    public void ustaw(Bitmap b) {
        boolean nowyRozmiar = b.getWidth() != bw || b.getHeight() != bh;
        int stareW = bw;
        bw = b.getWidth(); bh = b.getHeight();
        setImageBitmap(b);
        if (nowyRozmiar && stareW > 0 && skala() > minSkala * 1.05f) {
            // zmiana jakości przy powiększeniu – przeskaluj, by widok został w tym samym miejscu
            float f = stareW / (float) bw;
            m.preScale(f, f);
            minSkala = Math.min(getWidth() / (float) bw, getHeight() / (float) bh);
            ogranicz();
        } else if (nowyRozmiar) dopasuj();
        else setImageMatrix(m);
    }

    public void dopasuj() {
        if (bw == 0 || getWidth() == 0) return;
        minSkala = Math.min(getWidth() / (float) bw, getHeight() / (float) bh);
        m.reset();
        m.postScale(minSkala, minSkala);
        m.postTranslate((getWidth() - bw * minSkala) / 2f, (getHeight() - bh * minSkala) / 2f);
        setImageMatrix(m);
    }

    private void ogranicz() {
        m.getValues(v);
        float s = v[Matrix.MSCALE_X], w = bw * s, h = bh * s;
        float tx = v[Matrix.MTRANS_X], ty = v[Matrix.MTRANS_Y];
        float W = getWidth(), H = getHeight();
        float nx = w <= W ? (W - w) / 2f : Math.min(0, Math.max(W - w, tx));
        float ny = h <= H ? (H - h) / 2f : Math.min(0, Math.max(H - h, ty));
        m.postTranslate(nx - tx, ny - ty);
        setImageMatrix(m);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        dopasuj();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        sgd.onTouchEvent(e);
        if (!sgd.isInProgress()) gd.onTouchEvent(e);
        return true;
    }
}
