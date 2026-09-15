-- ============================================================
-- 배송 업무 선착순 수령(claim) 도입에 따른 delivery_plan 스키마 변경
-- - 관리자가 등록한 직후에는 배정된 기사가 없으므로 driver_id 를 NULL 허용으로 변경
-- - 수령 시각(assigned_at) 컬럼 추가
-- - 낙관적 락을 위한 version 컬럼 추가
-- - 미배정 목록 조회 / 기사별 보유 수량 집계용 인덱스 추가
-- ============================================================

USE delivery_service;

ALTER TABLE delivery_plan
    MODIFY COLUMN driver_id BIGINT NULL;

ALTER TABLE delivery_plan
    ADD COLUMN assigned_at DATETIME(6) NULL AFTER scheduled_departure_at;

ALTER TABLE delivery_plan
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- 기존 데이터는 모두 관리자 직접 할당분이므로 수령 시각을 생성 시각으로 채운다.
UPDATE delivery_plan
SET assigned_at = created_at
WHERE driver_id IS NOT NULL
  AND assigned_at IS NULL;

CREATE INDEX idx_delivery_plan_status_scheduled
    ON delivery_plan (status, scheduled_departure_at);

CREATE INDEX idx_delivery_plan_driver_status
    ON delivery_plan (driver_id, status);
