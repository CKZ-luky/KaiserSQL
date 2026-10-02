USE school_lab;
DROP PROCEDURE IF EXISTS GetClassStudents;
DROP FUNCTION IF EXISTS ScoreLevel;
DROP TRIGGER IF EXISTS CheckStudentScore;
DELIMITER $$
CREATE PROCEDURE GetClassStudents(IN p_class INT)
BEGIN
  SELECT id,name,score FROM students
  WHERE class_id=p_class ORDER BY id;
END$$
CREATE FUNCTION ScoreLevel(p_score DECIMAL(5,2))
RETURNS VARCHAR(10) DETERMINISTIC
BEGIN
  RETURN IF(p_score>=90,'优秀','继续努力');
END$$
CREATE TRIGGER CheckStudentScore
BEFORE INSERT ON students FOR EACH ROW
BEGIN
  IF NEW.score IS NULL THEN
    SET NEW.score=0;
  END IF;
END$$
DELIMITER ;
CALL GetClassStudents(1);
SELECT name,ScoreLevel(score) AS level_name
FROM students ORDER BY id;
INSERT INTO students VALUES (6,'触发器测试',20,NULL,1);
SELECT score FROM students WHERE id=6;
