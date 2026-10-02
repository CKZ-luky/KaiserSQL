-- 按手册逐段执行，观察每个结果；请先完成 02_手册练习数据初始化.sql。
USE school_lab;
SELECT name, score
FROM students
WHERE score >= 90
ORDER BY score DESC;

SELECT s.name, c.name AS class_name, s.score
FROM students AS s
JOIN classes AS c ON s.class_id = c.id
ORDER BY s.id;

SELECT class_id, ROUND(AVG(score),2) AS average_score
FROM students GROUP BY class_id ORDER BY class_id;

INSERT INTO students VALUES (5,'测试同学',20,60.00,1);
UPDATE students SET score=65.00 WHERE id=5;
SELECT * FROM students WHERE id=5;
DELETE FROM students WHERE id=5;
