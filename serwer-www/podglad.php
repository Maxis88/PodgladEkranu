<?php
/*
 * Podgląd Ekranu – pośrednik internetowy.
 * Wgraj ten plik na dowolny hosting z PHP (np. https://3dartgarage.pl/podglad.php).
 * Nic nie trzeba konfigurować: hasło ustawione w programie na Windows tworzy własny, oddzielny kanał.
 * Klatki trzymane są w folderze podglad_dane/ (zablokowanym dla przeglądarek), nadpisywane na bieżąco.
 */
header('Cache-Control: no-store');
header('Access-Control-Expose-Headers: X-Hash, X-Monitors');

$key = $_SERVER['HTTP_X_KEY'] ?? '';
$a   = $_GET['a'] ?? '';
if (strlen($key) < 4) { http_response_code(401); exit; }

$base = __DIR__ . '/podglad_dane';
if (!is_dir($base)) {
    @mkdir($base, 0700, true);
    @file_put_contents("$base/.htaccess", "Require all denied\nDeny from all\n");
    @file_put_contents("$base/index.html", "");
}
$dir = $base . '/' . hash('sha256', 'podglad|' . $key);

// Kanał tworzy tylko program Windows (poll/push). Telefon ze złym hasłem dostaje 401.
if (!is_dir($dir)) {
    if ($a !== 'poll' && $a !== 'push') { usleep(1500000); http_response_code(401); exit; }
    @mkdir($dir, 0700, true);
}

function czytaj($f) { $s = @file_get_contents($f); return $s ? json_decode($s, true) : null; }
function zapisz($f, $d) { file_put_contents("$f.tmp", json_encode($d)); rename("$f.tmp", $f); }

switch ($a) {
    case 'ping':   // telefon: czy komputer jest online
        $meta = czytaj("$dir/meta.json");
        $online = $meta && (time() - ($meta['seen'] ?? 0)) < 30;
        if (!$online) { http_response_code(503); echo 'Komputer offline'; exit; }
        echo $meta['name'] ?? 'Komputer';
        break;

    case 'shot':   // telefon: zgłasza chęć oglądania + pobiera najnowszą klatkę
        zapisz("$dir/req.json", [
            't' => microtime(true),
            'm' => (int)($_GET['m'] ?? 1),
            's' => (float)($_GET['s'] ?? 1),
            'q' => (int)($_GET['q'] ?? 80),
        ]);
        $meta = czytaj("$dir/meta.json");
        if ($meta) header('X-Monitors: ' . (int)($meta['mon'] ?? 1));
        if (!$meta || !is_file("$dir/frame.jpg") || ($meta['h'] ?? '') === ($_GET['h'] ?? '')) {
            if ($meta) header('X-Hash: ' . $meta['h']);
            http_response_code(204);
            exit;
        }
        header('X-Hash: ' . $meta['h']);
        header('Content-Type: image/jpeg');
        header('Content-Length: ' . filesize("$dir/frame.jpg"));
        readfile("$dir/frame.jpg");
        break;

    case 'poll':   // Windows: czy ktoś ogląda i z jakimi ustawieniami
        $meta = czytaj("$dir/meta.json") ?: ['h' => ''];
        $meta['seen'] = time();
        $meta['name'] = substr($_SERVER['HTTP_X_NAME'] ?? 'Komputer', 0, 60);
        zapisz("$dir/meta.json", $meta);
        $r = czytaj("$dir/req.json");
        header('Content-Type: application/json');
        echo json_encode($r ? ['wiek' => microtime(true) - $r['t'], 'm' => $r['m'], 's' => $r['s'], 'q' => $r['q']]
                            : ['wiek' => 99999]);
        break;

    case 'push':   // Windows: wysyła nową klatkę
        $jpg = file_get_contents('php://input', false, null, 0, 8 * 1024 * 1024);
        if (!$jpg || substr($jpg, 0, 2) !== "\xFF\xD8") { http_response_code(400); exit; }
        file_put_contents("$dir/frame.tmp", $jpg);
        rename("$dir/frame.tmp", "$dir/frame.jpg");
        zapisz("$dir/meta.json", [
            'h' => substr(preg_replace('/[^a-f0-9]/', '', $_SERVER['HTTP_X_HASH'] ?? ''), 0, 32),
            'mon' => (int)($_SERVER['HTTP_X_MONITORS'] ?? 1),
            'seen' => time(),
            'name' => substr($_SERVER['HTTP_X_NAME'] ?? 'Komputer', 0, 60),
        ]);
        echo 'ok';
        break;

    default:
        http_response_code(400);
}
