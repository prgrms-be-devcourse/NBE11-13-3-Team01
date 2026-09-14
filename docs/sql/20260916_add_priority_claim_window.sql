-- ============================================================
-- 추천 상위 기사 우선 수령 윈도우(차등 공개) 도입
-- - delivery_plan.public_at: 전체 기사에게 공개되는 시각. NULL 이면 처음부터 전체 공개
-- - delivery_plan_priority_driver: 공개 시각 전까지 수령할 수 있는 추천 상위 기사 목록
-- ============================================================

USE delivery_service;

ALTER TABLE delivery_plan
    ADD COLUMN public_at DATETIME(6) NULL AFTER assigned_at;

CREATE TABLE IF NOT EXISTS delivery_plan_priority_driver (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    delivery_plan_id BIGINT NOT NULL,
    driver_id        BIGINT NOT NULL,
    priority_rank    INT    NOT NULL,
    score            INT    NOT NULL,
    CONSTRAINT uk_plan_priority_driver UNIQUE (delivery_plan_id, driver_id),
    CONSTRAINT fk_plan_priority_plan
        FOREIGN KEY (delivery_plan_id) REFERENCES delivery_plan (id),
    CONSTRAINT fk_plan_priority_driver
        FOREIGN KEY (driver_id) REFERENCES users (id),
    INDEX idx_plan_priority_driver (delivery_plan_id, driver_id)
) ENGINE=InnoDB;
