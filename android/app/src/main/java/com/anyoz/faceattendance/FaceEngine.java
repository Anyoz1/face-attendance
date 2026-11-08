package com.anyoz.faceattendance;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Two persistent ONNX sessions, reused tensors and only one recognition per frame. */
public final class FaceEngine implements AutoCloseable {
    private static final int SIZE=320;
    private static final float[][] TARGET={{38.2946f,51.6963f},{73.5318f,51.5014f},{56.0252f,71.7366f},{41.5493f,92.3655f},{70.7299f,92.2041f}};
    private final OrtEnvironment env=OrtEnvironment.getEnvironment();
    private final OrtSession detector,recognizer;
    private final OrtSession.SessionOptions detectorOptions,recognizerOptions;
    private final MappedByteBuffer detectorModel,recognizerModel;
    private final FloatBuffer detectorInput=ByteBuffer.allocateDirect(3*SIZE*SIZE*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final FloatBuffer recognizerInput=ByteBuffer.allocateDirect(3*112*112*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final OnnxTensor detectorTensor,recognizerTensor;
    private final Map<String,OnnxTensor> detectorFeed=new HashMap<>(),recognizerFeed=new HashMap<>();
    private final Bitmap detectorBitmap=Bitmap.createBitmap(SIZE,SIZE,Bitmap.Config.ARGB_8888),aligned=Bitmap.createBitmap(112,112,Bitmap.Config.ARGB_8888);
    private final Canvas detectorCanvas=new Canvas(detectorBitmap),alignedCanvas=new Canvas(aligned);
    private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int[] detectorPixels=new int[SIZE*SIZE],alignedPixels=new int[112*112];
    public final String backend;
    public static final class Detection {
        public float x,y,w,h,score;public float[] points=new float[10];
        public RectF box(){return new RectF(x,y,x+w,y+h);}
    }
    public static final class Frame {
        public float[] embedding;public RectF box;public int width,height,faces;public float brightness,sharpness;public long millis;
    }
    public static final class QualityException extends Exception {
        public final RectF box;
        public QualityException(String message){this(message,null);}
        public QualityException(String message,RectF box){super(message);this.box=box;}
    }
    public FaceEngine(Context context,int threads,boolean xnnpack)throws Exception{
        detectorOptions=options(2,false);recognizerOptions=options(threads,xnnpack);backend=(xnnpack?"XNNPACK":"CPU")+" · "+threads+" потока";
        detectorModel=map(context,"models/yunet320.onnx");recognizerModel=map(context,"models/arcface.onnx");
        detector=env.createSession(detectorModel,detectorOptions);recognizer=env.createSession(recognizerModel,recognizerOptions);
        detectorTensor=OnnxTensor.createTensor(env,detectorInput,new long[]{1,3,SIZE,SIZE});recognizerTensor=OnnxTensor.createTensor(env,recognizerInput,new long[]{1,3,112,112});
        detectorFeed.put(detector.getInputNames().iterator().next(),detectorTensor);recognizerFeed.put(recognizer.getInputNames().iterator().next(),recognizerTensor);
    }
    private static OrtSession.SessionOptions options(int threads,boolean xnn)throws OrtException{
        OrtSession.SessionOptions o=new OrtSession.SessionOptions();o.setIntraOpNumThreads(xnn?1:threads);o.setInterOpNumThreads(1);o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        o.addConfigEntry("session.intra_op.allow_spinning","0");o.addConfigEntry("session.inter_op.allow_spinning","0");
        if(xnn){Map<String,String> m=new HashMap<>();m.put("intra_op_num_threads",Integer.toString(threads));o.addXnnpack(m);}return o;
    }
    private static MappedByteBuffer map(Context c,String name)throws Exception{
        try(AssetFileDescriptor fd=c.getAssets().openFd(name);FileInputStream stream=new FileInputStream(fd.getFileDescriptor())){
            return stream.getChannel().map(FileChannel.MapMode.READ_ONLY,fd.getStartOffset(),fd.getDeclaredLength());
        }
    }
    public synchronized List<Detection> detect(Bitmap bitmap)throws OrtException{
        float scale=Math.min(1f,Math.min((float)SIZE/bitmap.getWidth(),(float)SIZE/bitmap.getHeight()));
        detectorCanvas.drawColor(Color.BLACK);detectorCanvas.drawBitmap(bitmap,null,new RectF(0,0,bitmap.getWidth()*scale,bitmap.getHeight()*scale),paint);
        detectorBitmap.getPixels(detectorPixels,0,SIZE,0,0,SIZE,SIZE);int plane=SIZE*SIZE;
        for(int i=0;i<plane;i++){int c=detectorPixels[i];detectorInput.put(i,c&255);detectorInput.put(plane+i,(c>>8)&255);detectorInput.put(plane*2+i,(c>>16)&255);}
        List<Detection> detections=new ArrayList<>();
        try(OrtSession.Result result=detector.run(detectorFeed)){
            for(int stride:new int[]{8,16,32}){
                int cols=SIZE/stride;
                FloatBuffer cls=output(result,"cls_"+stride),obj=output(result,"obj_"+stride),bbox=output(result,"bbox_"+stride),kps=output(result,"kps_"+stride);
                for(int i=0;i<cols*cols;i++){
                    float score=(float)Math.sqrt(Math.max(0,Math.min(1,cls.get(i)))*Math.max(0,Math.min(1,obj.get(i))));if(score<.85f)continue;
                    int row=i/cols,col=i%cols;Detection d=new Detection();d.score=score;
                    d.w=(float)Math.exp(bbox.get(i*4+2))*stride/scale;d.h=(float)Math.exp(bbox.get(i*4+3))*stride/scale;
                    d.x=(col+bbox.get(i*4))*stride/scale-d.w/2;d.y=(row+bbox.get(i*4+1))*stride/scale-d.h/2;
                    for(int n=0;n<5;n++){d.points[n*2]=(kps.get(i*10+n*2)+col)*stride/scale;d.points[n*2+1]=(kps.get(i*10+n*2+1)+row)*stride/scale;}
                    if(Float.isFinite(d.w)&&Float.isFinite(d.h)&&d.w>0&&d.h>0)detections.add(d);
                }
            }
        }
        detections.sort((a,b)->Float.compare(b.score,a.score));List<Detection> keep=new ArrayList<>();
        int limit=Math.min(100,detections.size());for(int i=0;i<limit;i++){Detection d=detections.get(i);boolean overlap=false;for(Detection k:keep)if(iou(d,k)>.3f){overlap=true;break;}if(!overlap)keep.add(d);}return keep;
    }
    private static FloatBuffer output(OrtSession.Result result,String name){return ((OnnxTensor)result.get(name).get()).getFloatBuffer();}
    static float iou(Detection a,Detection b){float area=Math.max(0,Math.min(a.x+a.w,b.x+b.w)-Math.max(a.x,b.x))*Math.max(0,Math.min(a.y+a.h,b.y+b.h)-Math.max(a.y,b.y));return area/(a.w*a.h+b.w*b.h-area);}
    static Detection select(List<Detection> faces,int width,int height,boolean single)throws QualityException{
        if(faces.isEmpty())throw new QualityException("Посмотрите в камеру");
        if(single&&faces.size()!=1)throw new QualityException("При регистрации в кадре должны быть только вы");
        if(faces.size()==1)return faces.get(0);
        Detection best=null;float bestScore=0,runner=0;
        for(Detection d:faces){float cx=(d.x+d.w/2)/width,cy=(d.y+d.h/2)/height;if(cx<.2f||cx>.8f||cy<.1f||cy>.9f)continue;
            float distance=(2*cx-1)*(2*cx-1)+(2*cy-1)*(2*cy-1);float score=(float)Math.sqrt(d.w*d.h/(width*height))/(1+2*distance);
            if(score>bestScore){runner=bestScore;bestScore=score;best=d;}else if(score>runner)runner=score;
        }
        if(best==null)throw new QualityException("Подойдите ближе к центру камеры");
        if(runner>=bestScore*.8f)throw new QualityException("Два лица рядом — одному человеку нужно подойти ближе");return best;
    }
    public synchronized Frame analyze(Bitmap bitmap,boolean single)throws Exception{
        long start=SystemClock.elapsedRealtime();int w=bitmap.getWidth(),h=bitmap.getHeight();List<Detection> detections=detect(bitmap);Detection d=select(detections,w,h,single);RectF box=d.box();
        if(Math.min(d.w,d.h)<85||d.w*d.h/(w*h)<.025f)throw new QualityException("Подойдите ближе",box);
        if(d.x<0||d.y<0||d.x+d.w>w||d.y+d.h>h)throw new QualityException("Лицо должно полностью помещаться в кадре",box);
        float[] p=d.points.clone();sortPair(p,0,2);sortPair(p,6,8);
        double eyes=Math.hypot(p[0]-p[2],p[1]-p[3]);float yaw=(p[4]-(p[0]+p[2])/2)/(float)Math.max(eyes,1);
        if(Math.abs(yaw)>.32f)throw new QualityException("Посмотрите прямо в камеру",box);
        Matrix matrix=alignMatrix(p);alignedCanvas.drawColor(Color.BLACK);alignedCanvas.drawBitmap(bitmap,matrix,paint);
        aligned.getPixels(alignedPixels,0,112,0,0,112,112);float[] gray=new float[112*112];float brightness=0;
        for(int i=0;i<gray.length;i++){int c=alignedPixels[i];float r=(c>>16)&255,g=(c>>8)&255,b=c&255;gray[i]=.299f*r+.587f*g+.114f*b;brightness+=gray[i];recognizerInput.put(i,(r-127.5f)/127.5f);recognizerInput.put(gray.length+i,(g-127.5f)/127.5f);recognizerInput.put(gray.length*2+i,(b-127.5f)/127.5f);}
        brightness/=gray.length;double sum=0,sq=0;int count=0;
        for(int y=1;y<111;y++)for(int x=1;x<111;x++){int i=y*112+x;double lap=gray[i-1]+gray[i+1]+gray[i-112]+gray[i+112]-4*gray[i];sum+=lap;sq+=lap*lap;count++;}
        float sharpness=(float)(sq/count-(sum/count)*(sum/count));if(sharpness<25)throw new QualityException("Кадр размыт — держитесь неподвижно",box);
        if(brightness<30||brightness>225)throw new QualityException("Нужно более ровное освещение лица",box);
        float[] embedding=new float[512];try(OrtSession.Result result=recognizer.run(recognizerFeed)){((OnnxTensor)result.get(0)).getFloatBuffer().get(embedding);}
        Frame frame=new Frame();frame.embedding=Recognition.normalized(embedding);frame.box=box;frame.faces=detections.size();frame.width=w;frame.height=h;frame.brightness=brightness;frame.sharpness=sharpness;frame.millis=SystemClock.elapsedRealtime()-start;return frame;
    }
    private static void sortPair(float[] p,int a,int b){if(p[a]>p[b]){float x=p[a],y=p[a+1];p[a]=p[b];p[a+1]=p[b+1];p[b]=x;p[b+1]=y;}}
    static Matrix alignMatrix(float[] p){
        double sx=0,sy=0,tx=0,ty=0;for(int i=0;i<5;i++){sx+=p[2*i];sy+=p[2*i+1];tx+=TARGET[i][0];ty+=TARGET[i][1];}sx/=5;sy/=5;tx/=5;ty/=5;
        double aa=0,bb=0,den=0;for(int i=0;i<5;i++){double x=p[2*i]-sx,y=p[2*i+1]-sy,u=TARGET[i][0]-tx,v=TARGET[i][1]-ty;aa+=x*u+y*v;bb+=x*v-y*u;den+=x*x+y*y;}if(den<1e-6)throw new IllegalArgumentException("Не удалось выровнять лицо");
        float a=(float)(aa/den),b=(float)(bb/den);Matrix matrix=new Matrix();matrix.setValues(new float[]{a,-b,(float)(tx-a*sx+b*sy),b,a,(float)(ty-b*sx-a*sy),0,0,1});return matrix;
    }
    synchronized Bitmap alignedForTesting(){return aligned.copy(Bitmap.Config.ARGB_8888,false);}
    @Override public void close(){try{detectorTensor.close();recognizerTensor.close();detector.close();recognizer.close();detectorOptions.close();recognizerOptions.close();}catch(Exception ignored){}detectorBitmap.recycle();aligned.recycle();}
}
