# -*- coding: utf-8 -*-
"""Podgląd Ekranu – serwer dla Windows (okienko). Telefon łączy się aplikacją Podgląd Ekranu."""
import hashlib, io, json, os, secrets, socket, sys, threading, time, urllib.request
import tkinter as tk
from tkinter import ttk, messagebox
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

import mss
from PIL import Image

# --- sterowanie myszą i klawiaturą (Windows SendInput) ---
import ctypes
from ctypes import wintypes
if sys.platform == "win32":
    user32 = ctypes.windll.user32
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        try:
            user32.SetProcessDPIAware()
        except Exception:
            pass

    class _KI(ctypes.Structure):
        _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                    ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_size_t)]

    class _MI(ctypes.Structure):
        _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", ctypes.c_long),
                    ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_size_t)]

    class _HI(ctypes.Structure):
        _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD)]

    class _U(ctypes.Union):
        _fields_ = [("ki", _KI), ("mi", _MI), ("hi", _HI)]

    class _IN(ctypes.Structure):
        _fields_ = [("type", wintypes.DWORD), ("u", _U)]


def _wyslij(*inp):
    arr = (_IN * len(inp))(*inp)
    user32.SendInput(len(inp), arr, ctypes.sizeof(_IN))


def _mysz(flagi, dane=0):
    return _IN(0, _U(mi=_MI(0, 0, dane, flagi, 0, 0)))


def _klaw(vk=0, scan=0, flagi=0):
    return _IN(1, _U(ki=_KI(vk, scan, flagi, 0, 0)))


VK = {"enter": 0x0D, "backspace": 0x08, "tab": 0x09, "esc": 0x1B, "delete": 0x2E, "space": 0x20,
      "up": 0x26, "down": 0x28, "left": 0x25, "right": 0x27, "home": 0x24, "end": 0x23,
      "pgup": 0x21, "pgdn": 0x22, "win": 0x5B, "ctrl": 0x11, "alt": 0x12, "shift": 0x10,
      "f5": 0x74, "f11": 0x7A}
ROZSZERZONE = {0x2E, 0x26, 0x28, 0x25, 0x27, 0x24, 0x23, 0x21, 0x22, 0x5B}


def wykonaj(zdarzenia, monitor):
    """Wykonuje polecenia z telefonu. x,y to ułamki (0–1) szerokości/wysokości monitora."""
    if sys.platform != "win32":
        return
    with mss.mss() as sct:
        mons = sct.monitors
        m = mons[monitor] if 0 <= monitor < len(mons) else mons[1]
    for e in zdarzenia:
        t = e.get("t")
        if "x" in e:
            x = m["left"] + int(min(1, max(0, float(e["x"]))) * (m["width"] - 1))
            y = m["top"] + int(min(1, max(0, float(e["y"]))) * (m["height"] - 1))
            user32.SetCursorPos(x, y)
        if t == "click":
            dn, up = (0x0008, 0x0010) if e.get("b") == "right" else (0x0002, 0x0004)
            for _ in range(int(e.get("n", 1))):
                _wyslij(_mysz(dn), _mysz(up))
                time.sleep(0.03)
        elif t == "scroll":
            _wyslij(_mysz(0x0800, int(e.get("d", 1)) * 120))
        elif t == "text":
            for ch in str(e.get("s", ""))[:2000]:
                if ch == "\n":
                    _wyslij(_klaw(0x0D), _klaw(0x0D, 0, 2)); continue
                b = ch.encode("utf-16-le")
                for i in range(0, len(b), 2):
                    kod = int.from_bytes(b[i:i + 2], "little")
                    _wyslij(_klaw(0, kod, 4), _klaw(0, kod, 4 | 2))
        elif t == "key":
            klawisze = e.get("k", [])
            if isinstance(klawisze, str):
                klawisze = [klawisze]
            vks = []
            for k in klawisze:
                k = str(k).lower()
                v = VK.get(k) or (ord(k.upper()) if len(k) == 1 and k.isalnum() else None)
                if v:
                    vks.append(v)
            for v in vks:
                _wyslij(_klaw(v, 0, 1 if v in ROZSZERZONE else 0))
            for v in reversed(vks):
                _wyslij(_klaw(v, 0, (1 if v in ROZSZERZONE else 0) | 2))


APP = "PodgladEkranu"
UDP_PORT = 8766
CFG_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), APP)
CFG_FILE = os.path.join(CFG_DIR, "config.json")

stan = {"haslo": "", "log": None, "nazwa": socket.gethostname(), "sterowanie": False}
_lock = threading.Lock()


