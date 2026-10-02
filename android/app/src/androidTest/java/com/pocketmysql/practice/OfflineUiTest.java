package com.pocketmysql.practice;

import android.content.Context;
import android.graphics.Bitmap;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.junit.Assert.*;

/** App-owned WebView integration test: visible controls -> native engine -> result. */
@RunWith(AndroidJUnit4.class)
public class OfflineUiTest {
    WebView view;
    String js(String code) throws Exception {CompletableFuture<String> future=new CompletableFuture<>();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->view.evaluateJavascript(code,future::complete));return future.get(10,TimeUnit.SECONDS);}
    void until(String predicate) throws Exception {long deadline=System.currentTimeMillis()+90000;while(System.currentTimeMillis()<deadline){if("true".equals(js(predicate)))return;Thread.sleep(100);}throw new AssertionError("Timed out waiting for UI: "+predicate+"; status="+js("document.body.innerText"));}
    void run(String sql) throws Exception {until("!document.querySelector('#run').disabled");js("document.querySelector('#editor').value="+JSONObject.quote(sql)+";document.querySelector('#editor').dispatchEvent(new Event('input'));document.querySelector('#run').click();true");until("!document.querySelector('#run').disabled && document.querySelector('#duration').textContent!==''");}
    void screenshot(Context context,String name) throws Exception {InstrumentationRegistry.getInstrumentation().runOnMainSync(()->view.invalidate());InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(1200);Bitmap screen=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),name))){screen.compress(Bitmap.CompressFormat.PNG,100,out);}screen.recycle();}
    @Test public void visibleOfflineWorkspace() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();JSONObject report=new JSONObject();JSONArray checks=new JSONArray();
        try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)) {
            activity.onActivity(a->view=a.getBridge().getWebView());
            until("document.querySelector('#startup') && !document.querySelector('#startup').hidden && document.querySelector('#workspace')?.inert===true && document.querySelector('#startup-skip') && !document.querySelector('#startup-skip').disabled");
            js("document.querySelector('#startup-skip').click();true");until("document.querySelector('#startup').hidden===true && document.querySelector('#workspace').inert===false");checks.put("Startup can be skipped manually to enter the workspace");
            until("document.querySelector('#run') && !document.querySelector('#run').disabled && document.querySelector('#startup')?.hidden===true && document.querySelector('#workspace')?.inert===false");checks.put("Offline UI boots native engine without login or server address");
            until("document.querySelector('#startup-art')?.complete && document.querySelector('#startup-art').naturalWidth>0");checks.put("Bundled startup illustration loads without a network");
            assertEquals("true",js("document.querySelector('#work #account')===null && document.querySelector('#tools #account')!==null && document.querySelector('input[type=\"email\"],input[type=\"password\"]')===null && !document.querySelector('#dialog').open"));checks.put("Workspace opens without account entry or login fields; permissions are kept in tools");
            js("document.querySelector('[data-page=\"tools\"]').click();document.querySelector('#about').click();true");until("document.querySelector('#dialog').open && document.querySelector('#dialog').textContent.includes('KaiserSQL')");checks.put("About tool opens the KaiserSQL brand introduction");
            until("document.querySelector('#dialog .about-art img')?.complete && document.querySelector('#dialog .about-art img').naturalWidth>0 && document.querySelector('#dialog .about-art img').src===document.querySelector('#about-brand')?.src");checks.put("Bundled KaiserSQL brand image loads in the about dialog");
            js("document.querySelector('#dialog .dialog-head button').click();true");until("!document.querySelector('#dialog').open");
            js("document.querySelector('#account').click();true");until("document.querySelector('#dialog').open && document.querySelector('#dialog').textContent.includes('权限实验') && document.querySelector('#dialog').textContent.includes('App') && document.querySelector('#dialog').textContent.includes('无需登录') && document.querySelector('#dialog #user-input')!==null && document.querySelector('#dialog #password-input')?.type==='password'");checks.put("Database permissions form appears only when explicitly opened and explains no App login is needed");
            js("document.querySelector('#dialog .dialog-head button').click();true");until("!document.querySelector('#dialog').open");js("document.querySelector('[data-page=\"work\"]').click();true");
            run("CREATE DATABASE IF NOT EXISTS ui_lab CHARACTER SET utf8mb4; USE ui_lab; DROP TABLE IF EXISTS notes; CREATE TABLE notes(id INT PRIMARY KEY AUTO_INCREMENT,content VARCHAR(80)); INSERT INTO notes(content) VALUES('手机离线结果'),('练习事务与存储过程'),('我的数据库，我做主'); SELECT content FROM notes ORDER BY id;");
            until("document.querySelector('#results').textContent.includes('手机离线结果')");checks.put("Editor runs arbitrary DDL/DML and renders real results");
            run("SELECT missing_column FROM notes;");until("document.querySelector('#results').textContent.includes('1054')");checks.put("Visible MySQL error code and message");
            js("document.querySelector('#editor').value=\"SELECT SLEEP(10); INSERT INTO notes(content) VALUES('不应执行');\";document.querySelector('#editor').dispatchEvent(new Event('input'));document.querySelector('#run').click();true");until("document.querySelector('#run').disabled && !document.querySelector('#cancel').hidden");Thread.sleep(300);js("document.querySelector('#cancel').click();true");until("!document.querySelector('#run').disabled && document.querySelector('#duration').textContent.includes('已取消')");run("SELECT COUNT(*) AS cancelled_writes FROM notes WHERE content='不应执行';");until("document.querySelector('#results td').textContent==='0'");checks.put("Cancel UI stops remaining script even when SLEEP returns normally");
            run("START TRANSACTION; INSERT INTO notes(content) VALUES('未提交');");until("document.querySelector('#transaction').textContent.includes('事务未提交')");
            js("document.querySelector('[data-session=\"B\"]').click();true");run("SELECT COUNT(*) AS rows_visible FROM ui_lab.notes;");assertFalse(js("document.querySelector('#transaction').textContent").contains("事务未提交"));
            js("document.querySelector('[data-session=\"A\"]').click();true");until("document.querySelector('#transaction').textContent.includes('事务未提交')");run("ROLLBACK; SELECT content FROM notes ORDER BY id;");checks.put("A/B UI keeps independent connection and transaction state");
            js("document.querySelector('[data-page=\"schema\"]').click();true");until("document.querySelector('#schema-list').textContent.includes('ui_lab')");checks.put("Schema browser shows custom database");
            js("document.querySelector('[data-page=\"work\"]').click();document.querySelector('#new-tab').click();true");js("document.querySelector('#editor').value='-- 我的离线草稿';document.querySelector('#editor').dispatchEvent(new Event('input'));true");until("document.querySelector('#draft').textContent.includes('已保存在手机')");
            activity.recreate();activity.onActivity(a->view=a.getBridge().getWebView());until("document.querySelector('#run') && !document.querySelector('#run').disabled && document.querySelector('#startup')?.hidden===true && document.querySelector('#workspace')?.inert===false");assertTrue(js("document.querySelector('#editor').value").contains("我的离线草稿"));checks.put("Activity recreation restores project and script draft");
            run("USE ui_lab;\n\nSELECT id, content AS 记录\nFROM notes\nORDER BY id;");until("document.querySelector('#results').textContent.includes('手机离线结果')");
            js("document.querySelector('[data-page=\"practice\"]').click();true");until("document.querySelector('#banks').textContent.includes('电商 SQL')");js("document.querySelector('[data-bank]').click();true");until("document.querySelector('#lesson-list').textContent.includes('认识你的第一张表')");checks.put("Bundled question bank available offline");
            String fixture;ByteArrayOutputStream fixtureBytes=new ByteArrayOutputStream();try(ZipInputStream in=new ZipInputStream(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("fixture-bank.zip"));ZipOutputStream out=new ZipOutputStream(fixtureBytes)){ZipEntry entry;while((entry=in.getNextEntry())!=null){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);byte[] data=bytes.toByteArray();if(entry.getName().equals("bank.json")){JSONObject manifest=new JSONObject(new String(data,java.nio.charset.StandardCharsets.UTF_8));manifest.put("version",String.valueOf(System.nanoTime()));data=manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);}out.putNextEntry(new ZipEntry(entry.getName()));out.write(data);out.closeEntry();}}fixture=android.util.Base64.encodeToString(fixtureBytes.toByteArray(),android.util.Base64.NO_WRAP);
            js("const bankBytes=Uint8Array.from(atob("+JSONObject.quote(fixture)+"),c=>c.charCodeAt(0));const fileList=new DataTransfer();fileList.items.add(new File([bankBytes],'题库测试.zip',{type:'application/zip'}));document.querySelector('#bank-file').files=fileList.files;document.querySelector('#bank-file').dispatchEvent(new Event('change'));true");
            until("document.querySelector('#save-bank')!==null");js("document.querySelector('#save-bank').click();true");until("!document.querySelector('#dialog').open && document.querySelector('#banks').textContent.includes('我的第一份 SQL 题库')");checks.put("Excel/ZIP question bank imports and validates all reference SQL offline");
            js("document.querySelector('[data-page=\"work\"]').click();document.querySelector('#editor').scrollTop=0;window.scrollTo(0,0);true");until("document.querySelector('#toast').hidden");Thread.sleep(400);
            screenshot(context,"offline-ui-preview.png");js("document.querySelector('#results').scrollIntoView({block:'end'});true");screenshot(context,"offline-ui-results-preview.png");
            js("document.querySelector('[data-page=\"tools\"]').click();window.scrollTo(0,0);true");until("document.querySelector('#tools').classList.contains('active')");screenshot(context,"offline-ui-tools-preview.png");
            js("document.querySelector('[data-page=\"practice\"]').click();window.scrollTo(0,0);true");screenshot(context,"offline-ui-practice-preview.png");
            report.put("passed",true).put("checks",checks).put("androidApi",android.os.Build.VERSION.SDK_INT).put("abi",android.os.Build.SUPPORTED_ABIS[0]).put("physicalDeviceTested",false);
        }catch(Throwable error){report.put("passed",false).put("checks",checks).put("error",error.toString());throw error;}
        finally{try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),"offline-ui-test.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}LabRuntime.shared(context).stop();}
    }
}
