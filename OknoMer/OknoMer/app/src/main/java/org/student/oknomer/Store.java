package org.student.oknomer;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.json.*;

final class Store extends SQLiteOpenHelper {
    final File photos;
    Store(Context ctx){
        super(ctx,"oknomer.sqlite3",null,1); photos=new File(ctx.getFilesDir(),"photos");
        if(!photos.isDirectory()&&!photos.mkdirs())throw new IllegalStateException("Не удалось создать папку для фотографий.");
    }
    @Override public void onConfigure(SQLiteDatabase db){db.setForeignKeyConstraintsEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE objects(id TEXT PRIMARY KEY,name TEXT NOT NULL)");
        db.execSQL("CREATE TABLE measurements(id TEXT PRIMARY KEY,object_id TEXT NOT NULL REFERENCES objects(id),name TEXT NOT NULL,created TEXT NOT NULL,photo TEXT NOT NULL,payload TEXT NOT NULL)");
        db.execSQL("CREATE INDEX by_object ON measurements(object_id,created)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException();}
    static String key(){return UUID.randomUUID().toString();}
    static String checked(String name){
        if(name.trim().isEmpty())throw new IllegalArgumentException("Введите название.");
        return name.trim();
    }
    String add(String name){
        String id=key(); ContentValues v=new ContentValues();v.put("id",id);v.put("name",checked(name));getWritableDatabase().insertOrThrow("objects",null,v);return id;
    }
    String byName(String name){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM objects WHERE name=? ORDER BY rowid LIMIT 1",new String[]{name})){
            if(c.moveToFirst())return c.getString(0);
        }return add(name);
    }
    String objectName(String id){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT name FROM objects WHERE id=?",new String[]{id})){
            if(c.moveToFirst())return c.getString(0);
        }throw new IllegalArgumentException("Объект не найден.");
    }
    List<String[]> objects(){
        List<String[]> rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT o.id,o.name,count(m.id) FROM objects o LEFT JOIN measurements m ON o.id=m.object_id GROUP BY o.id ORDER BY o.rowid DESC",null)){
            while(c.moveToNext())rows.add(new String[]{c.getString(0),c.getString(1),c.getString(2)});
        }return rows;
    }
    String save(String object,String name,File source,JSONObject data) throws IOException{
        String id=key(),photo=id+".jpg"; ContentValues v=new ContentValues();
        v.put("id",id);v.put("name",checked(name));v.put("object_id",object);v.put("created",Instant.now().toString());v.put("photo",photo);v.put("payload",data.toString());
        File dest=new File(photos,photo);
        try{Files.copy(source.toPath(),dest.toPath());getWritableDatabase().insertOrThrow("measurements",null,v);}
        catch(IOException|RuntimeException e){dest.delete();throw e;}
        return id;
    }
    private JSONObject record(Cursor c)throws JSONException{
        JSONObject r=new JSONObject();
        for(String key:new String[]{"id","object_id","name","created","photo"})r.put(key,c.getString(c.getColumnIndexOrThrow(key)));
        return r.put("payload",new JSONObject(c.getString(c.getColumnIndexOrThrow("payload"))));
    }
    List<JSONObject> records(String object)throws JSONException{
        List<JSONObject> rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("measurements",null,"object_id=?",new String[]{object},null,null,"created DESC")){
            while(c.moveToNext())rows.add(record(c));
        }return rows;
    }
    JSONObject get(String id)throws JSONException{
        try(Cursor c=getReadableDatabase().query("measurements",null,"id=?",new String[]{id},null,null,null)){
            if(c.moveToFirst())return record(c);
        }throw new IllegalArgumentException("Замер не найден.");
    }
    void rename(String id,String name){
        ContentValues v=new ContentValues();v.put("name",checked(name));getWritableDatabase().update("measurements",v,"id=?",new String[]{id});
    }
    void delete(String id)throws JSONException{
        File f=new File(photos,get(id).getString("photo"));
        getWritableDatabase().delete("measurements","id=?",new String[]{id});f.delete();
    }
    private static String cell(String s){
        if(s.matches("(?s)^\\s*[=+@-].*"))s="'"+s;
        return "\""+s.replace("\"","\"\"")+"\"";
    }
    private static void entry(ZipOutputStream z,String name,String data)throws IOException{
        z.putNextEntry(new ZipEntry(name));z.write(data.getBytes(StandardCharsets.UTF_8));z.closeEntry();
    }
    void export(String object,OutputStream stream)throws IOException,JSONException{
        try(ZipOutputStream z=new ZipOutputStream(new BufferedOutputStream(stream))){
            String name=objectName(object); JSONArray rows=new JSONArray();
            StringBuilder csv=new StringBuilder("\ufeffОбъект;Название;Дата UTC;Ширина мм;Высота мм;Без коррекции ширина мм;Без коррекции высота мм;Контрольная ширина мм (рулетка);Контрольная высота мм (рулетка);Чувствительность %;Фото;Эталон;Ширина эталона мм;Высота эталона мм\r\n");
            for(JSONObject r:records(object)){
                JSONObject d=r.getJSONObject("payload").getJSONObject("result");String photo=r.getString("photo");
                Reference ref=Reference.fromId(d.optString("reference_type"));
                boolean horizontal=d.optBoolean("landscape");
                csv.append(cell(name)).append(';').append(cell(r.getString("name"))).append(';').append(r.getString("created")).append(';')
                  .append(d.getDouble("width_mm")).append(';').append(d.getDouble("height_mm")).append(';').append(d.getDouble("baseline_width_mm")).append(';')
                  .append(d.getDouble("baseline_height_mm")).append(";;;").append(d.getDouble("sensitivity_pct")).append(';').append(photo).append(';').append(ref.label).append(';').append(ref.width(horizontal)).append(';').append(ref.height(horizontal)).append("\r\n");
                rows.put(r);z.putNextEntry(new ZipEntry("photos/"+photo));Files.copy(new File(photos,photo).toPath(),z);z.closeEntry();
            }
            entry(z,"measurements.csv",csv.toString());entry(z,"measurements.json",rows.toString(2));
        }
    }
}
