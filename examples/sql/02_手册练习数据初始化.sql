CREATE DATABASE IF NOT EXISTS school_lab
  CHARACTER SET utf8mb4;
USE school_lab;
DROP TABLE IF EXISTS students;
DROP TABLE IF EXISTS classes;
CREATE TABLE classes (
  id INT PRIMARY KEY,
  name VARCHAR(40) NOT NULL
);
CREATE TABLE students (
  id INT PRIMARY KEY,
  name VARCHAR(40) NOT NULL,
  age INT CHECK (age BETWEEN 0 AND 120),
  score DECIMAL(5,2) CHECK (score BETWEEN 0 AND 100),
  class_id INT NOT NULL,
  FOREIGN KEY (class_id) REFERENCES classes(id)
);
INSERT INTO classes VALUES
  (1,'计算机一班'),(2,'软件一班');
INSERT INTO students VALUES
  (1,'小蔡',20,91.50,1),
  (2,'小林',21,88.00,1),
  (3,'小周',19,95.00,2),
  (4,'小陈',22,77.50,2);
SELECT * FROM students ORDER BY id;
