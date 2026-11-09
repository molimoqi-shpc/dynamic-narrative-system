import copy, json, unittest
import story_time_repair as repair

class StoryTimeRepairTest(unittest.TestCase):
    def setUp(self):
        self.logs=[{'log_id':1,'rule_snapshot':json.dumps({'storyStartSeconds':100,'storySeconds':200})},
                   {'log_id':2,'rule_snapshot':json.dumps({'storyStartSeconds':300,'storySeconds':400})}]
        self.corrections={'1':{'expectedStart':100,'expectedEnd':200,'elapsedParts':[{'amount':200,'unit':'SECOND'}],'basis':'原归档明确两百秒'},
                          '2':{'expectedStart':300,'expectedEnd':400,'elapsedParts':[{'amount':50,'unit':'SECOND'}],'basis':'原归档明确五十秒'}}
        self.t=repair.Timeline(self.logs,self.corrections)
    def test_boundaries_gaps_interior_and_cumulative_shift(self):
        self.assertEqual([0,100,200,300,350,400,425,450,550],[self.t.map(t) for t in [0,100,150,200,250,300,350,400,500]])
    def test_units_and_rounding(self):
        self.assertEqual(10800,repair.duration([{'amount':1.5,'unit':'SHICHEN'}]))
        self.assertEqual(1296030,repair.duration([{'amount':15,'unit':'DAY'},{'amount':0.5,'unit':'MINUTE'}]))
    def test_invalid_or_inconsistent_evidence_is_not_applied(self):
        for patch in [{'expectedEnd':201},{'basis':''},{'elapsedParts':[{'amount':-1,'unit':'DAY'}]}]:
            c=copy.deepcopy(self.corrections);c['1'].update(patch)
            with self.assertRaises(ValueError):repair.Timeline(self.logs,c)
    def test_prose_tick_quantities_and_reported_values_remain_exact(self):
        v={'text':'故事第200秒，等了100秒','generatedTick':200,'reportedElapsedSeconds':100,'elapsedSeconds':100,
           'startSeconds':100,'endSeconds':200,'health':50,'count':200,'nested':{'source':{'storySeconds':200}}}
        out=repair.rewrite(v,self.t)
        self.assertEqual(v['text'],out['text']);self.assertEqual(200,out['generatedTick']);self.assertEqual(100,out['reportedElapsedSeconds'])
        self.assertEqual(200,out['elapsedSeconds']);self.assertEqual(300,out['endSeconds']);self.assertEqual(300,out['nested']['source']['storySeconds']);self.assertEqual(50,out['health'])
    def test_unrelated_branch_with_same_clock_values_is_unchanged(self):
        s={'interaction_log':[{'log_id':77,'rule_snapshot':json.dumps({'storySeconds':200})}],
           'game_runtime':[{'story_seconds':'400'}],'world_book':[{'story_time':'故事第200秒','event_description':'旧故事'}]}
        self.assertEqual(s,repair.repair_snapshot(s,self.corrections))
    def test_checkpoints_world_book_plot_and_role_sources_stay_aligned(self):
        s={'interaction_log':self.logs,'game_runtime':[{'story_seconds':'400'}],
           'world_book':[{'story_time':'故事第400秒','event_description':json.dumps({'source':{'storySeconds':400},'text':'原文不变'})}],
           'role_knowledge':[{'content':json.dumps({'endSeconds':400}),'learned_tick':400}],
           'story_plot_event':[{'updated_seconds':'400','player_view':json.dumps({'source':{'storySeconds':400}})}]}
        out=repair.repair_snapshot(s,self.corrections)
        self.assertEqual('450',out['game_runtime'][0]['story_seconds']);self.assertEqual('故事第450秒',out['world_book'][0]['story_time'])
        self.assertEqual(450,json.loads(out['world_book'][0]['event_description'])['source']['storySeconds'])
        self.assertEqual(450,json.loads(out['role_knowledge'][0]['content'])['endSeconds']);self.assertEqual(400,out['role_knowledge'][0]['learned_tick'])
        self.assertEqual('450',out['story_plot_event'][0]['updated_seconds'])
    def test_json_formatting_does_not_break_backup_checksum(self):
        self.assertEqual(repair.digest({'content':'{"endSeconds": 200}'}),repair.digest({'content':'{ "endSeconds" :200 }'}))
    def test_operation_and_embedded_execution_keep_corrected_duration(self):
        row={'operation_id':'a','payload':json.dumps({'logId':1,'endSeconds':200,'sceneArchiveResult':{'timeProgress':{'elapsedSeconds':100}},'sceneDraft':{'elapsedSeconds':100}})}
        out=repair.rewrite_rows('game_operation',[row],self.t)[0];p=json.loads(out['payload'])
        self.assertEqual(300,p['endSeconds']);self.assertEqual(200,p['sceneDraft']['elapsedSeconds'])
        self.assertEqual(100,p['sceneArchiveResult']['timeProgress']['reportedElapsedSeconds'])
        self.assertEqual(200,p['sceneArchiveResult']['timeProgress']['elapsedSeconds'])
    def test_unknown_and_overflow_duration_rejected(self):
        for p in [[{'amount':1,'unit':'MONTH'}],[{'amount':'NaN','unit':'SECOND'}],[{'amount':2**63,'unit':'DAY'}]]:
            with self.assertRaises((ValueError,ArithmeticError)):repair.duration(p)
    def test_original_model_responses_and_role_requests_remain_unchanged(self):
        original={'sceneRoleInputs':{'a':[{'content':'{"endSeconds":200}'}]},
                  'modelFailureReplies':[{'content':'{"endSeconds":200}'}],
                  'sceneDirections':{'a':{'startSeconds':200}},'endSeconds':200}
        updated=repair.rewrite(original,self.t)
        for key in repair.ORIGINAL_RECORDS:
            if key in original:self.assertEqual(original[key],updated[key])
        self.assertEqual(300,updated['endSeconds'])
    def test_archive_branches_use_their_own_log_ids(self):
        before={k:[] for k in repair.TABLES};before['game_runtime']=[{'story_seconds':400,'branch_epoch':2,'revision':3}]
        before['interaction_log']=[{'log_id':77,'rule_snapshot':json.dumps({'storySeconds':400})}]
        before['session_checkpoint_archive']=[{'branch_epoch':0,'round_no':2,'payload':json.dumps({'interaction_log':self.logs,'game_runtime':[{'story_seconds':'400'}]})}]
        before['game_operation']=[{'branch_epoch':0,'payload':json.dumps({'endSeconds':400})},{'branch_epoch':2,'payload':json.dumps({'endSeconds':400})}]
        out=repair.project(before,self.corrections)
        self.assertEqual(before['game_runtime'],out['game_runtime']);self.assertEqual(450,json.loads(out['game_operation'][0]['payload'])['endSeconds'])
        self.assertEqual(400,json.loads(out['game_operation'][1]['payload'])['endSeconds'])

if __name__=='__main__':unittest.main()