def wczytaj_cfg():
    try:
        with open(CFG_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def zapisz_cfg(c):
    os.makedirs(CFG_DIR, exist_ok=True)
    with open(CFG_FILE, "w", encoding="utf-8") as f:
        json.dump(c, f)


def adresy_ip():
    ips = set()
    try:
        for i in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(i[4][0])
    except OSError:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0]); s.close()
    except OSError:
        pass
    ips.discard("127.0.0.1")
    return sorted(ips) or ["127.0.0.1"]


def zrzut(monitor, skala, jakosc, poprzedni_hash):
    with _lock, mss.mss() as sct:
        mons = sct.monitors
        m = mons[monitor] if 0 <= monitor < len(mons) else mons[1]
        raw = sct.grab(m)
        ile = len(mons) - 1
    h = hashlib.md5(raw.bgra).hexdigest()[:16]
    if h == poprzedni_hash:
        return h, None, ile
    img = Image.frombytes("RGB", raw.size, raw.bgra, "raw", "BGRX")
    if skala < 1:
        img = img.resize((max(1, int(img.width * skala)), max(1, int(img.height * skala))), Image.LANCZOS)
    buf = io.BytesIO()
    # subsampling=0 = pełny kolor przy literach -> ostry, czytelny tekst
    img.save(buf, "JPEG", quality=jakosc, optimize=True, subsampling=0 if jakosc >= 70 else 2)
    return h, buf.getvalue(), ile


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, code, body=b"", ctype="text/plain; charset=utf-8", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _auth(self):
        ok = secrets.compare_digest(self.headers.get("X-Key", "").encode(), stan["haslo"].encode())
        if not ok:
            time.sleep(1.5)
            if stan["log"]:
                stan["log"](f"Złe hasło z {self.client_address[0]}")
        return ok

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/ping":
            if not self._auth():
                return self._send(401)
            if stan["log"]:
                stan["log"](f"Połączono: {self.client_address[0]}")
            return self._send(200, stan["nazwa"].encode("utf-8"))
        if u.path == "/shot":
            if not self._auth():
                return self._send(401)
            q = parse_qs(u.query)
            g = lambda k, d: q.get(k, [d])[0]
            try:
                mon = int(g("m", "1"))
                skala = min(1.0, max(0.1, float(g("s", "0.6"))))
                jak = min(95, max(10, int(g("q", "55"))))
                h, jpg, ile = zrzut(mon, skala, jak, g("h", ""))
            except Exception as e:
                return self._send(500, str(e).encode())
            extra = {"X-Hash": h, "X-Monitors": str(ile), "X-Control": "1" if stan["sterowanie"] else "0"}
            if jpg is None:
                return self._send(204, extra=extra)
            return self._send(200, jpg, "image/jpeg", extra)
        self._send(404)


    def do_POST(self):
        u = urlparse(self.path)
        if u.path != "/input":
            return self._send(404)
        if not self._auth():
            return self._send(401)
        n = int(self.headers.get("Content-Length", "0") or 0)
        dane = self.rfile.read(min(n, 65536))
        if not stan["sterowanie"]:
            return self._send(403, "Sterowanie wyłączone na komputerze".encode("utf-8"))
        try:
            mon = int(parse_qs(u.query).get("m", ["1"])[0])
            wykonaj(json.loads(dane.decode("utf-8")), mon)
        except Exception as e:
            return self._send(500, str(e).encode())
        self._send(200, b"ok")


class Serwer:
    def __init__(self):
        self.http = None
        self.udp = None
        self.port = 8765

    def start(self, port):
        self.port = port
        self.http = ThreadingHTTPServer(("0.0.0.0", port), Handler)
        self.http.daemon_threads = True
        threading.Thread(target=self.http.serve_forever, daemon=True).start()
        try:
            self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.udp.bind(("0.0.0.0", UDP_PORT))
            threading.Thread(target=self._udp_loop, daemon=True).start()
        except OSError:
            self.udp = None

    def _udp_loop(self):
        s = self.udp
        while s is self.udp and s is not None:
            try:
                data, addr = s.recvfrom(256)
            except OSError:
                break
            if data.startswith(b"PODGLAD?"):
                try:
                    s.sendto(f"PODGLAD!{self.port}!{stan['nazwa']}".encode("utf-8"), addr)
                except OSError:
                    pass

    def stop(self):
        if self.http:
            self.http.shutdown(); self.http.server_close(); self.http = None
        if self.udp:
            u, self.udp = self.udp, None
            u.close()


