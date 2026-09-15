-- ============================================================
-- 삭제 배치(deliveryPlanCleanupJob) 대상 미리보기
--   배치를 돌리기 전에 무엇이 지워지는지 확인한다.
--   보관 기간을 바꿔보려면 아래 @retention_days 만 수정한다.
-- ============================================================

SET @retention_days = 30;
SET @cutoff = NOW() - INTERVAL @retention_days DAY;

-- 1) 삭제 대상 배송 계획과 딸려 갈 하위 건수
SELECT p.id                                   AS plan_id,
       p.departure_location                   AS 출발지,
       p.completed_at                         AS 완료시각,
       DATEDIFF(NOW(), p.completed_at)        AS 경과일,
       COUNT(DISTINCT s.id)                   AS 배송지,
       COUNT(DISTINCT i.id)                   AS 상품,
       COUNT(DISTINCT a.id)                   AS 위험도,
       COUNT(DISTINCT f.id)                   AS 위험요인
FROM delivery_plan p
         LEFT JOIN delivery_stop   s ON s.delivery_plan_id   = p.id
         LEFT JOIN delivery_item   i ON i.delivery_stop_id   = s.id
         LEFT JOIN risk_assessment a ON a.delivery_stop_id   = s.id
         LEFT JOIN risk_factor     f ON f.risk_assessment_id = a.id
WHERE p.status = 'COMPLETED'
  AND p.completed_at < @cutoff
GROUP BY p.id, p.departure_location, p.completed_at
ORDER BY p.id;

-- 2) 삭제될 총 행 수
SELECT '삭제 예정 합계' AS 구분,
       (SELECT COUNT(*) FROM delivery_plan p
        WHERE p.status = 'COMPLETED' AND p.completed_at < @cutoff)                      AS delivery_plan,
       (SELECT COUNT(*) FROM delivery_stop s JOIN delivery_plan p ON p.id = s.delivery_plan_id
        WHERE p.status = 'COMPLETED' AND p.completed_at < @cutoff)                      AS delivery_stop,
       (SELECT COUNT(*) FROM delivery_item i
                 JOIN delivery_stop s ON s.id = i.delivery_stop_id
                 JOIN delivery_plan p ON p.id = s.delivery_plan_id
        WHERE p.status = 'COMPLETED' AND p.completed_at < @cutoff)                      AS delivery_item,
       (SELECT COUNT(*) FROM risk_assessment a
                 JOIN delivery_stop s ON s.id = a.delivery_stop_id
                 JOIN delivery_plan p ON p.id = s.delivery_plan_id
        WHERE p.status = 'COMPLETED' AND p.completed_at < @cutoff)                      AS risk_assessment,
       (SELECT COUNT(*) FROM risk_factor f
                 JOIN risk_assessment a ON a.id = f.risk_assessment_id
                 JOIN delivery_stop   s ON s.id = a.delivery_stop_id
                 JOIN delivery_plan   p ON p.id = s.delivery_plan_id
        WHERE p.status = 'COMPLETED' AND p.completed_at < @cutoff)                      AS risk_factor;

-- 3) 남는 계획 (대상이 아닌 이유 포함)
SELECT p.id                            AS plan_id,
       p.status                        AS 상태,
       DATEDIFF(NOW(), p.completed_at) AS 경과일,
       CASE WHEN p.status <> 'COMPLETED'  THEN '완료 상태가 아님'
            WHEN p.completed_at IS NULL   THEN '완료 시각 없음'
            ELSE CONCAT('보관 기간 미경과 (', @retention_days, '일 기준)')
       END                             AS 유지사유
FROM delivery_plan p
WHERE NOT (p.status = 'COMPLETED' AND p.completed_at < @cutoff)
ORDER BY p.id;
