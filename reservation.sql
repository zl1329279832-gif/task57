-- ============================================================
-- 预约排队表
-- ============================================================
CREATE TABLE IF NOT EXISTS reservation (
    reservationId  INT AUTO_INCREMENT PRIMARY KEY,
    userId         INT      NOT NULL,
    bookId         INT      NOT NULL,
    status         TINYINT  NOT NULL DEFAULT 0 COMMENT '0-排队中 1-已保留 2-已完成 3-已取消 4-已过期',
    queuePosition  INT      NOT NULL,
    reserveTime    DATETIME          COMMENT '保留生效时间',
    expiryTime     DATETIME          COMMENT '保留过期时间',
    createTime     DATETIME NOT NULL,
    FOREIGN KEY (userId) REFERENCES user(userId),
    FOREIGN KEY (bookId) REFERENCES book_info(bookId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 常用查询索引
CREATE INDEX idx_reservation_book_status ON reservation(bookId, status, queuePosition);
CREATE INDEX idx_reservation_user        ON reservation(userId);
