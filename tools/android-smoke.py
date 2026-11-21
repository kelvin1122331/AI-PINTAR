import re, subprocess, time
import xml.etree.ElementTree as ET

def adb(*args):
    return subprocess.check_output(['adb', *args], text=True, timeout=45).strip()

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/verify-omni.xml')
    return list(ET.fromstring(adb('exec-out', 'cat', '/sdcard/verify-omni.xml')).iter('node'))

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
    for _ in range(8):
        item = node(label, 1)
        if item is not None:
            return item
        view = next((n for n in nodes() if n.get('scrollable') == 'true'), None)
        if view is None:
            scroll()
        else:
            left, top, right, bottom = map(int, re.findall(r'\d+', view.get('bounds', '')))
            x = str((left + right) // 2)
            adb('shell', 'input', 'swipe', x, str(bottom - 40), x, str(top + 30), '370')
    raise AssertionError('Missing UI: ' + label)

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

def annotation_pixels():
    raw = subprocess.check_output(['adb', 'exec-out', 'screencap'], timeout=20)
    w, h, pixel_format = struct.unpack_from('<III', raw)
    assert 100 < w < 3000 and 100 < h < 4000 and pixel_format in (1, 2), (w, h, pixel_format)
    data = raw[-w*h*4:]
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
    raise AssertionError('No visible translation annotation on fixture')

def fixture_labels():
    return [item.get('text') for item in nodes() if 'Good morning' in (item.get('text') or '')
        or 'Thank you for reading' in (item.get('text') or '')]

# Keep the emulator awake during the full workflow, including the model download.
adb('shell', 'svc', 'power', 'stayon', 'true')
adb('shell', 'settings', 'put', 'system', 'screen_off_timeout', '1800000')
adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
adb('shell', 'wm', 'dismiss-keyguard')
# Start the UI test with no model profile or app preferences.
adb('shell', 'pm', 'clear', PACKAGE)
adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.CAMERA')
adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
adb('shell', 'appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow')
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
reveal('Kontras terang')
adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')

tap_node(reveal('Belajar'))
require('AI Study Mode')
tap('Buat')
require('Isi materi terlebih dulu.')
reveal('Impor TXT')
reveal('Ulas kartu hari ini')
adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
tap_node(reveal('Kamera'))
require('Terjemah Kamera')
tap('Jeda')
require('Lanjut')
tap('Lanjut')
require('Jeda')
tap('Senter')
adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
open_activity()
tap_node(reveal('Aktifkan floating'))
adb('shell', 'am', 'start', '-W', '-n', TEST)
before = fixture_labels()
assert before, 'English fixture did not open'
bubble()
tap('Mode otomatis')
tap_node(reveal('Aktifkan Terjemah Layar'))
start_button = node('Start', 1) or node('Start now', 1)
assert start_button is not None, 'Screen capture confirmation did not appear'
tap_node(start_button)
first = wait_overlay(70)
for _ in range(9):
    visible = annotation_pixels()
    assert visible >= first * .55, ('Overlay flickered on a stable page', first, visible)
    time.sleep(.3)
print('PASS visible translation remains stable across consecutive screenshots', flush=True)

scroll()
time.sleep(1.2)
assert fixture_labels() != before, 'Underlying fixture cannot scroll'
wait_overlay(35)
print('PASS underlying page scrolls while translation is displayed', flush=True)
bubble()
tap_node(reveal('Kunci hasil'))
wait_overlay(15)
bubble()
tap_node(reveal('Buka kunci hasil'))
bubble()
tap_node(reveal('Nonaktifkan Terjemah Layar'))
reveal('Aktifkan Terjemah Layar')
print('PASS live translation locks, unlocks, and stops from the floating menu', flush=True)
