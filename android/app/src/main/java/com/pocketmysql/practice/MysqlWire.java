package com.pocketmysql.practice;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** MySQL text protocol over an app-private Unix socket. No remote connection. */
final class MysqlWire implements Closeable {
    static final int MORE_RESULTS = 8;
    final LocalSocket socket = new LocalSocket();
    InputStream in;
    OutputStream out;
    int sequence, status, warnings;
    long connectionId;
    String version;
    volatile boolean busy;
    volatile boolean cancelRequested;

    static final class SqlError extends IOException {
        final int code;
        final String sqlState;
        SqlError(int code, String state, String message) { super(message); this.code=code; sqlState=state; }
    }
    static final class Cursor {
        final byte[] data; int at;
        Cursor(byte[] data) { this.data=data; }
        int one() throws IOException { if(at>=data.length)throw new IOException("Incomplete MySQL packet"); return data[at++]&255; }
        long number(int n) throws IOException { long result=0; for(int i=0;i<n;i++)result|=(long)one()<<(i*8); return result; }
        byte[] bytes(int count) throws IOException { if(count<0||count>data.length-at)throw new IOException("Invalid MySQL packet length"); byte[] result=Arrays.copyOfRange(data,at,at+count);at+=count;return result; }
        byte[] zero() throws IOException { int begin=at;while(at<data.length&&data[at]!=0)at++;byte[] result=Arrays.copyOfRange(data,begin,at);if(at<data.length)at++;return result; }
        long length() throws IOException { int lead=one();if(lead<251)return lead;if(lead==251)return -1;if(lead==252)return number(2);if(lead==253)return number(3);if(lead==254)return number(8);throw new IOException("Invalid length-encoded value"); }
        byte[] value() throws IOException { long length=length();if(length<0)return null;if(length>Integer.MAX_VALUE)throw new IOException("Value too large");return bytes((int)length); }
        String string() throws IOException { byte[] value=value();return value==null?null:new String(value,StandardCharsets.UTF_8); }
    }
    static final class Column { String name; int type, charset; }