class Posrednik:
    """Łączy się z podglad.php w internecie: wysyła klatki tylko gdy ktoś ogląda."""
    def __init__(self, url, haslo, log):
        self.url, self.haslo, self.log = url.rstrip("/"), haslo, log
        self.dziala = True
        threading.Thread(target=self.petla, daemon=True).start()

    def _req(self, a, dane=None, extra=None):
        h = {"X-Key": self.haslo, "X-Name": stan["nazwa"].encode("utf-8").decode("latin-1"),
             "X-Control": "1" if stan["sterowanie"] else "0", "User-Agent": "PodgladEkranu/1.3"}
        h.update(extra or {})
        req = urllib.request.Request(f"{self.url}?a={a}", data=dane, headers=h, method="POST" if dane else "GET")
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.read()

    def petla(self):
        ostatni_hash, blad, ogladany = "", False, False
        while self.dziala:
            try:
                r = json.loads(self._req("poll"))
                if r.get("inp") and stan["sterowanie"]:
                    for paczka in r["inp"]:
                        try:
                            wykonaj(paczka.get("ev", []), int(paczka.get("m", 1)))
                        except Exception as e:
                            self.log(f"Sterowanie: błąd ({e})")
                if blad:
                    self.log("Internet: połączono ponownie"); blad = False
                if r.get("wiek", 99999) < 20:
                    if not ogladany:
                        self.log("Internet: ktoś ogląda"); ogladany = True
                    h, jpg, ile = zrzut(int(r["m"]), min(1.0, max(0.1, float(r["s"]))),
                                        min(95, max(10, int(r["q"]))), ostatni_hash)
                    if jpg:
                        self._req("push", jpg, {"X-Hash": h, "X-Monitors": str(ile), "Content-Type": "image/jpeg"})
                        ostatni_hash = h
                    time.sleep(0.4)
                else:
                    if ogladany:
                        self.log("Internet: podgląd zakończony"); ogladany = False
                    ostatni_hash = ""
                    time.sleep(2)
            except Exception as e:
                if not blad:
                    self.log(f"Internet: błąd ({e})"); blad = True
                time.sleep(5)

    def stop(self):
        self.dziala = False


def autostart(wlacz):
    import winreg
    klucz = r"Software\Microsoft\Windows\CurrentVersion\Run"
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, klucz, 0, winreg.KEY_SET_VALUE) as k:
        if wlacz:
            exe = sys.executable if getattr(sys, "frozen", False) else f'{sys.executable}" "{os.path.abspath(__file__)}'
            winreg.SetValueEx(k, APP, 0, winreg.REG_SZ, f'"{exe}" --auto')
        else:
            try:
                winreg.DeleteValue(k, APP)
            except FileNotFoundError:
                pass


