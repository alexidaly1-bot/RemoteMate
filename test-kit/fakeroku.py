"""A fake Roku TV for testing without a real TV.

HTTP ECP on 127.0.0.1:8060 (the real port) and an SSDP responder on 127.0.0.1:1901 (unicast, for tests).
The SSDP responder also pretends there's a Samsung TV (DIAL) so the "coming soon" path can be tested.
Every request is printed so the test can check it.  Run:  python3 fakeroku.py [--blocked]
--blocked answers 403, like a Roku with "Control by mobile apps" turned off.
"""
import socket, sys, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

BLOCKED = '--blocked' in sys.argv

DEVICE_INFO = """<?xml version="1.0" encoding="UTF-8" ?>
<device-info>
  <udn>29380000-0000-1000-8000-ac3a7a000001</udn>
  <serial-number>X00000000001</serial-number>
  <vendor-name>TCL</vendor-name>
  <model-name>TCL Roku TV</model-name>
  <friendly-device-name>Living Room Roku</friendly-device-name>
  <user-device-name>Lounge Roku TV</user-device-name>
  <is-tv>true</is-tv>
  <power-mode>PowerOn</power-mode>
</device-info>"""

APPS = """<?xml version="1.0" encoding="UTF-8" ?>
<apps>
  <app id="tvinput.hdmi1" type="tvin" version="1.0.0">HDMI 1</app>
  <app id="12" type="appl" version="5.1.0">Netflix</app>
  <app id="837" type="appl" version="2.21.0">YouTube</app>
  <app id="13" type="appl" version="14.1.0">Prime Video</app>
  <app id="291097" type="appl" version="1.0.0">Disney Plus &amp; more</app>
</apps>"""

ROKU_DESC = """<?xml version="1.0" encoding="UTF-8" ?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
<deviceType>urn:roku-com:device:player:1-0</deviceType><friendlyName>Living Room Roku</friendlyName>
<manufacturer>Roku</manufacturer><modelName>TCL Roku TV</modelName></device></root>"""

SAMSUNG_DESC = """<?xml version="1.0" encoding="UTF-8" ?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
<friendlyName>[TV] Samsung Q60</friendlyName><manufacturer>Samsung Electronics</manufacturer><modelName>QA55Q60</modelName></device></root>"""


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def reply(self, code, body=b'', ctype='text/xml'):
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        print('ROKU GET', self.path, flush=True)
        if BLOCKED and self.path.startswith('/query'):
            return self.reply(403)
        if self.path == '/query/device-info':
            return self.reply(200, DEVICE_INFO.encode())
        if self.path == '/query/apps':
            return self.reply(200, APPS.encode())
        if self.path == '/':
            return self.reply(200, ROKU_DESC.encode())
        if self.path == '/samsung.xml':
            return self.reply(200, SAMSUNG_DESC.encode())
        self.reply(404)

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        if n:
            self.rfile.read(n)
        if BLOCKED:
            print('ROKU POST blocked', self.path, flush=True)
            return self.reply(403)
        p = self.path
        ok = (p.startswith('/keypress/') or p.startswith('/keydown/') or p.startswith('/keyup/')
              or p.startswith('/launch/') or p.startswith('/search/browse?'))
        print('ROKU POST', unquote(p), '' if ok else '(UNKNOWN)', flush=True)
        self.reply(200 if ok else 404)


def ssdp():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(('127.0.0.1', 1901))
    while True:
        data, addr = s.recvfrom(4096)
        msg = data.decode(errors='replace')
        st = ''
        for line in msg.split('\r\n'):
            if line.upper().startswith('ST:'):
                st = line[3:].strip()
        if not msg.startswith('M-SEARCH'):
            continue
        if st == 'roku:ecp':
            r = ('HTTP/1.1 200 OK\r\nCache-Control: max-age=3600\r\nST: roku:ecp\r\n'
                 'LOCATION: http://127.0.0.1:8060/\r\nUSN: uuid:roku:ecp:X00000000001\r\n\r\n')
        elif st == 'urn:dial-multiscreen-org:service:dial:1':
            r = ('HTTP/1.1 200 OK\r\nST: urn:dial-multiscreen-org:service:dial:1\r\n'
                 'LOCATION: http://127.0.0.1:8060/samsung.xml\r\nSERVER: Samsung-Linux/4.1, UPnP/1.0\r\n\r\n')
        else:
            continue
        s.sendto(r.encode(), addr)


threading.Thread(target=ssdp, daemon=True).start()
print('fake Roku ready' + (' (blocked)' if BLOCKED else ''), flush=True)
ThreadingHTTPServer(('127.0.0.1', 8060), H).serve_forever()
