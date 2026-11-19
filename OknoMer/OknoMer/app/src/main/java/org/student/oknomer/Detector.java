package org.student.oknomer;

import android.graphics.Bitmap;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.*;

final class Detector {
    static final class Found {
        Point[] paper=new Point[0];
        final ArrayList<Point[]> openings=new ArrayList<>();
        boolean landscape;
    }
    private static Point[] ordered(Point[] p){
        double x=0,y=0;
        for(Point pt:p){x+=pt.x;y+=pt.y;}
        final double cx=x/4,cy=y/4;
        Arrays.sort(p,(a,b)->Double.compare(Math.atan2(a.y-cy,a.x-cx),Math.atan2(b.y-cy,b.x-cx)));
        int first=0;
        for(int i=1;i<4;i++) if(p[i].x+p[i].y<p[first].x+p[first].y) first=i;
        return new Point[]{p[first],p[(first+1)%4],p[(first+2)%4],p[(first+3)%4]};
    }
    static Found find(Bitmap bitmap){return find(bitmap,Reference.A4);}
    static Found find(Bitmap bitmap,Reference type) {
        System.loadLibrary("opencv_java4");
        Mat rgba=new Mat(),gray=new Mat();
        try {
            Utils.bitmapToMat(bitmap,rgba);
            Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY);
            return findGray(gray,rgba,type);
        } finally {rgba.release();gray.release();}
    }
    static Found findGray(Mat gray,Mat rgba,Reference type) {
        double scale=Math.min(1,(type==Reference.CARD?1600.0:1200.0)/Math.max(gray.rows(),gray.cols()));
        Mat small=new Mat(),blur=new Mat(),mask=new Mat();
        Found found=new Found();
        try{
            Imgproc.resize(gray,small,new Size(),scale,scale,Imgproc.INTER_LINEAR);
            double best=0;
            if(type==Reference.CARD){
                found.paper=findCard(rgba,small,scale);
            }else for(int t:new int[]{170,200,225}){
                Imgproc.threshold(small,mask,t,255,Imgproc.THRESH_BINARY);
                for(Point[] p:quadrangles(mask,1000,.4*small.total(),.02)){
                    double[] sides=Geometry.sides(p),d=Geometry.dimensions(sides);
                    double ratio=Math.max(d[0],d[1])/Math.min(d[0],d[1]);
                    if(ratio<=1.1||ratio>=2.2||min(sides)/scale<60)continue;
                    double score=Geometry.area(p)/(1+4*Math.abs(ratio-297./210));
                    if(score<=best||whiteCoverage(mask,p)<.7)continue;
                    Point[] full=scaled(p,1/scale);
                    MatOfPoint2f refined=new MatOfPoint2f(full);
                    try {
                        Imgproc.cornerSubPix(gray,refined,new Size(5,5),new Size(-1,-1),new TermCriteria(TermCriteria.COUNT+TermCriteria.EPS,25,.01));
                        Point[] sub=refined.toArray();boolean nearby=true;
                        for(int i=0;i<4;i++) if(Geometry.length(full[i],sub[i])>=8)nearby=false;
                        found.paper=nearby?sub:full;
                        best=score;
                    } finally {refined.release();}
                }
            }
            double area=gray.total(),paperArea=found.paper.length==4?Geometry.area(found.paper):0;
            Imgproc.GaussianBlur(small,blur,new Size(5,5),0);
            for(int t:new int[]{-1,-2,60,100,140,180}){
                if(t<0)Imgproc.Canny(blur,mask,t==-1?35:70,t==-1?100:180);
                else Imgproc.threshold(blur,mask,t,255,Imgproc.THRESH_BINARY);
                for(Point[] p:quadrangles(mask,Math.max(.035*area,3*paperArea)*scale*scale,.9*area*scale*scale,.018)){
                    Point[] full=scaled(p,1/scale);double[] s=Geometry.sides(full),d=Geometry.dimensions(s);
                    if(min(s)<80||Math.max(d[0],d[1])/Math.min(d[0],d[1])>4.5)continue;
                    boolean reject=false;
                    for(Point q:full) if(q.x<3||q.y<3||q.x>gray.cols()-4||q.y>gray.rows()-4)reject=true;
                    if(reject||(type==Reference.A4&&overlap(full,found.paper)>.05*paperArea))continue;
                    for(Point[] other:found.openings){
                        double delta=0;
                        for(int i=0;i<4;i++)delta+=Geometry.length(other[i],full[i]);
                        if(delta/4<8/scale){reject=true;break;}
                    }
                    if(!reject) found.openings.add(full);
                }
            }
            found.openings.sort((a,b)->Double.compare(Geometry.area(b),Geometry.area(a)));
            while(found.openings.size()>8)found.openings.remove(found.openings.size()-1);
            if(found.paper.length==4){
                double[] size=Geometry.dimensions(Geometry.sides(found.paper));
                found.landscape=size[0]>size[1];
            }
            return found;
        }finally{small.release();blur.release();mask.release();}
    }
    // A bank card can be dark or patterned. Traces from colour channels and both
    // threshold polarities are ranked by outline geometry, independently of any print.
    private static Point[] findCard(Mat rgba,Mat smallGray,double scale){
        Mat color=new Mat(),channel=new Mat(),blur=new Mat(),mask=new Mat();
        double best=0;Point[] chosen=new Point[0];
        try{
            Imgproc.resize(rgba,color,smallGray.size(),0,0,Imgproc.INTER_AREA);
            for(int band=-1;band<3;band++){
                if(band<0)smallGray.copyTo(channel);else Core.extractChannel(color,channel,band);
                Imgproc.GaussianBlur(channel,blur,new Size(3,3),0);
                int[] levels=band<0?new int[]{-1,55,105,155,205}:new int[]{-1};
                for(int level:levels){
                    if(level<0)Imgproc.Canny(blur,mask,22,70);
                    else Imgproc.threshold(blur,mask,level,255,Imgproc.THRESH_BINARY);
                    ArrayList<MatOfPoint> contours=new ArrayList<>();Mat hierarchy=new Mat();
                    MatOfPoint2f curve=new MatOfPoint2f(),poly=new MatOfPoint2f();
                    try{
                        Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_NONE);
                        for(MatOfPoint contour:contours){
                            double area=Imgproc.contourArea(contour);
                            if(area<Math.max(100,400*scale*scale)||area>smallGray.total()*.065)continue;
                            contour.convertTo(curve,CvType.CV_32F);
                            Imgproc.approxPolyDP(curve,poly,.026*Imgproc.arcLength(curve,true),true);
                            if(poly.total()!=4)continue;
                            Point[] q=ordered(poly.toArray());
                            double[] sides=Geometry.sides(q),dims=Geometry.dimensions(sides);
                            if(min(sides)<22*scale)continue;
                            double ratio=Math.max(dims[0],dims[1])/Math.min(dims[0],dims[1]);
                            if(ratio<1.13||ratio>2.18)continue;
                            boolean clipped=false;
                            for(Point v:q)if(v.x<2||v.y<2||v.x>=mask.cols()-2||v.y>=mask.rows()-2)clipped=true;
                            if(clipped)continue;
                            double target=Reference.CARD.longSide/Reference.CARD.shortSide;
                            double diff=Math.abs(Math.log(ratio/target));
                            double asymmetry=Math.abs(sides[0]-sides[2])/dims[0]+Math.abs(sides[1]-sides[3])/dims[1];
                            double fill=area/Geometry.area(q);
                            if(fill<.88||fill>1.25)continue;
                            // The physical card is normally much smaller than the opening.
                            double fraction=area/smallGray.total();
                            double score=1/(1+5*diff+asymmetry+Math.max(0,Math.log(fraction/.008)));
                            if(score<=best)continue;
                            Point[] precise=straightCorners(q,contour.toArray());
                            if(precise==null)continue;
                            chosen=scaled(precise,1/scale);best=score;
                        }
                    }finally{for(Mat c:contours)c.release();hierarchy.release();curve.release();poly.release();}
                }
            }
            return chosen;
        }finally{color.release();channel.release();blur.release();mask.release();}
    }
    // Fit the straight middle section of each edge. Their intersections recover
    // the nominal rectangular corners even when the physical card has rounded tips.
    private static Point[] straightCorners(Point[] q,Point[] outline){
        double[][] lines=new double[4][3];
        for(int i=0;i<4;i++){
            Point a=q[i],b=q[(i+1)%4];double dx=b.x-a.x,dy=b.y-a.y,len=Math.hypot(dx,dy);
            double sx=0,sy=0,sxx=0,sxy=0,syy=0;int count=0;
            for(Point p:outline){
                double x=p.x-a.x,y=p.y-a.y,t=(x*dx+y*dy)/(len*len);
                if(t<.12||t>.88||Math.abs(x*dy-y*dx)/len>Math.max(2.6,.08*len))continue;
                sx+=x;sy+=y;sxx+=x*x;sxy+=x*y;syy+=y*y;count++;
            }
            if(count<5)return null;
            double meanX=sx/count,meanY=sy/count;
            double theta=.5*Math.atan2(2*(sxy-count*meanX*meanY),sxx-syy-count*(meanX*meanX-meanY*meanY));
            double nx=-Math.sin(theta),ny=Math.cos(theta);
            lines[i]=new double[]{nx,ny,nx*(a.x+meanX)+ny*(a.y+meanY)};
        }
        Point[] result=new Point[4];
        for(int i=0;i<4;i++){
            double[] a=lines[(i+3)%4],b=lines[i];double det=a[0]*b[1]-a[1]*b[0];
            if(Math.abs(det)<.35)return null;
            result[i]=new Point((a[2]*b[1]-a[1]*b[2])/det,(a[0]*b[2]-a[2]*b[0])/det);
            if(Geometry.length(q[i],result[i])>Math.max(5,.15*min(Geometry.sides(q))))return null;
        }
        return result;
    }
    private static double min(double[] s){return Math.min(Math.min(s[0],s[1]),Math.min(s[2],s[3]));}
    private static Point[] scaled(Point[] p,double f){
        Point[] out=new Point[4]; for(int i=0;i<4;i++)out[i]=new Point(p[i].x*f,p[i].y*f); return out;
    }
    private static List<Point[]> quadrangles(Mat mask,double minArea,double maxArea,double epsilon){
        ArrayList<MatOfPoint> contours=new ArrayList<>(); Mat hierarchy=new Mat();
        List<Point[]> out=new ArrayList<>(); MatOfPoint2f c=new MatOfPoint2f(),poly=new MatOfPoint2f();
        MatOfPoint integers=new MatOfPoint();
        try{
            Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE);
            for(MatOfPoint contour:contours){
                double area=Imgproc.contourArea(contour);if(area<minArea||area>maxArea)continue;
                contour.convertTo(c,CvType.CV_32F);Imgproc.approxPolyDP(c,poly,epsilon*Imgproc.arcLength(c,true),true);
                if(poly.total()!=4)continue;
                poly.convertTo(integers,CvType.CV_32S);
                if(Imgproc.isContourConvex(integers))out.add(ordered(poly.toArray()));
            }
            return out;
        }finally{for(Mat contour:contours)contour.release();hierarchy.release();c.release();poly.release();integers.release();}
    }
    private static double whiteCoverage(Mat mask,Point[] p){
        Mat region=Mat.zeros(mask.size(),CvType.CV_8UC1);MatOfPoint contour=new MatOfPoint(p);
        try{Imgproc.fillConvexPoly(region,contour,new Scalar(255));return Core.mean(mask,region).val[0]/255;}
        finally{region.release();contour.release();}
    }
    private static double overlap(Point[] a,Point[] b){
        if(b.length!=4)return 0;
        MatOfPoint2f op=new MatOfPoint2f(a),ref=new MatOfPoint2f(b); Mat intersection=new Mat();
        try{return Imgproc.intersectConvexConvex(op,ref,intersection,true);}
        finally{op.release();ref.release();intersection.release();}
    }
}
