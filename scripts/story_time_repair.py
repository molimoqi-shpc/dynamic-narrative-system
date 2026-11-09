"""Offline, reviewable repair of recorded story clocks. Never parses narrative prose.

python scripts/story_time_repair.py plan corrections.json repair-plan.json
python scripts/story_time_repair.py apply repair-plan.json
python scripts/story_time_repair.py rollback repair-plan.json
Use with the backend stopped. A plan includes the exact before/after rows and their hashes.
"""
import argparse, copy, hashlib, json, os, re
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TABLES = {
    'game_session': ['session_id'], 'game_runtime': ['session_id'],
    'interaction_log': ['log_id'], 'world_book': ['entry_id'],
    'role_knowledge': ['knowledge_id'], 'story_plot_event': ['session_id', 'event_id'],
    'story_foreshadow': ['session_id', 'thread_id'],
    'game_operation': ['session_id', 'branch_epoch', 'operation_id'],
    'session_checkpoint': ['session_id', 'round_no'],
    'session_checkpoint_archive': ['session_id', 'branch_epoch', 'round_no'],
}
JSON_COLUMNS = {'rule_snapshot', 'content', 'event_description', 'record_json', 'player_view', 'payload'}
TIME_KEYS = {'storySeconds', 'storyStartSeconds', 'startSeconds', 'endSeconds',
             'recordedStartSeconds', 'recordedEndSeconds', 'snapshotAtSeconds',
             'changedSeconds', 'updatedSeconds', 'createdSeconds', 'atSeconds'}
UNITS = {'DAY': 86400, 'SHICHEN': 7200, 'HOUR': 3600, 'MINUTE': 60, 'SECOND': 1}
MAX_TIME = 2**63 - 1
ORIGINAL_RECORDS = {'rawResponse', 'unparsedResponse', 'sceneArchiveRawResponse', 'sceneArchiveReplies',
                    'sceneDirectionAttempts', 'modelFailureReplies', 'sceneRoleInputs', 'contexts',
                    'executionContexts', 'sceneDirections'}

def encode(value): return json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)
def canonical(value):
    if isinstance(value, list): return [canonical(v) for v in value]
    if isinstance(value, dict): return {k:canonical(parsed(v) if k in JSON_COLUMNS else v) for k,v in value.items()}
    return value
def digest(value): return hashlib.sha256(encode(canonical(value)).encode()).hexdigest()
def load(path): return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def parsed(value):
    if isinstance(value, str):
        try: return json.loads(value)
        except ValueError: return value
    return value

def duration(parts):
    if not isinstance(parts, list) or not parts: raise ValueError('Explicit duration parts are required')
    total = Decimal(0)
    for part in parts:
        amount = Decimal(str(part['amount']))
        if not amount.is_finite() or amount < 0 or part['unit'] not in UNITS: raise ValueError('Invalid duration')
        total += amount * UNITS[part['unit']]
    seconds = int(total.quantize(Decimal(1), rounding=ROUND_HALF_UP))
    if seconds > MAX_TIME: raise ValueError('Duration overflow')
    return seconds

class Timeline:
    def __init__(self, logs, corrections):
        self.segments = []
        self.by_log = {}
        for row in logs:
            correction = corrections.get(str(row['log_id']))
            if correction is None: continue
            rule = parsed(row['rule_snapshot'])
            start, end = rule.get('storyStartSeconds'), rule.get('storySeconds')
            if type(start) is not int or type(end) is not int or start < 0 or end <= start:
                raise ValueError('Correction requires a nonempty recorded chapter interval')
            if not correction.get('basis', '').strip(): raise ValueError('Original evidence is required')
            if correction.get('expectedStart') != start or correction.get('expectedEnd') != end:
                raise ValueError('Recorded interval differs from reviewed correction')
            segment = (start, end, duration(correction['elapsedParts']), str(row['log_id']), correction)
            self.segments.append(segment); self.by_log[str(row['log_id'])] = segment
        self.segments.sort()
        for previous, current in zip(self.segments, self.segments[1:]):
            if previous[1] > current[0]: raise ValueError('Overlapping corrections')

    def map(self, value):
        if type(value) is not int or value < 0: return value
        shift = 0
        for start, end, seconds, _, _ in self.segments:
            if value < start: break
            if value < end:
                result = start + shift + int((Decimal(value-start)*seconds/(end-start)).quantize(Decimal(1), rounding=ROUND_HALF_UP))
                if result > MAX_TIME: raise ValueError('Clock overflow')
                return result
            shift += seconds-(end-start)
        result = value+shift
        if result > MAX_TIME: raise ValueError('Clock overflow')
        return result

