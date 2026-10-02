-- 在会话 A 分步执行
START TRANSACTION;
UPDATE students SET age=99 WHERE id=1;
SELECT age FROM students WHERE id=1;
ROLLBACK;
SELECT age FROM students WHERE id=1;
