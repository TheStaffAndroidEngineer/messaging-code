"""Copied into each standalone companion. SQLite owns command admission and state."""
import argparse
import hashlib
import json
import math
import sqlite3
import time
import uuid
from contextlib import closing, contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Problem(Exception):
    def __init__(self, message, status=400):
        self.status = status
        super().__init__(message)


def require(condition, message):
    if not condition:
        raise Problem(message)


def integer(value, low=0, high=2**53-1):
    require(type(value) is int and low <= value <= high, 'Integer outside contract')
    return value


def fields(value, names):
    require(isinstance(value, dict) and set(value) == set(names), 'Unexpected fields')


def text(value, limit=500):
    require(isinstance(value, str) and 0 < len(value) <= limit, 'Text outside contract')
    return value


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)


class BaseService:
    KIND = ''
    def __init__(self, database, clock=None):
        self.database = str(database)
        self.clock = clock or (lambda: int(time.time() * 1000))
        with self.transaction() as db:
            db.execute('CREATE TABLE IF NOT EXISTS state (id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS commands (id TEXT PRIMARY KEY, payload TEXT NOT NULL, result TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS controls (id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL)')
            db.execute('INSERT OR IGNORE INTO state VALUES (1,?)', (canonical(self.seed()),))
            db.execute('INSERT OR IGNORE INTO controls VALUES (1,?)', ('{}',))

    @contextmanager
    def transaction(self):
        with closing(sqlite3.connect(self.database, timeout=10, isolation_level=None)) as db:
            db.execute('BEGIN IMMEDIATE')
            try:
                yield db
                db.commit()
            except BaseException:
                db.rollback()
                raise

    def controls(self, db):
        return json.loads(db.execute('SELECT value FROM controls WHERE id=1').fetchone()[0])

    def now(self, controls):
        return self.clock() + controls.get('advance_ms', 0)

    def route(self, method, path, body):
        with self.transaction() as db:
            controls = self.controls(db)
            if path == '/__control':
                if method == 'POST':
                    require(isinstance(body, dict) and set(body) <= {'advance_ms','drop_reply','unavailable'}, 'Unknown control')
                    if 'advance_ms' in body: integer(body['advance_ms'], 0, 86_400_000)
                    for key in ['drop_reply','unavailable']:
                        if key in body: require(type(body[key]) is bool, 'Control must be boolean')
                    db.execute('UPDATE controls SET value=? WHERE id=1', (canonical(body),))
                    controls = body
                elif method != 'GET': raise Problem('Method not supported',405)
                state = json.loads(db.execute('SELECT value FROM state WHERE id=1').fetchone()[0])
                return 200, {'controls':controls,'state':state,'commandCount':db.execute('SELECT COUNT(*) FROM commands').fetchone()[0]}, False
            if controls.get('unavailable'):
                raise Problem('Fixture unavailable',503)
            state = json.loads(db.execute('SELECT value FROM state WHERE id=1').fetchone()[0])
            now = self.now(controls)
            if method == 'GET' and path == '/view':
                return 200, {'kind':self.KIND,'observedAt':now,'data':self.view(state,now)}, False
            if method != 'POST' or path != '/commands':
                raise Problem('No such route',404)
            fields(body, {'id','action','args'})
            try: require(str(uuid.UUID(body['id'])) == body['id'], 'Canonical UUID required')
            except (ValueError, TypeError, AttributeError): raise Problem('Canonical UUID required')
            text(body['action'], 40)
            require(isinstance(body['args'], dict), 'args must be an object')
            payload = canonical({'action':body['action'],'args':body['args']})
            previous = db.execute('SELECT payload,result FROM commands WHERE id=?',(body['id'],)).fetchone()
            if previous:
                require(previous[0] == payload, 'Command identity reused for different intent')
                return 200, json.loads(previous[1]), bool(controls.get('drop_reply'))
            # Rejections returned by apply are durable outcomes too. Exceptions roll back.
            outcome = self.apply(state,body['action'],body['args'],now)
            result = {'kind':self.KIND,'commandId':body['id'],'outcome':outcome,'observedAt':now,'data':self.view(state,now)}
            db.execute('UPDATE state SET value=? WHERE id=1',(canonical(state),))
            db.execute('INSERT INTO commands VALUES (?,?,?)',(body['id'],payload,canonical(result)))
            return 200,result,bool(controls.get('drop_reply'))


def handler(service):
    class Handler(BaseHTTPRequestHandler):
        protocol_version='HTTP/1.1'
        def log_message(self,*args): pass
        def do_GET(self): self.serve('GET')
        def do_POST(self): self.serve('POST')
        def serve(self,method):
            try:
                length=int(self.headers.get('Content-Length','0'))
                require(0<=length<=65536,'Request too large')
                raw=self.rfile.read(length)
                body=json.loads(raw,parse_constant=lambda _: (_ for _ in ()).throw(ValueError())) if raw else {}
                status,result,drop=service.route(method,self.path,body)
                if drop:
                    self.close_connection=True
                    return
            except Problem as error:
                status,result=error.status,{'error':str(error)}
            except (ValueError, UnicodeError):
                status,result=400,{'error':'Invalid JSON or length'}
            except Exception:
                import traceback
                traceback.print_exc()
                status,result=500,{'error':'Internal fixture error'}
            data=canonical(result).encode()
            self.send_response(status)
            self.send_header('Content-Type','application/json')
            self.send_header('Content-Length',str(len(data)))
            self.send_header('Connection','close')
            self.end_headers()
            try: self.wfile.write(data)
            except (BrokenPipeError,ConnectionResetError): pass
            self.close_connection=True
    return Handler


def run(service_type, default_port):
    parser=argparse.ArgumentParser()
    parser.add_argument('--database',default='runtime/state.sqlite')
    parser.add_argument('--port',type=int,default=default_port)
    args=parser.parse_args()
    from pathlib import Path
    Path(args.database).parent.mkdir(parents=True,exist_ok=True)
    service=service_type(args.database)
    server=ThreadingHTTPServer(('127.0.0.1',args.port),handler(service))
    print(f'{service.KIND} listening on127.0.0.1:{args.port}; simulator data in{args.database}',flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()
