package com.anyoz.faceattendance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure decision rules: unit tested independently of the camera and Android UI. */
public final class Recognition {
    public static final String MODEL = "arcface-w600k-r50-4c06341c33c2-v1";
    public static final int DIM = 512, ENROLLMENT = 6;
    public static float dot(float[] a, float[] b) {
        float sum=0; for(int i=0;i<a.length;i++) sum+=a[i]*b[i]; return sum;
    }
    public static float[] normalized(float[] a) {
        double n=0; for(float v:a) { if(!Float.isFinite(v)) throw new IllegalArgumentException("Некорректный шаблон"); n+=v*v; }
        if(a.length!=DIM || n<1e-12) throw new IllegalArgumentException("Некорректный размер шаблона");
        float[] out=a.clone(); float scale=(float)(1/Math.sqrt(n)); for(int i=0;i<out.length;i++)out[i]*=scale; return out;
    }
    public static final class Profile {
        public final String id,name,group; public final float[][] samples; public final float[] centroid;
        public Profile(String id,String name,String group,List<float[]> values) {
            this.id=id;this.name=name;this.group=group;samples=new float[values.size()][];
            float[] mean=new float[DIM];
            for(int n=0;n<values.size();n++){ samples[n]=normalized(values.get(n));for(int i=0;i<DIM;i++)mean[i]+=samples[n][i]; }
            centroid=values.isEmpty()?mean:normalized(mean);
        }
    }
    public static final class Match {
        public final Profile person; public final float score,margin;
        Match(Profile p,float s,float m){person=p;score=s;margin=m;}
    }
    public static final class Gallery {
        public final List<Profile> people;
        public Gallery(List<Profile> p){people=new ArrayList<>(p);}
        public Match identify(float[] query){return rank(query,.52f,.10f);}
        public Match duplicate(List<float[]> samples,String exclude) {
            float[] mean=new float[DIM];for(float[] sample:samples)for(int i=0;i<DIM;i++)mean[i]+=sample[i];mean=normalized(mean);
            for(Profile p:people){if(p.id.equals(exclude))continue;if(p.samples.length<2||dot(p.centroid,mean)<.70f)continue;
                int support=0;float a=-1,b=-1,c=-1;for(float[] s:p.samples){float v=dot(s,mean);if(v>=.47f)support++;if(v>a){c=b;b=a;a=v;}else if(v>b){c=b;b=v;}else if(v>c)c=v;}
                float score=p.samples.length==2?(a+b)/2:(a+b+c)/3;if(score>=.70f&&support>=2)return new Match(p,score,0);
            }return null;
        }
        private Match rank(float[] query,float threshold,float margin) {
            Profile best=null;float bestScore=-1,runner=-1,bestCentroid=-1;int bestSupport=0;
            for(Profile p:people){if(p.samples.length==0)continue;float a=-1,b=-1,c=-1;int support=0;
                for(float[] sample:p.samples){float v=dot(sample,query);if(v>=.47f)support++;if(v>a){c=b;b=a;a=v;}else if(v>b){c=b;b=v;}else if(v>c)c=v;}
                float score=p.samples.length==1?a:p.samples.length==2?(a+b)/2:(a+b+c)/3;
                if(score>bestScore){runner=bestScore;bestScore=score;best=p;bestSupport=support;bestCentroid=dot(p.centroid,query);}else if(score>runner)runner=score;
            }
            if(best==null||bestSupport<2||bestScore<threshold||bestCentroid<.47f||bestScore-runner<margin)return null;
            return new Match(best,bestScore,bestScore-runner);
        }
    }
    public static final class Verifier {
        private String id;private float[] anchor;private final List<float[]> samples=new ArrayList<>();
        private final List<Long> times=new ArrayList<>();
        public void reset(){id=null;anchor=null;samples.clear();times.clear();}
        public int count(){return samples.size();}
        public boolean observe(float[] vector,Match match,Gallery gallery,long timestamp){
            if(!match.person.id.equals(id)||anchor==null||dot(anchor,vector)<.45f||(!times.isEmpty()&&timestamp-times.get(times.size()-1)>2000)){reset();id=match.person.id;anchor=vector.clone();}
            if(!times.isEmpty()&&timestamp<=times.get(times.size()-1))return false;
            while(!times.isEmpty()&&timestamp-times.get(0)>2400){times.remove(0);samples.remove(0);}
            samples.add(vector.clone());times.add(timestamp);
            // Keep enough frames for a 0.6 s span even on fast cameras.
            if(samples.size()>10){samples.remove(0);times.remove(0);}
            if(samples.size()<3||timestamp-times.get(0)<600)return false;
            float[] mean=new float[DIM];for(float[] s:samples)for(int i=0;i<DIM;i++)mean[i]+=s[i];
            Match verified=gallery.identify(normalized(mean));if(verified==null||!verified.person.id.equals(id)){reset();return false;}return true;
        }
    }
    private Recognition(){}
}
