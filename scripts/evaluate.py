#!/usr/bin/env python3
"""Fixed rewrite evaluation with server-managed compression. Requires a running backend; creates isolated sessions.
Demo scores exercise pipeline only. Live mode sends the fixed fixture to configured models.
"""
import argparse,json,time,urllib.request,statistics,pathlib,hashlib,http.cookiejar,uuid,secrets,os
root=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--index',action='store_true',help='Allow indexing an explicitly reused evaluation tenant');p.add_argument('--base',default='http://127.0.0.1:8080');p.add_argument('--output',default=str(root/'eval/latest.json'));a=p.parse_args()
opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def call(path,body=None):
 req=urllib.request.Request(a.base+'/api'+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
 with opener.open(req,timeout=180) as r:return json.load(r)
def turn(sid,text,case,rewrite):
 body={'message':text,'topic':case['topic'],'version':case['version'],'rewrite':rewrite}
 req=urllib.request.Request(a.base+'/api/sessions/'+sid+'/chat',data=json.dumps(body).encode(),headers={'Content-Type':'application/json'})
 result=None;run_id=None
 with opener.open(req,timeout=180) as r:
  name='';lines=[]
  for raw in r:
   line=raw.decode().rstrip('\r\n')
   if line.startswith('event:'):name=line[6:].strip()
   elif line.startswith('data:'):lines.append(line[5:].strip())
   elif not line and lines:
    data=json.loads('\n'.join(lines));lines=[]
    if name=='error':raise RuntimeError(data['message'])
    if name=='done':result=data
    if name=='terminal':run_id=data['id']
 if result is None:raise RuntimeError('SSE ended without done')
 result['usage']=call('/usage?runId='+run_id) if run_id else []
 return result
# A disposable tenant isolates evaluation data. Credentials never appear in outputs.
if os.environ.get('EVAL_TENANT'):
 call('/auth/login',{'tenantSlug':os.environ['EVAL_TENANT'],'username':os.environ['EVAL_USERNAME'],'password':os.environ['EVAL_PASSWORD']})
else:
 call('/auth/register',{'tenantSlug':'eval-'+uuid.uuid4().hex[:20],'tenantName':'评测专用机构','username':'evaluator','password':secrets.token_urlsafe(24)})
health=call('/health')
if health['retrieval']=='qdrant':
 if os.environ.get('EVAL_TENANT') and not a.index:
  raise RuntimeError('Reusing a Qdrant tenant requires --index to ensure the evaluation index matches its knowledge')
 call('/knowledge/index',{})
knowledge_snapshot=call('/knowledge')
provenance={'knowledgeSha256':hashlib.sha256(json.dumps(knowledge_snapshot,ensure_ascii=False,sort_keys=True).encode()).hexdigest(),'casesSha256':hashlib.sha256((root/'eval/cases.json').read_bytes()).hexdigest(),'sourceSha256':{str(p.relative_to(root/'backend/src/main/java')):hashlib.sha256(p.read_bytes()).hexdigest() for p in (root/'backend/src/main/java/com/mindhaven').rglob('*.java')}}
provenance['promptSha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((root/'backend/src/main/resources/prompts').glob('*.txt'))}
health=call('/health');compression=health['contextConfig']['compressionEnabled'];cases=json.loads((root/'eval/cases.json').read_text());rows=[]
for rewrite in [False,True]:
 for case in cases:
  sid=call('/sessions',{})['id']
  for message in case['history']:turn(sid,message,case,rewrite)
  result=turn(sid,case['question'],case,rewrite);retrieved=result['metrics']['retrievedIds'];expected=set(case['relevant'])
  rows.append({'case':case['id'],'category':case['category'],'rewrite':rewrite,'compression':compression,'recallAt4':None if not expected else len(expected.intersection(retrieved))/len(expected),'emptyWhenExpected':not retrieved if not expected else None,'retrieved':retrieved,'answer':result['message']['content'],'metrics':result['metrics'],'stageUsage':result['usage'],'summary':call('/sessions/'+sid+'/summary'),'requiredFacts':case['facts'],'factRetentionReview':None,'citationSupportReview':None})
  print(case['id'],rewrite,compression,rows[-1]['recallAt4'],flush=True)
summary=[]
for rewrite in [False,True]:
 subset=[r for r in rows if r['rewrite']==rewrite and r['compression']==compression];eligible=[r['recallAt4'] for r in subset if r['recallAt4'] is not None]
 summary.append({'rewrite':rewrite,'compression':compression,'macroRecallAt4':statistics.mean(eligible),'recallCaseCount':len(eligible),'emptyKnowledgeCaseCount':len(subset)-len(eligible),'meanFirstTokenMs':statistics.mean(r['metrics']['firstTokenMs'] for r in subset),'meanContextEstimate':statistics.mean(r['metrics']['contextEstimate'] for r in subset),'citationMissingCount':sum(r['metrics'].get('citationCheck',{}).get('status')=='MISSING' for r in subset),'citationInvalidCount':sum(r['metrics'].get('citationCheck',{}).get('status')=='INVALID' for r in subset)})
out={'mode':health,'provenance':provenance,'disclaimer':'Demo answers are deterministic, NOT model quality evidence. Retrieval mode/configuration are recorded per turn; BM25 and RRF scores are not confidence values. metrics contains final-answer usage; stageUsage records each observed model stage separately. UTF-8 estimate is not actual model token count. Human review fields are intentionally unset.','knowledgeVersion':'v1 (v99 for negative version case)','promptVersion':'content-sha256 (see provenance.promptSha256)','summary':summary,'rows':rows}
pathlib.Path(a.output).write_text(json.dumps(out,ensure_ascii=False,indent=2));print('Saved:',a.output)
