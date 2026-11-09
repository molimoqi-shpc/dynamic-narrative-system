"""Read-only audit of explicit authored passage quantities across stored operations.
No prose parsing and no model requests. Legacy estimates are reported, never silently rewritten.
"""
import argparse,json
from pathlib import Path
import story_time_repair as repair

def inspect(payload):
    draft=payload.get('sceneDraft',{})
    parts=draft.get('timeProgress',{}).get('elapsedParts')
    final=payload.get('sceneArchiveResult',{})
    publication=final.get('publication',{})
    declared=publication.get('unchanged') is True or isinstance(publication.get('story'),str) and bool(publication['story'].strip())
    final_publication=final.get('timeProgress',{}).get('version')=='FINAL_PUBLICATION' and declared
    if final_publication:parts=publication.get('timeProgress',{}).get('elapsedParts')
    if not parts:return {'kind':'LEGACY_OR_MISSING_AUTHOR_UNITS'}
    try: expected=repair.duration(parts)
    except (ValueError,KeyError,ArithmeticError):return {'kind':'INVALID_OPTIONAL_AUTHOR_UNITS'}
    actual=payload.get('sceneArchiveResult',{}).get('timeProgress',{}).get('elapsedSeconds')
    issues=[]
    if actual is not None and actual!=expected:issues.append('ARCHIVE_REPLACED_AUTHOR_TIME')
    start,end=payload.get('startSeconds'),payload.get('endSeconds')
    single=payload.get('sceneFrame',0)==0 and len(payload.get('scenePassages',[]))==1
    if single and type(start) is int and type(end) is int and end-start!=expected:issues.append('COMMITTED_CLOCK_DIFFERS')
    return {'kind':'EXPLICIT_AUTHOR_UNITS','authorVersion':'FINAL_PUBLICATION' if final_publication else 'DRAFT_OR_LEGACY','expectedElapsedSeconds':expected,'archiveElapsedSeconds':actual,
            'logId':payload.get('logId'),'singlePassage':single,'recordedStart':start,'recordedEnd':end,'issues':issues}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--session');p.add_argument('--out');args=p.parse_args()
    result={'readOnly':True,'modelCostCny':'0','operations':0,'explicitAuthorUnitOperations':0,'legacyOrMissingUnits':0,'issues':[]}
    with repair.connect() as conn:
        with conn.cursor() as c:
            where="status='COMPLETED'";params=()
            if args.session:where+=' AND session_id=%s';params=(args.session,)
            # Sort only small keys; frozen JSON can be megabytes long.
            c.execute('SELECT session_id,branch_epoch,operation_id FROM game_operation WHERE '+where+' ORDER BY session_id,branch_epoch,operation_id',params)
            keys=c.fetchall()
            for key in keys:
                c.execute('SELECT payload FROM game_operation WHERE session_id=%s AND branch_epoch=%s AND operation_id=%s',tuple(key[k] for k in ['session_id','branch_epoch','operation_id']))
                checked=inspect(repair.parsed(c.fetchone()['payload']));result['operations']+=1
                if checked['kind']=='EXPLICIT_AUTHOR_UNITS':
                    result['explicitAuthorUnitOperations']+=1
                    if checked['issues']:result['issues'].append(dict(key,**checked))
                else:result['legacyOrMissingUnits']+=1
    if args.out:Path(args.out).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False))

if __name__=='__main__':main()
