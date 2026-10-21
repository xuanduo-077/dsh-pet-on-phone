package com.whale.pet;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** 聊天记录持久化：SharedPreferences 里的 JSON 数组（role: u=用户 w=鲸宝），上限 200 条，新的在尾部。 */
public final class WhaleChat {
    private static final String KEY="chat-log";private static final int MAX=200;
    public static JSONArray read(SharedPreferences p){try{return p.getString(KEY,"[]")!=null?new JSONArray(p.getString(KEY,"[]")):new JSONArray();}catch(Exception e){return new JSONArray();}}
    public static synchronized void append(SharedPreferences p,String role,String text){
        if(text==null||text.trim().isEmpty())return;
        try{JSONArray a=read(p);JSONObject o=new JSONObject();o.put("r",role);o.put("t",text.trim());o.put("at",System.currentTimeMillis());a.put(o);
        while(a.length()>MAX){JSONArray n=new JSONArray();for(int i=a.length()-MAX;i<a.length();i++)n.put(a.get(i));a=n;}
        p.edit().putString(KEY,a.toString()).apply();}catch(Exception ignored){}
    }
    public static void clear(SharedPreferences p){p.edit().remove(KEY).apply();}
}
