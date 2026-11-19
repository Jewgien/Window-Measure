package org.student.oknomer;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.UUID;

/** A temporary, single-photo grant to the external system camera. */
public final class CaptureProvider extends ContentProvider {
    static final String AUTH=BuildConfig.APPLICATION_ID+".capture";
    static Uri newUri(Context ctx) throws IOException {
        String name=UUID.randomUUID()+".jpg";
        Uri uri=new Uri.Builder().scheme("content").authority(AUTH).appendPath(name).build();
        if(!file(ctx,uri).createNewFile())throw new IOException("Не удалось подготовить снимок.");
        return uri;
    }
    static File file(Context ctx, Uri uri) throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(!AUTH.equals(uri.getAuthority())||uri.getPathSegments().size()!=1||name==null||!name.matches("[a-f0-9-]{36}\\.jpg")) throw new FileNotFoundException();
        File dir=new File(ctx.getCacheDir(),"capture");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new FileNotFoundException("Нет места для снимка.");
        return new File(dir,name);
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri u){return "image/jpeg";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        File f=file(getContext(),uri);
        if(!f.isFile())throw new FileNotFoundException();
        return ParcelFileDescriptor.open(f,ParcelFileDescriptor.parseMode(mode));
    }
    @Override public Cursor query(Uri uri,String[] projection,String s,String[] a,String o){
        String[] cols=projection!=null?projection:new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE};
        MatrixCursor cursor=new MatrixCursor(cols);
        try{
            File f=file(getContext(),uri); Object[] row=new Object[cols.length];
            for(int i=0;i<cols.length;i++){
                if(OpenableColumns.SIZE.equals(cols[i]))row[i]=f.length();
                if(OpenableColumns.DISPLAY_NAME.equals(cols[i]))row[i]=f.getName();
            }
            cursor.addRow(row);
        }catch(IOException ignored){}
        return cursor;
    }
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
