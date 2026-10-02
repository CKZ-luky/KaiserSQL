package com.pocketmysql.practice;

import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import androidx.core.content.FileProvider;

@CapacitorPlugin(name="OfflineLab")
public class OfflineLabPlugin extends Plugin {
    LabRuntime runtime;
    final ExecutorService work=Executors.newCachedThreadPool();
    interface Task { JSONObject run() throws Exception; }
    @Override public void load(){runtime=LabRuntime.shared(getContext());}
    void task(PluginCall call,Task task){work.execute(()->{try{call.resolve(JSObject.fromJSONObject(task.run()));}catch(Exception error){call.reject(error.getMessage()==null?error.toString():error.getMessage(),error);}});}
    void progress(String text){JSObject value=new JSObject();value.put("message",text);notifyListeners("progress",value);}
    @PluginMethod public void projects(PluginCall call){task(call,()->new JSONObject().put("projects",runtime.list()));}
    @PluginMethod public void createProject(PluginCall call){task(call,()->runtime.create(call.getString("name")));}
    @PluginMethod public void renameProject(PluginCall call){task(call,()->{runtime.rename(call.getString("id"),call.getString("name"));return new JSONObject().put("ok",true);});}
    @PluginMethod public void deleteProject(PluginCall call){task(call,()->{runtime.delete(call.getString("id"));return new JSONObject().put("ok",true);});}
    @PluginMethod public void boot(PluginCall call){task(call,()->runtime.boot(call.getString("id"),this::progress));}
    @PluginMethod public void connect(PluginCall call){task(call,()->{String username=call.getString("user","root");String pass=call.getString("password",null);if(pass==null&&username.equals("root"))pass=runtime.password();return runtime.open(call.getString("session","A"),username,pass==null?"":pass,call.getString("database",""));});}
    @PluginMethod public void cancel(PluginCall call){task(call,()->{runtime.cancel(call.getString("session","A"));return new JSONObject().put("ok",true);});}
    @PluginMethod public void stop(PluginCall call){task(call,()->{runtime.stop();return new JSONObject().put("ok",true);});}
    @PluginMethod public void hideKeyboard(PluginCall call){getActivity().runOnUiThread(()->{android.view.inputmethod.InputMethodManager input=(android.view.inputmethod.InputMethodManager)getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);if(input!=null)input.hideSoftInputFromWindow(getBridge().getWebView().getWindowToken(),0);call.resolve();});}
    @PluginMethod public void startupAppearance(PluginCall call){getActivity().runOnUiThread(()->{int color=call.getBoolean("startup",false)?0xfffffdf4:0xff102f29;getActivity().getWindow().getDecorView().setBackgroundColor(color);getBridge().getWebView().setBackgroundColor(color);if(android.os.Build.VERSION.SDK_INT<35){getActivity().getWindow().setStatusBarColor(color);getActivity().getWindow().setNavigationBarColor(color);}call.resolve();});}
    @PluginMethod public void logs(PluginCall call){task(call,()->{File dir=runtime.project(call.getString("id"));return new JSONObject().put("log",LabRuntime.tail(new File(dir,"mysql.log"))+"\n"+LabRuntime.tail(new File(dir,"engine-output.log")));});}
    @PluginMethod public void execute(PluginCall call){task(call,()->{
        String name=call.getString("session","A");MysqlWire wire=runtime.session(name);
        synchronized(wire){if(wire.busy)throw new IOException("本会话已有 SQL 正在运行");wire.busy=true;wire.cancelRequested=false;}
        try {
            String script=call.getString("sql",null);JSONArray input=call.getArray("statements");
            if(script==null&&(input==null||input.length()==0))throw new IOException("请先输入 SQL");
            SqlScript parser=script==null?null:new SqlScript(script);
            int limit=Math.max(1,Math.min(5000,call.getInt("limit",200)));
            JSONArray results=new JSONArray();boolean failed=false;long began=System.currentTimeMillis();
            for(int i=0;;i++){
                if(wire.cancelRequested)break;
                JSONObject statement;
                if(parser!=null){String modes=wire.query("SELECT @@SESSION.sql_mode AS modes",1).getJSONObject(0).getJSONArray("rows").getJSONObject(0).getString("modes");try{statement=parser.next(modes.contains("NO_BACKSLASH_ESCAPES"),modes.contains("ANSI_QUOTES"));}catch(IOException error){results.put(new JSONObject().put("sql",script).put("startLine",parser.line).put("error",error.getMessage()).put("errorCode","CLIENT_DELIMITER").put("sqlState","CLIENT"));failed=true;break;}if(statement==null)break;}
                else{if(i>=input.length())break;statement=input.getJSONObject(i);}
                if(wire.cancelRequested)break;
                String sql=statement.getString("sql");long start=System.currentTimeMillis();
                try{JSONArray parts=wire.query(sql,limit);for(int j=0;j<parts.length();j++){JSONObject part=parts.getJSONObject(j);part.put("sql",sql).put("startLine",statement.optInt("startLine",1)).put("executionTime",System.currentTimeMillis()-start);results.put(part);}}
                catch(MysqlWire.SqlError error){results.put(new JSONObject().put("sql",sql).put("startLine",statement.optInt("startLine",1)).put("error",error.getMessage()).put("errorCode",error.code).put("sqlState",error.sqlState));failed=true;break;}
            }
            return new JSONObject().put("statements",results).put("hasError",failed).put("cancelled",wire.cancelRequested).put("executionTime",System.currentTimeMillis()-began).put("session",runtime.describe(name));
        }catch(MysqlWire.SqlError error){throw error;}catch(Exception error){try{runtime.cancel(name);}catch(Exception ignored){}synchronized(runtime){if(runtime.sessions.get(name)==wire){runtime.sessions.remove(name);wire.close();}}throw new IOException("会话连接中断，未提交事务将回滚。请重新连接。"+error.getMessage(),error);}
        finally{wire.busy=false;}
    });}
    @PluginMethod public void schema(PluginCall call){task(call,()->{
        try(MysqlWire root=runtime.admin()){
            JSONArray parts=root.query("SELECT TABLE_SCHEMA AS db, TABLE_NAME AS name, TABLE_TYPE AS kind FROM information_schema.tables WHERE TABLE_SCHEMA NOT IN ('mysql','sys','performance_schema','information_schema') ORDER BY TABLE_SCHEMA,TABLE_NAME",5000);
            JSONArray databases=root.query("SHOW DATABASES",5000).getJSONObject(0).getJSONArray("rows");
            return new JSONObject().put("tables",parts.getJSONObject(0).getJSONArray("rows")).put("databases",databases);
        }
    });}
    @PluginMethod public void importFile(PluginCall call){task(call,()->{
        File dir=runtime.project(runtime.activeId);String name=call.getString("name","data.csv").replaceAll("[^\\p{L}\\p{N}._-]","_");if(name.length()>100)name=name.substring(name.length()-100);name=UUID.randomUUID().toString().substring(0,8)+"-"+name;
        String encoded=call.getString("data","");if(encoded.length()>28*1024*1024)throw new IOException("文件超过导入上限");byte[] bytes=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT);File file=new File(dir,"exchange/"+name);try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}return new JSONObject().put("path","/lab/exchange/"+name);
    });}
    @PluginMethod public void grade(PluginCall call){task(call,()->LabGrader.grade(runtime,call.getString("dataset",""),call.getString("sql",""),call.getString("answer",""),call.getString("checker",""),call.getBoolean("ordered",false)));}
    @PluginMethod public void dump(PluginCall call){task(call,()->{
        synchronized(runtime){
            File dir=runtime.project(call.getString("id"));if(!dir.getName().equals(runtime.activeId))throw new IOException("请先打开要导出的项目");
            File output=runtime.dump();
            return new JSONObject().put("uri",FileProvider.getUriForFile(getContext(),getContext().getPackageName()+".fileprovider",output).toString());
        }
    });}
    @Override protected void handleOnDestroy(){work.shutdown();}
}