    MysqlWire(String path, String username, String password) throws Exception {
        try {
            socket.connect(new LocalSocketAddress(path,LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(60000);
            in=socket.getInputStream();out=socket.getOutputStream();
            Cursor greeting=new Cursor(read());
            if(greeting.one()!=10)throw new IOException("Unsupported MySQL handshake");
            version=new String(greeting.zero(),StandardCharsets.UTF_8);
            connectionId=greeting.number(4);
            byte[] salt1=greeting.bytes(8);greeting.one();
            long low=greeting.number(2);greeting.one();status=(int)greeting.number(2);
            long flags=low|(greeting.number(2)<<16);
            int saltLength=greeting.one();greeting.bytes(10);
            byte[] salt2=greeting.bytes(Math.min(greeting.data.length-greeting.at,Math.max(13,saltLength-8)));
            ByteArrayOutputStream seed=new ByteArrayOutputStream();seed.write(salt1);
            seed.write(salt2,0,salt2.length>0&&salt2[salt2.length-1]==0?salt2.length-1:salt2.length);
            String plugin=greeting.at<greeting.data.length?new String(greeting.zero(),StandardCharsets.UTF_8):"mysql_native_password";
            int clientFlags=(1|4|512|8192|32768|131072|524288)&(int)flags;
            ByteArrayOutputStream reply=new ByteArrayOutputStream();integer(reply,clientFlags,4);integer(reply,16*1024*1024,4);reply.write(45);reply.write(new byte[23]);zero(reply,username);
            byte[] auth=scramble(plugin,password,seed.toByteArray());reply.write(auth.length);reply.write(auth);zero(reply,plugin);send(reply.toByteArray());
            for(int attempt=0;attempt<5;attempt++) {
                byte[] response=read();int lead=response[0]&255;
                if(lead==255)throw error(response);
                if(lead==0){ok(response);return;}
                if(lead==254){Cursor change=new Cursor(response);change.one();plugin=new String(change.zero(),StandardCharsets.UTF_8);byte[] salt=change.bytes(change.data.length-change.at);if(salt.length>0&&salt[salt.length-1]==0)salt=Arrays.copyOf(salt,salt.length-1);send(scramble(plugin,password,salt));continue;}
                if(lead==1&&response.length>1&&response[1]==3)continue;
                // Unix sockets are trusted local transports for caching_sha2_password.
                if(lead==1&&response.length>1&&response[1]==4){ByteArrayOutputStream clear=new ByteArrayOutputStream();zero(clear,password);send(clear.toByteArray());continue;}
                throw new IOException("Unsupported MySQL authentication: "+plugin);
            }
            throw new IOException("MySQL authentication did not complete");
        } catch(Exception error) { close();throw error; }
    }
    static byte[] scramble(String plugin,String password,byte[] salt) throws Exception {
        if(password.isEmpty())return new byte[0];
        String algorithm=plugin.equals("mysql_native_password")?"SHA-1":plugin.equals("caching_sha2_password")?"SHA-256":null;
        if(algorithm==null)throw new IOException("Unsupported authentication plugin: "+plugin);
        MessageDigest digest=MessageDigest.getInstance(algorithm);
        byte[] first=digest.digest(password.getBytes(StandardCharsets.UTF_8)),second=digest.digest(first);
        if(algorithm.equals("SHA-1")){digest.update(salt);digest.update(second);}else{digest.update(second);digest.update(salt);}
        byte[] mix=digest.digest();for(int i=0;i<first.length;i++)first[i]^=mix[i];return first;
    }
    static void integer(OutputStream output,long value,int size) throws IOException { for(int i=0;i<size;i++)output.write((int)(value>>>(8*i))&255); }
    static void zero(OutputStream output,String value) throws IOException {output.write(value.getBytes(StandardCharsets.UTF_8));output.write(0);}
    byte[] read() throws IOException {
        ByteArrayOutputStream packet=new ByteArrayOutputStream();int size;
        do {
            byte[] head=exact(4);size=(head[0]&255)|((head[1]&255)<<8)|((head[2]&255)<<16);sequence=(head[3]&255)+1;
            if(packet.size()+size>64*1024*1024)throw new IOException("A single MySQL value exceeds 64 MB");
            packet.write(exact(size));
        }while(size==0xFFFFFF);
        if(packet.size()==0)throw new IOException("Empty MySQL packet");return packet.toByteArray();
    }
    byte[] exact(int size) throws IOException {byte[] result=new byte[size];int at=0;while(at<size){int n=in.read(result,at,size-at);if(n<0)throw new EOFException("MySQL connection closed");at+=n;}return result;}
    void send(byte[] packet) throws IOException {int offset=0;while(true){int size=Math.min(0xFFFFFF,packet.length-offset);integer(out,size,3);out.write(sequence++&255);out.write(packet,offset,size);offset+=size;if(size<0xFFFFFF)break;}out.flush();}
    static SqlError error(byte[] packet) throws IOException {Cursor c=new Cursor(packet);c.one();int code=(int)c.number(2);String state="HY000";if(c.at<c.data.length&&c.data[c.at]=='#'){c.one();state=new String(c.bytes(5),StandardCharsets.US_ASCII);}return new SqlError(code,state,new String(c.bytes(c.data.length-c.at),StandardCharsets.UTF_8));}
    long ok(byte[] packet) throws IOException {Cursor c=new Cursor(packet);c.one();long affected=c.length();c.length();status=(int)c.number(2);warnings=(int)c.number(2);return affected;}
    void eof(byte[] packet) throws IOException {Cursor c=new Cursor(packet);c.one();warnings=(int)c.number(2);status=(int)c.number(2);}
    JSONArray query(String sql,int limit) throws Exception {
        sequence=0;ByteArrayOutputStream request=new ByteArrayOutputStream();request.write(3);request.write(sql.getBytes(StandardCharsets.UTF_8));send(request.toByteArray());
        JSONArray results=new JSONArray();
        do {
            byte[] packet=read();int lead=packet[0]&255;
            if(lead==255)throw error(packet);
            if(lead==251)throw new IOException("LOAD DATA LOCAL INFILE requires importing a file through the app first; direct local file requests are disabled");
            JSONObject result=new JSONObject();
            if(lead==0){result.put("affectedRows",ok(packet));result.put("fields",new JSONArray());result.put("rows",new JSONArray());result.put("totalRows",0);}
            else {
                long count=new Cursor(packet).length();if(count<1||count>4096)throw new IOException("Invalid MySQL column count");
                List<Column> columns=new ArrayList<>();JSONArray names=new JSONArray(),types=new JSONArray();Set<String> used=new HashSet<>();
                for(int i=0;i<count;i++) {
                    byte[] definition=read();if((definition[0]&255)==255)throw error(definition);Cursor c=new Cursor(definition);c.string();c.string();c.string();c.string();String name=c.string();c.string();c.length();
                    Column field=new Column();field.charset=(int)c.number(2);c.number(4);field.type=c.one();String unique=name;for(int suffix=2;used.contains(unique);suffix++)unique=name+" ("+suffix+")";used.add(unique);field.name=unique;columns.add(field);names.put(unique);types.put(field.type);
                }
                byte[] terminator=read();if((terminator[0]&255)!=254)throw new IOException("Missing MySQL column terminator");eof(terminator);
                JSONArray rows=new JSONArray();long total=0;int storedBytes=0;boolean truncated=false;
                while(true) {
                    packet=read();if((packet[0]&255)==255)throw error(packet);
                    if((packet[0]&255)==254&&packet.length<9){eof(packet);break;}
                    total++;
                    if(rows.length()>=limit||storedBytes>4*1024*1024){truncated=true;continue;}
                    Cursor c=new Cursor(packet);JSONObject row=new JSONObject();
                    for(Column field:columns){byte[] value=c.value();Object v=JSONObject.NULL;if(value!=null){storedBytes+=value.length;
                        boolean hex=field.type==16||(field.charset==63&&(field.type>=249&&field.type<=254));
                        if(hex){StringBuilder text=new StringBuilder("0x");for(byte b:value)text.append(String.format(Locale.ROOT,"%02X",b&255));v=text.toString();}
                        else v=new String(value,StandardCharsets.UTF_8);
                    }row.put(field.name,v);}rows.put(row);
                }
                result.put("fields",names);result.put("fieldTypes",types);result.put("rows",rows);result.put("totalRows",total);result.put("truncated",truncated);result.put("affectedRows",0);
            }
            result.put("warnings",warnings);result.put("inTransaction",(status&1)!=0);result.put("autocommit",(status&2)!=0);results.put(result);
        }while((status&MORE_RESULTS)!=0);
        return results;
    }
    @Override public void close(){try{socket.close();}catch(Exception ignored){}}
}
