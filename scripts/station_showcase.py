# -*- coding: utf-8 -*-
"""Restore the approved station opening as a real, independent demo-account save.

Seed once; subsequent commands use the normal authenticated game-operation API.
No existing save is replaced. Credentials stay in memory and are never printed.
"""
from pathlib import Path
import argparse,json,os,re,uuid
from urllib.request import Request,urlopen
import pymysql

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'.runtime/station-showcase-20261026'
OUT.mkdir(parents=True,exist_ok=True)
SID='showcase_station_20261026'
PLAYER='station_player_20261026'
LIAN='station_lian_20261026'
SCENE='station_scene_20261026'
PID='p_demo'
BASE='http://127.0.0.1:8090/api'
def encoded(value):return json.dumps(value,ensure_ascii=False,default=str)
def save(name,value):(OUT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
def api(path,body=None,token=None):
 headers={'Content-Type':'application/json; charset=utf-8'}
 if token:headers['Authorization']='Bearer '+token
 with urlopen(Request(BASE+path,data=None if body is None else encoded(body).encode('utf-8'),headers=headers),timeout=35) as reply:return json.load(reply)
def login():return api('/auth/login',{'username':'player_demo','password':'123456'})['token']
def db():
 config=(ROOT/'backend/src/main/resources/application.yml').read_text(encoding='utf-8-sig')
 default=re.search(r'password:\s*\$\{MYSQL_BS_PASSWORD:([^}]+)\}',config).group(1)
 return pymysql.connect(host='localhost',user='root',password=os.environ.get('MYSQL_BS_PASSWORD',default),database='bs',charset='utf8mb4',cursorclass=pymysql.cursors.DictCursor)

OPENING='沈砚在灰港的一间钟表铺里长大。姐姐失踪那年，他第一次离开旧城。三年后，一封没有署名的来信把他带回这座车站。\n\n钟楼停在六点十七分。沈砚穿过空荡的月台时，最后一列列车正在倒着驶回车站。\n\n莉安拦住他，将一张没有日期的车票塞进他手里。票背写着他的名字，笔迹属于三年前失踪的姐姐。\n\n“上车以后，无论听见谁叫你，都别回头。”她说。\n\n广播忽然响了。那是姐姐的声音，正在念下一站的死者名单。第一个名字，是莉安。'
SITUATION='初秋薄暮，灰港车站空荡的月台。沈砚与成年精灵女子莉安相距几步，列车正在倒着驶回站内。钟楼停在六点十七分，这是钟表读数，不能据此认定时间停止。莉安刚把无日期车票交给沈砚，票背是沈砚姓名和姐姐的笔迹。广播中疑似姐姐的声音念出下一站死者名单，第一个名字是莉安；这只是广播内容，莉安仍活着站在眼前，尚未查明广播来源，不能当作确认死亡。沈砚尚未登车。'
WORLD={'title':'长夜渡星','showcase':True,'showcaseTitle':'无人车站','selectedTheme':'WESTERN','version':1,'genre':'西方幻想悬疑','culturalStyle':'WESTERN','era':'古老城邦与早期工业并存','namingBasis':'取长夜、行旅与微光的原创意象，未引用古籍原文。','introduction':'灰港是一座被铁轨、运河和旧城墙连接的城邦。人类与少数精灵在工坊、车站和街巷中生活。机械钟表与有限的电报已经通行，法术与异常现象仍罕见，不能替代普通人的生计和选择。城市里流传关于列车与失踪者的传闻，但每个说法都需要来历。','summary':'西方幻想悬疑世界。灰港是古老城邦与早期工业并存的城市，人类与少数精灵共同生活，列车、机械钟表和有限电报存在，无现代手机网络；超自然现象罕见且有代价，不默认神明真实存在，不以未知法术随意抹除事实。主角沈砚是普通钟表匠，寻访三年前失踪的姐姐。广播和传闻仅代表已听到的内容，不等于世界已经确认的事实。','boundaries':['无现代手机与互联网','人物没有天然全知与读心能力','广播声称死亡不等于已确认死亡'],'questions':['你叫什么，性别、年龄与出身如何？','你最重视什么？','面对困境会怎样选择？']}

def seed():
 with db() as conn:
  with conn.cursor() as q:
   q.execute('SELECT session_id,current_round FROM game_session WHERE session_id=%s',(SID,))
   existing=q.fetchone()
   if existing:print(encoded({'reused':existing,'modelCalls':0}));return
   q.execute("SELECT player_id FROM player_profile WHERE player_id=%s AND username='player_demo'",(PID,))
   if not q.fetchone():raise RuntimeError('Expected local demonstration account was not found')
   q.execute("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status,view_mode,current_round,created_at,updated_at) VALUES(%s,%s,%s,%s,'PLAYING','DREAMER',0,NOW(),NOW())",(SID,PID,PLAYER,SCENE))
   q.execute('INSERT INTO session_world(session_id,definition) VALUES(%s,%s)',(SID,encoded(WORLD)))
   for role,name,items,bases in [(PLAYER,'沈砚',['无日期车票','旧信'],[42,48,35,67,25,51]),(LIAN,'莉安',[],[39,46,32,61,24,57])]:
    q.execute("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui,inventory) VALUES(%s,%s,%s,'MORTAL','警觉',%s,%s,%s,%s,%s,%s,%s)",(role,SID,name,*bases,encoded(items)))
   q.execute("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y,log_count) VALUES(%s,%s,'灰港车站',%s,%s,0,0,0)",(SCENE,SID,encoded([LIAN]),SITUATION+'莉安身穿浅色旅装，长发与尖耳清楚可见，正留意广播。'))
   q.execute('INSERT INTO game_runtime(session_id) VALUES(%s)',(SID,))
   q.execute('INSERT INTO world_clock(session_id,tick) VALUES(%s,0)',(SID,))
   q.execute("INSERT INTO character_creation(session_id,player_id,view_mode,question_1,question_2,question_3,answer_1,answer_2,answer_3,personality_tags,birth_story,status) VALUES(%s,%s,'DREAMER',%s,%s,%s,'沈砚，男，二十五岁，灰港钟表匠。','亲友与承诺。','先观察，再决定。',%s,%s,'COMPLETED')",(SID,PID,*WORLD['questions'],encoded(['谨慎','重情']),OPENING))
   q.execute("INSERT INTO world_book(session_id,round_no,story_time,location_name,event_type,actor_ids,event_description,state_changes,importance,is_secret) VALUES(%s,0,'初秋薄暮','灰港车站','STORY_MILESTONE',%s,%s,'展示故事原始开局',10,0)",(SID,encoded([PLAYER,LIAN]),OPENING))
   def record(role,ref,title,kind,value):
    q.execute("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(%s,%s,%s,%s,%s,%s,'',%s,'灰港车站',0)",(SID,role,ref,title,encoded(value),kind,SCENE))
   record(PLAYER,'player:identity','主角来路','SELF_PROFILE',{'playerName':'沈砚','profile':'沈砚，男，二十五岁，灰港钟表匠。三年前姐姐失踪，今日按一封无署名旧信来到车站。谨慎、重情、识字，懂钟表机械，没有预知或读心能力。尚未知道列车和名单的真相。','narrativeTier':'PLAYER','sixStatsEstablished':True})
   record(LIAN,'station:self','莉安的来路','SELF_PROFILE',{'profile':'莉安，成年女性精灵，浅色旅装，灰港的短途信使。寡言谨慎，重视已经作出的托付，遇到危险先查明来由，不轻易相信未经证实的传闻。此前有人托她把无日期车票交给名为沈砚的钟表匠，她已经完成交付并说出上车以后不要回头的提醒；这段相遇之前她没有和沈砚建立盟约。她只知道本人接票、等候、交付以及自己记得的来路，并不天然知道广播幕后和沈砚姐姐现状。听到自己名字被列入死者名单会不安，仍能自主判断与解释；接下来愿意说什么、做什么由本人角色API根据处境生成。','narrativeTier':'CORE','sixStatsEstablished':True})
   for role in [PLAYER,LIAN]:
    record(role,'station:observed','刚刚亲历的场面','TURN_EVENT',{'text':SITUATION,'startSeconds':0,'endSeconds':0,'status':'COMPLETED','visibility':'PUBLIC'})
    record(role,'station:situation','当前处境','ROLE_STORY_STATE',{'situation':SITUATION,'atSeconds':0})
   record(PLAYER,'appearance:'+LIAN,'莉安的公开外貌','APPEARANCE',{'roleId':LIAN,'gender':'female','ageGroup':'ADULT','culturalStyle':'WESTERN','archetype':'ELF','species':'elf','basis':'初见时亲眼看到成年精灵女子莉安，浅色旅装、长发、尖耳。','recordedAtSeconds':0})
   record(PLAYER,'relation:'+LIAN,'月台相识','PLAYER_RELATION',{'roleId':LIAN,'name':'莉安','type':'ACQUAINTANCE','summary':'刚在月台相识，莉安递来无日期车票并提醒登车后不要回头。','basis':'本人亲历的交付与对话。','recordedAtSeconds':0})
   for suffix,name,origin,description in [('ticket','无日期车票','莉安刚在月台亲手交付。','票背写着沈砚的名字，笔迹属于三年前失踪的姐姐；来源与日期未查明。'),('letter','旧信','沈砚赴站之前收到的无署名来信。','引导沈砚来到灰港车站的线索，未载明幕后真相。')]:
    ref='station_'+suffix+'_20261026'
    record(PLAYER,ref,'线索物品持有','ITEM_STATE',{'ref':ref,'name':name,'ownerId':PLAYER,'holderId':PLAYER,'legalOwnerId':PLAYER,'sceneId':'','state':'HELD','origin':origin,'history':[{'kind':'GENESIS','ownerId':PLAYER,'legalOwnerId':PLAYER}]})
    record(PLAYER,'profile:'+ref,'重要线索说明','ITEM_PROFILE',{'itemRef':ref,'name':name,'kind':'KEY_ITEM','description':description,'origin':origin,'significance':'寻找姐姐与查明名单来源的重要线索。','knownEffects':'目前仅为线索，没有已确认的魔法能力。','recordedAtSeconds':0})
   q.execute('INSERT INTO session_npc_alias(session_id,alias,god_id) VALUES(%s,%s,%s)',(SID,'莉安',LIAN))
  conn.commit()
 save('seed.json',{'sessionId':SID,'playerId':PID,'opening':OPENING,'world':WORLD,'modelCalls':0})
 print(encoded({'created':SID,'playerId':PID,'modelCalls':0}))

def execute(mode,round_no,text):
 token=login()
 if mode=='status':
  sessions=api('/game/sessions?playerId='+PID,token=token)['sessions']
  print(encoded({'first':sessions[0] if sessions else None,'health':api('/engine/health',token=token)}));return
 api('/game/resume',{'sessionId':SID,'playerId':PID},token)
 name=f'round-{round_no}'
 request_file=OUT/(name+'-request.json')
 if mode=='start':
  if request_file.exists():raise RuntimeError('Already submitted. Poll the saved operation instead.')
  scene=api('/game/scene?sessionId='+SID,token=token)
  if scene.get('pendingOperationId'):raise RuntimeError('An earlier operation is unfinished')
  body={'operationId':str(uuid.uuid4()),'sessionId':SID,'sceneId':scene['sceneId'],'playerText':text,'storyMode':True,'branchEpoch':scene['branchEpoch'],'expectedRevision':scene['revision']}
  save(name+'-request.json',body);save(name+'-before.json',scene)
  result=api('/game/operations',body,token)
 else:
  body=json.loads(request_file.read_text(encoding='utf-8'))
  path='/game/operations/'+body['operationId']
  if mode=='recover':path+='/recover'
  path+='?sessionId='+SID+'&branchEpoch='+str(body['branchEpoch'])
  result=api(path,{} if mode=='recover' else None,token=token)
 save(name+'-result.json',result)
 print(encoded({k:result.get(k) for k in ['operationId','status','pendingKind','round','storySeconds','usage']}))
 if result.get('status')!='RUNNING':
  passage='\n\n'.join(line.get('speech','') for line in result.get('lines',[]))
  (OUT/(name+'-story.txt')).write_text(passage,encoding='utf-8')
  after=api('/game/scene?sessionId='+SID,token=token);save(name+'-after.json',after)
  transcript=api('/game/transcript?sessionId='+SID,token=token);save(name+'-transcript.json',transcript)
  with db() as conn:
   with conn.cursor() as q:
    q.execute('SELECT payload FROM game_operation WHERE session_id=%s AND operation_id=%s',(SID,body['operationId']))
    row=q.fetchone()
    if row:save(name+'-private-payload.json',json.loads(row['payload']))
  print(encoded({'storyChars':len(passage),'noPending':not after.get('pendingOperationId'),'sessionStatus':after.get('sessionStatus')}))

def repair_decision_footer():
 """Restore only the author's misplaced metadata, with a reversible row backup."""
 request=json.loads((OUT/'round-3-request.json').read_text(encoding='utf-8'))
 with db() as conn:
  with conn.cursor() as q:
   q.execute('SELECT active_operation FROM game_runtime WHERE session_id=%s FOR UPDATE',(SID,))
   if q.fetchone()['active_operation']:raise RuntimeError('Do not edit metadata during an active operation')
   q.execute('SELECT * FROM game_operation WHERE session_id=%s AND operation_id=%s FOR UPDATE',(SID,request['operationId']))
   operation=q.fetchone()
   if operation['status']!='COMPLETED':raise RuntimeError('Operation is not complete')
   payload=json.loads(operation['payload']);decision=None
   for call in reversed(payload.get('sceneMainCalls',[])):
    draft=call.get('draft',{});text=draft.get('story','');marker='\n\ndecision:'
    if draft.get('stop')!='DECISION' or marker not in text:continue
    try:candidate=json.loads(text.rsplit(marker,1)[1].strip())
    except ValueError:continue
    if candidate.get('question') and candidate.get('type'):decision=candidate;break
   if not decision:raise RuntimeError('No complete authored decision footer was found')
   log_id=payload['logId'];q.execute('SELECT * FROM interaction_log WHERE log_id=%s AND session_id=%s',(log_id,SID));log=q.fetchone()
   q.execute('SELECT * FROM session_checkpoint WHERE session_id=%s AND round_no=3',(SID,));checkpoint=q.fetchone()
   backup=OUT/'decision-metadata-before.json'
   if not backup.exists():save(backup.name,{'operation':operation,'log':log,'checkpoint':checkpoint})
   progress=payload.setdefault('storyStop',{})
   progress.update(reason=decision['question'],decisionType=decision['type'],counterpartId=decision.get('counterpartId',''),stakes=decision.get('stakes',''),requiresInput=True)
   payload['sceneDraft']['decision']=decision
   q.execute('UPDATE game_operation SET payload=%s WHERE session_id=%s AND operation_id=%s',(encoded(payload),SID,request['operationId']))
   snapshot=json.loads(log['rule_snapshot']);snapshot.setdefault('storyProgress',{}).update(progress)
   q.execute('UPDATE interaction_log SET rule_snapshot=%s WHERE log_id=%s AND session_id=%s',(encoded(snapshot),log_id,SID))
   if checkpoint:
    saved=json.loads(checkpoint['payload'])
    for row in saved.get('interaction_log',[]):
     if str(row['log_id'])==str(log_id):row['rule_snapshot']=encoded(snapshot)
    q.execute('UPDATE session_checkpoint SET payload=%s WHERE session_id=%s AND round_no=3',(encoded(saved),SID))
  conn.commit()
 print(encoded({'decisionMetadataRestored':True,'proseChanged':False,'modelCalls':0}))

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('mode',choices=['seed','start','poll','recover','status','repair-decision']);p.add_argument('--round',type=int,default=1);p.add_argument('--text',default='询问莉安为什么在死者名单中。听她解释，自然核对车票与广播，暂不登车。')
 a=p.parse_args()
 if a.mode=='seed':seed()
 elif a.mode=='repair-decision':repair_decision_footer()
 else:execute(a.mode,a.round,a.text)
