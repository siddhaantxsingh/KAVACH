"""Regression checks for pilot evidence accounting; stdlib unittest, no device needed."""
import unittest
from bench.pilot20_report import score, call_start_ms, _median, fnum

def row(**kw):
    return dict(call_id='T01',label='scam',status='ok',end_state='stop',date='2026-11-21',start_time_ist='14:00:00',**kw)

def log(r, first=None, offset=0):
    return dict(file='guardian_test.csv',start_wall_ms=call_start_ms(r)+offset*1000,
                first=first if first is not None else {'stop':5},end_s=60,replaced=0,mic_blocked=0)

class PilotTests(unittest.TestCase):
    def test_blank_rows_are_pending(self):
        r=row();r.update(status='',end_state='')
        x=score([r],{},[]);self.assertEqual(x['n_attempted'],0);self.assertEqual(x['scam_stop']['rate'],None)
    def test_ok_requires_end_state(self):
        r=row();r['end_state']=''
        with self.assertRaises(ValueError): score([r],{},[])
    def test_missing_annotation_not_success(self):
        r=row();x=score([r],{},[log(r)])
        self.assertIsNone(x['calls'][0]['warned_before_request']);self.assertFalse(x['timing_available'])
        self.assertEqual(x['scam_early_warning_confirmed_over_attempted']['k'],0)
    def test_request_after_warning(self):
        r=row();x=score([r],{'T01':{'first_request_s':'10'}},[log(r)])
        self.assertTrue(x['calls'][0]['warned_before_request'])
    def test_session_offset_applied(self):
        r=row();x=score([r],{'T01':{'first_request_s':'10'}},[log(r,offset=8)])
        self.assertFalse(x['calls'][0]['warned_before_request']);self.assertEqual(x['calls'][0]['first_stop_s'],13)
    def test_no_warning_is_timed_miss(self):
        r=row();r['end_state']='none';x=score([r],{'T01':{'first_request_s':'10'}},[log(r,{})])
        self.assertTrue(x['timing_available']);self.assertFalse(x['calls'][0]['warned_before_request'])
    def test_incomplete_log_not_timed(self):
        r=row();l=log(r);del l['end_s'];x=score([r],{'T01':{'first_request_s':'10'}},[l])
        self.assertFalse(x['timing_available'])
    def test_unavailable_in_attempted_denominator(self):
        r=row();s=row();s.update(call_id='T02',status='unavailable',end_state='')
        x=score([r,s],{},[]);self.assertEqual(x['scam_stop']['n'],1);self.assertEqual(x['scam_stop_over_attempted']['n'],2)
    def test_reused_log_rejected(self):
        r=row();s=row();s['call_id']='T02'
        with self.assertRaises(ValueError):score([r,s],{},[log(r)])
    def test_ambiguous_match_rejected(self):
        r=row()
        with self.assertRaises(ValueError):score([r],{},[log(r),log(r,offset=30)])
    def test_duplicate_ids_rejected(self):
        with self.assertRaises(ValueError):score([row(),row()],{},[])
    def test_nonfinite_timestamp_rejected(self):
        for value in ['nan','inf','-1']:
            with self.assertRaises(ValueError):fnum(value)
    def test_even_median(self):self.assertEqual(_median([3,1,9,5]),4)
    def test_info_is_not_warning(self):
        r=row();r['end_state']='info';x=score([r],{},[])
        self.assertEqual(x['scam_caution_or_stop']['k'],0)
    def test_missing_stop_in_log_excludes_timing(self):
        r=row();x=score([r],{'T01':{'first_request_s':'10'}},[log(r,{})])
        self.assertFalse(x['timing_available'])

if __name__=='__main__':unittest.main()
