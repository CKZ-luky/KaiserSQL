-- 按手册逐段执行，观察每个结果；请先完成 02_手册练习数据初始化.sql。
-- 已存在同名索引时，先 SHOW INDEX 检查后再决定是否删除或跳过创建。
USE school_lab;
CREATE OR REPLACE VIEW high_scores AS
SELECT id,name,score FROM students WHERE score>=90;
SELECT * FROM high_scores ORDER BY score DESC;
SHOW CREATE VIEW high_scores;

CREATE INDEX idx_student_score ON students(score);
SHOW INDEX FROM students;
EXPLAIN SELECT * FROM students WHERE score>=90;
-- 删除时运行：
-- DROP INDEX idx_student_score ON students;

SELECT CONCAT(name,'同学') AS label,
       ROUND(score,1) AS score_one_decimal,
       CHAR_LENGTH(name) AS name_length
FROM students ORDER BY id;
SELECT NOW() AS current_time, CURDATE() AS today;
SELECT name,score,
       DENSE_RANK() OVER (ORDER BY score DESC) AS rank_no
FROM students ORDER BY score DESC,id;