def rewrite(value, timeline):
    if isinstance(value, list): return [rewrite(v, timeline) for v in value]
    if not isinstance(value, dict): return value
    out = {}
    for key, child in value.items():
        if key in ORIGINAL_RECORDS: out[key] = copy.deepcopy(child)
        elif key in TIME_KEYS: out[key] = timeline.map(child)
        elif key in JSON_COLUMNS and isinstance(child, str) and isinstance(parsed(child), (dict, list)):
            out[key] = encode(rewrite(parsed(child), timeline))
        else: out[key] = rewrite(child, timeline)
    # Durations are relative quantities, unlike absolute timestamps.
    for start_key, end_key in [('startSeconds', 'endSeconds'), ('storyStartSeconds', 'storySeconds')]:
        start, end = value.get(start_key), value.get(end_key)
        if type(start) is int and type(end) is int and end >= start and 'elapsedSeconds' in value:
            out['elapsedSeconds'] = timeline.map(end)-timeline.map(start)
    return out

def rewrite_rows(table, rows, timeline):
    out = copy.deepcopy(rows)
    if not timeline.segments: return out
    for row in out:
        for column, value in list(row.items()):
            if column in {'story_seconds', 'updated_seconds'}:
                row[column] = str(timeline.map(int(value))) if isinstance(value, str) else timeline.map(value)
            elif column == 'story_time' and isinstance(value, str) and re.fullmatch(r'故事第\d+秒', value):
                row[column] = '故事第%d秒' % timeline.map(int(value[3:-1]))
            elif column in JSON_COLUMNS:
                decoded = parsed(value)
                if isinstance(decoded, (dict, list)): row[column] = encode(rewrite(decoded, timeline))
        if table == 'interaction_log' and str(row['log_id']) in timeline.by_log:
            _, _, seconds, _, correction = timeline.by_log[str(row['log_id'])]
            rule = parsed(row['rule_snapshot'])
            rule['storyTimeCorrection'] = {'elapsedParts': correction['elapsedParts'], 'basis': correction['basis']}
            row['rule_snapshot'] = encode(rule)
        if table == 'game_operation':
            payload=parsed(row['payload']);segment=timeline.by_log.get(str(payload.get('logId')))
            if segment:
                _, _, seconds, _, correction=segment
                for section in ['sceneArchiveResult', 'sceneDraft']:
                    result=payload.get(section, {})
                    if 'elapsedSeconds' in result: result['elapsedSeconds']=seconds
                    if isinstance(result.get('timeProgress'), dict):
                        time=result['timeProgress'];time.setdefault('reportedElapsedSeconds',time.get('elapsedSeconds'))
                        time.update(elapsedSeconds=seconds,elapsedParts=correction['elapsedParts'],elapsedDays=seconds//86400,elapsedRemainderSeconds=seconds%86400)
                row['payload']=encode(payload)
    return out

def repair_snapshot(snapshot, corrections):
    timeline = Timeline(snapshot.get('interaction_log', []), corrections)
    return {table: rewrite_rows('game_operation' if table=='_execution_v2' else table, rows, timeline) if table in TABLES or table=='_execution_v2' else copy.deepcopy(rows)
            for table, rows in snapshot.items()}

def project(before, corrections):
    out = copy.deepcopy(before)
    live = Timeline(before['interaction_log'], corrections)
    runtimes = before['game_runtime']
    epoch = int(runtimes[0]['branch_epoch']) if runtimes else 0
    branch_maps = {epoch: live}
    # Archived branches have independent clocks, even when they reuse the same rounds.
    for row in sorted(before['session_checkpoint_archive'], key=lambda r: int(r['round_no'])):
        snapshot = parsed(row['payload']); branch_maps[int(row['branch_epoch'])] = Timeline(snapshot.get('interaction_log', []), corrections)
    for table in TABLES:
        if table in {'session_checkpoint', 'session_checkpoint_archive'}:
            for row in out[table]: row['payload'] = encode(repair_snapshot(parsed(row['payload']), corrections))
        elif table == 'game_operation':
            out[table] = []
            for row in before[table]:
                timeline = branch_maps.get(int(row['branch_epoch']), Timeline([], corrections))
                out[table].extend(rewrite_rows(table, [row], timeline))
        else: out[table] = rewrite_rows(table, before[table], live)
    # Advance the revision only when current-branch clocks changed.
    if live.segments:
        for row in out['game_runtime']: row['revision'] = int(row['revision'])+1
    return out

def connect():
    import pymysql
    config=(ROOT/'backend/src/main/resources/application.yml').read_text(encoding='utf-8-sig')
    default=re.search(r'password:\s*\$\{MYSQL_BS_PASSWORD:([^}]+)\}',config)
    return pymysql.connect(host=os.getenv('MYSQL_HOST','localhost'),user=os.getenv('MYSQL_USER','root'),
        password=os.getenv('MYSQL_BS_PASSWORD',default.group(1) if default else ''),database=os.getenv('MYSQL_DATABASE','bs'),
        charset='utf8mb4',cursorclass=pymysql.cursors.DictCursor,autocommit=False)

def read_session(cursor, sid):
    result={}
    for table, keys in TABLES.items():
        cursor.execute('SELECT * FROM `'+table+'` WHERE session_id=%s ORDER BY '+','.join('`'+k+'`' for k in keys),(sid,))
        result[table]=cursor.fetchall()
    if len(result['game_session']) != 1: raise ValueError('Unknown session')
    if any(r.get('active_operation') for r in result['game_runtime']): raise ValueError('Finish or cancel the pending operation first')
    return result

def changes(before, after):
    result=[]
    for table, keys in TABLES.items():
        for old, new in zip(before[table], after[table]):
            columns=[column for column in old if canonical({column:old[column]}) != canonical({column:new[column]})]
            if columns: result.append({'table':table,'key':{k:old[k] for k in keys},'columns':columns})
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=['plan','apply','rollback']);parser.add_argument('input');parser.add_argument('output',nargs='?')
    args=parser.parse_args();doc=load(args.input)
    with connect() as conn:
        with conn.cursor() as cursor:
            if args.mode=='plan':
                if not args.output or Path(args.output).exists(): raise ValueError('Provide a new plan/backup path')
                sid=doc['sessionId'];before=read_session(cursor,sid)
                found={str(r['log_id']) for r in before['interaction_log']}
                for row in before['session_checkpoint_archive']:
                    found.update(str(r['log_id']) for r in parsed(row['payload']).get('interaction_log',[]))
                if not set(doc['corrections']).issubset(found): raise ValueError('Correction log not found in this session/its branches')
                after=project(before,doc['corrections'])
                plan={'version':1,'sessionId':sid,'corrections':doc['corrections'],'before':before,'after':after,
                      'beforeHash':digest(before),'afterHash':digest(after),'changes':changes(before,after)}
                Path(args.output).write_text(encode(plan),encoding='utf-8')
                print(encode({'sessionId':sid,'changedRows':len(plan['changes']),'beforeHash':plan['beforeHash'],'afterHash':plan['afterHash'],'plan':args.output}))
            else:
                sid=doc['sessionId'];cursor.execute('SELECT session_id FROM game_session WHERE session_id=%s FOR UPDATE',(sid,))
                current=read_session(cursor,sid);source,target=('before','after') if args.mode=='apply' else ('after','before')
                if digest(doc[source])!=doc[source+'Hash'] or digest(doc[target])!=doc[target+'Hash']: raise ValueError('Plan/backup checksum mismatch')
                if digest(current)!=doc[source+'Hash']: raise ValueError('Session changed since the plan; nothing written')
                if args.mode=='apply' and digest(project(doc['before'],doc['corrections']))!=doc['afterHash']: raise ValueError('Plan does not match deterministic projection')
                for change in changes(doc[source],doc[target]):
                    table=change['table'];keys=change['key'];columns=change['columns']
                    row=next(r for r in doc[target][table] if all(r[k]==v for k,v in keys.items()))
                    # Keep wall-clock audit times; a clock repair is not a newly played chapter.
                    columns=columns+[c for c in row if c.endswith('_at') and c not in columns]
                    sql='UPDATE `'+table+'` SET '+','.join('`'+c+'`=%s' for c in columns)+' WHERE '+ ' AND '.join('`'+k+'`=%s' for k in keys)
                    cursor.execute(sql,tuple(row[c] for c in columns)+tuple(keys.values()))
                    if cursor.rowcount!=1: raise ValueError('Concurrent row change; rolling back')
                # MySQL may canonicalize JSON whitespace. Compare decoded column values before commit.
                actual=read_session(cursor,sid)
                for table in TABLES:
                    for row, expected in zip(actual[table],doc[target][table]):
                        for key in expected:
                            if key in JSON_COLUMNS:
                                if canonical(parsed(row[key]))!=canonical(parsed(expected[key])): raise ValueError('Post-write JSON mismatch: '+table+'.'+key)
                            elif str(row[key])!=str(expected[key]): raise ValueError('Post-write field mismatch')
                conn.commit();print(encode({'sessionId':sid,'mode':args.mode,'changedRows':len(changes(doc[source],doc[target]))}))
        if args.mode=='plan':conn.rollback()

if __name__=='__main__': main()
