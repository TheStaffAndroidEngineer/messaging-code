"""Local fixture controls; never imported by the product app."""
import argparse
import json
import urllib.request
import urllib.error
import uuid

parser=argparse.ArgumentParser()
parser.add_argument('--port',type=int,default=8915)
sub=parser.add_subparsers(dest='mode',required=True)
sub.add_parser('view')
control=sub.add_parser('control');control.add_argument('json',nargs='?')
command=sub.add_parser('command');command.add_argument('action');command.add_argument('json');command.add_argument('--id')
get=sub.add_parser('get');get.add_argument('path')
args=parser.parse_args()
body=None
if args.mode=='view':path='/view'
elif args.mode=='get':path=args.path
elif args.mode=='control':
    path='/__control'
    if args.json is not None:body=json.loads(args.json)
else:
    path='/commands';command_id=args.id or str(uuid.uuid4())
    body={'id':command_id,'action':args.action,'args':json.loads(args.json)}
    print('COMMAND ID (reuse with exactly the same arguments after uncertainty): '+command_id,flush=True)
request=urllib.request.Request(f'http://127.0.0.1:{args.port}'+path,
    data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
try:
    with urllib.request.urlopen(request,timeout=6) as response:print(json.dumps(json.load(response),indent=2))
except urllib.error.HTTPError as error:
    print(f'HTTP {error.code}: '+error.read().decode());raise SystemExit(1)
except (OSError,ConnectionError) as error:
    print('Outcome not received: '+str(error));raise SystemExit(2)
