package com.anyoz.faceattendance;

import android.app.Application;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AttendanceApp extends Application {
    public final ExecutorService worker=Executors.newSingleThreadExecutor();
    public AttendanceStore store;
    public volatile Recognition.Gallery gallery;
    public volatile FaceEngine engine;
    public volatile String engineError;
    @Override public void onCreate(){super.onCreate();store=new AttendanceStore(this);gallery=new Recognition.Gallery(store.profiles());worker.execute(()->{
        try{
            try{engine=new FaceEngine(this,2,true);}catch(Exception unsupported){Log.w("FaceAttendance","XNNPACK unavailable; using CPU",unsupported);engine=new FaceEngine(this,2,false);}
            Log.i("FaceAttendance","ENGINE_READY "+engine.backend);
        }catch(Exception e){engineError=e.getMessage();Log.e("FaceAttendance","Model initialization failed",e);}
    });}
    public void reloadGallery(){gallery=new Recognition.Gallery(store.profiles());}
}
