-- 2M orders, ~600 MB. Deliberately larger than a small buffer pool.
DROP TABLE IF EXISTS orders, seq;
CREATE TABLE seq (n INT);
INSERT INTO seq VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

CREATE TABLE orders (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT NOT NULL,
  status     VARCHAR(16),
  amount     DECIMAL(10,2),
  created_at DATETIME,
  notes      CHAR(200),
  KEY idx_user (user_id)
) ENGINE=InnoDB;

INSERT INTO orders (user_id, status, amount, created_at, notes)
SELECT FLOOR(RAND()*500000),
       ELT(FLOOR(RAND()*3)+1,'paid','pending','shipped'),
       ROUND(RAND()*500,2),
       NOW() - INTERVAL FLOOR(RAND()*365) DAY,
       REPEAT('x',200)
FROM seq a, seq b, seq c, seq d, seq e, seq f;

INSERT INTO orders (user_id, status, amount, created_at, notes)
SELECT FLOOR(RAND()*500000),'paid',ROUND(RAND()*500,2),NOW(),REPEAT('x',200)
FROM seq a, seq b, seq c, seq d, seq e, seq f;

ANALYZE TABLE orders;
