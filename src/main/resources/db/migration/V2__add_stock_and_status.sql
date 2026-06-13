-- V2: Add stock tracking and borrow status for concurrency-safe borrow/return
-- Fixes: double-click borrow race condition, duplicate return, stock inconsistency

ALTER TABLE book_info
    ADD COLUMN stock          INT NOT NULL DEFAULT 1,
    ADD COLUMN availableStock INT NOT NULL DEFAULT 1;

-- Sync availableStock with current isBorrowed state
UPDATE book_info SET stock = 1,
    availableStock = CASE WHEN isBorrowed = 0 THEN 1 ELSE 0 END;

ALTER TABLE borrow
    ADD COLUMN status TINYINT NOT NULL DEFAULT 0;

-- Mark already-returned borrows as STATUS_RETURNED
UPDATE borrow SET status = 1 WHERE returnTime IS NOT NULL;

CREATE INDEX idx_borrow_user_status ON borrow(userId, status);
