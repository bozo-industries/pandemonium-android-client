package page.claras.pandemonium;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;

/** Grants the camera access to one temporary JPEG, never to other app files. */
public final class CaptureProvider extends ContentProvider {
    static final String AUTHORITY = "page.claras.pandemonium.capture";

    static Uri create(Context context) throws IOException {
        File directory = new File(context.getCacheDir(), "camera");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Camera directory unavailable");
        File[] previous = directory.listFiles();
        if (previous != null) for (File file : previous) {
            if (file.lastModified() < System.currentTimeMillis() - 86_400_000L) file.delete();
        }
        String name = UUID.randomUUID() + ".jpg";
        File file = new File(directory, name);
        if (!file.createNewFile()) throw new IOException("Camera file unavailable");
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build();
    }

    static File file(Context context, Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())
            || uri.getPathSegments().size() != 1 || name == null || !name.matches("[a-f0-9-]{36}\\.jpg")) {
            throw new FileNotFoundException("Invalid camera URI");
        }
        return new File(new File(context.getCacheDir(), "camera"), name);
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/jpeg"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(getContext(), uri), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File file = file(getContext(), uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
                if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException error) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
