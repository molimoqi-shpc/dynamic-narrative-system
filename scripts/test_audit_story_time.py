import unittest
from audit_story_time import inspect

class TimeAuditTest(unittest.TestCase):
    def fixture(self):
        return {'sceneDraft':{'timeProgress':{'elapsedParts':[{'amount':7,'unit':'DAY'}]}},
                'sceneArchiveResult':{'timeProgress':{'elapsedSeconds':21600}},
                'scenePassages':['正文不分析，所有日期词都不改变数值检查'],'startSeconds':100,'endSeconds':21700}
    def test_finds_replaced_archive_and_clock(self):
        result=inspect(self.fixture());self.assertEqual(604800,result['expectedElapsedSeconds']);self.assertEqual(2,len(result['issues']))
    def test_consistent_stored_clock_passes_without_prose_judgment(self):
        p=self.fixture();p['sceneArchiveResult']['timeProgress']['elapsedSeconds']=604800;p['endSeconds']=604900
        self.assertEqual([],inspect(p)['issues'])
    def test_multiple_passages_do_not_compare_round_duration_to_only_last_passage(self):
        p=self.fixture();p['sceneFrame']=1;p['scenePassages'].append('第二段');self.assertEqual(['ARCHIVE_REPLACED_AUTHOR_TIME'],inspect(p)['issues'])
    def test_legacy_seconds_are_not_reinterpreted_as_original_unit_evidence(self):
        p=self.fixture();p['sceneDraft']={'elapsedSeconds':5400,'story':'一个半时辰'}
        self.assertEqual('LEGACY_OR_MISSING_AUTHOR_UNITS',inspect(p)['kind'])
    def test_final_manuscript_is_the_author_source_not_an_archive_guess(self):
        p=self.fixture();p['sceneArchiveResult'].update({'publication':{'story':'最终稿只写了三日','timeProgress':{'elapsedParts':[{'amount':3,'unit':'DAY'}]}},'timeProgress':{'version':'FINAL_PUBLICATION','elapsedSeconds':259200}})
        p['endSeconds']=259300
        result=inspect(p);self.assertEqual([],result['issues']);self.assertEqual('FINAL_PUBLICATION',result['authorVersion']);self.assertEqual(259200,result['expectedElapsedSeconds'])
    def test_plain_archive_estimate_still_cannot_replace_author_units(self):
        p=self.fixture();p['sceneArchiveResult']['publication']={'timeProgress':{'elapsedParts':[{'amount':0.25,'unit':'DAY'}]}}
        self.assertEqual('DRAFT_OR_LEGACY',inspect(p)['authorVersion']);self.assertIn('ARCHIVE_REPLACED_AUTHOR_TIME',inspect(p)['issues'])

if __name__=='__main__':unittest.main()
