CREATE TABLE IF NOT EXISTS driver_location (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    driver_id   BIGINT      NOT NULL,
    latitude    DOUBLE      NOT NULL,
    longitude   DOUBLE      NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT uk_driver_location_driver UNIQUE (driver_id),
    CONSTRAINT fk_driver_location_driver
        FOREIGN KEY (driver_id) REFERENCES users (id),
    INDEX idx_driver_location_updated_at (updated_at)
) ENGINE=InnoDB;
