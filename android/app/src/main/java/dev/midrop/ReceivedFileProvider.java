package dev.midrop;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import java.io.*;
import java.net.URLConnection;
/** Read-only URI grants for files received on Android 8–9. */
public class ReceivedFileProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        try {
            File root=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"MiDrop").getCanonicalFile();
            File f=new File(root,uri.getLastPathSegment()).getCanonicalFile();
            if (!root.equals(f.getParentFile()) || !f.isFile()) throw new FileNotFoundException(); return f;
        } catch (IOException e) { throw new FileNotFoundException(); }
    }
    public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException { if (!mode.equals("r")) throw new FileNotFoundException(); return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY); }
    public String getType(Uri uri) { String t=URLConnection.guessContentTypeFromName(uri.getLastPathSegment()); return t==null?"application/octet-stream":t; }
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
        try { File f=file(uri); MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}); c.addRow(new Object[]{f.getName(),f.length()}); return c; } catch (Exception e) { return null; }
    }
    public Uri insert(Uri uri,ContentValues v) { throw new UnsupportedOperationException(); }
    public int update(Uri uri,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri,String s,String[] a) { throw new UnsupportedOperationException(); }
}
