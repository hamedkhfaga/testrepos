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


CREATE INDEX FP_SUM_1
ON ATTENDANCE_DAY_SUMMARY (ORGANIZATION_PARTY_ID, WORK_DATE);

CREATE INDEX FP_SUM_2
ON ATTENDANCE_DAY_SUMMARY (ORGANIZATION_PARTY_ID, PARTY_ID, WORK_DATE);
-- Idempotency lookup
CREATE INDEX FP_EVT_U1
ON BIOMETRIC_EVENT_LOG (ORGANIZATION_PARTY_ID, BIOMETRIC_DEVICE_ID, VENDOR_EVENT_ID);

-- Pairing window scan
CREATE INDEX FP_EVT_PAIRING_1
ON BIOMETRIC_EVENT_LOG (ORGANIZATION_PARTY_ID, PARTY_ID, EVENT_DATE_TIME);

-- Device user resolution
CREATE INDEX FP_DEVUSER_1
ON BIOMETRIC_DEVICE_USER (ORGANIZATION_PARTY_ID, BIOMETRIC_DEVICE_ID, DEVICE_USER_ID, FROM_DATE, THRU_DATE);

CREATE INDEX FP_DEVUSER_2
ON BIOMETRIC_DEVICE_USER (ORGANIZATION_PARTY_ID, BADGE_BARCODE, FROM_DATE, THRU_DATE);

-- Export/reporting queries
CREATE INDEX FP_SUM_1
ON ATTENDANCE_DAY_SUMMARY (ORGANIZATION_PARTY_ID, WORK_DATE);

CREATE INDEX FP_SUM_2
ON ATTENDANCE_DAY_SUMMARY (ORGANIZATION_PARTY_ID, PARTY_ID, WORK_DATE);
