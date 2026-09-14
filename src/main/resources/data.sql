-- ============================================================
-- delivery_service DB 및 테이블 생성 스크립트
-- 엔티티 기준: User, DriverLocation, DeliveryPlan, DeliveryStop, DeliveryItem,
--             RiskAssessment, RiskFactor, Weather
-- ============================================================

CREATE DATABASE IF NOT EXISTS delivery_service
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE delivery_service;

-- 자식 -> 부모 순서로 DROP (재실행 시 FK 충돌 방지)
DROP TABLE IF EXISTS risk_factor;
DROP TABLE IF EXISTS risk_assessment;
DROP TABLE IF EXISTS delivery_item;
DROP TABLE IF EXISTS delivery_stop;
DROP TABLE IF EXISTS delivery_plan_priority_driver;
DROP TABLE IF EXISTS delivery_plan;
DROP TABLE IF EXISTS driver_location;
-- 기존 RDB Refresh Token 테이블 제거용
DROP TABLE IF EXISTS refresh_token;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS weather;

-- ------------------------------------------------------------
-- users
-- ------------------------------------------------------------
CREATE TABLE users (
                       id        BIGINT AUTO_INCREMENT PRIMARY KEY,
                       login_id  VARCHAR(255) NOT NULL,
                       password  VARCHAR(255) NOT NULL,
                       name      VARCHAR(255) NOT NULL,
                       role      VARCHAR(30)  NOT NULL,
                       CONSTRAINT uk_users_login_id UNIQUE (login_id)
) ENGINE=InnoDB;

-- ============================================================
-- 관리자 테스트 계정
-- loginId: admin
-- password: 1234
-- ============================================================

INSERT INTO users (
    login_id,
    password,
    name,
    role
)
VALUES (
           'admin',
           '$2y$10$Z3CLfcNpZ2VZag4YoSHUj.Ku3NmM6ZFhMywRmazbw1nmBi4KTO4hi',
           '관리자',
           'ROLE_ADMIN'
       );

-- ============================================================
-- 배송 기사 테스트 계정
-- loginId: user1, user2, user3
-- password: 1234
-- ============================================================

INSERT INTO users (
    login_id,
    password,
    name,
    role
)
VALUES
    (
        'user1',
        '$2y$10$Z3CLfcNpZ2VZag4YoSHUj.Ku3NmM6ZFhMywRmazbw1nmBi4KTO4hi',
        '기사1',
        'ROLE_DELIVERY_DRIVER'
    ),
    (
        'user2',
        '$2y$10$Z3CLfcNpZ2VZag4YoSHUj.Ku3NmM6ZFhMywRmazbw1nmBi4KTO4hi',
        '기사2',
        'ROLE_DELIVERY_DRIVER'
    ),
    (
        'user3',
        '$2y$10$Z3CLfcNpZ2VZag4YoSHUj.Ku3NmM6ZFhMywRmazbw1nmBi4KTO4hi',
        '기사3',
        'ROLE_DELIVERY_DRIVER'
    );

-- ------------------------------------------------------------
-- driver_location (기사별 최신 위치 한 건)
-- ------------------------------------------------------------
CREATE TABLE driver_location (
                                 id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                                 driver_id   BIGINT      NOT NULL,
                                 latitude    DOUBLE      NOT NULL,
                                 longitude   DOUBLE      NOT NULL,
                                 updated_at  DATETIME(6) NOT NULL,
                                 CONSTRAINT uk_driver_location_driver UNIQUE (driver_id),
                                 CONSTRAINT fk_driver_location_driver
                                     FOREIGN KEY (driver_id) REFERENCES users (id)
) ENGINE=InnoDB;

CREATE INDEX idx_driver_location_updated_at
    ON driver_location (updated_at);

INSERT INTO driver_location (driver_id, latitude, longitude, updated_at)
VALUES
    (2, 37.5665, 126.9780, CURRENT_TIMESTAMP(6)),
    (3, 37.5651, 126.9895, CURRENT_TIMESTAMP(6)),
    (4, 37.5700, 126.9920, CURRENT_TIMESTAMP(6));

