# -*- coding: utf-8 -*-
"""Podgląd Ekranu – serwer dla Windows (okienko). Telefon łączy się aplikacją Podgląd Ekranu."""
import hashlib, io, json, os, secrets, socket, sys, threading, time
import tkinter as tk
from tkinter import ttk, messagebox
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

import mss
from PIL import Image

APP = "PodgladEkranu"
UDP_PORT = 8766
CFG_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), APP)
CFG_FILE = os.path.join(CFG_DIR, "config.json")

stan = {"haslo": "", "log": None}
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
        img = img.resize((max(1, int(img.width * skala)), max(1, int(img.height * skala))), Image.BILINEAR)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=jakosc, optimize=True)
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
            return self._send(200, socket.gethostname().encode())
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
            extra = {"X-Hash": h, "X-Monitors": str(ile)}
            if jpg is None:
                return self._send(204, extra=extra)
            return self._send(200, jpg, "image/jpeg", extra)
        self._send(404)


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
                    s.sendto(f"PODGLAD!{self.port}!{socket.gethostname()}".encode(), addr)
                except OSError:
                    pass

    def stop(self):
        if self.http:
            self.http.shutdown(); self.http.server_close(); self.http = None
        if self.udp:
            u, self.udp = self.udp, None
            u.close()


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

        ttk.Label(f, text="Port:").grid(row=2, column=0, sticky="w", pady=(8, 0))
        self.port = tk.StringVar(value=str(self.cfg.get("port", 8765)))
        ttk.Entry(f, textvariable=self.port, width=8).grid(row=3, column=0, sticky="w")

        self.btn = ttk.Button(f, text="▶  Uruchom", command=self.przelacz)
        self.btn.grid(row=4, column=0, columnspan=2, sticky="we", pady=10)

        self.status = tk.StringVar(value="Zatrzymany")
        ttk.Label(f, textvariable=self.status, foreground="#a00").grid(row=5, column=0, columnspan=2, sticky="w")
        self.adres = tk.StringVar()
        ttk.Label(f, textvariable=self.adres, font=("Segoe UI", 11, "bold")).grid(row=6, column=0, columnspan=2, sticky="w")

        self.auto = tk.BooleanVar(value=self.cfg.get("auto", False))
        ttk.Checkbutton(f, text="Uruchamiaj razem z Windows (w tle)", variable=self.auto,
                        command=self.zmien_auto).grid(row=7, column=0, columnspan=2, sticky="w", pady=(8, 0))

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

    def zapisz(self):
        try:
            zapisz_cfg({"haslo": self.haslo.get(), "port": int(self.port.get() or 8765), "auto": self.auto.get()})
        except Exception:
            pass

    def przelacz(self):
        if self.srv.http:
            self.srv.stop()
            self.btn.config(text="▶  Uruchom"); self.status.set("Zatrzymany"); self.adres.set("")
            return
        h = self.haslo.get()
        if len(h) < 4:
            messagebox.showwarning("Hasło", "Ustaw hasło (min. 4 znaki).")
            return
        try:
            port = int(self.port.get())
            stan["haslo"] = h
            self.srv.start(port)
        except Exception as e:
            messagebox.showerror("Błąd", f"Nie mogę uruchomić serwera:\n{e}")
            return
        self.zapisz()
        self.btn.config(text="■  Zatrzymaj")
        self.status.set("Działa – w telefonie naciśnij „Szukaj” albo wpisz adres:")
        self.adres.set("   ".join(adresy_ip()) + f"   port {port}")
        self.dopisz("Serwer uruchomiony")

    def run(self):
        self.root.mainloop()
        self.srv.stop()


if __name__ == "__main__":
    Okno().run()
