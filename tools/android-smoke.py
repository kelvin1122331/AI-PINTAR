import re, subprocess, time
import xml.etree.ElementTree as ET

def adb(*args):
    return subprocess.check_output(['adb', *args], text=True, timeout=45).strip()

def nodes():
    for _ in range(4):
        dump = adb('shell', 'uiautomator', 'dump', '/sdcard/verify-omni.xml')
        if 'ERROR:' in dump:
            time.sleep(.4)
            continue
        state = adb('exec-out', 'cat', '/sdcard/verify-omni.xml')
        current = list(ET.fromstring(state).iter('node'))
        if any("Pixel Launcher isn't responding" == item.get('text') for item in current):
            close = next((item for item in current if item.get('text') == "Close app"), None)
            if close is not None:
                tap_node(close)
                print('Dismissed a test emulator launcher dialog', flush=True)
                continue
        return current
    raise AssertionError('Android test emulator did not return its UI hierarchy')

def node(label, retries=3):
    for _ in range(retries):
        for item in nodes():
            if any((item.get(key) or '').casefold() == label.casefold() for key in ('text','content-desc')):
                return item
        time.sleep(.3)
    return None

def tap_node(item):
    assert item is not None
    x, y, r, b = map(int, re.findall(r'\d+', item.get('bounds', '')))
    adb('shell', 'input', 'tap', str((x+r)//2), str((y+b)//2))
    time.sleep(.5)

def tap(label):
    item = node(label)
    assert item is not None, f'Missing UI: {label}'
    tap_node(item)

def require(label):
    if node(label) is None:
        labels = [item.get('text') or item.get('content-desc') for item in nodes()
                  if item.get('text') or item.get('content-desc')]
        focus = adb('shell', 'dumpsys', 'activity', 'activities')
        focus_lines = [line.strip() for line in focus.splitlines() if 'ResumedActivity' in line]
        raise AssertionError(f'Missing UI: {label}; visible: {labels}; foreground: {focus_lines}')
    print(f'PASS UI {label}', flush=True)

def scroll():
    size = re.findall(r'(\d+)x(\d+)', adb('shell','wm','size'))[-1]
    w,h = map(int,size)
    adb('shell','input','swipe',str(w//2),str(h*7//10),str(w//2),str(h*4//10),'380')


import struct

PACKAGE = 'id.omni.floatai'
APP = PACKAGE + '/.MainActivity'
TEST = PACKAGE + '.test/id.omni.floatai.EnglishFixture'

def reveal(label):
    target = label.casefold()
    current = []
    for step in range(12):
        current = nodes()
        match = next((item for item in current if any((item.get(key) or '').casefold() == target
                     for key in ('text', 'content-desc'))), None)
        if match is not None:
            return match
        gestured = [item for item in current if item.get('scrollable') == 'true'
                    and 'ScrollView' in (item.get('class') or '')]
        if not gestured:
            gestured = [item for item in current if item.get('scrollable') == 'true']
        if not gestured:
            scroll()
            continue
        bounds = [tuple(map(int, re.findall(r'\d+', item.get('bounds', '')))) for item in gestured]
        left, top, right, bottom = max(bounds, key=lambda box: (box[2]-box[0]) * (box[3]-box[1]))
        x = str(left + max(6, (right-left)//32))
        high, low = int(top + (bottom-top)*.86), int(top + (bottom-top)*.21)
        if step >= 8:
            high, low = low, high
        adb('shell', 'input', 'swipe', x, str(high), x, str(low), '320')
    labels = [item.get('text') or item.get('content-desc') for item in current
              if item.get('text') or item.get('content-desc')]
    raise AssertionError(f'Missing UI: {label}; visible: {labels}')

def open_activity():
    launched = adb('shell', 'am', 'start', '-S', '-W', '-n', APP)
    print('Launch app:', launched, flush=True)
    require('Omni Float AI')

def bubble():
    icon = node('Buka chat Omni Float AI', 1)
    if icon is not None:
        tap_node(icon)
    else:
        density = int(re.findall(r'\d+', adb('shell', 'wm', 'density'))[-1]) / 160
        for y in (153, 177, 195):
            adb('shell', 'input', 'tap', str(round(44 * density)), str(round(y * density)))
            if node('Mode otomatis', 1) is not None:
                break
    require('Mode otomatis')

def screencap():
    errors = []
    for attempt in range(4):
        try:
            if attempt:
                adb('shell', 'screencap', '/data/local/tmp/omni-regression.rgba')
                command = ['adb', 'exec-out', 'cat', '/data/local/tmp/omni-regression.rgba']
            else:
                command = ['adb', 'exec-out', 'screencap']
            raw = subprocess.check_output(command, timeout=18)
            if len(raw) >= 12:
                w, h, pixel_format = struct.unpack_from('<III', raw)
                required = w * h * 4
                if 100 < w < 3000 and 100 < h < 4000 and pixel_format in (1, 2) and len(raw) >= required + 12:
                    return w, h, raw[-required:]
                errors.append((len(raw), required, w, h, pixel_format))
            else:
                errors.append((len(raw),))
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            errors.append(str(error))
        time.sleep(.25)
    raise AssertionError('No complete RGBA frame from the test emulator: ' + repr(errors))

def annotation_pixels():
    w, h, data = screencap()
    count = 0
    for y in range(h // 8, h * 9 // 10, 3):
        for x in range(w // 24, w * 23 // 24, 3):
            i = (y * w + x) * 4
            r, g, b = data[i:i+3]
            if 89 <= r <= 112 and 77 <= g <= 100 and 105 <= b <= 132:
                count += 1
    return count

def wait_overlay(seconds):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        count = annotation_pixels()
        if count > 350:
            return count
        time.sleep(.65)
    print('Projection at capture timeout:', adb('shell', 'dumpsys', 'media_projection'))
    bubble()
    labels = [item.get('text') or item.get('content-desc') for item in nodes()
              if item.get('text') or item.get('content-desc')]
    raise AssertionError('No visible translation annotation on fixture; panel: ' + repr(labels))

def fixture_labels():
    return [item.get('text') for item in nodes() if 'Good morning' in (item.get('text') or '')
        or 'Thank you for reading' in (item.get('text') or '')]

# Use a practical portrait viewport and keep the test device awake.
adb('shell', 'wm', 'size', '800x1800')
adb('shell', 'wm', 'density', '320')
adb('shell', 'svc', 'power', 'stayon', 'true')
adb('shell', 'settings', 'put', 'system', 'screen_off_timeout', '1800000')
adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
adb('shell', 'wm', 'dismiss-keyguard')
# Start the UI test with no model profile or app preferences.
adb('shell', 'pm', 'clear', PACKAGE)
adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.CAMERA')
adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
adb('shell', 'appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow')
def test_screen():
    open_activity()
    tap_node(reveal('Aktifkan floating'))
    adb('shell', 'am', 'start', '-W', '-n', TEST)
    before = fixture_labels()
    assert before, 'English fixture did not open'
    bubble()
    tap('Mode otomatis')
    tap_node(reveal('Aktifkan Terjemah Layar'))
    start_button = None
    for _ in range(7):
        shown = nodes()
        start_button = next((item for item in shown
                             if item.get('package') == 'com.android.systemui'
                             and (item.get('text') or '').casefold() in ('start', 'start now', 'share screen', 'share')), None)
        if start_button is not None:
            break
        time.sleep(.65)
    if start_button is None:
        print('Consent UI:', [(item.get('text'), item.get('content-desc'), item.get('package'))
                             for item in shown if item.get('text') or item.get('content-desc')])
        current = adb('shell', 'dumpsys', 'activity', 'activities')
        print('Foreground:', [line.strip() for line in current.splitlines() if 'ResumedActivity' in line])
        launch = adb('logcat', '-d', '-s', 'ActivityTaskManager:I', 'WindowManager:W')
        print('Launch log:', '\n'.join(line for line in launch.splitlines() if 'capture' in line.lower()
              or 'projection' in line.lower() or 'floatai' in line.lower())[-6000:])
        raise AssertionError('Screen capture confirmation did not appear')
    tap_node(start_button)
    print('PASS UI Android screen capture confirmation', flush=True)
    first = wait_overlay(70)
    for _ in range(9):
        visible = annotation_pixels()
        assert visible >= first * .55, ('Overlay flickered on a stable page', first, visible)
        time.sleep(.3)
    print('PASS visible translation remains stable across consecutive screenshots', flush=True)

    for _ in range(3):
        scroll()
        assert annotation_pixels() > 350, 'Translations vanished during consecutive scroll gestures'
        time.sleep(.25)
    time.sleep(1.2)
    assert fixture_labels() != before, 'Underlying fixture cannot scroll'
    wait_overlay(35)
    print('PASS translation remains visible during repeated scrolling and after the new page settles', flush=True)
    bubble()
    tap_node(reveal('Kunci hasil'))
    wait_overlay(15)
    bubble()
    tap_node(reveal('Buka kunci hasil'))
    bubble()
    tap_node(reveal('Nonaktifkan Terjemah Layar'))
    reveal('Aktifkan Terjemah Layar')
    print('PASS live translation locks, unlocks, and stops from the floating menu', flush=True)
    tap_node(reveal('Hasil layar terakhir'))
    require('Hasil layar terakhir')
    require('Ekspor TXT')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')


def test_settings():
    open_activity()
    tap_node(reveal('Atur terjemah layar'))
    require('Area pembacaan')
    require('Susunan terjemahan')
    require('Kepekatan teks layar')
    tap('Kecepatan pembaruan')
    tap('Stabil')
    require('Stabil')
    reveal('Ukuran teks layar')
    contrast = reveal('Kontras terang')
    initial = contrast.get('checked')
    tap_node(contrast)
    assert reveal('Kontras terang').get('checked') != initial, 'Overlay contrast toggle did not change'
    tap_node(reveal('Kontras terang'))
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')


def test_translation():
    open_activity()
    tap_node(reveal('Terjemahan'))
    require('Bahasa sumber')
    require('Bahasa tujuan')
    tap('Terjemahkan teks')
    require('Masukkan teks terlebih dulu.')
    tap('Pilih foto')
    time.sleep(.9)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    require('Terjemahan')
    tap_node(reveal('Riwayat'))
    tap_node(reveal('Favorit'))
    reveal('Pasangan bahasa')
    reveal('Kamus pribadi')
    reveal('Atur tampilan layar')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')


def test_study():
    open_activity()
    tap_node(reveal('Belajar'))
    require('AI Study Mode')
    tap('Buat')
    require('Isi materi terlebih dulu.')
    reveal('Impor TXT')
    reveal('Ulas kartu hari ini')
    reveal('Impor kartu TSV')
    reveal('Ekspor kartu TSV')
    tap_node(reveal('Statistik belajar'))
    require('Statistik belajar')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')


def test_camera():
    open_activity()
    tap_node(reveal('Kamera'))
    require('Terjemah Kamera')
    tap('Jeda')
    require('Lanjut')
    tap('Lanjut')
    require('Jeda')
    require('Zoom kamera')
    tap('Bekukan gambar')
    require('Lanjutkan kamera')
    tap('Lanjutkan kamera')
    tap('Kamera depan')
    require('Kamera belakang')
    tap('Senter')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')


def test_connection():
    open_activity()
    tap_node(reveal('Monitor koneksi'))
    require('Tes katalog model')
    require('Filter status koneksi')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')


def test_share_text():
    print(adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.ShareTranslation',
              '-a', 'android.intent.action.SEND', '-t', 'text/plain',
              '--es', 'android.intent.extra.TEXT', 'Good_morning_from_share'), flush=True)
    require('Good_morning_from_share')
    require('Teks diterima. Ketuk Terjemahkan teks.')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')


def test_share_image():
    print(adb('shell', 'am', 'start', '-W', '-n', TEST, '--es', 'fixture', 'image'), flush=True)
    deadline = time.monotonic() + 85
    while time.monotonic() < deadline:
        actual = [item.get('text') or '' for item in nodes()]
        if any('selamat pagi' in text.lower() for text in actual):
            print('PASS received image from another app and translated its text', flush=True)
            break
    else:
        raise AssertionError('Shared picture did not translate; visible: ' + repr(actual))

failures = []
for verify in (test_screen, test_settings, test_translation, test_study, test_camera, test_connection,
               test_share_text, test_share_image):
    print("Checking", verify.__name__, flush=True)
    try:
        verify()
    except Exception as error:
        print(f"FAIL {verify.__name__}: {error}", flush=True)
        failures.append(verify.__name__)
    else:
        print(f"PASS {verify.__name__}", flush=True)
if failures:
    raise AssertionError("Failed emulator scenarios: " + ", ".join(failures))