-- ------------------------------------------------------------
-- delivery_plan
-- ------------------------------------------------------------
CREATE TABLE delivery_plan (
                               id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
                               -- 관리자가 등록한 직후에는 수령한 기사가 없으므로 NULL 을 허용한다.
                               driver_id               BIGINT       NULL,
                               departure_location      VARCHAR(255) NOT NULL,
                               departure_latitude      DOUBLE       NOT NULL,
                               departure_longitude     DOUBLE       NOT NULL,
                               scheduled_departure_at  DATETIME(6),
                               assigned_at             DATETIME(6),
                               -- 전체 공개 시각. NULL 이면 우선권 없이 처음부터 전체 공개된 업무다.
                               public_at               DATETIME(6),
                               actual_departure_at     DATETIME(6),
                               status                  VARCHAR(30)  NOT NULL,
                               created_at              DATETIME(6)  NOT NULL,
                               completed_at            DATETIME(6),
                               version                 BIGINT       NOT NULL DEFAULT 0,
                               CONSTRAINT fk_delivery_plan_driver
                                   FOREIGN KEY (driver_id) REFERENCES users (id)
) ENGINE=InnoDB;

CREATE INDEX idx_delivery_plan_driver_created
    ON delivery_plan (driver_id, created_at);

-- 미배정(OPEN) 업무 목록 조회용
CREATE INDEX idx_delivery_plan_status_scheduled
    ON delivery_plan (status, scheduled_departure_at);

-- 기사별 진행 중 업무 수 집계용
CREATE INDEX idx_delivery_plan_driver_status
    ON delivery_plan (driver_id, status);

