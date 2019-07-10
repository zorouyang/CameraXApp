package com.zorouyang.cameraxapp.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Log;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * QRCode Image decoder
 */
public class ImageDecoder {
    private static final String TAG = Decoder.class.getSimpleName();
    public static final int MAX_IMAGE_RESOLUTION = 1200;

    private static final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    static  {
        hints.put(DecodeHintType.CHARACTER_SET, "utf-8");
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, BarcodeFormat.QR_CODE);
    }

    /**
     * Only support QRCode
     * @param path
     * @return
     */
    static public Result decodeQRCode(String path) {
        long start = System.nanoTime();
        Result rawResult = null;

        Bitmap original = decodeSampledBitmap(path, MAX_IMAGE_RESOLUTION, MAX_IMAGE_RESOLUTION);//BitmapFactory.decodeFile(path);
        int width = original.getWidth();
        int height = original.getHeight();
        Log.d("Decoder", "decodeQRCode final: " + width + "x" + height);

        byte[] data = getYUV420sp(width, height, original);
        PlanarYUVLuminanceSource source;
        source = new PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false);

        //BitmapLuminanceSource source = new BitmapLuminanceSource(decodeSampledBitmap(path, 1080));

        if (source != null) {
            QRCodeReader qrCodeReader = new QRCodeReader();
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            try {
                rawResult = qrCodeReader.decode(bitmap, hints);
            } catch (ReaderException ignored) {
            } finally {
                qrCodeReader.reset();
            }
        }

        if (rawResult != null) {
            // Don't log the barcode contents for security.
            long end = System.nanoTime();
            Log.d(TAG, "Found barcode in " + TimeUnit.NANOSECONDS.toMillis(end - start) + " ms");
        }
        return rawResult;
    }

    /**
     * RGB of support all format with ZXing library
     * @param path image real path
     * @return decoded result
     */
    static public Result decodeImage(String path) {
        long start = System.nanoTime();
        Result rawResult = null;

        Bitmap original = decodeSampledBitmap(path, MAX_IMAGE_RESOLUTION, MAX_IMAGE_RESOLUTION);

        //BitmapLuminanceSource source = new BitmapLuminanceSource(original);

        int[] pixels = new int[original.getWidth() * original.getHeight()];
        original.getPixels(pixels, 0, original.getWidth(), 0, 0, original.getWidth(), original.getHeight());
        RGBLuminanceSource source = new RGBLuminanceSource(original.getWidth(), original.getHeight(), pixels);

        if (source != null) {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            MultiFormatReader multiFormatReader = new MultiFormatReader();
            try {
                rawResult = multiFormatReader.decodeWithState(bitmap);
            } catch (ReaderException ignored) {
            } finally {
                multiFormatReader.reset();
            }
        }

        if (rawResult != null) {
            // Don't log the barcode contents for security.
            long end = System.nanoTime();
            Log.d(TAG, "Found barcode in " + TimeUnit.NANOSECONDS.toMillis(end - start) + " ms");
        }
        return rawResult;
    }

    private static byte[] yuvs;
    /**
     * 根据Bitmap的ARGB值生成YUV420SP数据。
     *
     * @param inputWidth image width
     * @param inputHeight image height
     * @param scaled bmp
     * @return YUV420SP数组
     */
    public static byte[] getYUV420sp(int inputWidth, int inputHeight, Bitmap scaled) {
        int[] argb = new int[inputWidth * inputHeight];

        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight);

        /**
         * 需要转换成偶数的像素点，否则编码YUV420的时候有可能导致分配的空间大小不够而溢出。
         */
        int requiredWidth = inputWidth % 2 == 0 ? inputWidth : inputWidth + 1;
        int requiredHeight = inputHeight % 2 == 0 ? inputHeight : inputHeight + 1;

        int byteLength = requiredWidth * requiredHeight * 3 / 2;
        if (yuvs == null || yuvs.length < byteLength) {
            yuvs = new byte[byteLength];
        } else {
            Arrays.fill(yuvs, (byte) 0);
        }

        encodeYUV420SP(yuvs, argb, inputWidth, inputHeight);

        scaled.recycle();

        return yuvs;
    }

    /**
     * RGB转YUV420sp
     *
     * @param yuv420sp inputWidth * inputHeight * 3 / 2
     * @param argb inputWidth * inputHeight
     * @param width image width
     * @param height image height
     */
    private static void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height) {
        // 帧图片的像素大小
        final int frameSize = width * height;
        // ---YUV数据---
        int Y, U, V;
        // Y的index从0开始
        int yIndex = 0;
        // UV的index从frameSize开始
        int uvIndex = frameSize;

        // ---颜色数据---
        int R, G, B;
        int rgbIndex = 0;

        // ---循环所有像素点，RGB转YUV---
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {

                R = (argb[rgbIndex] & 0xff0000) >> 16;
                G = (argb[rgbIndex] & 0xff00) >> 8;
                B = (argb[rgbIndex] & 0xff);
                //
                rgbIndex++;

                // well known RGB to YUV algorithm
                Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                Y = Math.max(0, Math.min(Y, 255));
                U = Math.max(0, Math.min(U, 255));
                V = Math.max(0, Math.min(V, 255));

                // NV21 has a plane of Y and interleaved planes of VU each sampled by a factor of 2
                // meaning for every 4 Y pixels there are 1 V and 1 U. Note the sampling is every other
                // pixel AND every other scan line.
                // ---Y---
                yuv420sp[yIndex++] = (byte) Y;
                // ---UV---
                if ((j % 2 == 0) && (i % 2 == 0)) {
                    //
                    yuv420sp[uvIndex++] = (byte) V;
                    //
                    yuv420sp[uvIndex++] = (byte) U;
                }
            }
        }
    }

    static public Bitmap decodeSampledBitmap(String path) {
        return decodeSampledBitmap(path, MAX_IMAGE_RESOLUTION, MAX_IMAGE_RESOLUTION);
    }

    static public Bitmap decodeSampledBitmap(String path, int width, int height) {
        // First decode with inJustDecodeBounds=true to check dimensions
        BitmapFactory.Options options = new BitmapFactory.Options();
        if (width <= 0 || height <= 0) {
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, options);
        }
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        Log.d("Decoder", "decodeQRCode original: " + options.outWidth + "x" + options.outHeight + ", req: " + width + "x" + height);
        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, width, height);
        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, options);
    }

    static private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            // Calculate ratios of height and width to requested height and width
            final int heightRatio = Math.round((float) height / (float) reqHeight);
            final int widthRatio = Math.round((float) width / (float) reqWidth);
            // Choose the smallest ratio as inSampleSize value, this will guarantee a final image
            // with both dimensions larger than or equal to the requested height and width.
            inSampleSize = (heightRatio < widthRatio) ? heightRatio : widthRatio;
        }
        Log.d("Decoder", "calculateInSampleSize: " + inSampleSize);
        return inSampleSize;
    }

    static public Bitmap cropImage(Bitmap bitmap) {
        int x = bitmap.getWidth();
        int y = bitmap.getHeight();

        int width = x * 6 / 8;
        int height = y * 6 / 8;

        int leftOffset = (x - width) / 2;
        int topOffset = (y - height) / 2;
        Rect rect = new Rect(leftOffset, topOffset, leftOffset + width, topOffset + height);

        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height());
    }
}