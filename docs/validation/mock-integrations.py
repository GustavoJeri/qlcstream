"""Controlled read-only fixture. It never adds or changes torrents."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body.encode())

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        if self.path == '/api/v2/auth/login':
            self.reply(200, 'Ok.' if b'fictional_candidate' in body else 'Fails.')
        else:
            self.reply(405, '')

    def do_GET(self):
        if self.path == '/api/v2/app/version':
            self.reply(200, 'v5.1.4')
        elif self.path == '/api/v2/torrents/info':
            self.reply(200, '[]')
        elif self.path == '/api/v2/app/preferences':
            self.reply(200, json.dumps({'save_path': '/downloads'}))
        elif self.path.startswith('/api/v1/'):
            if self.headers.get('X-Api-Key') != 'fictional_candidate':
                self.reply(401, '')
            else:
                self.reply(200, '{"version":"1.0"}' if self.path.endswith('/status') else '[]')
        elif self.headers.get('Authorization') in ('Bearer fictional_candidate', 'Bearer fictional_environment_token'):
            self.reply(200, '{"images":{},"change_keys":[]}' if self.path == '/configuration' else '{"results":[]}')
        else:
            self.reply(401, '')


ThreadingHTTPServer(('0.0.0.0', 8000), Handler).serve_forever()
