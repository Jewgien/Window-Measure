package org.student.oknomer;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.opencv.core.Point;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    static final int BG=0xfff4f6f1,INK=0xff162f29,MUTED=0xff52675e,GREEN=0xff125c49,PALE=0xffe1ece2,WHITE=0xffffffff;
    static final int PICK=21,CAMERA=22,EXPORT=23;
    static final String QUICK="Мои замеры", DEMO="Демонстрация";
    static final Locale RU=new Locale("ru");
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private Store store;
    private String page="home",objectId,savedId, pendingPhoto, exportingId;
    private Uri cameraUri;
    private File draft;
    private Bitmap bitmap;
    private int serial,candidate;
    private boolean manual,editPaper,landscape,busy,demoPhoto;
    private Reference reference=Reference.A4;
    private String measurementName="Проём",note="";
    private Point[] opening=new Point[0],paper=new Point[0];
    private List<Point[]> choices=new ArrayList<>();
    private JSONObject result;
    private LinearLayout root,body,controls;
    private TextView size,hint;
    private Button save,openingButton,paperButton,orientationButton;
    private EditText nameInput;
    private PhotoMarkup markup;
    interface Task { void run() throws Exception; }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        store=new Store(this);
        // Own the insets on all supported Android versions, including edge-to-edge displays.
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        if(state==null)home();else safe(()->restore(state));
        if(root==null)home();
    }
    private void safe(Task task){try{task.run();}catch(Exception e){alert("ОкноМер",error(e));}}
    private String error(Throwable e){return e.getLocalizedMessage()==null?"Не удалось выполнить действие. Попробуйте ещё раз.":e.getLocalizedMessage();}
    private void alert(String title,String msg){
        if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("Понятно",null).show();
    }
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_SHORT).show();}
    private int dp(float d){return Math.round(d*getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,int radius){
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout.LayoutParams lp(int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);return p;
    }
    private TextView text(String title,int sp,int color,boolean bold){
        TextView t=new TextView(this);t.setText(title);t.setTextSize(sp);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        t.setIncludeFontPadding(false);t.setLineSpacing(dp(2),1);return t;
    }
    private void line(LinearLayout parent,String value,int sp,int color,boolean bold,int top){
        parent.addView(text(value,sp,color,bold),lp(top));
    }
    private void page(String id,String title,boolean back){
        page=id;nameInput=null;markup=null;
        root=column();root.setBackgroundColor(BG);root.setFocusableInTouchMode(true);
        root.setOnApplyWindowInsetsListener((v,in)->{
            root.setPadding(in.getSystemWindowInsetLeft(),in.getSystemWindowInsetTop(),in.getSystemWindowInsetRight(),in.getSystemWindowInsetBottom());return in.consumeSystemWindowInsets();
        });
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(18),dp(4),dp(18),dp(4));
        if(back){
            Button prev=button("‹",false,this::onBackPressed);prev.setTextSize(28);prev.setPadding(0,0,0,dp(3));
            prev.setContentDescription("Назад");header.addView(prev,new LinearLayout.LayoutParams(dp(44),dp(44)));
        }
        TextView h=text(title,back?23:30,INK,true);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,-2,1);hp.leftMargin=back?dp(12):0;
        header.addView(h,hp);root.addView(header,new LinearLayout.LayoutParams(-1,dp(66)));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
        body=column();body.setPadding(dp(18),0,dp(18),dp(28));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);root.requestApplyInsets();
    }
    private Button button(String label,boolean primary,Task click){
        Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextSize(15);b.setGravity(Gravity.CENTER);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextColor(primary?WHITE:GREEN);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x25145e49),background(primary?GREEN:PALE,12),null));
        b.setPadding(dp(9),dp(8),dp(9),dp(8));b.setStateListAnimator(null);b.setOnClickListener(v->safe(click));return b;
    }
    private Button action(LinearLayout parent,String title,boolean primary,Task click){
        Button b=button(title,primary,click);parent.addView(b,lp(8));return b;
    }
    private LinearLayout row(LinearLayout parent){
        LinearLayout l=new LinearLayout(this);l.setBaselineAligned(false);parent.addView(l,lp(8));return l;
    }
    private void half(LinearLayout row,Button b){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(row.getChildCount()>0)p.leftMargin=dp(8);row.addView(b,p);
    }
    private void item(String title,String detail,Task click){
        LinearLayout item=column();item.setPadding(dp(17),dp(14),dp(17),dp(14));
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x25145e49),background(WHITE,14),null));
        line(item,title,17,INK,true,0);line(item,detail,14,MUTED,false,5);item.setOnClickListener(v->safe(click));body.addView(item,lp(8));
    }
    private void prompt(String title,String value,java.util.function.Consumer<String> accepted){
        EditText input=new EditText(this);input.setSingleLine();input.setText(value);input.setSelectAllOnFocus(true);input.setHint("Название");
        FrameLayout wrap=new FrameLayout(this);wrap.setPadding(dp(24),dp(8),dp(24),0);wrap.addView(input);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(wrap).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            String name=input.getText().toString().trim();
            if(name.isEmpty()){input.setError("Введите название");return;}dialog.dismiss();accepted.accept(name);
        }));dialog.show();
    }
    private void home(){
        page("home","ОкноМер",false);
        LinearLayout card=column();card.setBackground(background(GREEN,20));card.setPadding(dp(20),dp(21),dp(20),dp(21));
        line(card,"Размер проёма\nпо фотографии",28,WHITE,true,0);
        line(card,"Поместите лист А4 или банковскую карту в одной плоскости с границами окна или двери. В кадре должны быть видны все четыре угла проёма и эталона.",16,0xffe0f0e5,false,11);body.addView(card,lp(2));
        action(body,"Измерить по фотографии",true,()->start(store.byName(QUICK),false));
        action(body,"Попробовать на примере",false,()->start(store.byName(DEMO),true));
        line(body,"Мои объекты",21,INK,true,24);
        List<String[]> all=store.objects();
        if(all.isEmpty())line(body,"Здесь появятся сохранённые замеры.",15,MUTED,false,8);
        for(String[] o:all)item(o[1],"Замеров: "+o[2],()->object(o[0]));
        action(body,"Создать объект",false,()->prompt("Название объекта","",name->safe(()->object(store.add(name)))));
    }
    private void object(String id)throws Exception{
        objectId=id;page("object",store.objectName(id),true);
        action(body,"Новый замер",true,()->start(id,false));
        List<JSONObject> rows=store.records(id);
        if(rows.isEmpty()){line(body,"Пока нет замеров",21,INK,true,24);line(body,"Добавьте фотографию проёма с листом А4 или банковской картой.",15,MUTED,false,6);}
        else{
            action(body,"Экспортировать объект в ZIP",false,this::beginExport);
            line(body,"Сохранённые замеры",20,INK,true,20);
            for(JSONObject r:rows)item(r.getString("name"),dims(r.getJSONObject("payload").getJSONObject("result"))+"  ·  "+date(r),()->saved(r.getString("id")));
        }
    }
    private String date(JSONObject row)throws JSONException{
        return DateTimeFormatter.ofPattern("dd.MM.yyyy",RU).withZone(ZoneId.systemDefault()).format(Instant.parse(row.getString("created")));
    }
    private String dims(JSONObject d){return String.format(RU,"%.0f × %.0f мм",d.optDouble("width_mm"),d.optDouble("height_mm"));}
    private void clearDraft(){
        serial++;pendingPhoto=null;busy=false;bitmap=null;demoPhoto=false;reference=Reference.A4;
        if(draft!=null)draft.delete();draft=null;
        opening=new Point[0];paper=new Point[0];choices=new ArrayList<>();candidate=0;landscape=false;manual=false;editPaper=false;result=null;note="";nameInput=null;markup=null;
    }
    private void start(String id,boolean demo)throws Exception{
        clearDraft();objectId=id;measurementName=demo?"Учебный пример":"Проём";editor();
        if(demo)load("demo");
    }
    private void sync(){
        if(nameInput!=null&&"edit".equals(page))measurementName=nameInput.getText().toString();
        if(markup!=null&&"edit".equals(page)){opening=markup.opening;paper=markup.paper;}
    }
    private void editor(){
        page("edit","Новый замер",true);
        line(body,"Эталон на фотографии",16,INK,true,3);
        LinearLayout types=row(body);
        for(Reference type:Reference.values()){
            Button choice=button(type.label,type==reference,()->changeReference(type));
            choice.setSelected(type==reference);choice.setEnabled(!busy);half(types,choice);
        }
        String tips=reference==Reference.CARD?"85,60 × 53,98 мм. Должны быть видны все четыре края карты. Реквизиты можно закрыть.":"210 × 297 мм. Все четыре угла листа должны попасть в кадр.";
        line(body,tips,14,MUTED,false,7);
        LinearLayout select=row(body);
        half(select,button("Выбрать фото",true,this::pick));half(select,button("Камера",false,this::camera));
        if(bitmap==null){hint=text(busy?"Открываем фотографию…":note,15,MUTED,false);body.addView(hint,lp(12));return;}
        if(manual&&reference==Reference.CARD){
            LinearLayout zoomControls=row(body);
            half(zoomControls,button("Увеличить участок",false,()->{
                markup.chooseZoom();toast("Коснитесь участка фотографии, который хотите увеличить.");
            }));
            half(zoomControls,button("Весь снимок",false,()->markup.fitPhoto()));
        }
        markup=photo(body,bitmap,opening,paper);
        if(manual&&editPaper&&reference==Reference.CARD){
            PhotoMarkup view=markup;view.post(view::focusCard);
        }
        markup.editing=manual&&!busy;markup.editPaper=editPaper;markup.changed=()->{
            boolean completed=paper.length<4&&markup.paper.length==4;
            sync();
            if(completed){
                double[] edges=Geometry.dimensions(Geometry.sides(paper));
                landscape=edges[0]>edges[1];
                if(orientationButton!=null)orientationButton.setText(orientation());
            }
            result=null;note="После изменения углов нажмите «Пересчитать размер».";status();};
        TextView legend=text("●  Проём       ●  "+reference.label,14,MUTED,true);
        android.text.SpannableString colors=new android.text.SpannableString(legend.getText());
        colors.setSpan(new android.text.style.ForegroundColorSpan(0xff225ec5),0,8,0);colors.setSpan(new android.text.style.ForegroundColorSpan(0xff0b7450),15,colors.length(),0);
        legend.setText(colors);body.addView(legend,lp(7));
        size=text("",27,INK,true);body.addView(size,lp(16));hint=text("",15,MUTED,false);body.addView(hint,lp(5));
        LinearLayout options=row(body);
        if(choices.size()>1)half(options,button("Другой контур",false,()->{sync();candidate=(candidate+1)%choices.size();opening=Geometry.copy(choices.get(candidate));markup.opening=opening;markup.invalidate();calculate();}));
        half(options,button(manual?"Завершить правку":"Исправить границы",false,()->{sync();manual=!manual;if(!manual)calculate();editor();}));
        controls=column();body.addView(controls,lp(0));
        if(manual){
            if(reference==Reference.CARD)line(controls,"У карты закруглённые углы. Ставьте точки там, где пересеклись бы продолжения прямых краёв.",14,MUTED,false,8);
            line(controls,"Выберите контур. Касайтесь углов по часовой стрелке от верхнего левого или перетащите готовые точки.",14,MUTED,false,10);
            LinearLayout targets=row(controls);openingButton=button("Углы проёма",!editPaper,()->setTarget(false));paperButton=button(reference==Reference.CARD?"Углы карты":"Углы А4",editPaper,()->setTarget(true));
            half(targets,openingButton);half(targets,paperButton);
            orientationButton=action(controls,orientation(),false,()->{
                landscape=!landscape;orientationButton.setText(orientation());calculate();
            });
            action(controls,"Сбросить выбранные точки",false,()->{
                if(editPaper)markup.paper=new Point[0];else markup.opening=new Point[0];markup.invalidate();markup.changed.run();
            });
            action(controls,"Пересчитать размер",true,this::calculate);
        }
        line(body,"Название замера",14,MUTED,true,17);
        nameInput=new EditText(this);nameInput.setSingleLine();nameInput.setTextSize(17);nameInput.setText(measurementName);nameInput.setSelectAllOnFocus(true);body.addView(nameInput,lp(0));
        save=action(body,"Сохранить замер",true,this::save);status();
    }
    private void setTarget(boolean ref){sync();editPaper=ref;editor();}
    private void changeReference(Reference next){
        if(next==reference||busy)return;
        sync();reference=next;landscape=next==Reference.CARD;
        result=null;paper=new Point[0];markup=null;nameInput=null;
        if(bitmap==null){editor();return;}
        // Reuse the original in-memory pixels for a real photo, including any edited opening.
        load(demoPhoto?(next==Reference.CARD?"demo-card":"demo"):"current");
    }
    private String orientation(){
        return reference.shortLabel+(landscape?" горизонтально · ":" вертикально · ")+reference.dimensions(landscape);
    }
    private PhotoMarkup photo(LinearLayout p,Bitmap image,Point[] op,Point[] ref){
        PhotoMarkup m=new PhotoMarkup(this);m.bitmap=image;m.opening=op;m.paper=ref;
        int width=(int)(getResources().getDisplayMetrics().widthPixels-2*dp(18));
        int height=Math.min(dp(385),Math.max(dp(230),Math.round(width*(image.getHeight()/(float)image.getWidth()))));
        LinearLayout.LayoutParams params=lp(12);params.height=height;
        m.setBackground(background(PALE,13));m.setClipToOutline(true);p.addView(m,params);return m;
    }
    private void status(){
        if(!"edit".equals(page)||bitmap==null||size==null||save==null)return;
        size.setText(busy?"Ищем границы…":result==null?"Уточните разметку":dims(result));
        hint.setText(busy?"Идёт обработка фотографии.":note);boolean enabled=result!=null&&!busy;save.setEnabled(enabled);save.setAlpha(enabled?1f:.45f);
    }
    private void calculate(){
        if(bitmap==null||busy)return;sync();
        try{
            result=Geometry.measure(opening,paper,bitmap.getWidth(),bitmap.getHeight(),reference,landscape);
            note="Ширина × высота. Проверьте синий контур проёма и зелёный контур эталона.";
            JSONArray warnings=result.optJSONArray("warnings");for(int i=0;i<warnings.length();i++)note+="\n"+warnings.optString(i);
        }catch(Exception e){result=null;note=error(e);}
        status();
    }
    private void pick(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK);
    }
    private void camera()throws Exception{
        Uri u=CaptureProvider.newUri(this);cameraUri=u;
        Intent i=new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,u);
        i.setClipData(ClipData.newRawUri("Фотография проёма",u));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try{startActivityForResult(i,CAMERA);}catch(ActivityNotFoundException e){removeCapture(u);cameraUri=null;alert("Камера недоступна","На этом устройстве можно выбрать готовое фото.");}
    }
    private void removeCapture(Uri u){
        if(u==null)return;
        try{revokeUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);CaptureProvider.file(this,u).delete();}catch(Exception ignored){}
    }
    private void beginExport(){
        exportingId=objectId;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"OknoMer_"+objectId.substring(0,8)+".zip"),EXPORT);
    }
    @Override protected void onActivityResult(int code,int status,Intent data){
        super.onActivityResult(code,status,data);
        if(code==CAMERA){
            Uri u=cameraUri;cameraUri=null;
            if(status==RESULT_OK&&u!=null)load(u.toString());else removeCapture(u);
        }else if(code==PICK&&status==RESULT_OK&&data!=null&&data.getData()!=null){
            load(data.getData().toString());
        }else if(code==EXPORT){
            String id=exportingId;exportingId=null;
            if(status==RESULT_OK&&id!=null&&data!=null&&data.getData()!=null){
                Uri dest=data.getData();
                io.execute(()->{
                    File tmp=null;
                    try {
                        tmp=File.createTempFile("export-",".zip",getCacheDir());
                        try(OutputStream o=new FileOutputStream(tmp)){store.export(id,o);}
                        try(OutputStream out=getContentResolver().openOutputStream(dest,"wt")){
                            if(out==null)throw new IOException("Не удалось открыть файл.");
                            java.nio.file.Files.copy(tmp.toPath(),out);
                        }
                        runOnUiThread(()->toast("Архив с фотографиями, CSV и JSON сохранён."));
                    }catch(Exception e){runOnUiThread(()->alert("Не удалось сохранить архив",error(e)));}
                    finally{if(tmp!=null)tmp.delete();}
                });
            }
        }
    }
    private Bitmap decode(String source)throws IOException{
        ImageDecoder.Source input=isDemo(source)?ImageDecoder.createSource(getAssets(),"demo-card".equals(source)?"card.jpg":"front.jpg"):ImageDecoder.createSource(getContentResolver(),Uri.parse(source));
        return ImageDecoder.decodeBitmap(input,(decoder,info,src)->{
            int w=info.getSize().getWidth(),h=info.getSize().getHeight();float scale=Math.min(1,2400f/Math.max(w,h));
            if(Math.min(w,h)*scale<100)throw new IllegalArgumentException("Фотография слишком маленькая. Выберите другой снимок.");
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
    }
    private boolean isDemo(String source){return "demo".equals(source)||"demo-card".equals(source);}
    private void load(String source){
        sync();int token=++serial;busy=true;pendingPhoto=source;
        final Reference selected=reference;
        final boolean reuse="current".equals(source)&&bitmap!=null&&draft!=null;
        final Bitmap previous=bitmap;
        final File previousFile=draft;
        final Point[] previousOpening=reuse?Geometry.copy(opening):new Point[0];
        editor();
        io.execute(()->{
            File temp=null;Uri u=Uri.parse(source);
            try{
                Bitmap image=reuse?previous:decode(source);
                if(!reuse){
                    temp=File.createTempFile("draft-",".jpg",getCacheDir());
                    try(OutputStream stream=new FileOutputStream(temp)){
                        if(!image.compress(Bitmap.CompressFormat.JPEG,95,stream))throw new IOException("Не удалось сохранить фотографию.");
                    }
                }
                Detector.Found detection;String failure=null;
                try{detection=Detector.find(image,selected);}catch(RuntimeException|LinkageError e){
                    detection=new Detector.Found();failure="Автопоиск недоступен. Укажите четыре угла проёма и эталона вручную.";
                }
                final String problem=failure;final Detector.Found found=detection;final File next=reuse?previousFile:temp;
                runOnUiThread(()->{
                    if(token!=serial||!page.equals("edit")||isDestroyed()){if(!reuse)next.delete();return;}
                    sync();markup=null;nameInput=null;if(!reuse&&draft!=null)draft.delete();draft=next;bitmap=image;demoPhoto=isDemo(source);busy=false;pendingPhoto=null;
                    choices=found.openings;candidate=0;opening=choices.isEmpty()?new Point[0]:Geometry.copy(choices.get(0));
                    if(previousOpening.length==4)opening=previousOpening;
                    paper=found.paper;landscape=paper.length==4?found.landscape:selected==Reference.CARD;editPaper=paper.length!=4;manual=false;
                    calculate();
                    if(result==null){
                        manual=true;
                        if(problem!=null)note=problem;
                        else if(paper.length!=4)note=selected==Reference.CARD?"Карта не найдена. Отметьте четыре угла карты.":"Лист А4 не найден. Отметьте его четыре угла.";
                        else if(opening.length!=4)note="Проём не найден. Отметьте четыре угла проёма.";
                    }
                    editor();
                });
            }catch(Exception|OutOfMemoryError e){
                if(temp!=null)temp.delete();
                runOnUiThread(()->{
                    if(token!=serial||isDestroyed())return;
                    busy=false;pendingPhoto=null;note="Не удалось открыть фотографию. Выберите другой снимок.";
                    editor();
                    alert("Фотография",note);
                });
            }finally{
                if(CaptureProvider.AUTH.equals(u.getAuthority())&&!isDestroyed())removeCapture(u);
            }
        });
    }
    private JSONObject payload()throws JSONException{
        return new JSONObject().put("opening",Geometry.json(opening)).put("reference",Geometry.json(paper))
            .put("image_size",new JSONArray().put(bitmap.getWidth()).put(bitmap.getHeight())).put("result",result);
    }
    private void save()throws Exception{
        sync();if(busy||result==null||draft==null)return;
        String id=store.save(objectId,measurementName,draft,payload());clearDraft();saved(id);
    }
    private void saved(String id)throws Exception{
        JSONObject row=store.get(id),p=row.getJSONObject("payload"),d=p.getJSONObject("result");
        savedId=id;objectId=row.getString("object_id");page("saved","Сохранённый замер",true);
        line(body,row.getString("name"),23,INK,true,7);
        line(body,store.objectName(objectId)+"  ·  "+date(row),14,MUTED,false,4);
        Bitmap image=BitmapFactory.decodeFile(new File(store.photos,row.getString("photo")).getPath());
        Reference used=Reference.fromId(d.optString("reference_type"));
        if(image!=null)photo(body,image,Geometry.points(p.getJSONArray("opening")),Geometry.points(p.getJSONArray("reference"))).setContentDescription("Фото с контурами проёма и эталона");
        line(body,"Эталон: "+used.label+" · "+used.dimensions(d.optBoolean("landscape")),14,MUTED,false,8);
        line(body,dims(d),30,INK,true,18);line(body,"Ширина × высота с коррекцией перспективы",15,MUTED,false,5);
        line(body,String.format(RU,"Без коррекции: %.0f × %.0f мм",d.getDouble("baseline_width_mm"),d.getDouble("baseline_height_mm")),14,MUTED,false,12);
        line(body,String.format(RU,"Чувствительность к сдвигу углов на 1 пиксель: %.1f %%",d.getDouble("sensitivity_pct")),14,MUTED,false,5);
        action(body,"Переименовать замер",false,()->prompt("Название замера",row.getString("name"),n->safe(()->{store.rename(id,n);saved(id);})));
        action(body,"Экспортировать объект в ZIP",false,this::beginExport);
        action(body,"Удалить замер",false,()->new AlertDialog.Builder(this).setTitle("Удалить замер?").setMessage("Фотография и результат «"+row.getString("name")+"» будут удалены из этого объекта.").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(a,b)->safe(()->{store.delete(id);object(objectId);})).show());
    }
    @Override public void onBackPressed(){
        if("home".equals(page)){super.onBackPressed();return;}
        safe(()->{
            if("object".equals(page))home();
            else {if("edit".equals(page))clearDraft();object(objectId);}
        });
    }
    @Override protected void onSaveInstanceState(Bundle b){
        super.onSaveInstanceState(b);sync();
        b.putString("page",page);b.putString("object",objectId);b.putString("saved",savedId);b.putString("export",exportingId);
        if(cameraUri!=null)b.putString("camera",cameraUri.toString());
        if(!"edit".equals(page))return;
        b.putString("referenceType",reference.id);b.putBoolean("demoPhoto",demoPhoto);b.putString("source",pendingPhoto);b.putString("name",measurementName);
        if(draft==null)return;
        b.putString("draft",draft.getName());b.putInt("index",candidate);b.putBoolean("manual",manual);b.putBoolean("editPaper",editPaper);b.putBoolean("landscape",landscape);
        try{
            b.putString("opening",Geometry.json(opening).toString());b.putString("paper",Geometry.json(paper).toString());
            JSONArray list=new JSONArray();for(Point[] p:choices)list.put(Geometry.json(p));b.putString("choices",list.toString());
            b.putString("result",result==null?null:result.toString());b.putString("note",note);
        }catch(JSONException ignored){}
    }
    private void restore(Bundle b)throws Exception{
        objectId=b.getString("object");savedId=b.getString("saved");exportingId=b.getString("export");
        if(b.getString("camera")!=null)cameraUri=Uri.parse(b.getString("camera"));
        String screen=b.getString("page","home");
        if("edit".equals(screen)&&objectId!=null){
            reference=Reference.fromId(b.getString("referenceType"));demoPhoto=b.getBoolean("demoPhoto");landscape=reference==Reference.CARD;
            measurementName=b.getString("name","Проём");
            if(b.getString("draft")!=null){
                File f=new File(getCacheDir(),b.getString("draft"));bitmap=BitmapFactory.decodeFile(f.getPath());
                if(bitmap!=null){
                    draft=f;opening=Geometry.points(new JSONArray(b.getString("opening","[]")));paper=Geometry.points(new JSONArray(b.getString("paper","[]")));
                    JSONArray list=new JSONArray(b.getString("choices","[]"));for(int i=0;i<list.length();i++)choices.add(Geometry.points(list.getJSONArray(i)));
                    candidate=b.getInt("index");manual=b.getBoolean("manual");editPaper=b.getBoolean("editPaper");landscape=b.getBoolean("landscape");
                    result=b.getString("result")==null?null:new JSONObject(b.getString("result"));note=b.getString("note","");
                }
            }
            editor();if(b.getString("source")!=null)load(b.getString("source"));
        }else if("saved".equals(screen)&&savedId!=null)saved(savedId);
        else if("object".equals(screen)&&objectId!=null)object(objectId);
        else home();
    }
    @Override protected void onDestroy(){
        super.onDestroy();serial++;io.execute(()->store.close());io.shutdown();
    }
}
