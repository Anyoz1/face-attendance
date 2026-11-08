package com.anyoz.faceattendance;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MainActivity extends ComponentActivity {
    private static final int BG=0xfff2f6f0,INK=0xff173d36,MUTED=0xff687a70,GREEN=0xff206a50;
    private AttendanceApp app;private LinearLayout page;private String screen="home";
    private ProcessCameraProvider cameraProvider;private PreviewView preview;private FaceOverlay overlay;private TextView cameraResult,cameraDetail;
    private volatile boolean cameraActive=false;private volatile int generation=0;private final Object cameraGate=new Object();
    private boolean cameraScreen=false,resumeCamera=false,front=true;
    private String enrollName,enrollGroup,recaptureId,sessionId;
    private final Recognition.Verifier verifier=new Recognition.Verifier();private final List<float[]> enrollSamples=new ArrayList<>();
    private final Set<String> marked=new HashSet<>();private long lastAnalysis,lastTimestamp;private String pendingExport;
    volatile long processedFrames;

    @Override public void onCreate(Bundle state){super.onCreate(state);app=(AttendanceApp)getApplication();showHome();}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String quantity(int n,String one,String few,String many){int h=n%100,d=n%10;return n+" "+(h>=11&&h<=14?many:d==1?one:d>=2&&d<=4?few:many);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(5));return t;}
    private void title(String value){TextView t=text(value,26,INK);t.setTypeface(null,Typeface.BOLD);page.addView(t);}
    private void note(String value){page.addView(text(value,14,MUTED));}
    private Button button(String value,Runnable action){Button b=new Button(this);b.setText(value);b.setTextSize(15);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackgroundTintList(ColorStateList.valueOf(GREEN));b.setMinHeight(dp(52));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);b.setLayoutParams(lp);b.setOnClickListener(v->{try{action.run();}catch(Exception e){error(e);}});page.addView(b);return b;}
    private void link(String value,Runnable action){Button b=button(value,action);b.setTextColor(INK);b.setBackgroundTintList(ColorStateList.valueOf(0xffe2ebdd));}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(1);c.setPadding(dp(16),dp(12),dp(16),dp(12));c.setBackground(shape(Color.WHITE,16));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);c.setLayoutParams(lp);page.addView(c);return c;}
    private void base(String name){stopCamera();screen=name;ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);page=new LinearLayout(this);page.setOrientation(1);page.setPadding(dp(20),dp(18),dp(20),dp(30));scroll.addView(page);setContentView(scroll);TextView brand=text("п  ПЕРЕКЛИЧКА     ·     ОФЛАЙН",13,GREEN);brand.setTypeface(null,Typeface.BOLD);brand.setPadding(0,0,0,dp(18));page.addView(brand);if(!name.equals("home")){TextView back=text("‹ На главную",14,GREEN);back.setPadding(0,0,0,dp(12));back.setOnClickListener(v->showHome());page.addView(back);}}
    private void error(Exception e){Log.w("FaceAttendance","User operation",e);String message=e instanceof android.database.sqlite.SQLiteException?"Не удалось сохранить данные. Проверьте имя и выбранную группу.":e.getMessage()==null?"Повторите действие":e.getMessage();new AlertDialog.Builder(this).setTitle("Не получилось").setMessage(message).setPositiveButton("Понятно",null).show();}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void confirm(String title,String description,Runnable action){new AlertDialog.Builder(this).setTitle(title).setMessage(description).setNegativeButton("Отмена",null).setPositiveButton("Продолжить",(d,w)->{try{action.run();}catch(Exception e){error(e);}}).show();}
    private EditText field(String hint,String value){EditText e=new EditText(this);e.setHint(hint);e.setText(value);e.setSingleLine(true);e.setTextColor(INK);e.setPadding(dp(12),dp(10),dp(12),dp(10));return e;}
    private Spinner spinner(List<String> values,String selected){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));if(selected!=null)s.setSelection(Math.max(0,values.indexOf(selected)));return s;}
    private String date(long time){return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(time));}

    private void showHome(){base("home");title("Всё на телефоне");note("Камера, узнавание и список посещаемости работают без компьютера и интернета.");
        AttendanceStore.Session active=app.store.active();int count=app.store.people().size();LinearLayout stats=card();stats.addView(text(quantity(count,"человек","человека","человек")+"   ·   "+quantity(app.store.groups().size(),"группа","группы","групп"),21,INK));
        if(active!=null){stats.addView(text(active.name+"\n"+active.present+" из "+active.total+" отмечены",16,GREEN));button("Продолжить проверку",()->openCamera(null,null,null));link("Список текущей проверки",()->showReport(active));}
        else button("Начать проверку",this::startDialog);
        button("Люди и регистрация",this::showPeople);link("Группы",this::showGroups);link("История проверок",this::showHistory);link("Резервная копия и настройки",this::showSettings);
        LinearLayout about=card();about.addView(text("Локальные данные",16,INK));about.addView(text("Фотографии не сохраняются. Для узнавания хранятся числовые шаблоны лиц. Проверка живости пока отключена.",13,MUTED));
    }
    private void showPeople(){base("people");title("Люди");note("Для каждого человека сохраняются шесть подходящих кадров автоматически.");button("Добавить человека",()->enrollmentDialog(null));
        for(AttendanceStore.Person p:app.store.people()){LinearLayout c=card();c.addView(text(p.name,19,INK));c.addView(text(p.group+" · "+(p.samples>=6?"Лицо готово":"Нужно переснять"),13,p.samples>=6?GREEN:0xffad6837));c.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(p.name).setItems(new String[]{"Переснять лицо","Перевести в другую группу","В архив"},(d,item)->{
            if(item==0)enrollmentDialog(p);else if(item==1){List<String> groups=app.store.groups();new AlertDialog.Builder(this).setTitle("Новая группа").setItems(groups.toArray(new String[0]),(a,pos)->{try{app.store.move(p.id,groups.get(pos));app.worker.execute(app::reloadGallery);showPeople();}catch(Exception e){error(e);}}).show();}
            else confirm("Архивировать?","История посещаемости сохранится.",()->{app.store.archive(p.id);app.worker.execute(app::reloadGallery);showPeople();});
        }).show());}
        if(app.store.people().isEmpty())note("Список пока пуст. Сначала добавьте себя или другого участника.");
    }
    private void enrollmentDialog(AttendanceStore.Person existing){LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(20),dp(8),dp(20),0);EditText name=field("Имя",existing==null?"":existing.name);Spinner group=spinner(app.store.groups(),existing==null?null:existing.group);form.addView(name);form.addView(group);if(existing!=null){name.setEnabled(false);group.setEnabled(false);}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"Новый человек":"Обновить лицо").setView(form).setNegativeButton("Отмена",null).setPositiveButton("Открыть камеру",null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(b->{try{String n=AttendanceStore.clean(name.getText().toString(),100),g=group.getSelectedItem().toString();dialog.dismiss();openCamera(n,g,existing==null?null:existing.id);}catch(Exception e){name.setError(e.getMessage());}}));dialog.show();}
    private void showGroups(){base("groups");title("Группы");button("Создать группу",()->{EditText input=field("Название группы","");AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Новая группа").setView(input).setNegativeButton("Отмена",null).setPositiveButton("Создать",null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(b->{try{app.store.addGroup(input.getText().toString());dialog.dismiss();showGroups();}catch(Exception e){input.setError(e.getMessage());}}));dialog.show();});
        List<AttendanceStore.Person> people=app.store.people();for(String group:app.store.groups()){int n=0;for(AttendanceStore.Person p:people)if(p.group.equals(group))n++;LinearLayout c=card();c.addView(text(group,20,INK));c.addView(text(quantity(n,"человек","человека","человек"),14,MUTED));c.setOnClickListener(v->showPeople());}
    }
    private void startDialog(){List<String> groups=app.store.groups();LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(20),0,dp(20),0);EditText name=field("Название проверки","Перекличка "+DateFormat.getDateInstance(DateFormat.SHORT).format(new Date()));form.addView(name);List<CheckBox> boxes=new ArrayList<>();for(String group:groups){CheckBox c=new CheckBox(this);c.setText(group);c.setTextColor(INK);c.setChecked(groups.size()==1);boxes.add(c);form.addView(c);}AlertDialog d=new AlertDialog.Builder(this).setTitle("Какие группы проверяем?").setView(form).setNegativeButton("Отмена",null).setPositiveButton("Начать",null).create();d.setOnShowListener(v->d.getButton(-1).setOnClickListener(b->{try{List<String> selected=new ArrayList<>();for(CheckBox c:boxes)if(c.isChecked())selected.add(c.getText().toString());app.store.start(name.getText().toString(),selected);app.worker.execute(app::reloadGallery);d.dismiss();openCamera(null,null,null);}catch(Exception e){error(e);}}));d.show();}
    private void showHistory(){base("history");title("История");List<AttendanceStore.Session> sessions=app.store.sessions();if(sessions.isEmpty())note("Здесь появятся проведённые проверки.");for(AttendanceStore.Session s:sessions){LinearLayout c=card();c.addView(text(s.name,19,INK));c.addView(text(date(s.opened)+" · "+s.present+" / "+s.total+(s.closed==0?" · идёт":""),13,MUTED));c.setOnClickListener(v->showReport(s));}}
    private void showReport(AttendanceStore.Session selected){AttendanceStore.Session s=selected;for(AttendanceStore.Session candidate:app.store.sessions())if(candidate.id.equals(selected.id))s=candidate;final AttendanceStore.Session session=s;
        base("report");title(session.name);note(String.join(" + ",session.groups)+"\n"+date(session.opened));LinearLayout total=card();total.addView(text(session.present+" из "+session.total+" присутствуют",21,GREEN));
        if(session.closed==0){button("Продолжить с камерой",()->openCamera(null,null,null));link("Завершить проверку",()->confirm("Завершить проверку?","Неотмеченные участники получат статус «Отсутствует».",()->{app.store.closeSession(session.id);showReport(session);}));}
        link("Сохранить CSV",()->export(app.store.csv(session),"text/csv","Посещаемость.csv",601));
        for(String group:session.groups){List<AttendanceStore.Entry> all=app.store.entries(session.id);int totalN=0,presentN=0;for(AttendanceStore.Entry e:all)if(e.group.equals(group)){totalN++;if(e.present>0)presentN++;}note(group+" · "+presentN+" / "+totalN);}
        for(AttendanceStore.Entry e:app.store.entries(session.id)){LinearLayout c=card();c.addView(text(e.name,18,INK));c.addView(text(e.group+" · "+(e.present>0?"Присутствует":session.closed>0?"Отсутствует":"Ещё не отмечен"),13,e.present>0?GREEN:MUTED));if(e.blocked)c.addView(text("Автоотметка отключена вручную",12,0xffad6837));if(session.closed==0)c.setOnClickListener(v->confirm(e.present>0?"Снять отметку?":"Отметить вручную?",e.present>0?"Камера не вернёт отметку автоматически в этой проверке.":e.name,()->{app.store.mark(session.id,e.id,e.present==0,true);showReport(session);}));}
    }
    private void showSettings(){base("settings");title("Данные и настройки");note("Первая версия · 0.1.0\nArcFace ResNet50 · всё распознавание на устройстве\n"+(app.engine==null?"Модель загружается":app.engine.backend));
        java.io.File incoming=new java.io.File(getFilesDir(),"incoming-backup.json");
        if(incoming.exists())button("Перенести данные с ноутбука",()->confirm("Перенести данные с ноутбука?","Текущая база телефона будет заменена подготовленной копией. Если вы уже добавили людей на телефоне, сначала сохраните резервную копию.",()->app.worker.execute(()->{try(InputStream in=new java.io.FileInputStream(incoming)){app.store.restore(readText(in,32*1024*1024));app.reloadGallery();runOnUiThread(()->{toast("Люди и история перенесены");showHome();});}catch(Exception e){runOnUiThread(()->error(e));}})));
        button("Сохранить резервную копию",()->app.worker.execute(()->{try{String backup=app.store.backup();runOnUiThread(()->export(backup,"application/json","Перекличка-резервная-копия.json",602));}catch(Exception e){runOnUiThread(()->error(e));}}));
        link("Восстановить из копии",()->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("application/json");intent.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,603);});
        note("Копия содержит шаблоны лиц и историю. Храните её у себя. Удаление приложения удаляет его локальные данные.");
        link("О моделях",()->new AlertDialog.Builder(this).setTitle("Модели и зависимости").setMessage(readAssetNotice()).setPositiveButton("Понятно",null).show());
    }
    private String readAssetNotice(){try(InputStream in=getAssets().open("model_notice.txt")){return readText(in,100000);}catch(Exception e){return "ArcFace / YuNet / ONNX Runtime";}}
    private void export(String text,String type,String name,int request){pendingExport=text;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(type);intent.putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(intent,request);}
    private static String readText(InputStream in,int limit)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>limit)throw new IllegalArgumentException("Файл слишком большой");out.write(buffer,0,n);}return out.toString("UTF-8");}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(request==601||request==602){if(pendingExport==null){toast("Повторите экспорт");return;}try(OutputStream out=getContentResolver().openOutputStream(data.getData())){out.write(pendingExport.getBytes(StandardCharsets.UTF_8));pendingExport=null;toast("Файл сохранён");}catch(Exception e){error(e);}}
        if(request==603)confirm("Заменить данные приложения?","Люди и история будут заменены выбранной копией. Сначала сохраните текущую копию, если она нужна.",()->app.worker.execute(()->{try(InputStream in=getContentResolver().openInputStream(data.getData())){app.store.restore(readText(in,32*1024*1024));app.reloadGallery();runOnUiThread(()->{toast("Данные восстановлены");showHome();});}catch(Exception e){runOnUiThread(()->error(e));}}));
    }

    private void openCamera(String name,String group,String existing){
        base("camera");cameraScreen=true;enrollName=name;enrollGroup=group;recaptureId=existing;
        AttendanceStore.Session active=app.store.active();sessionId=active==null?null:active.id;if(name==null&&sessionId==null){showHome();toast("Сначала начните проверку");return;}
        title(name==null?"Отметка посещаемости":"Регистрация лица");note(name==null?"Подойдите ближе к центру. Рамка покажет, чьё лицо проверяем.":name+" · "+group+"\nВ кадре должны быть только вы.");
        android.widget.FrameLayout stage=new android.widget.FrameLayout(this);stage.setBackground(shape(0xff132d25,18));page.addView(stage,new LinearLayout.LayoutParams(-1,dp(360)));
        preview=new PreviewView(this);preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);stage.addView(preview,new android.widget.FrameLayout.LayoutParams(-1,-1));overlay=new FaceOverlay();stage.addView(overlay,new android.widget.FrameLayout.LayoutParams(-1,-1));
        cameraResult=text("Подготовка камеры…",19,INK);cameraResult.setTypeface(null,Typeface.BOLD);page.addView(cameraResult);cameraDetail=text("Модель работает на телефоне",12,MUTED);page.addView(cameraDetail);
        link("Переключить камеру",()->{stopCameraInternal();front=!front;startCamera();});link(name==null?"Открыть список":"Отменить регистрацию",()->{if(name==null&&active!=null)showReport(active);else showPeople();});
        note("Экран остаётся включённым во время съёмки. Проверка живости пока отключена.");
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();else requestPermissions(new String[]{Manifest.permission.CAMERA},41);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==41){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED&&cameraScreen)startCamera();else if(cameraResult!=null)cameraResult.setText("Разрешите камеру в настройках приложения и откройте этот экран снова.");}}
    private void startCamera(){if(!cameraScreen||cameraActive)return;final int version;synchronized(cameraGate){cameraActive=true;version=++generation;}verifier.reset();enrollSamples.clear();marked.clear();lastAnalysis=0;lastTimestamp=0;if(sessionId!=null)for(AttendanceStore.Entry e:app.store.entries(sessionId))if(e.present>0)marked.add(e.id);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);future.addListener(()->{
            if(!cameraActive||generation!=version)return;try{cameraProvider=future.get();cameraProvider.unbindAll();Preview p=new Preview.Builder().setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3).build();p.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analysis=new ImageAnalysis.Builder().setTargetResolution(new Size(640,480)).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();analysis.setAnalyzer(app.worker,image->analyze(image,version));
                CameraSelector selector=front?CameraSelector.DEFAULT_FRONT_CAMERA:CameraSelector.DEFAULT_BACK_CAMERA;if(!cameraProvider.hasCamera(selector)){front=false;selector=CameraSelector.DEFAULT_BACK_CAMERA;}
                cameraProvider.bindToLifecycle(this,selector,p,analysis);
            }catch(Exception e){cameraActive=false;cameraResult.setText("Не удалось включить камеру: "+e.getMessage());Log.e("FaceAttendance","Camera failed",e);}
        },ContextCompat.getMainExecutor(this));
    }
    private void analyze(ImageProxy image,int version){Bitmap bitmap=null;try{
        if(!cameraActive||generation!=version)return;long now=SystemClock.elapsedRealtime();if(now-lastAnalysis<220)return;lastAnalysis=now;
        long timestamp=image.getImageInfo().getTimestamp()/1000000;if(timestamp<=lastTimestamp)return;lastTimestamp=timestamp;
        if(app.engine==null){updateCamera(version,app.engineError==null?"Загрузка модели…":"Не удалось загрузить модель",app.engineError==null?"Первый запуск может занять несколько секунд":"Закройте и снова откройте приложение",null,0,0,false);return;}
        Bitmap raw=image.toBitmap();int rotation=image.getImageInfo().getRotationDegrees();if(rotation==0)bitmap=raw;else{Matrix rotate=new Matrix();rotate.postRotate(rotation);bitmap=Bitmap.createBitmap(raw,0,0,raw.getWidth(),raw.getHeight(),rotate,true);if(raw!=bitmap)raw.recycle();}
        int w=bitmap.getWidth(),h=bitmap.getHeight();FaceEngine.Frame frame;
        try{frame=app.engine.analyze(bitmap,enrollName!=null);processedFrames++;}catch(FaceEngine.QualityException e){processedFrames++;verifier.reset();enrollSamples.clear();updateCamera(version,e.getMessage(),"Посмотрите прямо, держите телефон неподвижно",e.box,w,h,false);return;}
        if(!cameraActive||generation!=version)return;
        synchronized(cameraGate){if(!cameraActive||generation!=version)return;
            if(enrollName!=null){for(float[] previous:enrollSamples)if(Recognition.dot(previous,frame.embedding)<.55f){enrollSamples.clear();updateCamera(version,"Начинаем съёмку заново","Лицо или ракурс сильно изменились",frame.box,w,h,false);return;}
                enrollSamples.add(frame.embedding);Recognition.Match duplicate=app.gallery.duplicate(enrollSamples,recaptureId);if(duplicate!=null){enrollSamples.clear();updateCamera(version,"Лицо уже есть: "+duplicate.person.name,"Выберите существующего человека и переснимите его лицо",frame.box,w,h,false);return;}
                if(enrollSamples.size()>=6){String savedName=enrollName;app.store.enroll(recaptureId,enrollName,enrollGroup,enrollSamples);app.reloadGallery();cameraActive=false;runOnUiThread(()->{if(generation==version){stopCamera();toast(savedName+" — регистрация завершена");showPeople();}});return;}
                updateCamera(version,"Сохраняем лицо · "+enrollSamples.size()+" из 6",frame.millis+" мс · держитесь неподвижно",frame.box,w,h,false);return;
            }
            Recognition.Match match=app.gallery.identify(frame.embedding);if(match==null){verifier.reset();updateCamera(version,"Лицо не узнано",frame.millis+" мс · посмотрите прямо или обновите регистрацию",frame.box,w,h,false);return;}
            if(!verifier.observe(frame.embedding,match,app.gallery,timestamp)){updateCamera(version,"Проверяем лицо…",frame.millis+" мс · подтверждений: "+verifier.count(),frame.box,w,h,false);return;}
            if(marked.contains(match.person.id)){updateCamera(version,match.person.name+" — уже отмечен",frame.millis+" мс · "+match.person.group,frame.box,w,h,true);return;}
            app.store.mark(sessionId,match.person.id,true,false);marked.add(match.person.id);updateCamera(version,match.person.name+" — отмечен",frame.millis+" мс · "+match.person.group,frame.box,w,h,true);
        }
    }catch(Exception e){verifier.reset();updateCamera(version,e.getMessage()==null?"Ошибка обработки кадра":e.getMessage(),"Повторите попытку",null,0,0,false);Log.w("FaceAttendance","Frame",e);}finally{if(bitmap!=null)bitmap.recycle();image.close();}}
    private void updateCamera(int version,String message,String detail,RectF box,int width,int height,boolean success){runOnUiThread(()->{if(!cameraActive||generation!=version||cameraResult==null)return;cameraResult.setText(message);cameraResult.setTextColor(success?GREEN:INK);cameraDetail.setText(detail);overlay.box=box==null?null:new RectF(box);overlay.imageWidth=width;overlay.imageHeight=height;overlay.success=success;overlay.invalidate();});}
    private void stopCameraInternal(){synchronized(cameraGate){cameraActive=false;generation++;}if(cameraProvider!=null)cameraProvider.unbindAll();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void stopCamera(){resumeCamera=false;cameraScreen=false;stopCameraInternal();}
    @Override protected void onStop(){boolean was=cameraScreen&&cameraActive;stopCameraInternal();resumeCamera=was;super.onStop();}
    @Override protected void onResume(){super.onResume();if(resumeCamera&&cameraScreen){resumeCamera=false;if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();}}
    @Override public void onBackPressed(){if(!screen.equals("home"))showHome();else super.onBackPressed();}
    private final class FaceOverlay extends View {
        RectF box;int imageWidth,imageHeight;boolean success;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        FaceOverlay(){super(MainActivity.this);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(0xffd7ecbf);
            if(box==null||imageWidth==0){p.setAlpha(140);canvas.drawRoundRect(new RectF(getWidth()*.22f,getHeight()*.16f,getWidth()*.78f,getHeight()*.84f),dp(55),dp(55),p);return;}
            float scale=Math.min((float)getWidth()/imageWidth,(float)getHeight()/imageHeight),left=(getWidth()-imageWidth*scale)/2,top=(getHeight()-imageHeight*scale)/2;RectF r=new RectF(box);
            if(front){float x=imageWidth-r.right;r.right=imageWidth-r.left;r.left=x;}r.set(left+r.left*scale,top+r.top*scale,left+r.right*scale,top+r.bottom*scale);p.setAlpha(255);p.setColor(success?0xffb9ed83:0xffe3efbd);canvas.drawRoundRect(r,dp(18),dp(18),p);
            p.setStyle(Paint.Style.FILL);p.setTextSize(dp(12));canvas.drawText(success?"Готово":"Выбрано лицо",r.left,Math.max(dp(18),r.top-dp(8)),p);
        }
    }
}
