package com.anyoz.faceattendance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Private, offline SQLite storage. Session membership is a historical snapshot. */
public final class AttendanceStore extends SQLiteOpenHelper {
    public AttendanceStore(Context c){this(c,"attendance.db");}
    AttendanceStore(Context c,String name){super(c,name,null,1);setWriteAheadLoggingEnabled(true);}
    @Override public void onConfigure(SQLiteDatabase db){db.setForeignKeyConstraintsEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE groups(name TEXT PRIMARY KEY)");
        db.execSQL("INSERT INTO groups VALUES ('Основная')");
        db.execSQL("CREATE TABLE people(id TEXT PRIMARY KEY,name TEXT NOT NULL,group_name TEXT NOT NULL REFERENCES groups(name),archived INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE templates(person_id TEXT NOT NULL REFERENCES people(id),model TEXT NOT NULL,embedding BLOB NOT NULL)");
        db.execSQL("CREATE INDEX templates_person ON templates(person_id,model)");
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,name TEXT NOT NULL,opened_at INTEGER NOT NULL,closed_at INTEGER)");
        db.execSQL("CREATE UNIQUE INDEX one_active_session ON sessions((1)) WHERE closed_at IS NULL");
        db.execSQL("CREATE TABLE session_groups(session_id TEXT REFERENCES sessions(id),group_name TEXT NOT NULL,PRIMARY KEY(session_id,group_name))");
        db.execSQL("CREATE TABLE attendance(session_id TEXT REFERENCES sessions(id),person_id TEXT REFERENCES people(id),name TEXT NOT NULL,group_name TEXT NOT NULL,present_at INTEGER,method TEXT,auto_blocked INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(session_id,person_id))");
        db.execSQL("CREATE TABLE audit(at INTEGER,session_id TEXT,person_id TEXT,action TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int old,int current){throw new IllegalStateException("Неподдерживаемая версия базы");}
    public static String id(){return UUID.randomUUID().toString().replace("-","");}
    public static final class Person {
        public String id,name,group;public int samples;
    }
    public static final class Session {
        public String id,name; public long opened,closed;public int total,present;public List<String> groups=new ArrayList<>();
    }
    public static final class Entry {public String id,name,group,method;public long present;public boolean blocked;}
    public List<String> groups(){List<String> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT name FROM groups ORDER BY name",null)){while(c.moveToNext())out.add(c.getString(0));}return out;}
    public void addGroup(String name){name=clean(name,80);ContentValues v=new ContentValues();v.put("name",name);if(getWritableDatabase().insertWithOnConflict("groups",null,v,SQLiteDatabase.CONFLICT_IGNORE)<0)throw new IllegalArgumentException("Группа уже существует");}
    public List<Person> people(){List<Person> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT p.id,p.name,p.group_name,(SELECT count(*) FROM templates t WHERE t.person_id=p.id AND t.model=?) FROM people p WHERE p.archived=0 ORDER BY p.group_name,p.name",new String[]{Recognition.MODEL})){while(c.moveToNext()){Person p=new Person();p.id=c.getString(0);p.name=c.getString(1);p.group=c.getString(2);p.samples=c.getInt(3);out.add(p);}}return out;}
    public List<Recognition.Profile> profiles(){
        Map<String,List<float[]>> vectors=new HashMap<>();Map<String,String[]> names=new HashMap<>();List<Recognition.Profile> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT p.id,p.name,p.group_name,t.embedding FROM templates t JOIN people p ON p.id=t.person_id WHERE t.model=? AND (p.archived=0 OR EXISTS(SELECT 1 FROM attendance a JOIN sessions s ON s.id=a.session_id WHERE a.person_id=p.id AND s.closed_at IS NULL))",new String[]{Recognition.MODEL})){
            while(c.moveToNext()){String id=c.getString(0);byte[] bytes=c.getBlob(3);if(bytes.length!=Recognition.DIM*4)continue;float[] v=new float[Recognition.DIM];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v);try{v=Recognition.normalized(v);}catch(IllegalArgumentException ignored){continue;}
                if(!vectors.containsKey(id))vectors.put(id,new ArrayList<>());vectors.get(id).add(v);names.put(id,new String[]{c.getString(1),c.getString(2)});
            }
        }for(String id:vectors.keySet()){String[] n=names.get(id);out.add(new Recognition.Profile(id,n[0],n[1],vectors.get(id)));}return out;
    }
    public String enroll(String existing,String name,String group,List<float[]> vectors){
        name=clean(name,100);group=clean(group,80);if(vectors.size()!=6)throw new IllegalArgumentException("Нужно шесть кадров");
        for(int i=0;i<6;i++){Recognition.normalized(vectors.get(i));for(int j=0;j<i;j++)if(Recognition.dot(vectors.get(i),vectors.get(j))<.55f)throw new IllegalArgumentException("Лицо изменилось — повторите съёмку");}
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();String id=existing==null?id():existing;
        try{db.execSQL("INSERT OR IGNORE INTO groups VALUES (?)",new Object[]{group});
            if(existing==null){try(Cursor c=db.rawQuery("SELECT 1 FROM people WHERE archived=0 AND group_name=? AND name=? COLLATE NOCASE",new String[]{group,name})){if(c.moveToFirst())throw new IllegalArgumentException("Человек с таким именем уже есть");}
                db.execSQL("INSERT INTO people VALUES (?,?,?,0)",new Object[]{id,name,group});
            }else{try(Cursor c=db.rawQuery("SELECT 1 FROM people WHERE id=? AND archived=0",new String[]{id})){if(!c.moveToFirst())throw new IllegalArgumentException("Человек больше недоступен");}db.delete("templates","person_id=? AND model=?",new String[]{id,Recognition.MODEL});}
            for(float[] vector:vectors){ByteBuffer b=ByteBuffer.allocate(Recognition.DIM*4).order(ByteOrder.LITTLE_ENDIAN);b.asFloatBuffer().put(vector);ContentValues v=new ContentValues();v.put("person_id",id);v.put("model",Recognition.MODEL);v.put("embedding",b.array());db.insertOrThrow("templates",null,v);}
            db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}
    }
    public void archive(String id){getWritableDatabase().execSQL("UPDATE people SET archived=1 WHERE id=?",new Object[]{id});}
    public void move(String id,String group){getWritableDatabase().execSQL("UPDATE people SET group_name=? WHERE id=?",new Object[]{group,id});}
    public List<Session> sessions(){List<Session> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT s.id,s.name,s.opened_at,s.closed_at,count(a.person_id),count(a.present_at) FROM sessions s LEFT JOIN attendance a ON a.session_id=s.id GROUP BY s.id ORDER BY s.opened_at DESC",null)){while(c.moveToNext()){Session s=new Session();s.id=c.getString(0);s.name=c.getString(1);s.opened=c.getLong(2);s.closed=c.isNull(3)?0:c.getLong(3);s.total=c.getInt(4);s.present=c.getInt(5);try(Cursor g=getReadableDatabase().rawQuery("SELECT group_name FROM session_groups WHERE session_id=? ORDER BY group_name",new String[]{s.id})){while(g.moveToNext())s.groups.add(g.getString(0));}out.add(s);}}return out;}
    public Session active(){try(Cursor c=getReadableDatabase().rawQuery("SELECT s.id,s.name,s.opened_at,count(a.person_id),count(a.present_at) FROM sessions s LEFT JOIN attendance a ON a.session_id=s.id WHERE s.closed_at IS NULL GROUP BY s.id",null)){if(!c.moveToFirst())return null;Session s=new Session();s.id=c.getString(0);s.name=c.getString(1);s.opened=c.getLong(2);s.total=c.getInt(3);s.present=c.getInt(4);try(Cursor g=getReadableDatabase().rawQuery("SELECT group_name FROM session_groups WHERE session_id=? ORDER BY group_name",new String[]{s.id})){while(g.moveToNext())s.groups.add(g.getString(0));}return s;}}
    public String start(String name,List<String> groups){
        name=clean(name,100);if(groups.isEmpty())throw new IllegalArgumentException("Выберите группы");SQLiteDatabase db=getWritableDatabase();db.beginTransaction();String id=id();
        try{if(active()!=null)throw new IllegalArgumentException("Сначала завершите текущую проверку");
            for(String g:groups){try(Cursor c=db.rawQuery("SELECT count(*) FROM people WHERE archived=0 AND group_name=?",new String[]{g})){c.moveToFirst();if(c.getInt(0)==0)throw new IllegalArgumentException("Группа пуста: "+g);}}
            db.execSQL("INSERT INTO sessions VALUES (?,?,?,NULL)",new Object[]{id,name,System.currentTimeMillis()});
            for(String g:groups){db.execSQL("INSERT INTO session_groups VALUES (?,?)",new Object[]{id,g});db.execSQL("INSERT INTO attendance(session_id,person_id,name,group_name) SELECT ?,id,name,group_name FROM people WHERE archived=0 AND group_name=?",new Object[]{id,g});}
            db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}
    }
    public void closeSession(String id){getWritableDatabase().execSQL("UPDATE sessions SET closed_at=? WHERE id=? AND closed_at IS NULL",new Object[]{System.currentTimeMillis(),id});}
    /** Delete a completed check and its snapshots atomically; people and face templates stay. */
    public void deleteSession(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Не выбрана проверка");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor c = db.rawQuery("SELECT closed_at FROM sessions WHERE id=?", new String[]{id})) {
                if (!c.moveToFirst()) throw new IllegalArgumentException("Проверка уже удалена");
                if (c.isNull(0)) throw new IllegalArgumentException("Сначала завершите текущую проверку");
            }
            deleteSessionDetails(db, "session_id=?", new String[]{id});
            if (db.delete("sessions", "id=? AND closed_at IS NOT NULL", new String[]{id}) != 1)
                throw new IllegalStateException("Не удалось удалить проверку");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    /** Clear completed history only, including its attendance, group snapshots and audit rows. */
    public int clearHistory() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            deleteSessionDetails(db, "session_id IN (SELECT id FROM sessions WHERE closed_at IS NOT NULL)", null);
            int removed = db.delete("sessions", "closed_at IS NOT NULL", null);
            db.setTransactionSuccessful();
            return removed;
        } finally { db.endTransaction(); }
    }
    private static void deleteSessionDetails(SQLiteDatabase db, String where, String[] args) {
        db.delete("audit", where, args);
        db.delete("attendance", where, args);
        db.delete("session_groups", where, args);
    }
    public List<Entry> entries(String session){List<Entry> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT person_id,name,group_name,present_at,method,auto_blocked FROM attendance WHERE session_id=? ORDER BY group_name,name",new String[]{session})){while(c.moveToNext()){Entry e=new Entry();e.id=c.getString(0);e.name=c.getString(1);e.group=c.getString(2);e.present=c.isNull(3)?0:c.getLong(3);e.method=c.getString(4);e.blocked=c.getInt(5)!=0;out.add(e);}}return out;}
    public boolean mark(String session,String person,boolean present,boolean manual){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            try(Cursor c=db.rawQuery("SELECT a.present_at,a.auto_blocked,s.closed_at FROM attendance a JOIN sessions s ON s.id=a.session_id WHERE a.session_id=? AND a.person_id=?",new String[]{session,person})){
                if(!c.moveToFirst())throw new IllegalArgumentException("Человека нет в списке этой проверки");if(!c.isNull(2))throw new IllegalArgumentException("Проверка завершена");
                if(!manual&&c.getInt(1)!=0)throw new IllegalArgumentException("Автоотметка отключена вручную для этого человека");
                if(!manual&&!c.isNull(0))return false;
            }
            ContentValues v=new ContentValues();if(present)v.put("present_at",System.currentTimeMillis());else v.putNull("present_at");v.put("method",manual?"manual":"auto");v.put("auto_blocked",manual&&!present?1:0);
            db.update("attendance",v,"session_id=? AND person_id=?",new String[]{session,person});db.execSQL("INSERT INTO audit VALUES (?,?,?,?)",new Object[]{System.currentTimeMillis(),session,person,(manual?"manual:":"auto:")+(present?"present":"clear")});
            db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    public String csv(Session s){StringBuilder out=new StringBuilder("\uFEFFИмя;Группа;Статус;Время;Способ\r\n");for(Entry e:entries(s.id)){out.append(cell(e.name)).append(';').append(cell(e.group)).append(';').append(e.present>0?"Присутствует":s.closed>0?"Отсутствует":"Ещё не отмечен").append(';').append(e.present>0?java.time.Instant.ofEpochMilli(e.present):"").append(';').append(e.method==null?"":e.method).append("\r\n");}return out.toString();}
    private static String cell(String s){if(!s.isEmpty()&&"=+-@\t\r\n".indexOf(s.charAt(0))>=0)s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
    public String backup()throws Exception{
        JSONObject root=new JSONObject();root.put("format","face-attendance-android");root.put("version",1);root.put("model",Recognition.MODEL);
        SQLiteDatabase db=getReadableDatabase();db.beginTransaction();try{for(String table:new String[]{"groups","people","templates","sessions","session_groups","attendance","audit"}){JSONArray rows=new JSONArray();try(Cursor c=db.rawQuery("SELECT * FROM "+table,null)){while(c.moveToNext()){JSONObject row=new JSONObject();for(int i=0;i<c.getColumnCount();i++){Object value;switch(c.getType(i)){case Cursor.FIELD_TYPE_NULL:value=JSONObject.NULL;break;case Cursor.FIELD_TYPE_INTEGER:value=c.getLong(i);break;case Cursor.FIELD_TYPE_BLOB:value=android.util.Base64.encodeToString(c.getBlob(i),android.util.Base64.NO_WRAP);break;default:value=c.getString(i);}row.put(c.getColumnName(i),value);}rows.put(row);}}root.put(table,rows);}db.setTransactionSuccessful();}finally{db.endTransaction();}return root.toString();
    }
    public void restore(String text)throws Exception{
        JSONObject root=new JSONObject(text);if(!"face-attendance-android".equals(root.getString("format"))||root.getInt("version")!=1||!Recognition.MODEL.equals(root.getString("model")))throw new IllegalArgumentException("Неподходящий формат или модель резервной копии");
        for(String table:new String[]{"groups","people","templates","sessions","session_groups","attendance","audit"})if(!root.has(table)||root.getJSONArray(table).length()>100000)throw new IllegalArgumentException("Некорректная копия");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            for(String table:new String[]{"audit","attendance","session_groups","sessions","templates","people","groups"})db.delete(table,null,null);
            for(String table:new String[]{"groups","people","templates","sessions","session_groups","attendance","audit"}){
                List<String> columns=new ArrayList<>();try(Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null)){while(c.moveToNext())columns.add(c.getString(1));}
                JSONArray rows=root.getJSONArray(table);for(int n=0;n<rows.length();n++){JSONObject row=rows.getJSONObject(n);ContentValues v=new ContentValues();for(String column:columns){if(!row.has(column))throw new IllegalArgumentException("Отсутствует поле "+column);Object value=row.get(column);if(value==JSONObject.NULL)v.putNull(column);else if(table.equals("templates")&&column.equals("embedding")){byte[] bytes=android.util.Base64.decode(value.toString(),android.util.Base64.DEFAULT);if(bytes.length!=2048)throw new IllegalArgumentException("Размер шаблона лица не совпадает");float[] vec=new float[512];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vec);Recognition.normalized(vec);v.put(column,bytes);}else if(value instanceof Number)v.put(column,((Number)value).longValue());else v.put(column,value.toString());}db.insertOrThrow(table,null,v);}
            }
            try(Cursor c=db.rawQuery("PRAGMA foreign_key_check",null)){if(c.moveToFirst())throw new IllegalArgumentException("В копии нарушены связи данных");}db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    static String clean(String s,int max){s=s.trim();if(s.isEmpty()||s.length()>max)throw new IllegalArgumentException("Заполните название (до "+max+" символов)");return s;}
}
