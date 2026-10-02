package com.pocketmysql.practice;

import org.json.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/** Restricted fresh-schema grading; independent from the free laboratory data. */
final class LabGrader {
    static JSONArray script(MysqlWire wire,String text,boolean initialization) throws Exception {
        SqlScript parser=new SqlScript(text);JSONObject statement;JSONArray last=new JSONArray();
        while(true){String modes=OfflineEngineModes(wire);statement=parser.next(modes.contains("NO_BACKSLASH_ESCAPES"),modes.contains("ANSI_QUOTES"));if(statement==null)break;String sql=statement.getString("sql");if(initialization&&sql.replaceAll("(?s)^\\s*(?:(?:--[^\\n]*|#[^\\n]*)\\n|/\\*.*?\\*/\\s*)*","").matches("(?is)^\\s*(CREATE\\s+DATABASE\\b|USE\\b).*"))continue;last=wire.query(sql,5000);}
        return last;
    }
    static String OfflineEngineModes(MysqlWire wire) throws Exception{return wire.query("SELECT @@SESSION.sql_mode AS v",1).getJSONObject(0).getJSONArray("rows").getJSONObject(0).getString("v");}
    static JSONObject lastTable(JSONArray results) throws Exception {for(int i=results.length()-1;i>=0;i--){JSONObject result=results.getJSONObject(i);if(result.optJSONArray("fields")!=null&&result.getJSONArray("fields").length()>0)return result;}throw new IOException("答案没有返回可比较的查询结果");}
    static List<String> canonical(JSONObject result,boolean ordered) throws Exception {
        if(result.optBoolean("truncated"))throw new IOException("判题结果超过 5000 行或 4 MB，请缩小题目数据集");
        JSONArray fields=result.getJSONArray("fields"),types=result.getJSONArray("fieldTypes"),rows=result.getJSONArray("rows");List<String> output=new ArrayList<>();
        for(int i=0;i<rows.length();i++){JSONArray values=new JSONArray();JSONObject row=rows.getJSONObject(i);for(int j=0;j<fields.length();j++){Object value=row.get(fields.getString(j));if(value==JSONObject.NULL){values.put(JSONObject.NULL);continue;}String text=value.toString();int type=types.getInt(j);boolean number=Arrays.asList(0,1,2,3,4,5,8,9,13,246).contains(type);if(number)try{text=new BigDecimal(text).stripTrailingZeros().toPlainString();}catch(NumberFormatException ignored){}values.put((number?"number:":"text:")+text);}output.add(values.toString());}
        if(!ordered)Collections.sort(output);return output;
    }
    static JSONObject grade(LabRuntime runtime,String dataset,String sql,String answer,String checker,boolean ordered) throws Exception {
        synchronized(runtime){
            String id="g"+UUID.randomUUID().toString().replace("-","").substring(0,16);String secret=UUID.randomUUID().toString().replace("-","");
            String[] names={id+"s",id+"r"};
            try(MysqlWire root=runtime.admin()){
                try {
                    for(String name:names){root.query("CREATE DATABASE `"+name+"` CHARACTER SET utf8mb4",1);root.query("CREATE USER '"+name+"'@'localhost' IDENTIFIED WITH mysql_native_password BY '"+secret+"'",1);root.query("GRANT ALL ON `"+name+"`.* TO '"+name+"'@'localhost'",1);}
                    JSONObject expected,actual;
                    try(MysqlWire student=new MysqlWire(runtime.socketPath(),names[0],secret);MysqlWire teacher=new MysqlWire(runtime.socketPath(),names[1],secret)){
                        student.query("USE `"+names[0]+"`",1);teacher.query("USE `"+names[1]+"`",1);student.query("SET NAMES utf8mb4",1);teacher.query("SET NAMES utf8mb4",1);
                        script(student,dataset,true);script(teacher,dataset,true);
                        JSONArray solution=script(teacher,answer,false),submission;
                        try{submission=script(student,sql,false);}catch(MysqlWire.SqlError error){return new JSONObject().put("passed",false).put("message",error.getMessage()).put("errorCode",error.code).put("sqlState",error.sqlState);}
                        if(checker!=null&&!checker.trim().isEmpty()){
                            // Match the legacy teaching contract: final committed state.
                            teacher.query("COMMIT",1);student.query("COMMIT",1);
                            teacher.query("START TRANSACTION READ ONLY",1);student.query("START TRANSACTION READ ONLY",1);
                            expected=lastTable(script(teacher,checker,false));actual=lastTable(script(student,checker,false));
                        }else{expected=lastTable(solution);actual=lastTable(submission);}
                        boolean passed=expected.getJSONArray("fields").length()==actual.getJSONArray("fields").length()&&canonical(expected,ordered).equals(canonical(actual,ordered));
                        return new JSONObject().put("passed",passed).put("message",passed?"结果正确":"结果与参考答案不一致，请检查列、数据及排序").put("actual",actual);
                    }
                }finally{for(String name:names){try{root.query("DROP DATABASE IF EXISTS `"+name+"`",1);}catch(Exception ignored){}try{root.query("DROP USER IF EXISTS '"+name+"'@'localhost'",1);}catch(Exception ignored){}}}
            }
        }
    }
}
