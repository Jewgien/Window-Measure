package org.student.oknomer;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import org.opencv.core.Point;
import java.util.Arrays;

final class PhotoMarkup extends View {
    Bitmap bitmap;
    Point[] opening=new Point[0],paper=new Point[0];
    boolean editing,editPaper;
    Runnable changed=()->{};
    private final RectF bounds=new RectF();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final float density=getResources().getDisplayMetrics().density;
    private float scale,zoom=1;
    private Point center;
    private boolean pickingCenter;
    void chooseZoom(){zoom=1;center=null;pickingCenter=true;invalidate();}
    void fitPhoto(){zoom=1;center=null;pickingCenter=false;invalidate();}
    void focusCard(){
        if(paper.length!=4||getWidth()==0||bitmap==null)return;
        double x=0,y=0,maxX=0,maxY=0,minX=bitmap.getWidth(),minY=bitmap.getHeight();
        for(Point p:paper){
            x+=p.x;y+=p.y;minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);minY=Math.min(minY,p.y);maxY=Math.max(maxY,p.y);
        }
        double base=Math.min(getWidth()/(double)bitmap.getWidth(),getHeight()/(double)bitmap.getHeight());
        zoom=(float)Math.max(1,Math.min(12,.65*Math.min(getWidth()/((maxX-minX)*base),getHeight()/((maxY-minY)*base))));
        center=new Point(x/4,y/4);pickingCenter=false;invalidate();
    }
    private float origin(float viewport,float scaled,double focus){
        if(scaled<=viewport)return (viewport-scaled)/2;
        return Math.max(viewport-scaled,Math.min(0,viewport/2-(float)(focus*scale)));
    }
    private int drag=-1;
    PhotoMarkup(Context ctx){super(ctx);setContentDescription("Фотография с контурами проёма и эталона");}
    private void layoutImage(){
        scale=zoom*Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight());
        float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;
        float left=origin(getWidth(),w,center==null?bitmap.getWidth()/2.0:center.x);
        float top=origin(getHeight(),h,center==null?bitmap.getHeight()/2.0:center.y);
        bounds.set(left,top,left+w,top+h);
    }
    @Override protected void onDraw(Canvas c){
        c.drawColor(0xffe2e8df); if(bitmap==null)return;
        layoutImage();paint.setColor(-1);paint.setStyle(Paint.Style.FILL);
        c.drawBitmap(bitmap,null,bounds,paint);
        outline(c,opening,0xff225ec5);outline(c,paper,0xff0f8059);
        if(drag>=0){
            Point p=(editPaper?paper:opening)[drag];
            float size=Math.min(106*density,getWidth()*.34f);
            float x= (bounds.left+p.x*scale)>getWidth()/2 ? 6*density :getWidth()-size-6*density;
            float y=6*density;
            RectF lens=new RectF(x,y,x+size,y+size);
            c.save();c.clipRect(lens);c.drawColor(0xff22342d);
            float zoom=Math.max(scale*3,size/60);
            RectF source=new RectF((float)(lens.centerX()-p.x*zoom),(float)(lens.centerY()-p.y*zoom),0,0);
            source.right=source.left+bitmap.getWidth()*zoom;source.bottom=source.top+bitmap.getHeight()*zoom;
            paint.setColor(-1);c.drawBitmap(bitmap,null,source,paint);c.restore();
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*density);c.drawRect(lens,paint);
            paint.setColor(0xffe25633);c.drawLine(lens.centerX()-8*density,lens.centerY(),lens.centerX()+8*density,lens.centerY(),paint);
            c.drawLine(lens.centerX(),lens.centerY()-8*density,lens.centerX(),lens.centerY()+8*density,paint);paint.setStyle(Paint.Style.FILL);
        }
    }
    private void outline(Canvas c,Point[] p,int color){
        if(p.length==0)return;
        Path path=new Path();
        for(int i=0;i<p.length;i++){
            float x=bounds.left+(float)p[i].x*scale,y=bounds.top+(float)p[i].y*scale;
            if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
        }
        if(p.length==4)path.close();
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4*density);paint.setColor(0xccffffff);c.drawPath(path,paint);
        paint.setStrokeWidth(2*density);paint.setColor(color);c.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);
        for(int i=0;i<p.length;i++){
            float x=bounds.left+(float)p[i].x*scale,y=bounds.top+(float)p[i].y*scale;
            float radius=(editing?7:4)*density;paint.setColor(-1);c.drawCircle(x,y,radius+density,paint);
            paint.setColor(color);c.drawCircle(x,y,radius,paint);
            if(editing){paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(10*density);paint.setColor(-1);c.drawText(String.valueOf(i+1),x,y+3.4f*density,paint);}
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!editing||bitmap==null)return false;
        layoutImage();float x=e.getX(),y=e.getY();Point[] points=editPaper?paper:opening;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                if(!bounds.contains(x,y))return false;
                if(pickingCenter){
                    center=imagePoint(x,y);zoom=8;pickingCenter=false;invalidate();return true;
                }
                double best=points.length==4?25*density:0;drag=-1;
                for(int i=0;i<points.length;i++){
                    double dist=Math.hypot(bounds.left+points[i].x*scale-x,bounds.top+points[i].y*scale-y);
                    if(dist<best){best=dist;drag=i;}
                }
                if(drag<0){
                    if(points.length==4)return false;
                    drag=points.length;points=Arrays.copyOf(points,drag+1);
                    points[drag]=imagePoint(x,y);
                    if(editPaper)paper=points;else opening=points;
                    changed.run();
                }
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                getParent().requestDisallowInterceptTouchEvent(true);invalidate();return true;
            case MotionEvent.ACTION_MOVE:
                if(drag<0)return false;
                points[drag]=imagePoint(x,y);changed.run();invalidate();return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drag=-1;getParent().requestDisallowInterceptTouchEvent(false);invalidate();performClick();return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private Point imagePoint(float x,float y){
        return new Point(Math.max(0,Math.min(bitmap.getWidth()-1,(x-bounds.left)/scale)),Math.max(0,Math.min(bitmap.getHeight()-1,(y-bounds.top)/scale)));
    }
}
