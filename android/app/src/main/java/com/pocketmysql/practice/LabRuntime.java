package com.pocketmysql.practice;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Owns one embedded MySQL process. Each project has its own complete datadir. */
final class LabRuntime {
    private static LabRuntime shared;
    static synchronized LabRuntime shared(Context context){if(shared==null)shared=new LabRuntime(context.getApplicationContext());return shared;}
    interface Progress { void update(String message); }
    final Context context;
    final File home, runtime, projects;
    final Map<String,MysqlWire> sessions = new HashMap<>();
    String activeId;
    JSONObject activeConfig;
    Process process;
    LabRuntime(Context context) {this.context=context.getApplicationContext();home=new File(context.getFilesDir(),"lab");runtime=new File(home,"runtime");projects=new File(home,"projects");projects.mkdirs();}
    static String readText(InputStream in) throws IOException {ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))>=0)bytes.write(buffer,0,n);return bytes.toString("UTF-8");}
    static JSONObject readJson(File file) throws Exception {try(InputStream in=new FileInputStream(file)){return new JSONObject(readText(in));}}
    static void writeJson(File file,JSONObject value) throws Exception {File temporary=new File(file.getParentFile(),file.getName()+".tmp");try(FileOutputStream out=new FileOutputStream(temporary)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}if(!temporary.renameTo(file))throw new IOException("Unable to save project metadata");}
    File project(String id) throws IOException {if(id==null||!id.matches("[a-f0-9]{16}"))throw new IOException("Invalid project ID");return new File(projects,id);}
    synchronized JSONArray list() throws Exception {JSONArray result=new JSONArray();File[] files=projects.listFiles();if(files==null)return result;Arrays.sort(files,Comparator.comparing(File::getName));for(File dir:files){File config=new File(dir,"project.json");if(config.isFile()){JSONObject value=readJson(config);result.put(new JSONObject().put("id",value.getString("id")).put("name",value.getString("name")).put("created",value.optLong("created")).put("active",dir.getName().equals(activeId)));}}return result;}
    synchronized JSONObject create(String name) throws Exception {if(name==null||name.trim().isEmpty()||name.length()>80)throw new IOException("项目名称需要 1 至 80 个字符");String id=UUID.randomUUID().toString().replace("-","").substring(0,16);File dir=project(id);if(!dir.mkdirs())throw new IOException("Unable to create project");byte[] secret=new byte[24];new SecureRandom().nextBytes(secret);StringBuilder password=new StringBuilder();for(byte b:secret)password.append(String.format(Locale.ROOT,"%02x",b&255));JSONObject value=new JSONObject().put("id",id).put("name",name.trim()).put("created",System.currentTimeMillis()).put("rootPassword",password.toString()).put("passwordReady",false);writeJson(new File(dir,"project.json"),value);return new JSONObject().put("id",id).put("name",name.trim());}
    synchronized void rename(String id,String name) throws Exception {if(name==null||name.trim().isEmpty()||name.length()>80)throw new IOException("项目名称需要 1 至 80 个字符");File file=new File(project(id),"project.json");JSONObject value=readJson(file);value.put("name",name.trim());writeJson(file,value);if(id.equals(activeId))activeConfig=value;}
    synchronized void delete(String id) throws Exception {if(id.equals(activeId))stop();removeTree(project(id),projects);}
    static void removeTree(File file,File boundary) throws Exception {String target=file.getCanonicalPath(),root=boundary.getCanonicalPath()+File.separator;if(!target.startsWith(root))throw new IOException("Refusing to delete outside lab directory");if(!file.exists())return;File[] children=file.listFiles();if(children!=null)for(File child:children){if(java.nio.file.Files.isSymbolicLink(child.toPath())){if(!child.delete())throw new IOException("Cannot remove link");}else removeTree(child,boundary);}if(!file.delete())throw new IOException("Cannot remove "+file.getName());}
    void install(Progress progress) throws Exception {
        JSONObject info;try(InputStream in=context.getAssets().open("offline-engine/runtime-info.json")){info=new JSONObject(readText(in));}
        File ready=new File(runtime,".ready");if(ready.isFile()&&readJson(ready).optString("runtimeSha256").equals(info.getString("runtimeSha256")))return;
        progress.update("首次使用：解压手机内置的 MySQL 运行时…");
        File stage=new File(home,"runtime-stage");if(stage.exists())removeTree(stage,home);stage.mkdirs();
        boolean supported=Arrays.asList(Build.SUPPORTED_ABIS).contains(info.getString("abi"));if(!supported)throw new IOException("安装包架构与手机不匹配："+info.getString("abi"));
        MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=context.getAssets().open("offline-engine/mysql-runtime.zip")){byte[] block=new byte[65536];int n;while((n=in.read(block))>=0)hash.update(block,0,n);}
        StringBuilder checksum=new StringBuilder();for(byte b:hash.digest())checksum.append(String.format(Locale.ROOT,"%02x",b&255));if(!checksum.toString().equals(info.getString("runtimeSha256")))throw new IOException("MySQL 运行时校验失败");
        JSONObject manifest=null;
        try(ZipInputStream zip=new ZipInputStream(context.getAssets().open("offline-engine/mysql-runtime.zip"))){ZipEntry entry;byte[] block=new byte[65536];while((entry=zip.getNextEntry())!=null){String name=entry.getName();if(name.equals("runtime-manifest.json")){manifest=new JSONObject(readText(zip));continue;}File target=new File(stage,name);if(name.startsWith("/")||!target.getCanonicalPath().startsWith(stage.getCanonicalPath()+File.separator))throw new IOException("Invalid runtime path");target.getParentFile().mkdirs();try(OutputStream out=new FileOutputStream(target)){int n;while((n=zip.read(block))>=0)out.write(block,0,n);}zip.closeEntry();}}
        if(manifest==null)throw new IOException("Missing runtime manifest");JSONArray entries=manifest.getJSONArray("entries");
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);File file=new File(stage,entry.getString("path"));if(entry.getString("type").equals("dir")){file.mkdirs();Os.chmod(file.getAbsolutePath(),0700|(entry.getInt("mode")&0777));}else if(entry.getString("type").equals("file")){Os.chmod(file.getAbsolutePath(),0600|(entry.getInt("mode")&0777));}}
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);if(entry.getString("type").equals("symlink")){File file=new File(stage,entry.getString("path"));file.getParentFile().mkdirs();Os.symlink(entry.getString("target"),file.getAbsolutePath());}}
        new File(stage,"tmp").mkdirs();new File(stage,"lab").mkdirs();writeJson(new File(stage,".ready"),info);
        if(runtime.exists())removeTree(runtime,home);if(!stage.renameTo(runtime))throw new IOException("Cannot install runtime");
    }
    ProcessBuilder builder(File dir,String executable,List<String> arguments) throws Exception {
        String nativeDir=context.getApplicationInfo().nativeLibraryDir;
        List<String> command=new ArrayList<>(Arrays.asList(nativeDir+"/libproot.so","--kill-on-exit","-0","-r",runtime.getAbsolutePath(),"-b","/dev","-b","/proc","-b",dir.getAbsolutePath()+":/lab","-w","/lab",executable));command.addAll(arguments);
        ProcessBuilder builder=new ProcessBuilder(command);Map<String,String> env=builder.environment();env.clear();env.put("PATH","/usr/sbin:/usr/bin:/sbin:/bin");env.put("LD_LIBRARY_PATH",nativeDir);env.put("PROOT_LOADER",nativeDir+"/libproot_loader.so");env.put("PROOT_TMP_DIR",new File(dir,"tmp").getAbsolutePath());env.put("TMPDIR","/lab/tmp");env.put("LANG","C.UTF-8");env.put("LC_ALL","C.UTF-8");return builder;
    }
    Process launch(File dir,List<String> arguments,File log) throws Exception {return builder(dir,"/usr/sbin/mysqld",arguments).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start();}
    synchronized JSONObject boot(String id,Progress progress) throws Exception {
        if(id.equals(activeId)&&process!=null&&process.isAlive())return information();
        for(MysqlWire session:sessions.values())if(session.busy)throw new IOException("请先结束正在运行的 SQL，再切换项目");
        stop();install(progress);File dir=project(id);activeConfig=readJson(new File(dir,"project.json"));activeId=id;
        new File(dir,"tmp").mkdirs();new File(dir,"exchange").mkdirs();File output=new File(dir,"engine-output.log");File data=new File(dir,"data");
        try {
            if(!data.isDirectory()) {
                progress.update("初始化独立实验数据库，首次打开需要稍等…");
                File initial=new File(dir,"init-data");if(initial.exists())removeTree(initial,dir);initial.mkdirs();
                Process setup=launch(dir,Arrays.asList("--no-defaults","--initialize-insecure","--user=root","--basedir=/usr","--datadir=/lab/init-data","--innodb-buffer-pool-size=32M","--innodb-use-native-aio=0","--performance-schema=OFF"),output);
                if(!setup.waitFor(180,TimeUnit.SECONDS)){setup.destroyForcibly();throw new IOException("MySQL 初始化超时，请查看引擎日志");}
                if(setup.exitValue()!=0)throw new IOException("MySQL 初始化失败："+tail(output));
                if(!initial.renameTo(data))throw new IOException("Cannot commit initialized database");
            }
            new File(dir,"mysql.sock").delete();
            progress.update("启动手机内的 MySQL 8.0.45…");
            process=launch(dir,Arrays.asList("--no-defaults","--user=root","--basedir=/usr","--datadir=/lab/data","--socket=/lab/mysql.sock","--pid-file=/lab/mysql.pid","--log-error=/lab/mysql.log","--skip-networking","--mysqlx=OFF","--ssl=OFF","--skip-log-bin","--performance-schema=OFF","--innodb-buffer-pool-size=32M","--innodb-use-native-aio=0","--innodb-flush-method=fsync","--innodb-redo-log-capacity=16M","--max-connections=12","--table-open-cache=128","--thread-cache-size=2","--event-scheduler=ON","--secure-file-priv=/lab/exchange","--tmpdir=/lab/tmp","--max-allowed-packet=16M"),output);
            long deadline=System.currentTimeMillis()+90000;Exception last=null;MysqlWire root=null;
            while(System.currentTimeMillis()<deadline) {
                if(!process.isAlive())throw new IOException("MySQL 启动失败："+tail(new File(dir,"mysql.log"))+" "+tail(output));
                try {root=new MysqlWire(socketPath(),"root",activeConfig.optBoolean("passwordReady")?password():"");break;}catch(Exception error){last=error;
                    if(!activeConfig.optBoolean("passwordReady"))try{root=new MysqlWire(socketPath(),"root",password());break;}catch(Exception ignored){}
                    Thread.sleep(200);
                }
            }
            if(root==null)throw new IOException("MySQL 启动超时："+(last==null?"":last.getMessage())+" "+tail(new File(dir,"mysql.log")));
            try {if(!activeConfig.optBoolean("passwordReady")){root.query("ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY '"+password()+"'",1);activeConfig.put("passwordReady",true);writeJson(new File(dir,"project.json"),activeConfig);}}finally{root.close();}
            open("A","root",password(),"");open("B","root",password(),"");
            progress.update("离线 MySQL 已就绪");return information();
        }catch(Exception error){stop();throw error;}
    }
    static String tail(File file) {try{if(!file.isFile())return "";try(RandomAccessFile in=new RandomAccessFile(file,"r")){in.seek(Math.max(0,in.length()-4000));byte[] bytes=new byte[(int)(in.length()-in.getFilePointer())];in.readFully(bytes);return new String(bytes,StandardCharsets.UTF_8);}}catch(Exception ignored){return "";}}
    String socketPath() throws Exception {return new File(project(activeId),"mysql.sock").getAbsolutePath();}
    String password() throws Exception {return activeConfig.getString("rootPassword");}
    synchronized MysqlWire session(String name) throws Exception {MysqlWire wire=sessions.get(name);if(wire==null)throw new IOException("会话已关闭，请重新连接");return wire;}
    synchronized JSONObject open(String name,String user,String pass,String database) throws Exception {if(!Arrays.asList("A","B").contains(name))throw new IOException("Invalid session name");MysqlWire old=sessions.get(name);if(old!=null&&old.busy)throw new IOException("会话正在执行");MysqlWire wire=new MysqlWire(socketPath(),user,pass);try{wire.query("SET NAMES utf8mb4",1);if(database!=null&&!database.isEmpty())wire.query("USE `"+database.replace("`","``")+"`",1);}catch(Exception error){wire.close();throw error;}if(old!=null)old.close();sessions.put(name,wire);return describe(name);}
    synchronized JSONObject describe(String name) throws Exception {MysqlWire wire=session(name);return new JSONObject().put("name",name).put("connectionId",wire.connectionId).put("inTransaction",(wire.status&1)!=0).put("autocommit",(wire.status&2)!=0);}
    synchronized JSONObject information() throws Exception {JSONObject result=new JSONObject().put("projectId",activeId).put("projectName",activeConfig.getString("name")).put("engine","MySQL Community Server").put("version","8.0.45").put("offline",true);JSONArray list=new JSONArray();for(String name:sessions.keySet())list.put(describe(name));return result.put("sessions",list);}
    synchronized File dump() throws Exception {
        File dir=project(activeId);List<String> names=new ArrayList<>();
        try(MysqlWire root=admin()){JSONArray rows=root.query("SHOW DATABASES",5000).getJSONObject(0).getJSONArray("rows");for(int i=0;i<rows.length();i++){String name=rows.getJSONObject(i).getString("Database");if(!Arrays.asList("mysql","sys","performance_schema","information_schema").contains(name))names.add(name);}}
        if(names.isEmpty())throw new IOException("项目中还没有用户数据库");
        File config=new File(dir,"client.cnf");try(FileOutputStream out=new FileOutputStream(config)){out.write(("[client]\nuser=root\npassword="+password()+"\nsocket=/lab/mysql.sock\ndefault-character-set=utf8mb4\n").getBytes(StandardCharsets.UTF_8));}
        List<String> arguments=new ArrayList<>(Arrays.asList("--defaults-extra-file=/lab/client.cnf","--single-transaction","--routines","--events","--triggers","--set-gtid-purged=OFF","--no-tablespaces","--databases"));arguments.addAll(names);
        File output=new File(context.getCacheDir(),"mysql-lab-"+System.currentTimeMillis()+".sql"),log=new File(dir,"dump.log");
        try{Process child=builder(dir,"/usr/bin/mysqldump",arguments).redirectOutput(output).redirectError(log).start();if(!child.waitFor(120,TimeUnit.SECONDS)){child.destroyForcibly();throw new IOException("备份超时，请稍后重试");}if(child.exitValue()!=0)throw new IOException("备份失败："+tail(log));}finally{config.delete();}
        return output;
    }
    MysqlWire admin() throws Exception {return new MysqlWire(socketPath(),"root",password());}
    void cancel(String name) throws Exception {MysqlWire wire=session(name);wire.cancelRequested=true;try(MysqlWire root=admin()){root.query("KILL QUERY "+wire.connectionId,1);}}
    synchronized void stop() throws Exception {
        for(MysqlWire wire:sessions.values())wire.close();sessions.clear();
        if(process!=null&&process.isAlive()){try(MysqlWire root=admin()){root.query("SHUTDOWN",1);}catch(Exception ignored){}if(!process.waitFor(12,TimeUnit.SECONDS)){process.destroy();if(!process.waitFor(3,TimeUnit.SECONDS))process.destroyForcibly();}}
        process=null;activeId=null;activeConfig=null;
    }
}
