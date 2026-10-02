package com.pocketmysql.practice;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OfflineEngineTest {
    @Test public void offlineMysqlLaboratory() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        LabRuntime lab=new LabRuntime(context);JSONArray checks=new JSONArray();JSONObject report=new JSONObject();long start=System.currentTimeMillis();
        String id=null,otherId=null;
        try{
            assertFalse(Arrays.asList(context.getPackageManager().getPackageInfo(context.getPackageName(),android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions).contains("android.permission.INTERNET"));checks.put("APK has no INTERNET permission");
            JSONObject project=lab.create("原生离线验证");id=project.getString("id");
            lab.boot(id,message->android.util.Log.i("OfflineEngineTest",message));
            MysqlWire a=lab.session("A"),b=lab.session("B");
            assertTrue(value(a,"SELECT VERSION() AS v","v").startsWith("8.0.45"));checks.put("Real MySQL 8.0.45 started inside APK");
            run(a,"CREATE DATABASE 独立实验 CHARACTER SET utf8mb4; USE 独立实验; CREATE TABLE notes(id INT PRIMARY KEY AUTO_INCREMENT, content VARCHAR(80), price DECIMAL(10,2), payload JSON); INSERT INTO notes(content,price,payload) VALUES ('中文离线数据',12.30,JSON_OBJECT('n',1));");
            assertEquals("中文离线数据",value(a,"SELECT content AS v FROM notes WHERE id=1","v"));checks.put("Custom database, DDL, Chinese data, DECIMAL and JSON");
            run(b,"USE 独立实验; SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;");run(a,"START TRANSACTION; INSERT INTO notes(content) VALUES ('未提交');");
            assertTrue((a.status&1)!=0);assertEquals("1",value(b,"SELECT COUNT(*) AS v FROM notes","v"));run(a,"COMMIT;");assertEquals("2",value(b,"SELECT COUNT(*) AS v FROM notes","v"));checks.put("Persistent A/B sessions and cross-click transaction visibility");
            run(a,"START TRANSACTION; UPDATE notes SET content='回滚值' WHERE id=1;");run(a,"ROLLBACK;");assertEquals("中文离线数据",value(a,"SELECT content AS v FROM notes WHERE id=1","v"));checks.put("Rollback preserves committed data");
            run(a,"-- comment before delimiter\nDELIMITER $$\nCREATE PROCEDURE loop_test(IN n INT) BEGIN DECLARE i INT DEFAULT 0; WHILE i<n DO SET i=i+1; END WHILE; SELECT i AS v; END$$\nDELIMITER ;\n");assertEquals("5",a.query("CALL loop_test(5)",10).getJSONObject(0).getJSONArray("rows").getJSONObject(0).getString("v"));checks.put("DELIMITER, procedure, parameters, variables and loop");
            run(a,"DELIMITER //\nCREATE FUNCTION twice(n INT) RETURNS INT DETERMINISTIC BEGIN RETURN n*2; END//\nDELIMITER ;\n");assertEquals("8",value(a,"SELECT twice(4) AS v","v"));checks.put("Stored function");
            run(a,"CREATE TABLE audit_log(v VARCHAR(80)); CREATE TRIGGER log_insert AFTER INSERT ON notes FOR EACH ROW INSERT INTO audit_log VALUES(NEW.content); INSERT INTO notes(content) VALUES ('触发器');");assertEquals("触发器",value(a,"SELECT v FROM audit_log","v"));checks.put("Trigger runs inside engine");
            run(a,"CREATE VIEW names AS SELECT id,content FROM notes; CREATE INDEX ix_content ON notes(content); SELECT ROW_NUMBER() OVER(ORDER BY id) FROM names;");checks.put("View, index and window query");
            run(a,"CREATE USER 'reader'@'localhost' IDENTIFIED WITH mysql_native_password BY 'local-test'; GRANT SELECT ON 独立实验.* TO 'reader'@'localhost'; CREATE ROLE 'reading'; GRANT SELECT ON 独立实验.* TO 'reading'; GRANT 'reading' TO 'reader'@'localhost';");
            try(MysqlWire reader=new MysqlWire(lab.socketPath(),"reader","local-test")){assertEquals("3",value(reader,"SELECT COUNT(*) AS v FROM 独立实验.notes","v"));try{reader.query("DELETE FROM 独立实验.notes",5);fail("Privilege check did not reject DELETE");}catch(MysqlWire.SqlError denied){assertEquals(1142,denied.code);}}checks.put("Real local database users, GRANT, roles and denial");
            try{a.query("SELECT missing_column FROM notes",5);fail("Invalid column accepted");}catch(MysqlWire.SqlError error){assertEquals(1054,error.code);assertEquals("42S22",error.sqlState);}checks.put("Real MySQL error code and SQLSTATE");
            JSONArray duplicates=a.query("SELECT 1 AS x,2 AS x,NULL AS z",10).getJSONObject(0).getJSONArray("rows");assertEquals("1",duplicates.getJSONObject(0).getString("x"));assertEquals("2",duplicates.getJSONObject(0).getString("x (2)"));assertTrue(duplicates.getJSONObject(0).isNull("z"));checks.put("Duplicate column names and NULL preserved");
            run(a,"SET SESSION sql_mode='NO_BACKSLASH_ESCAPES'; SELECT 'ends\\' AS v; SET SESSION sql_mode='STRICT_TRANS_TABLES'; SELECT 'it\\'s' AS v;");checks.put("Script scanning follows session NO_BACKSLASH_ESCAPES changes");
            run(a,"DELIMITER $$\nCREATE PROCEDURE cursor_test() BEGIN DECLARE total INT DEFAULT 0; DECLARE n INT; DECLARE done BOOL DEFAULT FALSE; DECLARE cur CURSOR FOR SELECT id FROM notes; DECLARE CONTINUE HANDLER FOR NOT FOUND SET done=TRUE; OPEN cur; read_loop: LOOP FETCH cur INTO n; IF done THEN LEAVE read_loop; END IF; SET total=total+n; END LOOP; CLOSE cur; SELECT total AS v; END$$\nDELIMITER ;\n");assertEquals("6",a.query("CALL cursor_test()",10).getJSONObject(0).getJSONArray("rows").getJSONObject(0).getString("v"));checks.put("Cursor and NOT FOUND handler");
            String[] levels={"READ UNCOMMITTED","READ COMMITTED","REPEATABLE READ","SERIALIZABLE"};for(String level:levels){run(b,"SET SESSION TRANSACTION ISOLATION LEVEL "+level+";");assertEquals(level.replace(' ','-'),value(b,"SELECT @@SESSION.transaction_isolation AS v","v"));}run(b,"SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;");checks.put("All four isolation levels");
            run(a,"SET SESSION sql_mode='ANSI_QUOTES'; SELECT 7 AS \"v\\\"; SET SESSION sql_mode='STRICT_TRANS_TABLES'; SELECT 'normal' AS v;");checks.put("ANSI_QUOTES identifier scanning follows MySQL mode");
            ExecutorService execution=Executors.newSingleThreadExecutor();try{Future<Integer> waiting=execution.submit(()->{try{a.query("SELECT 1 FROM notes WHERE SLEEP(10)",1);return 0;}catch(MysqlWire.SqlError error){return error.code;}});Thread.sleep(250);lab.cancel("A");assertEquals(Integer.valueOf(1317),waiting.get(5,TimeUnit.SECONDS));assertEquals("1",value(a,"SELECT 1 AS v","v"));}finally{execution.shutdownNow();}checks.put("Cancel running SQL and reuse connection");
            run(a,"CREATE EVENT event_test ON SCHEDULE AT CURRENT_TIMESTAMP + INTERVAL 2 SECOND DO INSERT INTO audit_log VALUES ('定时事件');");long eventDeadline=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<eventDeadline&&!value(a,"SELECT COUNT(*) AS v FROM audit_log WHERE v='定时事件'","v").equals("1"))Thread.sleep(200);assertEquals("1",value(a,"SELECT COUNT(*) AS v FROM audit_log WHERE v='定时事件'","v"));checks.put("Real event scheduler while engine is running");
            String dataset="CREATE TABLE t(v INT); INSERT INTO t VALUES(1),(2);";
            assertTrue(LabGrader.grade(lab,dataset,"SELECT COUNT(*) FROM t","SELECT COUNT(*) FROM t","",false).getBoolean("passed"));assertFalse(LabGrader.grade(lab,dataset,"SELECT SUM(v) FROM t","SELECT COUNT(*) FROM t","",false).getBoolean("passed"));assertTrue(LabGrader.grade(lab,dataset,"UPDATE t SET v=v+2","UPDATE t SET v=v+2","SELECT v FROM t ORDER BY v",true).getBoolean("passed"));assertFalse(LabGrader.grade(lab,dataset,"SELECT * FROM 独立实验.notes","SELECT * FROM t","",false).getBoolean("passed"));assertEquals("3",value(a,"SELECT COUNT(*) AS v FROM notes","v"));checks.put("Fresh offline query/DML grading, denial and free-data isolation");
            File backup=lab.dump();String dumpText;try(InputStream in=new FileInputStream(backup)){dumpText=LabRuntime.readText(in);}assertTrue(dumpText.contains("loop_test"));assertTrue(dumpText.contains("CREATE DATABASE"));checks.put("Bundled mysqldump exports data and stored programs");
            otherId=lab.create("隔离与恢复验证").getString("id");lab.boot(otherId,message->{});assertEquals("0",value(lab.session("A"),"SELECT COUNT(*) AS v FROM information_schema.schemata WHERE SCHEMA_NAME='独立实验'","v"));run(lab.session("A"),dumpText);assertEquals("3",value(lab.session("A"),"SELECT COUNT(*) AS v FROM 独立实验.notes","v"));run(lab.session("A"),"USE 独立实验;");assertEquals("5",lab.session("A").query("CALL loop_test(5)",10).getJSONObject(0).getJSONArray("rows").getJSONObject(0).getString("v"));checks.put("Independent project plus SQL backup restoration");backup.delete();
            lab.stop();lab.boot(id,message->android.util.Log.i("OfflineEngineTest",message));assertEquals("3",value(lab.session("A"),"SELECT COUNT(*) AS v FROM 独立实验.notes","v"));checks.put("Engine restart restores committed database");
            report.put("passed",true).put("checks",checks).put("durationMs",System.currentTimeMillis()-start).put("engine","MySQL 8.0.45").put("abi",android.os.Build.SUPPORTED_ABIS[0]).put("androidApi",android.os.Build.VERSION.SDK_INT).put("physicalDeviceTested",false);
        }catch(Throwable error){report.put("passed",false).put("checks",checks).put("error",error.toString());throw error;}
        finally{try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),"offline-engine-test.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}try{lab.stop();}finally{if(id!=null)lab.delete(id);if(otherId!=null)lab.delete(otherId);}}
    }
    static String value(MysqlWire wire,String sql,String name) throws Exception{return wire.query(sql,10).getJSONObject(0).getJSONArray("rows").getJSONObject(0).getString(name);}
    static void run(MysqlWire wire,String text) throws Exception{SqlScript parser=new SqlScript(text);JSONObject statement;while(true){String modes=value(wire,"SELECT @@SESSION.sql_mode AS v","v");statement=parser.next(modes.contains("NO_BACKSLASH_ESCAPES"),modes.contains("ANSI_QUOTES"));if(statement==null)break;wire.query(statement.getString("sql"),100);}}
}
