-- =========
-- Fingerprint Module Indexes - Oracle
-- =========

CREATE UNIQUE INDEX ux_fp_biolog_dev_vendor_evt
ON biometric_event_log (biometric_device_id, vendor_event_id);

CREATE INDEX ix_fp_biolog_dev_user_dt
ON biometric_event_log (biometric_device_id, device_user_id, event_date_time);

CREATE INDEX ix_fp_biolog_party_dt
ON biometric_event_log (party_id, event_date_time);

CREATE INDEX ix_fp_devuser_map
ON biometric_device_user (biometric_device_id, device_user_id, from_date, thru_date);

CREATE UNIQUE INDEX ux_fp_daysum_party_day
ON attendance_day_summary (party_id, work_date);

CREATE INDEX ix_fp_pair_party_day
ON attendance_punch_pair (party_id, work_date);

CREATE INDEX ix_fp_pair_inlog
ON attendance_punch_pair (in_event_log_id);

CREATE INDEX ix_fp_pair_outlog
ON attendance_punch_pair (out_event_log_id);

CREATE INDEX ix_fp_exc_party_day
ON attendance_exception (party_id, work_date);