-- ------------------------------------------------------------
-- delivery_plan_priority_driver
-- 공개 시각 전까지 해당 업무를 수령할 수 있는 추천 상위 기사 목록
-- ------------------------------------------------------------
CREATE TABLE delivery_plan_priority_driver (
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

-- ------------------------------------------------------------
-- delivery_stop
-- ------------------------------------------------------------
CREATE TABLE delivery_stop (
                               id                BIGINT AUTO_INCREMENT PRIMARY KEY,
                               delivery_plan_id  BIGINT       NOT NULL,
                               sequence          INT,
                               status            VARCHAR(30)  NOT NULL,
                               address           VARCHAR(255) NOT NULL,
                               latitude          DOUBLE       NOT NULL,
                               longitude         DOUBLE       NOT NULL,
                               completed_at      DATETIME(6),

                               CONSTRAINT fk_delivery_stop_plan
                                   FOREIGN KEY (delivery_plan_id)
                                       REFERENCES delivery_plan (id)
) ENGINE=InnoDB;

CREATE INDEX idx_delivery_stop_plan
    ON delivery_stop (delivery_plan_id);

-- ------------------------------------------------------------
-- delivery_item
-- ------------------------------------------------------------
CREATE TABLE delivery_item (
                               id                BIGINT AUTO_INCREMENT PRIMARY KEY,
                               delivery_stop_id  BIGINT       NOT NULL,
                               product_name      VARCHAR(255) NOT NULL,
                               product_type      VARCHAR(30)  NOT NULL,
                               quantity          INT          NOT NULL,
                               CONSTRAINT fk_delivery_item_stop
                                   FOREIGN KEY (delivery_stop_id) REFERENCES delivery_stop (id)
) ENGINE=InnoDB;

-- ------------------------------------------------------------
-- risk_assessment
-- ------------------------------------------------------------
CREATE TABLE risk_assessment (
                                 id                BIGINT AUTO_INCREMENT PRIMARY KEY,
                                 delivery_stop_id  BIGINT      NOT NULL,
                                 level             VARCHAR(30) NOT NULL,
                                 analyzed_at       DATETIME(6) NOT NULL,
                                 CONSTRAINT uk_risk_assessment_stop UNIQUE (delivery_stop_id),
                                 CONSTRAINT fk_risk_assessment_stop
                                     FOREIGN KEY (delivery_stop_id) REFERENCES delivery_stop (id)
) ENGINE=InnoDB;

-- ------------------------------------------------------------
-- risk_factor
-- ------------------------------------------------------------
CREATE TABLE risk_factor (
                             id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
                             risk_assessment_id  BIGINT       NOT NULL,
                             type                VARCHAR(30)  NOT NULL,
                             description         VARCHAR(255),
                             CONSTRAINT fk_risk_factor_assessment
                                 FOREIGN KEY (risk_assessment_id) REFERENCES risk_assessment (id)
) ENGINE=InnoDB;

CREATE INDEX idx_delivery_item_stop
    ON delivery_item (delivery_stop_id);

CREATE INDEX idx_risk_factor_assessment
    ON risk_factor (risk_assessment_id);

-- ------------------------------------------------------------
-- weather
-- ------------------------------------------------------------
CREATE TABLE weather (
                         id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                         nx          INT          NOT NULL,
                         ny          INT          NOT NULL,
                         fcst_date   DATE         NOT NULL,
                         fcst_time   TIME         NOT NULL,
                         base_date   DATE         NOT NULL,
                         base_time   TIME         NOT NULL,
                         category    VARCHAR(10)  NOT NULL,
                         fcst_value  VARCHAR(50)  NOT NULL,
                         fetched_at  DATETIME(6)  NOT NULL,
                         CONSTRAINT uk_weather_slot UNIQUE (nx, ny, fcst_date, fcst_time, category)
) ENGINE=InnoDB;

-- ============================================================
-- 배송 계획 테스트 데이터
-- 기사별 배송지 10곳 구성
-- 1001: 배송 준비 10곳
-- 1002: 배송 준비 10곳 (전체 27박스 / 삽입 시점 2시간 뒤 출발)
-- 1003: 배송 완료 10곳
-- 1004: 기사3 배송 준비 10곳 (1002와 동일 시각 출발)
-- ============================================================

INSERT INTO delivery_plan (
    id,
    driver_id,
    departure_location,
    departure_latitude,
    departure_longitude,
    scheduled_departure_at,
    actual_departure_at,
    status,
    created_at,
    completed_at
)
VALUES
    (
        1001,
        (SELECT id FROM users WHERE login_id = 'user1'),
        '서울특별시 종로구 세종대로 175',
        37.5716,
        126.9769,
        TIMESTAMP(DATE_ADD(NOW(), INTERVAL 2 HOUR), '09:00:00'),
        NULL,
        'READY',
        DATE_SUB(NOW(), INTERVAL 2 HOUR),
        NULL
    ),
    (
        1002,
        (SELECT id FROM users WHERE login_id = 'user2'),
        '서울특별시 관악구 관악로 1',
        37.4599,
        126.9519,
        DATE_ADD(NOW(), INTERVAL 2 HOUR),
        NULL,
        'READY',
        DATE_SUB(NOW(), INTERVAL 1 DAY),
        NULL
    ),
    (
        1003,
        (SELECT id FROM users WHERE login_id = 'user3'),
        '서울특별시 강남구 테헤란로 152',
        37.5000,
        127.0365,
        DATE_SUB(NOW(), INTERVAL 26 HOUR),
        DATE_SUB(NOW(), INTERVAL 25 HOUR),
        'COMPLETED',
        DATE_SUB(NOW(), INTERVAL 2 DAY),
        DATE_SUB(NOW(), INTERVAL 20 HOUR)
    ),
    (
        1004,
        (SELECT id FROM users WHERE login_id = 'user3'),
        '서울특별시 강남구 테헤란로 152',
        37.5000,
        127.0365,
        DATE_ADD(NOW(), INTERVAL 2 HOUR),
        NULL,
        'READY',
        NOW(),
        NULL
    );

INSERT INTO delivery_stop (
    id,
    delivery_plan_id,
    sequence,
    status,
    address,
    latitude,
    longitude,
    completed_at
)
VALUES
    (1101,1001,0,'READY','서울특별시 종로구 세종대로 175',37.5716,126.9769,NULL),
    (1102,1001,1,'READY','서울특별시 종로구 율곡로 75',37.5759,126.9891,NULL),
    (1103,1001,2,'READY','서울특별시 종로구 대학로 101',37.5837,127.0007,NULL),
    (1104,1001,3,'READY','서울특별시 종로구 창경궁로 185',37.5788,126.9950,NULL),
    (1105,1001,4,'READY','서울특별시 종로구 종로 51',37.5704,126.9831,NULL),
    (1106,1001,5,'READY','서울특별시 종로구 삼일대로 428',37.5689,126.9875,NULL),
    (1107,1001,6,'READY','서울특별시 종로구 북촌로 37',37.5826,126.9849,NULL),
    (1108,1001,7,'READY','서울특별시 종로구 사직로 161',37.5755,126.9710,NULL),
    (1109,1001,8,'READY','서울특별시 종로구 인사동길 44',37.5740,126.9850,NULL),
    (1110,1001,9,'READY','서울특별시 종로구 혜화로 12',37.5862,127.0016,NULL),
    (1111,1001,10,'READY','서울특별시 종로구 자하문로 10',37.5768,126.9726,NULL),
    (1112,1001,11,'READY','서울특별시 종로구 필운대로 35',37.5792,126.9688,NULL),
    (1113,1001,12,'READY','서울특별시 종로구 통일로 230',37.5875,126.9697,NULL),
    (1114,1001,13,'READY','서울특별시 종로구 평창문화로 50',37.6065,126.9685,NULL),
    (1115,1001,14,'READY','서울특별시 종로구 진흥로 432',37.6084,126.9567,NULL),
    (1116,1001,15,'READY','서울특별시 종로구 세검정로 240',37.6015,126.9593,NULL),
    (1117,1001,16,'READY','서울특별시 종로구 창의문로 145',37.5928,126.9664,NULL),
    (1118,1001,17,'READY','서울특별시 종로구 북촌로 120',37.5869,126.9813,NULL),
    (1119,1001,18,'READY','서울특별시 종로구 삼청로 156',37.5877,126.9818,NULL),
    (1120,1001,19,'READY','서울특별시 종로구 삼청로 30',37.5805,126.9810,NULL),
    (1121,1001,20,'READY','서울특별시 종로구 경희궁길 26',37.5712,126.9706,NULL),
    (1122,1001,21,'READY','서울특별시 종로구 새문안로 55',37.5703,126.9695,NULL),
    (1123,1001,22,'READY','서울특별시 종로구 종로 157',37.5707,126.9944,NULL),
    (1124,1001,23,'READY','서울특별시 종로구 종로 199',37.5708,127.0011,NULL),
    (1125,1001,24,'READY','서울특별시 종로구 율곡로 283',37.5758,127.0060,NULL),
    (1126,1001,25,'READY','서울특별시 종로구 지봉로 5',37.5719,127.0151,NULL),
    (1127,1001,26,'READY','서울특별시 종로구 난계로 251',37.5735,127.0177,NULL),
    (1128,1001,27,'READY','서울특별시 종로구 동숭길 122',37.5824,127.0038,NULL),
    (1129,1001,28,'READY','서울특별시 종로구 성균관로 25',37.5882,126.9936,NULL),
    (1130,1001,29,'READY','서울특별시 종로구 창경궁로 265',37.5847,126.9971,NULL);


INSERT INTO delivery_stop (
    id,
    delivery_plan_id,
    sequence,
    status,
    address,
    latitude,
    longitude,
    completed_at
)
VALUES
    (1201,1002,0,'READY','서울특별시 관악구 관악로 1',37.4599,126.9519,NULL),
    (1202,1002,1,'READY','서울특별시 관악구 남부순환로 1820',37.4820,126.9298,NULL),
    (1203,1002,2,'READY','서울특별시 관악구 신림로 120',37.4802,126.9368,NULL),
    (1204,1002,3,'READY','서울특별시 관악구 봉천로 450',37.4895,126.9575,NULL),
    (1205,1002,4,'READY','서울특별시 관악구 대학길 10',37.4692,126.9354,NULL),
    (1206,1002,5,'READY','서울특별시 관악구 은천로 93',37.4868,126.9427,NULL),
    (1207,1002,6,'READY','서울특별시 관악구 쑥고개로 80',37.4765,126.9478,NULL),
    (1208,1002,7,'READY','서울특별시 관악구 난곡로 250',37.4645,126.9155,NULL),
    (1209,1002,8,'READY','서울특별시 관악구 호암로 399',37.4552,126.9421,NULL),
    (1210,1002,9,'READY','서울특별시 관악구 신림동길 15',37.4872,126.9279,NULL);


INSERT INTO delivery_stop (
    id,
    delivery_plan_id,
    sequence,
    status,
    address,
    latitude,
    longitude,
    completed_at
)
VALUES
    (1301,1003,0,'COMPLETED','서울특별시 강남구 테헤란로 152',
     37.5000,127.0365,DATE_SUB(NOW(),INTERVAL 24 HOUR)),

    (1302,1003,1,'COMPLETED','서울특별시 강남구 역삼로 180',
     37.5009,127.0351,DATE_SUB(NOW(),INTERVAL 23 HOUR)),

    (1303,1003,2,'COMPLETED','서울특별시 강남구 선릉로 420',
     37.5045,127.0489,DATE_SUB(NOW(),INTERVAL 22 HOUR)),

    (1304,1003,3,'COMPLETED','서울특별시 강남구 삼성로 212',
     37.5070,127.0630,DATE_SUB(NOW(),INTERVAL 21 HOUR)),

    (1305,1003,4,'COMPLETED','서울특별시 강남구 봉은사로 524',
     37.5147,127.0580,DATE_SUB(NOW(),INTERVAL 20 HOUR)),

    (1306,1003,5,'COMPLETED','서울특별시 강남구 학동로 342',
     37.5150,127.0400,DATE_SUB(NOW(),INTERVAL 19 HOUR)),

    (1307,1003,6,'COMPLETED','서울특별시 강남구 논현로 508',
     37.5140,127.0310,DATE_SUB(NOW(),INTERVAL 18 HOUR)),

    (1308,1003,7,'COMPLETED','서울특별시 강남구 도산대로 45',
     37.5222,127.0365,DATE_SUB(NOW(),INTERVAL 17 HOUR)),

    (1309,1003,8,'COMPLETED','서울특별시 강남구 압구정로 343',
     37.5285,127.0404,DATE_SUB(NOW(),INTERVAL 16 HOUR)),

    (1310,1003,9,'COMPLETED','서울특별시 강남구 청담동 123',
     37.5235,127.0470,DATE_SUB(NOW(),INTERVAL 15 HOUR));


INSERT INTO delivery_stop (
    id,
    delivery_plan_id,
    sequence,
    status,
    address,
    latitude,
    longitude,
    completed_at
)
VALUES
    (1401,1004,0,'READY','서울특별시 강남구 테헤란로 152',
     37.5000,127.0365,NULL),

    (1402,1004,1,'READY','서울특별시 강남구 역삼로 180',
     37.5009,127.0351,NULL),

    (1403,1004,2,'READY','서울특별시 강남구 압구정로 343',
     37.5285,127.0404,NULL),

    (1404,1004,3,'READY','서울특별시 강남구 도산대로 45',
     37.5222,127.0365,NULL),

    (1405,1004,4,'READY','서울특별시 강남구 삼성로 212',
     37.5070,127.0630,NULL),

    (1406,1004,5,'READY','서울특별시 강남구 논현로 508',
     37.5140,127.0310,NULL),

    (1407,1004,6,'READY','서울특별시 강남구 선릉로 420',
     37.5045,127.0489,NULL),

    (1408,1004,7,'READY','서울특별시 강남구 학동로 342',
     37.5150,127.0400,NULL),

    (1409,1004,8,'READY','서울특별시 강남구 봉은사로 524',
     37.5147,127.0580,NULL),

    (1410,1004,9,'READY','서울특별시 강남구 청담동 123',
     37.5235,127.0470,NULL);


-- 배송지별 상품
INSERT INTO delivery_item (
    delivery_stop_id,
    product_name,
    product_type,
    quantity
)
VALUES
    (1101, '생수', 'NORMAL', 45),
    (1101, '냉동식품 세트', 'FROZEN', 18),

    (1102, '샐러드 세트', 'REFRIGERATED', 28),
    (1102, '냉동식품 세트', 'FROZEN', 16),

    (1103, '냉동만두', 'FROZEN', 42),
    (1103, '냉동식품 세트', 'FROZEN', 20),

    (1104, '와인잔 세트', 'FRAGILE', 15),
    (1104, '냉동식품 세트', 'FROZEN', 18),

    (1105, '생활용품 박스', 'NORMAL', 38),
    (1106, '주방용품', 'NORMAL', 32),
    (1107, '수제 디저트', 'REFRIGERATED', 27),
    (1108, '아이스크림 세트', 'FROZEN', 35),
    (1109, '도자기 세트', 'FRAGILE', 22),
    (1110, '반려동물 용품', 'NORMAL', 31),

    (1111, '생수', 'NORMAL', 48),

    (1112, '우유 세트', 'REFRIGERATED', 25),
    (1112, '식품 용기', 'NORMAL', 17),

    (1113, '냉동식품 세트', 'FROZEN', 36),
    (1114, '유리 밀폐용기', 'FRAGILE', 24),
    (1115, '세제 박스', 'NORMAL', 39),
    (1116, '화장지 세트', 'NORMAL', 44),
    (1117, '신선 과일 세트', 'REFRIGERATED', 31),
    (1118, '냉동 피자', 'FROZEN', 34),
    (1119, '유리컵 세트', 'FRAGILE', 19),
    (1120, '반려동물 사료', 'NORMAL', 41),
    (1121, '음료수 박스', 'NORMAL', 47),
    (1122, '요거트 세트', 'REFRIGERATED', 29),
    (1123, '냉동 육류', 'FROZEN', 33),
    (1124, '접시 세트', 'FRAGILE', 21),
    (1125, '욕실용품', 'NORMAL', 36),
    (1126, '세탁용품', 'NORMAL', 40),
    (1127, '신선 식품 세트', 'REFRIGERATED', 32),
    (1128, '냉동 해산물', 'FROZEN', 37),
    (1129, '유리병 세트', 'FRAGILE', 23),
    (1130, '캠핑용품', 'NORMAL', 43),

    (1201, '신선 우유', 'REFRIGERATED', 2),
    (1201, '샐러드', 'REFRIGERATED', 30),
    (1202, '사무용품', 'NORMAL', 5),
    (1203, '냉동 도시락', 'FROZEN', 3),
    (1204, '유리 화병', 'FRAGILE', 1),
    (1205, '과일 선물세트', 'REFRIGERATED', 2),
    (1206, '세제 묶음', 'NORMAL', 4),
    (1207, '캠핑용품', 'NORMAL', 2),
    (1208, '유아 식품', 'REFRIGERATED', 3),
    (1209, '냉동 육류', 'FROZEN', 1),
    (1210, '조명 기구', 'FRAGILE', 4),
    (1301, '도서', 'NORMAL', 2),
    (1302, '케이크', 'REFRIGERATED', 1),
    (1303, '냉동식품', 'FROZEN', 3),
    (1304, '의류', 'NORMAL', 2),
    (1304, '냉동식품', 'FROZEN', 5),
    (1305, '요거트 세트', 'REFRIGERATED', 4),
    (1306, '냉동 해산물', 'FROZEN', 1),
    (1307, '유리 식기', 'FRAGILE', 3),
    (1308, '휴지 묶음', 'NORMAL', 2),
    (1309, '치즈 세트', 'REFRIGERATED', 1),
    (1310, '전자기기', 'FRAGILE', 5),
    (1401, '생필품 세트', 'NORMAL', 2),
    (1402, '신선 채소', 'REFRIGERATED', 3),
    (1403, '디저트 세트', 'REFRIGERATED', 1),
    (1404, '냉동 간편식', 'FROZEN', 4),
    (1405, '유리 보관용기', 'FRAGILE', 2),
    (1406, '사무용품 박스', 'NORMAL', 5),
    (1407, '아이스크림', 'FROZEN', 1),
    (1408, '화장품 세트', 'FRAGILE', 2),
    (1409, '유제품', 'REFRIGERATED', 3),
    (1410, '반려동물 사료', 'NORMAL', 2);

-- 모든 배송지에 1:1 위험도 평가 생성
INSERT INTO risk_assessment (
    id,
    delivery_stop_id,
    level,
    analyzed_at
)
VALUES
    (2101, 1101, 'SAFE', NOW()),
    (2102, 1102, 'SAFE', NOW()),
    (2103, 1103, 'CAUTION', NOW()),
    (2104, 1104, 'DANGER', NOW()),
    (2105, 1105, 'UNKNOWN', NOW()),
    (2106, 1106, 'SAFE', NOW()),
    (2107, 1107, 'CAUTION', NOW()),
    (2108, 1108, 'DANGER', NOW()),
    (2109, 1109, 'SAFE', NOW()),
    (2110, 1110, 'UNKNOWN', NOW()),
    (2111, 1111, 'SAFE', NOW()),
    (2112, 1112, 'CAUTION', NOW()),
    (2113, 1113, 'DANGER', NOW()),
    (2114, 1114, 'SAFE', NOW()),
    (2115, 1115, 'UNKNOWN', NOW()),
    (2116, 1116, 'SAFE', NOW()),
    (2117, 1117, 'CAUTION', NOW()),
    (2118, 1118, 'DANGER', NOW()),
    (2119, 1119, 'CAUTION', NOW()),
    (2120, 1120, 'SAFE', NOW()),
    (2121, 1121, 'UNKNOWN', NOW()),
    (2122, 1122, 'CAUTION', NOW()),
    (2123, 1123, 'DANGER', NOW()),
    (2124, 1124, 'SAFE', NOW()),
    (2125, 1125, 'SAFE', NOW()),
    (2126, 1126, 'UNKNOWN', NOW()),
    (2127, 1127, 'CAUTION', NOW()),
    (2128, 1128, 'DANGER', NOW()),
    (2129, 1129, 'SAFE', NOW()),
    (2130, 1130, 'CAUTION', NOW()),

    (2201, 1201, 'SAFE', DATE_SUB(NOW(), INTERVAL 1 HOUR)),
    (2202, 1202, 'CAUTION', DATE_SUB(NOW(), INTERVAL 1 HOUR)),
    (2203, 1203, 'DANGER', NOW()),
    (2204, 1204, 'SAFE', NOW()),
    (2205, 1205, 'CAUTION', NOW()),
    (2206, 1206, 'UNKNOWN', NOW()),
    (2207, 1207, 'SAFE', NOW()),
    (2208, 1208, 'CAUTION', NOW()),
    (2209, 1209, 'DANGER', NOW()),
    (2210, 1210, 'SAFE', NOW()),
    (2301, 1301, 'SAFE', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (2302, 1302, 'CAUTION', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (2303, 1303, 'DANGER', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (2304, 1304, 'SAFE', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (2305, 1305, 'CAUTION', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (2306, 1306, 'DANGER', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (2307, 1307, 'SAFE', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (2308, 1308, 'CAUTION', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (2309, 1309, 'SAFE', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (2310, 1310, 'UNKNOWN', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (2401, 1401, 'SAFE', NOW()),
    (2402, 1402, 'CAUTION', NOW()),
    (2403, 1403, 'SAFE', NOW()),
    (2404, 1404, 'DANGER', NOW()),
    (2405, 1405, 'UNKNOWN', NOW()),
    (2406, 1406, 'SAFE', NOW()),
    (2407, 1407, 'CAUTION', NOW()),
    (2408, 1408, 'DANGER', NOW()),
    (2409, 1409, 'SAFE', NOW()),
    (2410, 1410, 'UNKNOWN', NOW());

-- 점수는 RiskFactorType 기준으로 엔티티에서 자동 합산된다.
INSERT INTO risk_factor (
    risk_assessment_id,
    type,
    description
)
VALUES
    (2102, 'HEAT_WAVE', '폭염'),
    (2103, 'WEATHER_WARNING', '기상 특보'),
    (2104, 'HEAVY_RAIN', '폭우'),
    (2104, 'WEATHER_WARNING', '기상 특보'),
    (2106, 'HEAT_WAVE', '폭염'),
    (2107, 'WEATHER_WARNING', '기상 특보'),
    (2108, 'HEAVY_RAIN', '폭우'),
    (2108, 'WEATHER_WARNING', '기상 특보'),
    (2109, 'HEAT_WAVE', '폭염'),
    (2112, 'HEAT_WAVE', '폭염'),
    (2112, 'WEATHER_WARNING', '기상 특보'),
    (2113, 'HEAVY_RAIN', '폭우'),
    (2113, 'WEATHER_WARNING', '기상 특보'),
    (2115, 'WEATHER_WARNING', '기상 특보'),
    (2117, 'HEAT_WAVE', '폭염'),
    (2117, 'WEATHER_WARNING', '기상 특보'),
    (2118, 'HEAVY_RAIN', '폭우'),
    (2118, 'WEATHER_WARNING', '기상 특보'),
    (2119, 'HEAT_WAVE', '폭염'),
    (2121, 'WEATHER_WARNING', '기상 특보'),
    (2122, 'HEAT_WAVE', '폭염'),
    (2122, 'WEATHER_WARNING', '기상 특보'),
    (2123, 'HEAVY_RAIN', '폭우'),
    (2123, 'WEATHER_WARNING', '기상 특보'),
    (2126, 'WEATHER_WARNING', '기상 특보'),
    (2127, 'HEAT_WAVE', '폭염'),
    (2127, 'WEATHER_WARNING', '기상 특보'),
    (2128, 'HEAVY_RAIN', '폭우'),
    (2128, 'WEATHER_WARNING', '기상 특보'),
    (2130, 'HEAT_WAVE', '폭염'),
    (2130, 'WEATHER_WARNING', '기상 특보'),
    (2202, 'WEATHER_WARNING', '기상 특보'),
    (2203, 'HEAVY_RAIN', '폭우'),
    (2203, 'WEATHER_WARNING', '기상 특보'),
    (2204, 'HEAT_WAVE', '폭염'),
    (2205, 'HEAVY_RAIN', '폭우'),
    (2205, 'HEAT_WAVE', '폭염'),
    (2207, 'HEAT_WAVE', '폭염'),
    (2208, 'WEATHER_WARNING', '기상 특보'),
    (2209, 'HEAVY_RAIN', '폭우'),
    (2209, 'WEATHER_WARNING', '기상 특보'),
    (2210, 'HEAT_WAVE', '폭염'),
    (2302, 'WEATHER_WARNING', '기상 특보'),
    (2303, 'HEAVY_RAIN', '폭우'),
    (2303, 'WEATHER_WARNING', '기상 특보'),
    (2304, 'HEAT_WAVE', '폭염'),
    (2305, 'WEATHER_WARNING', '기상 특보'),
    (2306, 'HEAVY_RAIN', '폭우'),
    (2306, 'WEATHER_WARNING', '기상 특보'),
    (2307, 'HEAT_WAVE', '폭염'),
    (2308, 'HEAVY_RAIN', '폭우'),
    (2308, 'HEAT_WAVE', '폭염'),
    (2401, 'HEAT_WAVE', '폭염'),
    (2402, 'WEATHER_WARNING', '기상 특보'),
    (2403, 'HEAT_WAVE', '폭염'),
    (2404, 'HEAVY_RAIN', '폭우'),
    (2404, 'WEATHER_WARNING', '기상 특보'),
    (2406, 'HEAT_WAVE', '폭염'),
    (2407, 'WEATHER_WARNING', '기상 특보'),
    (2408, 'HEAVY_RAIN', '폭우'),
    (2408, 'WEATHER_WARNING', '기상 특보'),
    (2409, 'HEAT_WAVE', '폭염');

select * from risk_factor;
select * from risk_assessment;
select * from delivery_stop;
select * from delivery_plan;
select * from weather;
