-- 假设 CSV 第一行是 name,age，其余行为数据
USE school_lab;
CREATE TABLE IF NOT EXISTS imported_students (
  name VARCHAR(40), age INT
);
LOAD DATA INFILE '/lab/exchange/实际显示的文件名.csv'
INTO TABLE imported_students
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 LINES (name,@age)
SET age=CAST(TRIM(TRAILING '\r' FROM @age) AS UNSIGNED);
