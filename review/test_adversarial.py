"""Independent Threadline protocol review: ephemeral HTTP and temporary SQLite only.

Run this file in its own Python process; sibling fixtures also use module names base/service.
"""
import concurrent.futures
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
from base import Problem, handler
from service import Service


class Gate:
    def __init__(self, delegate, identity):
        self.delegate, self.identity = delegate, identity
        self.entered, self.release = threading.Event(), threading.Event()

    def route(self, method, path, body):
        if path == '/commands' and body.get('id') == self.identity:
            self.entered.set()
            if not self.release.wait(8): raise RuntimeError('Review gate not released')
        return self.delegate.route(method, path, body)


class ThreadlineReview(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='threadline-review-')
        self.addCleanup(self.temp.cleanup)
        self.database = Path(self.temp.name) / 'threadline.sqlite'
        self.a = Service(self.database, clock=lambda: 1_000_000)
        self.b = Service(self.database, clock=lambda: 1_000_000)
        self.servers = []
        self.addCleanup(self.stop)

    def stop(self):
        for server, thread in self.servers:
            server.shutdown(); server.server_close(); thread.join(2)

    def serve(self, owner):
        server = ThreadingHTTPServer(('127.0.0.1', 0), handler(owner))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start(); self.servers.append((server, thread))
        return f'http://127.0.0.1:{server.server_port}'

    def envelope(self, action, args, identity=None):
        return {'id': identity or str(uuid.uuid4()), 'action': action, 'args': args}

    def command(self, action, args, identity=None, owner=None):
        return (owner or self.a).route('POST', '/commands', self.envelope(action,args,identity))[1]

    def send(self, message_id, text='One logical message', epoch=1, owner=None):
        return self.command('send', {'epoch':epoch,'messageId':message_id,'text':text}, owner=owner)

    def sync(self, cursor, owner=None):
        return (owner or self.a).route('GET',f'/sync?after={cursor}',{})[1]['data']

    def http(self, base, path, body=None):
        req = Request(base+path, data=None if body is None else json.dumps(body).encode(),
                      headers={'Content-Type':'application/json'})
        with urlopen(req,timeout=4) as response: return response.status,json.load(response)

    def snapshot(self, owner=None):
        data = self.sync(2**53-1,owner)
        self.assertTrue(data['reset'])
        return data

    def test_same_logical_message_concurrent_commands_admit_one_ordered_message(self):
        barrier = threading.Barrier(2)
        def work(owner):
            barrier.wait(); return self.send('parallel',owner=owner)['outcome']
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            outcomes = list(pool.map(work,(self.a,self.b)))
        self.assertEqual([3,3],[outcome['sequence'] for outcome in outcomes])
        data = self.snapshot()
        self.assertEqual(3,data['cursor'])
        self.assertEqual(1,sum(m['id']=='parallel' for m in data['messages']))

    def test_conflicting_text_author_or_command_payload_cannot_reuse_identity(self):
        self.send('immutable','Original')
        before = self.snapshot()
        with self.assertRaises(Problem): self.send('immutable','Replacement')
        with self.assertRaises(Problem): self.command('incoming',{'epoch':1,'messageId':'immutable','text':'Original'})
        identity = str(uuid.uuid4())
        self.command('delete',{'epoch':1,'messageId':'immutable'},identity)
        with self.assertRaises(Problem): self.command('leave',{'epoch':1},identity)
        self.assertEqual(before['cursor']+1,self.snapshot()['cursor'])

    def test_delete_tombstone_survives_old_send_receipt_and_new_command_replay(self):
        command = self.envelope('send',{'epoch':1,'messageId':'gone','text':'Original'})
        receipt = self.a.route('POST','/commands',command)[1]
        self.command('delete',{'epoch':1,'messageId':'gone'})
        reopened = Service(self.database)
        self.assertEqual(receipt,reopened.route('POST','/commands',command)[1])
        self.assertEqual(3,self.send('gone','Original',owner=reopened)['outcome']['sequence'])
        data = self.snapshot(reopened)
        self.assertEqual(4,data['cursor'])
        self.assertNotIn('gone',[m['id'] for m in data['messages']])
        events = self.sync(2,reopened)['events']
        self.assertEqual(['upsert','delete'],[event['kind'] for event in events])
        self.assertTrue(all(event['message']['deleted'] for event in events))

    def test_gated_old_membership_send_is_rejected_after_leave_and_rejoin(self):
        command = self.envelope('send',{'epoch':1,'messageId':'late','text':'Old queued text'})
        gate = Gate(self.a,command['id']); base = self.serve(gate)
        with concurrent.futures.ThreadPoolExecutor(1) as pool:
            pending = pool.submit(self.http,base,'/commands',command)
            try:
                self.assertTrue(gate.entered.wait(2))
                self.command('leave',{'epoch':1},owner=self.b)
                self.command('rejoin',{'epoch':2},owner=self.b)
            finally: gate.release.set()
            receipt = pending.result(4)[1]
        self.assertFalse(receipt['outcome']['accepted'])
        reopened = Service(self.database)
        self.assertEqual(receipt,reopened.route('POST','/commands',command)[1])
        self.assertEqual(3,self.snapshot()['epoch'])
        self.assertNotIn('late',[m['id'] for m in self.snapshot()['messages']])

    def test_inactive_sync_clears_view_and_membership_epoch_requires_snapshot_on_rejoin(self):
        self.send('existing')
        cursor = self.snapshot()['cursor']
        self.command('leave',{'epoch':1})
        inactive = self.sync(cursor)
        self.assertEqual((False,True,[],[],False),tuple(inactive[k] for k in ('active','reset','messages','events','more')))
        self.assertEqual(2,inactive['epoch'])
        self.command('rejoin',{'epoch':2})
        delta = self.sync(cursor)
        self.assertEqual(3,delta['epoch'])
        self.assertEqual([],delta['events'])
        # An unchanged cursor is insufficient after a membership change: the client must request reset.
        self.assertIn('existing',[m['id'] for m in self.snapshot()['messages']])

    def test_four_event_http_pages_merge_ordered_complete_history(self):
        for i in range(6): self.send(f'm-{i}')
        base = self.serve(self.a); cursor = 0; cache = {}; pages = []
        while True:
            data = self.http(base,f'/sync?after={cursor}')[1]['data']
            self.assertFalse(data['reset'])
            self.assertLessEqual(len(data['events']),4)
            for event in data['events']:
                self.assertGreater(event['event'],cursor)
                cache[event['messageId']] = event['message']
                cursor = event['event']
            self.assertEqual(cursor,data['cursor'])
            pages.append(cursor)
            if not data['more']: break
        self.assertEqual([4,8],pages)
        self.assertEqual(list(range(1,9)),sorted(m['sequence'] for m in cache.values()))

    def test_retention_floor_exact_boundary_and_future_cursor_reset(self):
        for i in range(7): self.send(f'm-{i}')
        self.assertTrue(self.sync(0)['reset'])
        at_floor = self.sync(1)
        self.assertFalse(at_floor['reset'])
        self.assertEqual([2,3,4,5],[event['event'] for event in at_floor['events']])
        current = self.sync(9)
        self.assertFalse(current['reset']); self.assertEqual([],current['events'])
        self.assertTrue(self.sync(10)['reset'])
        self.assertEqual(9,self.sync(10)['cursor'])

    def test_retention_reset_excludes_deleted_messages_and_returns_matching_cursor(self):
        self.send('deleted')
        self.command('delete',{'epoch':1,'messageId':'deleted'})
        for i in range(10): self.send(f'new-{i}')
        data = self.sync(0)
        self.assertTrue(data['reset']); self.assertFalse(data['more'])
        self.assertNotIn('deleted',[m['id'] for m in data['messages']])
        self.assertEqual(14,data['cursor'])
        self.assertTrue(all(m['sequence']<=data['cursor'] and not m['deleted'] for m in data['messages']))
        self.assertEqual([],self.sync(data['cursor'])['events'])

    def test_lost_send_reply_reconstruction_and_retry_create_one_message(self):
        base = self.serve(self.a)
        command = self.envelope('send',{'epoch':1,'messageId':'lost','text':'Please retain exactly once'})
        self.http(base,'/__control',{'drop_reply':True})
        with self.assertRaises(OSError): self.http(base,'/commands',command)
        self.http(base,'/__control',{})
        reopened = Service(self.database)
        receipt = reopened.route('POST','/commands',command)[1]
        self.assertTrue(receipt['outcome']['accepted'])
        self.assertEqual(3,receipt['outcome']['sequence'])
        self.assertEqual(1,sum(m['id']=='lost' for m in self.snapshot(reopened)['messages']))

    def test_deletion_ownership_and_duplicate_deletes_do_not_add_events(self):
        self.assertFalse(self.command('delete',{'epoch':1,'messageId':'welcome-1'})['outcome']['accepted'])
        self.assertFalse(self.command('delete',{'epoch':1,'messageId':'absent'})['outcome']['accepted'])
        self.command('delete',{'epoch':1,'messageId':'welcome-2'})
        self.command('delete',{'epoch':1,'messageId':'welcome-2'})
        self.assertEqual(3,self.snapshot()['cursor'])

    def test_history_bound_retains_tombstone_identity_instead_of_reusing_capacity(self):
        for i in range(98): self.send(f'bounded-{i}')
        self.command('delete',{'epoch':1,'messageId':'bounded-0'})
        rejected = self.send('overflow')['outcome']
        self.assertFalse(rejected['accepted'])
        self.assertTrue(self.send('bounded-0')['outcome']['accepted'])
        self.assertNotIn('bounded-0',[m['id'] for m in self.snapshot()['messages']])
        self.assertEqual(101,self.snapshot()['cursor'])

    def test_bad_cursors_epochs_and_schema_are_rejected_without_advancing_order(self):
        for epoch in (True,1.0,'1',0,-1):
            with self.subTest(epoch=epoch):
                with self.assertRaises(Problem): self.send('invalid',epoch=epoch)
        base = self.serve(self.a)
        for query in ('','after=-1','after=1.0','after=1&after=2','after=0&extra=1','after=9007199254740992','after=%C2%B2'):
            with self.subTest(query=query):
                with self.assertRaises(HTTPError) as error: self.http(base,'/sync?'+query)
                self.assertEqual(400,error.exception.code)
        self.assertEqual(2,self.snapshot()['cursor'])


if __name__ == '__main__': unittest.main()