class Okno:
    def __init__(self):
        self.cfg = wczytaj_cfg()
        self.srv = Serwer()
        self.root = tk.Tk()
        self.root.title("Podgląd Ekranu – serwer")
        self.root.resizable(False, False)
        f = ttk.Frame(self.root, padding=14); f.pack()

        ttk.Label(f, text="Hasło (to samo wpisz w telefonie):").grid(row=0, column=0, sticky="w")
        self.haslo = tk.StringVar(value=self.cfg.get("haslo", ""))
        self.e_haslo = ttk.Entry(f, textvariable=self.haslo, show="•", width=28)
        self.e_haslo.grid(row=1, column=0, sticky="we")
        self.pokaz = tk.BooleanVar()
        ttk.Checkbutton(f, text="pokaż", variable=self.pokaz,
                        command=lambda: self.e_haslo.config(show="" if self.pokaz.get() else "•")).grid(row=1, column=1, padx=6)

        ttk.Label(f, text="Nazwa tego komputera (widoczna w telefonie):").grid(row=12, column=0, columnspan=2, sticky="w", pady=(8, 0))
        self.nazwa = tk.StringVar(value=self.cfg.get("nazwa", socket.gethostname()))
        ttk.Entry(f, textvariable=self.nazwa, width=48).grid(row=13, column=0, columnspan=2, sticky="we")

        ttk.Label(f, text="Port:").grid(row=2, column=0, sticky="w", pady=(8, 0))
        self.port = tk.StringVar(value=str(self.cfg.get("port", 8765)))
        ttk.Entry(f, textvariable=self.port, width=8).grid(row=3, column=0, sticky="w")

        ttk.Label(f, text="Przez internet – adres pliku podglad.php (opcjonalnie):").grid(row=10, column=0, columnspan=2, sticky="w", pady=(8, 0))
        self.url = tk.StringVar(value=self.cfg.get("url", ""))
        ttk.Entry(f, textvariable=self.url, width=48).grid(row=11, column=0, columnspan=2, sticky="we")
        self.posrednik = None

        self.btn = ttk.Button(f, text="▶  Uruchom", command=self.przelacz)
        self.btn.grid(row=4, column=0, columnspan=2, sticky="we", pady=10)

        self.status = tk.StringVar(value="Zatrzymany")
        ttk.Label(f, textvariable=self.status, foreground="#a00").grid(row=5, column=0, columnspan=2, sticky="w")
        self.adres = tk.StringVar()
        ttk.Label(f, textvariable=self.adres, font=("Segoe UI", 11, "bold")).grid(row=6, column=0, columnspan=2, sticky="w")

        self.auto = tk.BooleanVar(value=self.cfg.get("auto", False))
        ttk.Checkbutton(f, text="Uruchamiaj razem z Windows (w tle)", variable=self.auto,
                        command=self.zmien_auto).grid(row=7, column=0, columnspan=2, sticky="w", pady=(8, 0))

        self.ster = tk.BooleanVar(value=self.cfg.get("sterowanie", False))
        ttk.Checkbutton(f, text="Zezwalaj na sterowanie myszą i klawiaturą z telefonu", variable=self.ster,
                        command=self.zmien_ster).grid(row=14, column=0, columnspan=2, sticky="w", pady=(8, 0))
        stan["sterowanie"] = self.ster.get()

        ttk.Label(f, text="Zdarzenia:").grid(row=8, column=0, sticky="w", pady=(8, 0))
        self.lista = tk.Listbox(f, height=6, width=48)
        self.lista.grid(row=9, column=0, columnspan=2)
        stan["log"] = lambda t: self.root.after(0, self.dopisz, t)

        if "--auto" in sys.argv and self.haslo.get():
            self.przelacz()
            self.root.iconify()

    def dopisz(self, t):
        self.lista.insert(0, time.strftime("%H:%M:%S ") + t)
        self.lista.delete(50, tk.END)

    def zmien_auto(self):
        try:
            autostart(self.auto.get())
        except Exception as e:
            messagebox.showerror("Błąd", str(e))
        self.zapisz()

    def zmien_ster(self):
        stan["sterowanie"] = self.ster.get()
        self.dopisz("Sterowanie " + ("WŁĄCZONE" if stan["sterowanie"] else "wyłączone"))
        self.zapisz()

    def zapisz(self):
        try:
            zapisz_cfg({"haslo": self.haslo.get(), "port": int(self.port.get() or 8765), "auto": self.auto.get(),
                        "url": self.url.get().strip(), "nazwa": self.nazwa.get().strip(),
                        "sterowanie": self.ster.get()})
        except Exception:
            pass

    def przelacz(self):
        if self.srv.http:
            self.srv.stop()
            if self.posrednik:
                self.posrednik.stop(); self.posrednik = None
            self.btn.config(text="▶  Uruchom"); self.status.set("Zatrzymany"); self.adres.set("")
            return
        h = self.haslo.get()
        if len(h) < 4:
            messagebox.showwarning("Hasło", "Ustaw hasło (min. 4 znaki).")
            return
        try:
            port = int(self.port.get())
            stan["haslo"] = h
            stan["nazwa"] = self.nazwa.get().strip() or socket.gethostname()
            self.srv.start(port)
        except Exception as e:
            messagebox.showerror("Błąd", f"Nie mogę uruchomić serwera:\n{e}")
            return
        self.zapisz()
        self.btn.config(text="■  Zatrzymaj")
        self.status.set("Działa – w telefonie naciśnij „Szukaj” albo wpisz adres:")
        self.adres.set("   ".join(adresy_ip()) + f"   port {port}")
        self.dopisz("Serwer uruchomiony")
        url = self.url.get().strip()
        if url:
            if not url.startswith("http"):
                url = "https://" + url
            self.posrednik = Posrednik(url, h, lambda t: self.root.after(0, self.dopisz, t))
            self.adres.set(self.adres.get() + "\nInternet: " + url)

    def run(self):
        self.root.protocol("WM_DELETE_WINDOW", lambda: (self.zapisz(), self.root.destroy()))
        self.root.mainloop()
        self.srv.stop()
        if self.posrednik:
            self.posrednik.stop()


if __name__ == "__main__":
    Okno().run()
