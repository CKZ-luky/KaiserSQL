-- 使用默认本机管理员，在 school_lab 已建立后运行
CREATE USER IF NOT EXISTS 'lab_reader'@'localhost'
  IDENTIFIED BY 'Lab_reader_2026!';
GRANT SELECT ON school_lab.* TO 'lab_reader'@'localhost';
SHOW GRANTS FOR 'lab_reader'@'localhost';
