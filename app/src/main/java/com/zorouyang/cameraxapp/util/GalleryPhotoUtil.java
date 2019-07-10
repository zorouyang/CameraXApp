package com.zorouyang.cameraxapp.util;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

public class GalleryPhotoUtil {

    public static String getPath(final Context context, final Uri uri) {
        final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;

        // DocumentProvider  
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider  
            if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    Log.d("DEBUG", "isKitKat or later, isDocumentUri and isExternalStorageDocument.");
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                }

                // TODO handle non-primary volumes  
            }
            // DownloadsProvider  
            else if (isDownloadsDocument(uri)) {

                final String id = DocumentsContract.getDocumentId(uri);
                final Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));
                Log.d("DEBUG", "isKitKat or later, isDocumentUri and isDownloadsDocument.");
                return getDataColumn(context, contentUri, null, null);
            }
            // MediaProvider  
            else if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[]{ split[1] };
                Log.d("DEBUG", "isKitKat or later, isDocumentUri and isMediaDocument.");
                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        } else if (isKitKat && !DocumentsContract.isDocumentUri(context, uri)) {
            Log.d("DEBUG", "isKitKat or later, not DocumentUri.");
            return selectImage(context, uri);
        }
        // MediaStore (and general)  
        else if ("content".equalsIgnoreCase(uri.getScheme())) {

            // Return the remote address  
            if (isGooglePhotosUri(uri)) {
                Log.d("DEBUG", "Below KitKat, isDocumentUri and isGooglePhotosUri.");
                return uri.getLastPathSegment();
            }

            Log.d("DEBUG", "Below KitKat, isDocumentUri and besides.");
            return getDataColumn(context, uri, null, null);
        }
        // File  
        else if ("file".equalsIgnoreCase(uri.getScheme())) {
            Log.d("DEBUG", "Below KitKat, file.");
            return uri.getPath();
        }
        Log.d("DEBUG", "without, unknown.");
        return null;
    }

    public static String selectImage(Context context, Uri contentUri) {
        if (contentUri != null) {
            String uriStr = contentUri.toString();
            String path = uriStr.substring(10);
            if (path.startsWith("com.sec.android.gallery3d")) {
                Log.w("DEBUG", "It's auto backup pic path:" + contentUri.toString());
                return null;
            }
        }
        String[] filePathColumn = {MediaStore.Images.Media.DATA};
        Cursor cursor = context.getContentResolver().query(contentUri, filePathColumn, null, null, null);
        cursor.moveToFirst();
        int columnIndex = cursor.getColumnIndex(filePathColumn[0]);
        String picturePath = cursor.getString(columnIndex);
        cursor.close();
        return picturePath;
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context       The context.
     * @param uri           The Uri to query.
     * @param selection     (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    public static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {

        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {column};

        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                final int index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(index);
            }
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
    }


    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is Google Photos.
     */
    public static boolean isGooglePhotosUri(Uri uri) {
        return "com.google.android.apps.photos.content".equals(uri.getAuthority());
    }
}
