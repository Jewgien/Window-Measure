package org.student.oknomer;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.opencv.core.Point;

public class Verify extends Instrumentation {
    private MainActivity activity;
    private boolean afterRestart;
    private StringBuilder lines=new StringBuilder();
    @Override public void onCreate(Bundle args){super.onCreate(args);afterRestart=args!=null&&"reopen".equals(args.getString("phase"));start();}
    void check(boolean cond,String msg){if(!cond)throw new AssertionError(msg);}
    void log(String msg){lines.append(msg).append("\n");android.util.Log.i("OknoCheck",msg);}
    void main(Runnable r){final Throwable[] err={null};runOnMainSync(()->{try{r.run();}catch(Throwable e){err[0]=e;}});if(err[0]!=null)throw new RuntimeException(err[0]);}
    View match(View v,String s){
        if(v instanceof TextView && ((TextView)v).getText().toString().equals(s))return v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View f=match(g.getChildAt(i),s);if(f!=null)return f;}}
        return null;
    }
    <T> T first(View v,Class<T> type){
        if(type.isInstance(v))return type.cast(v);
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){T f=first(g.getChildAt(i),type);if(f!=null)return f;}}return null;
    }
    View view(String s){return match(activity.getWindow().getDecorView(),s);}
    void click(String s){main(()->{View v=view(s);check(v!=null&&v.isEnabled(),s);v.performClick();});waitForIdleSync();}
    void waitForButton(String s){
        for(int i=0;i<300;i++){boolean[] yes={false};main(()->{View v=view(s);yes[0]=v!=null&&v.isEnabled();});if(yes[0])return;SystemClock.sleep(200);}
        throw new AssertionError("Timeout: "+s);
    }
    String string(InputStream in)throws Exception{try(InputStream i=in;ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=i.read(buf))!=-1)b.write(buf,0,n);return b.toString("UTF-8");}}
    void fixtures()throws Exception{
        for(String scene:new String[]{"front","angle_small","angle_large"}){
            Bitmap image;
            try(InputStream in=getContext().getAssets().open(scene+".jpg")){image=BitmapFactory.decodeStream(in);}
            JSONObject f=new JSONObject(string(getContext().getAssets().open(scene+".json")));
            Point[] opening=Geometry.points(f.getJSONArray("opening")),paper=Geometry.points(f.getJSONArray("reference"));
            JSONObject actual=Geometry.measure(opening,paper,image.getWidth(),image.getHeight(),false);
            check(Math.abs(actual.getDouble("width_mm")-1200)<.2&&Math.abs(actual.getDouble("height_mm")-1500)<.2,"geometry "+scene);
            Detector.Found found=Detector.find(image);
            check(found.paper.length==4&&!found.openings.isEmpty(),"autodetect "+scene);
            JSONObject auto=Geometry.measure(found.openings.get(0),found.paper,image.getWidth(),image.getHeight(),found.landscape);
            check(Math.abs(auto.getDouble("width_mm")/1200-1)<.1&&Math.abs(auto.getDouble("height_mm")/1500-1)<.1,"automeasure "+scene);
            log("CASE "+scene+" baseline="+actual.getDouble("baseline_width_mm")+"x"+actual.getDouble("baseline_height_mm"));
            log(scene+" exact="+actual.getDouble("width_mm")+"x"+actual.getDouble("height_mm")+" auto="+auto.getDouble("width_mm")+"x"+auto.getDouble("height_mm")+" contours="+found.openings.size());
        }
        Bitmap white=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888);white.eraseColor(-1);
        Detector.Found blank=Detector.find(white);check(blank.paper.length==0&&blank.openings.isEmpty(),"blank image");log("blank rejected");
    }
    void cardFixtures()throws Exception{
        for(String scene:new String[]{"card","card-portrait","card-rotated","card-inside"}){
            Bitmap image;
            try(InputStream in=getContext().getAssets().open(scene+".jpg")){image=BitmapFactory.decodeStream(in);}
            JSONObject f=new JSONObject(string(getContext().getAssets().open(scene+".json")));
            Point[] opening=Geometry.points(f.getJSONArray("opening")),card=Geometry.points(f.getJSONArray("reference"));
            JSONObject exact=Geometry.measure(opening,card,image.getWidth(),image.getHeight(),Reference.CARD,f.getBoolean("landscape"));
            check(Math.abs(exact.getDouble("width_mm")-1200)<.2&&Math.abs(exact.getDouble("height_mm")-1500)<.2,"metric card "+scene);
            Detector.Found found=Detector.find(image,Reference.CARD);
            log(scene+" found ref="+Geometry.json(found.paper)+" openings="+found.openings.size());
            check(found.paper.length==4&&!found.openings.isEmpty(),"missing "+scene);
            double cornerError=0;
            for(int j=0;j<4;j++)cornerError=Math.max(cornerError,Geometry.length(card[j],found.paper[j]));
            check(cornerError<3,"wrong reference "+scene+" delta="+cornerError);
            check(found.landscape==f.getBoolean("landscape"),"wrong orientation");
            JSONObject detected=Geometry.measure(found.openings.get(0),found.paper,image.getWidth(),image.getHeight(),Reference.CARD,found.landscape);
            check(Math.abs(detected.getDouble("width_mm")/1200-1)<.1&&Math.abs(detected.getDouble("height_mm")/1500-1)<.1,"auto card size");
            log("CASE "+scene+" baseline="+exact.getDouble("baseline_width_mm")+"x"+exact.getDouble("baseline_height_mm"));
            log(scene+" exact="+exact.getDouble("width_mm")+"x"+exact.getDouble("height_mm")+" auto="+detected.getDouble("width_mm")+"x"+detected.getDouble("height_mm")+" max corner error="+cornerError);
        }
        Bitmap blank=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888);blank.eraseColor(-1);
        check(Detector.find(blank,Reference.CARD).paper.length==0,"empty scene without card");
    }
    void cardFlow()throws Exception{
        Intent intent=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity=(MainActivity)startActivitySync(intent);waitForIdleSync();
        click("Попробовать на примере");waitForButton("Сохранить замер");
        click("Банковская карта");waitForButton("Сохранить замер");
        click("Лист А4");waitForButton("Сохранить замер");
        click("Банковская карта");waitForButton("Сохранить замер");
        click("Исправить границы");click("Углы карты");
        click("Весь снимок");click("Сбросить выбранные точки");
        main(()->check(!view("Сохранить замер").isEnabled(),"reset reference invalidates size"));
        JSONObject f=new JSONObject(string(getContext().getAssets().open("card.json")));
        Point[] points=Geometry.points(f.getJSONArray("reference"));
        main(()->{
            PhotoMarkup canvas=first(activity.getWindow().getDecorView(),PhotoMarkup.class);
            float scale=Math.min(canvas.getWidth()/(float)canvas.bitmap.getWidth(),canvas.getHeight()/(float)canvas.bitmap.getHeight());
            for(Point p:points){
                float x=(canvas.getWidth()-scale*canvas.bitmap.getWidth())/2+(float)p.x*scale;
                float y=(canvas.getHeight()-scale*canvas.bitmap.getHeight())/2+(float)p.y*scale;
                for(int action:new int[]{0,1}){
                    long t=SystemClock.uptimeMillis();MotionEvent touch=MotionEvent.obtain(t,t,action,x,y,0);canvas.onTouchEvent(touch);touch.recycle();
                }
            }
        });
        click("Пересчитать размер");waitForButton("Сохранить замер");click("Сохранить замер");
        waitForButton("Переименовать замер");
        main(()->check(view("Эталон: Банковская карта · 85,60 × 53,98 мм")!=null,"saved card label"));
        Store db=new Store(getTargetContext());String id=db.byName("Демонстрация");boolean old=false,updated=false;
        // Install a legacy-format record so this regression also runs on an empty emulator.
        JSONObject source=null;
        for(JSONObject candidate:db.records(id)){
            if("a4".equals(candidate.getJSONObject("payload").getJSONObject("result").optString("reference_type"))){source=candidate;break;}
        }
        check(source!=null,"regular A4 saved earlier in this run");
        JSONObject legacy=new JSONObject(source.getJSONObject("payload").toString());
        JSONObject lr=legacy.getJSONObject("result");
        lr.remove("reference_type");lr.remove("reference_width_mm");lr.remove("reference_height_mm");
        String legacyId=db.save(id,"Проверка старого формата",new File(db.photos,source.getString("photo")),legacy);
        for(JSONObject row:db.records(id)){
            JSONObject d=row.getJSONObject("payload").getJSONObject("result");
            if(!d.has("reference_type"))old=Reference.fromId(d.optString("reference_type"))==Reference.A4;
            if("id1".equals(d.optString("reference_type")))updated=d.getDouble("reference_width_mm")==85.6;
        }
        check(old&&updated,"old and new saved measurements");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();db.export(id,bytes);boolean csv=false,json=false;
        try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))){
            for(ZipEntry e;(e=z.getNextEntry())!=null;){
                if(e.getName().startsWith("photos/"))continue;
                ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=z.read(buf))!=-1)b.write(buf,0,n);
                String body=b.toString("UTF-8");
                if(e.getName().endsWith("csv"))csv=body.contains("Банковская карта;85.6;53.98")&&body.contains("Лист А4");
                if(e.getName().endsWith("json"))json=body.contains("id1")&&body.contains("reference_width_mm");
            }
        }
        check(csv&&json,"card export");
        db.rename(legacyId,"Проверка переименования");
        JSONObject renamed=db.get(legacyId);check("Проверка переименования".equals(renamed.getString("name")),"rename");
        File copy=new File(db.photos,renamed.getString("photo"));db.delete(legacyId);
        check(!copy.exists(),"removed photo");
        boolean missing=false;try{db.get(legacyId);}catch(IllegalArgumentException e){missing=true;}check(missing,"removed row");
        db.close();
        log("UI: reference selection, demonstration, manual card, save, legacy A4 and export");
    }
    void flow()throws Exception{
        Intent i=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity=(MainActivity)startActivitySync(i);waitForIdleSync();
        click("Попробовать на примере");waitForButton("Сохранить замер");log("demo opened");
        click("Другой контур");waitForButton("Сохранить замер");
        click("Исправить границы");click("Углы А4");
        main(()->{
            PhotoMarkup canvas=first(activity.getWindow().getDecorView(),PhotoMarkup.class);check(canvas.paper.length==4,"paper corners");
            float sc=Math.min(canvas.getWidth()/(float)canvas.bitmap.getWidth(),canvas.getHeight()/(float)canvas.bitmap.getHeight());
            float x=(canvas.getWidth()-sc*canvas.bitmap.getWidth())/2+(float)canvas.paper[0].x*sc;
            float y=(canvas.getHeight()-sc*canvas.bitmap.getHeight())/2+(float)canvas.paper[0].y*sc;
            for(int action:new int[]{0,2,1}){
                long t=SystemClock.uptimeMillis();MotionEvent touch=MotionEvent.obtain(t,t,action,x+(action==0?0:0.4f),y,0);canvas.onTouchEvent(touch);touch.recycle();
            }
            check(!view("Сохранить замер").isEnabled(),"invalidation");
        });
        click("Пересчитать размер");waitForButton("Сохранить замер");log("manual corner drag and recalculate");
        click("Завершить правку");click("Сохранить замер");waitForButton("Переименовать замер");
        main(()->activity.onBackPressed());waitForIdleSync();
        main(()->activity.onBackPressed());waitForIdleSync();
        main(()->activity.finish());waitForIdleSync();
        activity=(MainActivity)startActivitySync(i);waitForIdleSync();
        main(()->{View label=view("Демонстрация");check(label!=null,"saved object after relaunch");((View)label.getParent()).performClick();});
        main(()->{View label=view("Учебный пример");check(label!=null,"record after relaunch");((View)label.getParent()).performClick();});
        waitForButton("Переименовать замер");log("saved record reopened after activity restart");
        Store store=new Store(getTargetContext());String id=store.byName("Демонстрация");
        List<JSONObject> records=store.records(id);check(records.size()>0,"db");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();store.export(id,bytes);
        int photos=0;boolean csv=false,json=false;
        try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))){
            for(ZipEntry e;(e=z.getNextEntry())!=null;){
                if(e.getName().startsWith("photos/")){check(BitmapFactory.decodeStream(z)!=null,"jpeg");photos++;}
                else if(e.getName().equals("measurements.json")){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=z.read(buf))!=-1)b.write(buf,0,n);json=new JSONArray(b.toString("UTF-8")).length()==records.size();}
                else if(e.getName().equals("measurements.csv")){csv=z.read()!=-1;}
            }
        }
        check(csv&&json&&photos==records.size(),"export");log("ZIP: CSV, JSON, "+photos+" JPEG");store.close();
    }
    private void badInput(Point[] op,Point[] ref,Reference type)throws Exception{
        try{Geometry.measure(op,ref,1500,1400,type,true);}catch(IllegalArgumentException expected){return;}
        throw new AssertionError("invalid input accepted");
    }
    private void invalidGeometry()throws Exception{
        JSONObject f=new JSONObject(string(getContext().getAssets().open("card.json")));
        Point[] op=Geometry.points(f.getJSONArray("opening")),ref=Geometry.points(f.getJSONArray("reference"));
        badInput(op,new Point[0],Reference.CARD);
        badInput(Arrays.copyOf(op,3),ref,Reference.CARD);
        badInput(new Point[]{op[0],op[2],op[1],op[3]},ref,Reference.CARD);
        badInput(new Point[]{op[0],op[0],op[2],op[3]},ref,Reference.CARD);
        badInput(new Point[]{new Point(-1,200),op[1],op[2],op[3]},ref,Reference.CARD);
        badInput(op,new Point[]{new Point(1,1),new Point(10,1),new Point(10,8),new Point(1,8)},Reference.CARD);
        badInput(op,ref,Reference.A4);
        log("Invalid input: missing, incomplete, crossing, duplicate, out-of-image and undersized references rejected");
    }
    private void cardComparison()throws Exception{
        Random draws=new Random(2026);
        for(String scene:new String[]{"card","card-portrait","card-rotated","card-inside"}){
            JSONObject f=new JSONObject(string(getContext().getAssets().open(scene+".json")));
            Point[] op=Geometry.points(f.getJSONArray("opening")),ref=Geometry.points(f.getJSONArray("reference"));
            boolean horizontal=f.getBoolean("landscape");
            double[] baseline=new double[100],improved=new double[100];int oldValid=0,newValid=0;
            for(int i=0;i<100;i++){
                Point[] noisyOp=Geometry.perturb(op,draws),noisyRef=Geometry.perturb(ref,draws);
                try{
                    Point[] projected=Geometry.rectify(noisyOp,noisyRef,Reference.CARD.width(horizontal),Reference.CARD.height(horizontal));
                    double[] dims=Geometry.dimensions(Geometry.sides(projected));
                    baseline[oldValid++]=Math.max(Math.abs(dims[0]/1200-1),Math.abs(dims[1]/1500-1))*100;
                }catch(IllegalArgumentException badProjection){}
                try{
                    JSONObject r=Geometry.measure(noisyOp,noisyRef,1500,1400,Reference.CARD,horizontal);
                    improved[newValid++]=Math.max(Math.abs(r.getDouble("width_mm")/1200-1),Math.abs(r.getDouble("height_mm")/1500-1))*100;
                }catch(IllegalArgumentException badGeometry){}
            }
            check(oldValid>0&&newValid>0,"noise simulation");
            Arrays.sort(baseline,0,oldValid);Arrays.sort(improved,0,newValid);
            log(String.format(Locale.US,"NOISE %s n=100 old_valid=%d old_median_pct=%.3f old_p95_pct=%.3f new_valid=%d new_median_pct=%.3f new_p95_pct=%.3f",
                scene,oldValid,percentile(baseline,oldValid,.5),percentile(baseline,oldValid,.95),
                newValid,percentile(improved,newValid,.5),percentile(improved,newValid,.95)));
        }
    }
    private static double percentile(double[] values,int n,double fraction){
        double pos=(n-1)*fraction;int low=(int)pos,high=Math.min(low+1,n-1);
        return values[low]+(values[high]-values[low])*(pos-low);
    }
    private void reopenSaved()throws Exception{
        activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        main(()->{View label=view("Демонстрация");check(label!=null,"object after force-stop");((View)label.getParent()).performClick();});
        main(()->{View label=view("Учебный пример");check(label!=null,"record after force-stop");((View)label.getParent()).performClick();});
        waitForButton("Переименовать замер");
        main(()->check(first(activity.getWindow().getDecorView(),PhotoMarkup.class).bitmap!=null,"saved photograph after force-stop"));
        log("Persisted object, measurement and photograph reopened after an external force-stop");
    }
    @Override public void onStart(){
        Bundle results=new Bundle();
        try{
            if(afterRestart)reopenSaved();
            else{fixtures();cardFixtures();invalidGeometry();cardComparison();flow();cardFlow();}
            results.putString("stream","\n"+lines+"ALL PASSED\n");finish(Activity.RESULT_OK,results);}
        catch(Throwable e){android.util.Log.e("OknoCheck","Error",e);results.putString("stream","\n"+lines+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,results);}
    }
}
