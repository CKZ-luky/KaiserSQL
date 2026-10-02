-- 独立的真实 MySQL 练习库；首次启动导入，不修改电脑原有数据库。
CREATE DATABASE IF NOT EXISTS sql_practice CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE sql_practice;

CREATE TABLE customers (
  id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(40) NOT NULL,
  city VARCHAR(40) NOT NULL,
  joined_at DATE NOT NULL
);
CREATE TABLE products (
  id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(80) NOT NULL,
  category VARCHAR(40) NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  stock INT NOT NULL DEFAULT 0
);
CREATE TABLE orders (
  id INT PRIMARY KEY AUTO_INCREMENT,
  customer_id INT NOT NULL,
  order_date DATE NOT NULL,
  status ENUM('待付款','已付款','已发货','已完成','已取消') NOT NULL,
  CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
);
CREATE TABLE order_items (
  id INT PRIMARY KEY AUTO_INCREMENT,
  order_id INT NOT NULL,
  product_id INT NOT NULL,
  quantity INT NOT NULL,
  unit_price DECIMAL(10,2) NOT NULL,
  CONSTRAINT fk_order FOREIGN KEY (order_id) REFERENCES orders(id),
  CONSTRAINT fk_product FOREIGN KEY (product_id) REFERENCES products(id)
);

INSERT INTO customers (name,city,joined_at) VALUES
('林晨','上海','2026-01-08'),('陈雨','杭州','2026-01-15'),
('周宁','北京','2026-02-02'),('许安','深圳','2026-02-10'),
('沈月','上海','2026-03-01'),('苏航','成都','2026-03-18'),
('叶青','杭州','2026-04-12'),('江远','北京','2026-05-03'),
('顾岚','广州','2026-05-16'),('唐可','南京','2026-06-04'),
('陆川','武汉','2026-07-20'),('何夏','苏州','2026-08-11');

INSERT INTO products (name,category,price,stock) VALUES
('机械键盘','数码',329.00,42),('无线鼠标','数码',129.00,86),
('降噪耳机','数码',599.00,24),('帆布背包','生活',189.00,65),
('保温杯','生活',89.00,120),('桌面台灯','生活',159.00,38),
('SQL 入门手册','图书',59.00,150),('数据库设计实战','图书',79.00,72);

INSERT INTO orders (customer_id,order_date,status) VALUES
(1,'2026-09-01','已完成'),(2,'2026-09-02','已完成'),
(3,'2026-09-03','已完成'),(1,'2026-09-05','已完成'),
(4,'2026-09-06','已完成'),(5,'2026-09-08','已完成'),
(6,'2026-09-10','已完成'),(2,'2026-09-12','已完成'),
(7,'2026-09-14','已完成'),(8,'2026-09-15','已发货'),
(9,'2026-09-16','已发货'),(3,'2026-09-18','已发货'),
(10,'2026-09-20','已付款'),(4,'2026-09-21','已付款'),
(5,'2026-09-22','已付款'),(6,'2026-09-24','待付款'),
(7,'2026-09-25','待付款'),(8,'2026-09-26','已取消'),
(9,'2026-09-28','已付款'),(1,'2026-09-30','已付款');

INSERT INTO order_items (order_id,product_id,quantity,unit_price) VALUES
(1,1,1,329),(1,2,1,129),(2,4,1,189),(2,5,2,89),
(3,3,1,599),(3,7,1,59),(4,7,2,59),(4,8,1,79),
(5,1,1,329),(5,6,1,159),(6,4,1,189),(6,7,1,59),
(7,5,2,89),(7,8,1,79),(8,3,1,599),(8,2,1,129),
(9,6,1,159),(9,7,1,59),(10,1,1,329),(10,4,1,189),
(11,2,2,129),(11,5,1,89),(12,3,1,599),(12,8,1,79),
(13,7,3,59),(13,8,2,79),(14,1,1,329),(14,2,1,129),
(15,4,2,189),(15,6,1,159),(16,5,1,89),(16,7,1,59),
(17,3,1,599),(17,2,1,129),(18,4,1,189),(18,8,1,79),
(19,6,1,159),(19,5,2,89),(20,1,1,329),(20,3,1,599);

