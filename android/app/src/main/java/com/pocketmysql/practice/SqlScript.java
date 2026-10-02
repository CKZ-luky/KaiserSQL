package com.pocketmysql.practice;

import java.io.IOException;
import org.json.JSONObject;

/** Client delimiter scanner; MySQL itself parses and validates each SQL body. */
final class SqlScript {
    final String text;
    int at=0,line=1;
    String delimiter=";";
    SqlScript(String text){this.text=text;}
    JSONObject next(boolean noBackslashEscapes) throws Exception {
        return next(noBackslashEscapes,false);
    }
    JSONObject next(boolean noBackslashEscapes,boolean ansiQuotes) throws Exception {
        int start=at,startLine=line;
        while(at<text.length()) {
            if(at==0||text.charAt(at-1)=='\n') {
                int end=text.indexOf('\n',at);if(end<0)end=text.length();
                String command=text.substring(at,end).trim();
                if(command.matches("(?i)^DELIMITER(?:\\s.*)?$")) {
                    if(!commentsOnly(text.substring(start,at)))throw new IOException("第 "+line+" 行：更改 DELIMITER 前请结束上一条 SQL");
                    String[] words=command.split("\\s+");if(words.length!=2||words[1].length()>32)throw new IOException("第 "+line+" 行：DELIMITER 后需要一个不含空格的定界符");
                    delimiter=words[1];at=end;if(at<text.length()){at++;line++;}start=at;startLine=line;continue;
                }
            }
            if(text.startsWith(delimiter,at)) {
                String raw=text.substring(start,at);at+=delimiter.length();
                if(!commentsOnly(raw))return statement(raw,startLine);
                start=at;startLine=line;continue;
            }
            char c=text.charAt(at);
            if(c=='\''||c=='"'||c=='`') {
                char quote=c;at++;
                while(at<text.length()) {
                    char v=text.charAt(at++);if(v=='\n')line++;
                    if(v=='\\'&&quote!='`'&&!(quote=='"'&&ansiQuotes)&&!noBackslashEscapes&&at<text.length()){if(text.charAt(at)=='\n')line++;at++;continue;}
                    if(v==quote){if(at<text.length()&&text.charAt(at)==quote){at++;continue;}break;}
                }
                continue;
            }
            if(c=='#'||(text.startsWith("--",at)&&(at+2==text.length()||text.charAt(at+2)<=32))) {
                int end=text.indexOf('\n',at);if(end<0){at=text.length();break;}at=end+1;line++;continue;
            }
            if(text.startsWith("/*",at)) {
                int end=text.indexOf("*/",at+2);end=end<0?text.length():end+2;
                for(int i=at;i<end;i++)if(text.charAt(i)=='\n')line++;at=end;continue;
            }
            if(c=='\n')line++;at++;
        }
        String raw=text.substring(start,at);return commentsOnly(raw)?null:statement(raw,startLine);
    }
    static JSONObject statement(String raw,int line) throws Exception {int leading=0;while(leading<raw.length()&&Character.isWhitespace(raw.charAt(leading))){if(raw.charAt(leading)=='\n')line++;leading++;}return new JSONObject().put("sql",raw.trim()).put("startLine",line);}
    static boolean commentsOnly(String text) {
        int i=0;while(i<text.length()){char c=text.charAt(i);if(Character.isWhitespace(c)){i++;continue;}
            if(c=='#'||(text.startsWith("--",i)&&(i+2==text.length()||text.charAt(i+2)<=32))){int end=text.indexOf('\n',i);i=end<0?text.length():end+1;continue;}
            if(text.startsWith("/*",i)&&!text.startsWith("/*!",i)&&!text.startsWith("/*+",i)){int end=text.indexOf("*/",i+2);if(end<0)return false;i=end+2;continue;}
            return false;
        }return true;
    }
}
