package com.anyoz.faceattendance;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RecognitionTest {
    static float[] vector(int i){float[] a=new float[512];a[i]=1;return a;}
    @Test public void seventyPeopleAndUnknown(){List<Recognition.Profile> people=new ArrayList<>();for(int i=0;i<70;i++)people.add(new Recognition.Profile(""+i,"Name "+i,"Group",Arrays.asList(vector(i),vector(i),vector(i),vector(i),vector(i),vector(i))));Recognition.Gallery gallery=new Recognition.Gallery(people);for(int i=0;i<70;i++)assertEquals(""+i,gallery.identify(vector(i)).person.id);assertNull(gallery.identify(vector(100)));}
    @Test public void confirmationNeedsTimeAndNewIdentity(){Recognition.Profile a=new Recognition.Profile("a","A","G",Arrays.asList(vector(0),vector(0),vector(0)));Recognition.Profile b=new Recognition.Profile("b","B","G",Arrays.asList(vector(1),vector(1),vector(1)));Recognition.Gallery gallery=new Recognition.Gallery(Arrays.asList(a,b));Recognition.Verifier v=new Recognition.Verifier();assertFalse(v.observe(vector(0),gallery.identify(vector(0)),gallery,1000));assertFalse(v.observe(vector(0),gallery.identify(vector(0)),gallery,1350));assertFalse(v.observe(vector(1),gallery.identify(vector(1)),gallery,1700));assertFalse(v.observe(vector(1),gallery.identify(vector(1)),gallery,2050));assertTrue(v.observe(vector(1),gallery.identify(vector(1)),gallery,2400));}
    @Test public void qualityResetAndPauseNeedFreshEvidence(){Recognition.Gallery gallery=new Recognition.Gallery(Arrays.asList(new Recognition.Profile("a","A","G",Arrays.asList(vector(0),vector(0),vector(0)))));Recognition.Verifier v=new Recognition.Verifier();Recognition.Match m=gallery.identify(vector(0));v.observe(vector(0),m,gallery,1000);v.observe(vector(0),m,gallery,1350);v.reset();assertFalse(v.observe(vector(0),m,gallery,1700));assertFalse(v.observe(vector(0),m,gallery,5000));assertEquals(1,v.count());}
    @Test public void ambiguousProfilesAndOneOutlierAreRejected(){Recognition.Gallery g=new Recognition.Gallery(Arrays.asList(new Recognition.Profile("a","A","G",Arrays.asList(vector(0),vector(0),vector(0))),new Recognition.Profile("b","B","G",Arrays.asList(vector(0),vector(0),vector(0)))));assertNull(g.identify(vector(0)));g=new Recognition.Gallery(Arrays.asList(new Recognition.Profile("a","A","G",Arrays.asList(vector(0),vector(1),vector(1),vector(1),vector(1),vector(1)))));assertNull(g.identify(vector(0)));}
    @Test(expected=IllegalArgumentException.class) public void invalidVectorRejected(){Recognition.normalized(new float[512]);}
}
