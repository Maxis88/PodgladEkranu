<?php
/*
 * Podgląd Ekranu – pośrednik internetowy (wersja z wieloma komputerami).
 * Wgraj ten plik na hosting z PHP (np. https://3dartgarage.pl/podglad.php). Nic nie trzeba konfigurować.
 * Hasło tworzy prywatną grupę; każdy komputer z tym hasłem jest w niej widoczny pod swoją nazwą.
 */
header('Cache-Control: no-store');

$key = $_SERVER['HTTP_X_KEY'] ?? '';
$a   = $_GET['a'] ?? '';
if (strlen($key) < 4) { http_response_code(401); exit; }

$base = __DIR__ . '/podglad_dane';
if (!is_dir($base)) {
    @mkdir($base, 0700, true);
    @file_put_contents("$base/.htaccess", "Require all denied\nDeny from all\n");
    @file_put_contents("$base/index.html", "");
}
$kdir = $base . '/' . hash('sha256', 'podglad|' . $key);
$komputer = ($a === 'poll' || $a === 'push') ? ($_SERVER['HTTP_X_NAME'] ?? 'Komputer') : ($_GET['pc'] ?? '');
$komputer = mb_substr(trim($komputer), 0, 60) ?: 'Komputer';
$dir = $kdir . '/' . substr(hash('sha256', $komputer), 0, 24);

function czytaj($f) { $s = @file_get_contents($f); return $s ? json_decode($s, true) : null; }
function zapisz($f, $d) { file_put_contents("$f.tmp", json_encode($d)); rename("$f.tmp", $f); }

if ($a === 'poll' || $a === 'push') {
    if (!is_dir($dir)) @mkdir($dir, 0700, true);
} elseif (!is_dir($kdir)) {
    usleep(1500000); http_response_code(401); exit;   // złe hasło
}

switch ($a) {
    case 'list':   // telefon: lista komputerów w grupie
        $out = [];
        foreach (glob("$kdir/*/meta.json") as $f) {
            $m = czytaj($f);
            if (!$m || empty($m['name'])) continue;
            $out[] = ['name' => $m['name'], 'online' => (time() - ($m['seen'] ?? 0)) < 30];
        }
        header('Content-Type: application/json');
        echo json_encode($out);
        break;

    case 'ping':
        $meta = czytaj("$dir/meta.json");
        if (!$meta || (time() - ($meta['seen'] ?? 0)) >= 30) { http_response_code(503); echo 'offline'; exit; }
        echo $meta['name'];
        break;

    case 'shot':
        if (!is_dir($dir)) { http_response_code(503); exit; }
        zapisz("$dir/req.json", [
            't' => microtime(true),
            'm' => (int)($_GET['m'] ?? 1),
            's' => (float)($_GET['s'] ?? 1),
            'q' => (int)($_GET['q'] ?? 80),
        ]);
        $meta = czytaj("$dir/meta.json");
        if ($meta) { header('X-Monitors: ' . (int)($meta['mon'] ?? 1)); header('X-Hash: ' . ($meta['h'] ?? '')); }
        if (!$meta || empty($meta['h']) || !is_file("$dir/frame.jpg") || $meta['h'] === ($_GET['h'] ?? '')) {
            http_response_code(204); exit;
        }
        header('Content-Type: image/jpeg');
        header('Content-Length: ' . filesize("$dir/frame.jpg"));
        readfile("$dir/frame.jpg");
        break;

    case 'poll':
        $meta = czytaj("$dir/meta.json") ?: ['h' => ''];
        $meta['seen'] = time();
        $meta['name'] = $komputer;
        zapisz("$dir/meta.json", $meta);
        $r = czytaj("$dir/req.json");
        header('Content-Type: application/json');
        echo json_encode($r ? ['wiek' => microtime(true) - $r['t'], 'm' => $r['m'], 's' => $r['s'], 'q' => $r['q']]
                            : ['wiek' => 99999]);
        break;

    case 'push':
        $jpg = file_get_contents('php://input', false, null, 0, 8 * 1024 * 1024);
        if (!$jpg || substr($jpg, 0, 2) !== "\xFF\xD8") { http_response_code(400); exit; }
        file_put_contents("$dir/frame.tmp", $jpg);
        rename("$dir/frame.tmp", "$dir/frame.jpg");
        zapisz("$dir/meta.json", [
            'h' => substr(preg_replace('/[^a-f0-9]/', '', $_SERVER['HTTP_X_HASH'] ?? ''), 0, 32),
            'mon' => (int)($_SERVER['HTTP_X_MONITORS'] ?? 1),
            'seen' => time(),
            'name' => $komputer,
        ]);
        echo 'ok';
        break;

    default:
        http_response_code(400);
}
