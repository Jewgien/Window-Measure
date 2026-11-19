package org.student.oknomer;

import java.util.*;
import org.json.*;
import org.opencv.core.Point;

final class Geometry {
    static double length(Point a, Point b) { return Math.hypot(a.x-b.x,a.y-b.y); }
    static double[] sides(Point[] p) {
        double[] s = new double[4];
        for (int i=0;i<4;i++) s[i]=length(p[i],p[(i+1)%4]);
        return s;
    }
    static double[] dimensions(double[] s) { return new double[]{(s[0]+s[2])/2,(s[1]+s[3])/2}; }
    static double area(Point[] p) {
        double sum=0;
        for (int i=0;i<4;i++) { Point a=p[i], b=p[(i+1)%4]; sum+=a.x*b.y-a.y*b.x; }
        return Math.abs(sum)/2;
    }
    static void check(Point[] p,String title,double min) {
        if(p.length!=4) throw new IllegalArgumentException(title+" — укажите четыре угла.");
        double first=0;
        for(int i=0;i<4;i++){
            Point a=p[i],b=p[(i+1)%4],c=p[(i+2)%4];
            if(length(a,b)<min) throw new IllegalArgumentException((min>=32 ? title+" — снимите ближе. Короткая сторона эталона должна занимать хотя бы "+(int)min+" пикс. по длине." : title+" — две точки слишком близки."));
            double cross=(b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x);
            if(!Double.isFinite(cross)||Math.abs(cross)<1e-4||(i>0&&cross*first<=0)) throw new IllegalArgumentException(title+" — обойдите углы по порядку, начиная с верхнего левого.");
            if(i==0) first=cross;
        }
        if(area(p)<min*min) throw new IllegalArgumentException(title+" — площадь контура слишком мала.");
    }
    // Map the reference quadrilateral to the paper plane by solving eight linear equations.
    static double[] homography(Point[] ref,double w,double h) {
        double[] u={0,w,w,0},v={0,0,h,h};
        double[][] rows=new double[8][9];
        for(int i=0;i<4;i++){
            double x=ref[i].x,y=ref[i].y;
            rows[i*2]=new double[]{x,y,1,0,0,0,-x*u[i],-y*u[i],u[i]};
            rows[i*2+1]=new double[]{0,0,0,x,y,1,-x*v[i],-y*v[i],v[i]};
        }
        for(int j=0;j<8;j++){
            int pivot=j;
            for(int i=j+1;i<8;i++) if(Math.abs(rows[i][j])>Math.abs(rows[pivot][j])) pivot=i;
            double[] temp=rows[j]; rows[j]=rows[pivot]; rows[pivot]=temp;
            double d=rows[j][j];
            if(Math.abs(d)<1e-12) throw new IllegalArgumentException("Не удалось исправить перспективу. Проверьте четыре угла.");
            for(int k=j;k<9;k++) rows[j][k]/=d;
            for(int i=0;i<8;i++) if(i!=j){
                double factor=rows[i][j];
                for(int k=j;k<9;k++) rows[i][k]-=factor*rows[j][k];
            }
        }
        double[] m=new double[8];
        for(int i=0;i<8;i++) m[i]=rows[i][8];
        return m;
    }
    static Point[] rectify(Point[] op,Point[] ref,double width,double height){
        double[] m=homography(ref,width,height);
        Point[] out=new Point[4];
        double first=denominator(m,ref[0]);
        for(Point p:ref) verifyPlane(first,denominator(m,p));
        for(int i=0;i<4;i++){
            Point p=op[i]; double z=denominator(m,p); verifyPlane(first,z);
            out[i]=new Point((m[0]*p.x+m[1]*p.y+m[2])/z,(m[3]*p.x+m[4]*p.y+m[5])/z);
        }
        return out;
    }
    private static double denominator(double[] m,Point p){return m[6]*p.x+m[7]*p.y+1;}
    private static void verifyPlane(double a,double z){
        if(a*z<=0||Math.abs(z)<1e-8) throw new IllegalArgumentException("Слишком сильный наклон. Снимите проём ближе к фронтальному виду.");
    }
    static Point[] perturb(Point[] p,Random r){
        Point[] n=new Point[4];
        for(int i=0;i<4;i++) n[i]=new Point(p[i].x+r.nextGaussian(),p[i].y+r.nextGaussian());
        return n;
    }
    static double round(double v,int digits){double factor=Math.pow(10,digits);return Math.round(v*factor)/factor;}
    // Rectifying the large opening avoids extrapolating a homography from a tiny card.
    // In the unit-opening plane each row of [cardEdge1/rw, cardEdge2/rh]
    // is a row of a rotation matrix divided by the corresponding opening dimension.
    static Point[] metricCard(Point[] opening,Point[] card,double rw,double rh) {
        Point[] q=rectify(card,opening,1,1);
        Point a=new Point((q[1].x-q[0].x+q[2].x-q[3].x)/2,(q[1].y-q[0].y+q[2].y-q[3].y)/2);
        Point b=new Point((q[3].x-q[0].x+q[2].x-q[1].x)/2,(q[3].y-q[0].y+q[2].y-q[1].y)/2);
        double w=1/Math.hypot(a.x/rw,b.x/rh),h=1/Math.hypot(a.y/rw,b.y/rh);
        if(!Double.isFinite(w)||!Double.isFinite(h))throw new IllegalArgumentException("Проверьте контур карты.");
        for(Point point:q){point.x*=w;point.y*=h;}
        return new Point[]{new Point(0,0),new Point(w,0),new Point(w,h),new Point(0,h),q[0],q[1],q[2],q[3]};
    }
    static Point[] metric(Point[] op,Point[] ref,Reference type,boolean landscape){
        double rw=type.width(landscape),rh=type.height(landscape);
        return type==Reference.CARD?metricCard(op,ref,rw,rh):rectify(op,ref,rw,rh);
    }
    static JSONObject measure(Point[] op,Point[] ref,int width,int height,boolean landscape) throws JSONException {
        return measure(op,ref,width,height,Reference.A4,landscape);
    }
    static JSONObject measure(Point[] op,Point[] ref,int width,int height,Reference type,boolean landscape) throws JSONException {
        check(op,"Проём",5);check(ref,type.label,type.minPixels);
        for(Point[] points:new Point[][]{op,ref}) for(Point p:points)
            if(p.x<0||p.x>=width||p.y<0||p.y>=height) throw new IllegalArgumentException("Все углы должны быть внутри фотографии.");
        Point[] mapped=metric(op,ref,type,landscape);
        Point[] plane=Arrays.copyOf(mapped,4);check(plane,"Проём",1);
        Point[] qualityQuad=type==Reference.CARD?Arrays.copyOfRange(mapped,4,8):plane;
        double[] s=sides(plane),d=dimensions(s);
        double[] qs=sides(qualityQuad),qd=dimensions(qs);
        double mismatch=Math.max(Math.abs(qs[0]-qs[2])/qd[0],Math.abs(qs[1]-qs[3])/qd[1]);
        if(type==Reference.CARD){
            mismatch=Math.max(mismatch,Math.max(Math.abs(qd[0]/type.width(landscape)-1),Math.abs(qd[1]/type.height(landscape)-1)));
        }
        double cosine=0;
        for(int i=0;i<4;i++){
            Point a=qualityQuad[i],b=qualityQuad[(i+1)%4],c=qualityQuad[(i+2)%4];
            cosine=Math.max(cosine,Math.abs((b.x-a.x)*(c.x-b.x)+(b.y-a.y)*(c.y-b.y))/(qs[i]*qs[(i+1)%4]));
        }
        if(mismatch>.2||cosine>.3) throw new IllegalArgumentException("Проверьте углы обоих контуров, ориентацию эталона и совпадение плоскостей.");
        if(Math.min(d[0],d[1])<50||Math.max(d[0],d[1])>15000) throw new IllegalArgumentException("Размер за пределами диапазона 50–15 000 мм. Проверьте разметку.");
        Random rng=new Random(42); double[] deviations=new double[40];
        for(int i=0;i<deviations.length;i++){
            Point[] variedRef=perturb(ref,rng);
            try {
                double[] v=dimensions(sides(metric(perturb(op,rng),variedRef,type,landscape)));
                deviations[i]=Math.max(Math.abs(v[0]/d[0]-1),Math.abs(v[1]/d[1]-1));
            } catch(IllegalArgumentException e){deviations[i]=1;}
        }
        Arrays.sort(deviations); double sensitivity=100*(deviations[37]*.95+deviations[38]*.05);
        if(sensitivity>20) throw new IllegalArgumentException("Разметка неустойчива. Увеличьте эталон в кадре или уменьшите наклон.");
        double[] raw=dimensions(sides(op)),paper=dimensions(sides(ref));
        JSONArray warnings=new JSONArray();
        if(sensitivity>5) warnings.put("Результат чувствителен к положению точек. Уточните углы эталона или снимите ближе.");
        if(mismatch>.05||cosine>.10) warnings.put("Проверьте, что эталон и границы проёма лежат в одной плоскости.");
        return new JSONObject().put("width_mm",round(d[0],1)).put("height_mm",round(d[1],1))
            .put("baseline_width_mm",round(raw[0]*type.width(landscape)/paper[0],1))
            .put("baseline_height_mm",round(raw[1]*type.height(landscape)/paper[1],1))
            .put("sensitivity_pct",round(sensitivity,2)).put("mismatch_pct",round(mismatch*100,2))
            .put("reference_type",type.id).put("reference_width_mm",type.width(landscape)).put("reference_height_mm",type.height(landscape))
            .put("algorithm",type==Reference.A4?"a4-homography-v1":"id1-opening-homography-v1").put("landscape",landscape)
            .put("warnings",warnings).put("rectified_points",json(plane));
    }
    static JSONArray json(Point[] points) throws JSONException {
        JSONArray a=new JSONArray();
        for(Point p:points) a.put(new JSONArray().put(p.x).put(p.y));
        return a;
    }
    static Point[] points(JSONArray a) throws JSONException {
        Point[] p=new Point[a.length()];
        for(int i=0;i<p.length;i++){ JSONArray pair=a.getJSONArray(i); p[i]=new Point(pair.getDouble(0),pair.getDouble(1)); }
        return p;
    }
    static Point[] copy(Point[] points){
        Point[] p=new Point[points.length];for(int i=0;i<p.length;i++)p[i]=points[i].clone();return p;
    }
}
