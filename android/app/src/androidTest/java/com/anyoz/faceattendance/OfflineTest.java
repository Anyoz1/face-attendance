package com.anyoz.faceattendance;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OfflineTest {
    private final Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Context test=InstrumentationRegistry.getInstrumentation().getContext();
    private final AttendanceApp app=(AttendanceApp)target.getApplicationContext();
    private String read(String name)throws Exception{try(InputStream in=test.getAssets().open("fixtures/"+name)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private Bitmap bitmap(String name)throws Exception{try(InputStream in=test.getAssets().open("fixtures/"+name)){return BitmapFactory.decodeStream(in);}}
    private FaceEngine engine()throws Exception{long until=SystemClock.elapsedRealtime()+90000;while(app.engine==null&&app.engineError==null&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);assertNull(app.engineError);assertNotNull(app.engine);return app.engine;}
    private static float[] vector(int i){float[] a=new float[512];a[i]=1;return a;}
    private static float[] floats(JSONArray a)throws Exception{float[] f=new float[a.length()];for(int i=0;i<f.length;i++)f[i]=(float)a.getDouble(i);return f;}
    @Test public void realModelMatchesDesktopAndRejectsDifferentPerson()throws Exception{
        FaceEngine engine=engine();JSONObject reference=new JSONObject(read("reference.json"));Bitmap a=bitmap("a.jpg"),b=bitmap("b.jpg");try{
            FaceEngine.Frame fa=engine.analyze(a,true);Bitmap crop=engine.alignedForTesting();try(OutputStream out=target.openFileOutput("android-aligned-a.png",Context.MODE_PRIVATE)){crop.compress(Bitmap.CompressFormat.PNG,100,out);}finally{crop.recycle();}
            FaceEngine.Detection detection=engine.detect(a).get(0);JSONObject diagnostic=new JSONObject();diagnostic.put("width",a.getWidth());diagnostic.put("height",a.getHeight());diagnostic.put("points",new JSONArray(detection.points));diagnostic.put("box",new JSONArray(new float[]{detection.x,detection.y,detection.w,detection.h}));diagnostic.put("embedding",new JSONArray(fa.embedding));save("native-reference-diagnostic.json",diagnostic.toString());
            FaceEngine.Frame fb=engine.analyze(b,true);float same=Recognition.dot(fa.embedding,floats(reference.getJSONObject("a").getJSONArray("embedding")));float different=Recognition.dot(fa.embedding,fb.embedding);
            Log.i("FaceAttendanceTest","REFERENCE same_resolution="+same+" different="+different+" time="+fa.millis+"ms");assertTrue("Same-resolution pipeline differs: "+same,same>.98f);assertTrue(different<.5f);
            float[] legacyA=floats(reference.getJSONObject("a").getJSONArray("legacy_embedding")),legacyB=floats(reference.getJSONObject("b").getJSONArray("legacy_embedding"));
            Recognition.Gallery legacy=new Recognition.Gallery(Arrays.asList(new Recognition.Profile("a","A","Test",Arrays.asList(legacyA,legacyA,legacyA,legacyA,legacyA,legacyA)),new Recognition.Profile("b","B","Test",Arrays.asList(legacyB,legacyB,legacyB,legacyB,legacyB,legacyB))));
            assertNotNull(legacy.identify(fa.embedding));assertEquals("a",legacy.identify(fa.embedding).person.id);assertNotNull(legacy.identify(fb.embedding));assertEquals("b",legacy.identify(fb.embedding).person.id);
        }finally{a.recycle();b.recycle();}
    }
    @Test public void realSeventyProfileGallery()throws Exception{
        FaceEngine engine=engine();JSONArray data;try{data=new JSONArray(read("gallery.json"));}catch(FileNotFoundException e){org.junit.Assume.assumeTrue("Optional 70-person public dataset not prepared",false);return;}
        List<Recognition.Profile> profiles=new ArrayList<>();for(int n=0;n<data.length();n++){JSONObject p=data.getJSONObject(n);List<float[]> values=new ArrayList<>();JSONArray samples=p.getJSONArray("samples");for(int j=0;j<samples.length();j++)values.add(floats(samples.getJSONArray(j)));profiles.add(new Recognition.Profile(p.getString("id"),p.getString("name"),"Test",values));}
        Recognition.Gallery gallery=new Recognition.Gallery(profiles);int correct=0,wrong=0,quality=0,rejected=0;
        JSONArray errors=new JSONArray();for(int n=0;n<data.length();n++){JSONObject p=data.getJSONObject(n);if(!p.has("probe"))continue;Bitmap image=bitmap(p.getString("probe"));try{FaceEngine.Frame f=engine.analyze(image,false);Recognition.Match m=gallery.identify(f.embedding);if(m==null)rejected++;else if(m.person.id.equals(p.getString("id")))correct++;else{wrong++;errors.put(p.getString("id")+" -> "+m.person.id);}}catch(FaceEngine.QualityException e){quality++;errors.put(p.getString("id")+": "+e.getMessage());}finally{image.recycle();}}
        JSONArray unknown=new JSONArray(read("unknown.json"));int falseAccept=0,unknownQuality=0;for(int n=0;n<unknown.length();n++){Bitmap image=bitmap(unknown.getString(n));try{if(gallery.identify(engine.analyze(image,false).embedding)!=null)falseAccept++;}catch(FaceEngine.QualityException e){unknownQuality++;}finally{image.recycle();}}
        JSONObject report=new JSONObject();report.put("gallery_people",data.length());report.put("known_correct",correct);report.put("wrong_identity",wrong);report.put("quality_rejected",quality);report.put("policy_rejected",rejected);report.put("unknown_people",unknown.length());report.put("unknown_false_accept",falseAccept);report.put("unknown_quality_rejected",unknownQuality);report.put("errors",errors);save("native-gallery-test.json",report.toString(2));Log.i("FaceAttendanceTest","GALLERY "+report);
        assertEquals(70,data.length());assertEquals(0,wrong);assertEquals(0,falseAccept);assertTrue("Too many rejections: "+report,correct>=65);
    }
    @Test public void databaseGroupsHistoryManualOverrideAndBackup()throws Exception{
        String file="test-"+AttendanceStore.id()+".db";AttendanceStore store=new AttendanceStore(target,file);try{
            store.addGroup("Вторая");String a=store.enroll(null,"A","Основная",Arrays.asList(vector(0),vector(0),vector(0),vector(0),vector(0),vector(0)));String b=store.enroll(null,"B","Вторая",Arrays.asList(vector(1),vector(1),vector(1),vector(1),vector(1),vector(1)));
            String session=store.start("Две группы",Arrays.asList("Основная","Вторая"));assertEquals(2,store.active().total);assertTrue(store.mark(session,a,true,false));assertFalse(store.mark(session,a,true,false));store.mark(session,a,false,true);
            try{store.mark(session,a,true,false);fail("Manual clear must block auto marking");}catch(IllegalArgumentException expected){}store.mark(session,b,true,false);assertEquals(1,store.active().present);
            store.move(a,"Вторая");for(AttendanceStore.Entry e:store.entries(session))if(e.id.equals(a))assertEquals("Основная",e.group);String backup=store.backup();store.closeSession(session);assertTrue(store.csv(store.sessions().get(0)).contains("Отсутствует"));store.restore(backup);assertNotNull(store.active());assertEquals(2,store.people().size());
            JSONObject invalid=new JSONObject(backup);invalid.getJSONArray("templates").getJSONObject(0).put("embedding","aA==");try{store.restore(invalid.toString());fail("Invalid restore should fail");}catch(IllegalArgumentException expected){}assertEquals(2,store.people().size());assertEquals(1,store.active().present);
        }finally{store.close();target.deleteDatabase(file);}
    }
    @Test public void desktopBackupRestoresIntoSeparateDatabase()throws Exception{
        String backup;try(InputStream in=target.openFileInput("incoming-backup.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);backup=out.toString("UTF-8");}catch(FileNotFoundException absent){org.junit.Assume.assumeTrue("No optional desktop migration staged",false);return;}
        JSONObject original=new JSONObject(backup);String file="migration-test-"+AttendanceStore.id()+".db";AttendanceStore isolated=new AttendanceStore(target,file);try{isolated.restore(backup);int active=0;JSONArray people=original.getJSONArray("people");for(int i=0;i<people.length();i++)if(people.getJSONObject(i).getInt("archived")==0)active++;assertEquals(active,isolated.people().size());assertEquals(original.getJSONArray("sessions").length(),isolated.sessions().size());Log.i("FaceAttendanceTest","MIGRATION people="+active+" sessions="+isolated.sessions().size());}finally{isolated.close();target.deleteDatabase(file);}
    }
    @Test public void benchmarkProviders()throws Exception{
        engine();Bitmap image=bitmap("a.jpg");JSONArray results=new JSONArray();long best=Long.MAX_VALUE;int bestThreads=2;boolean bestXnn=false;
        try{for(int[] mode:new int[][]{{1,0},{2,0},{4,0},{2,1}}){try(FaceEngine e=new FaceEngine(target,mode[0],mode[1]==1)){
                    e.analyze(image,true);long[] times=new long[5];for(int i=0;i<times.length;i++)times[i]=e.analyze(image,true).millis;Arrays.sort(times);long median=times[2];JSONObject item=new JSONObject();item.put("threads",mode[0]);item.put("xnnpack",mode[1]==1);item.put("median_ms",median);item.put("max_ms",times[4]);results.put(item);Log.i("FaceAttendanceTest","BENCHMARK "+item);if(median<best){best=median;bestThreads=mode[0];bestXnn=mode[1]==1;}
                }catch(Exception error){JSONObject item=new JSONObject();item.put("threads",mode[0]);item.put("xnnpack",mode[1]==1);item.put("error",error.toString());results.put(item);}}
            JSONObject report=new JSONObject();report.put("results",results);report.put("best_threads",bestThreads);report.put("best_xnnpack",bestXnn);report.put("best_median_ms",best);save("native-benchmark.json",report.toString(2));assertTrue(best<10000);
        }finally{image.recycle();}
    }
    @Test public void actualCameraProcessesFrames()throws Exception{
        engine();AttendanceStore original=app.store;Recognition.Gallery originalGallery=app.gallery;String file="camera-test-"+AttendanceStore.id()+".db";AttendanceStore isolated=new AttendanceStore(target,file);
        try{isolated.enroll(null,"Camera test","Основная",Arrays.asList(vector(0),vector(0),vector(0),vector(0),vector(0),vector(0)));isolated.start("Camera test",Arrays.asList("Основная"));app.store=isolated;app.gallery=new Recognition.Gallery(isolated.profiles());
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
                AtomicReference<MainActivity> activity=new AtomicReference<>();scenario.onActivity(a->{activity.set(a);android.view.View view=a.findViewById(R.id.home_action);assertNotNull(view);view.performClick();});
                long until=SystemClock.elapsedRealtime()+30000;while(activity.get().processedFrames<3&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(200);assertTrue("Camera did not produce analyzed frames",activity.get().processedFrames>=3);
                long before=activity.get().processedFrames;scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
                until=SystemClock.elapsedRealtime()+30000;while(activity.get().processedFrames<before+4&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(200);assertTrue("Camera did not resume after returning",activity.get().processedFrames>=before+4);
                Log.i("FaceAttendanceTest","CAMERA frames="+activity.get().processedFrames);
            }
        }finally{app.store=original;app.gallery=originalGallery;isolated.close();target.deleteDatabase(file);}
    }
    private static android.view.View find(android.view.View v,String text){if(v instanceof android.widget.TextView&&((android.widget.TextView)v).getText().toString().equals(text))return v;if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++){android.view.View found=find(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private void save(String name,String text)throws Exception{try(OutputStream out=target.openFileOutput(name,Context.MODE_PRIVATE)){out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
}
