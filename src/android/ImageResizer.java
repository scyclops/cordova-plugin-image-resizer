package info.protonet.imageresizer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;

import org.apache.cordova.LOG;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;

import org.apache.cordova.camera.CameraLauncher;
import org.apache.cordova.camera.FileHelper;
import org.apache.cordova.camera.ExifHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Closeable;
import java.text.SimpleDateFormat;
import java.util.Date;


public class ImageResizer extends CordovaPlugin {

    private static final String LOG_TAG = "ImageResizer";

    public CallbackContext callbackContext;

    private String uriString;

    private int quality;
    // for compression, 0-100

    private int width;
    private int height;
    // image is scaled to fit within width & height

    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;

        if (action.equals("resize")) {
            try {
                JSONObject jsonObject = args.getJSONObject(0);

                // required
                uriString = jsonObject.getString("uri");
                width = jsonObject.getInt("width");
                height = jsonObject.getInt("height");

                // optional
                quality = jsonObject.optInt("quality", 85);

                // run resize operation in the background b/c it can be slow
                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        resize();
                    }
                });

            } catch (JSONException e) {
                e.printStackTrace();
                String msg = "Error parsing arguments: " + e.toString();
                LOG.e(LOG_TAG, msg);
                callbackContext.error(msg);
            }
            return true;
        }
        return false;
    }

    private void resize() {

        Bitmap bitmap = null;
        try {
            bitmap = copyAndGetScaledBitmap();
        } catch (Exception e) {
            e.printStackTrace();
            LOG.w(LOG_TAG, "Error running copyAndGetScaledBitmap: " + e.toString());
        }

        if (bitmap == null) {
            // try a simpler method
            try {
                readExifData(uriString);
                bitmap = getScaledBitmap(uriString);
            } catch (Exception e) {
                e.printStackTrace();
                LOG.w(LOG_TAG, "Error running simple bitmap scaling: " + e.toString());
            }
        }

        if (bitmap == null) {
            String msg = "Error reading image";
            LOG.e(LOG_TAG, msg);
            callbackContext.error(msg);
            return;
        }

        try {
            String modifiedPath = outputModifiedBitmap(bitmap);

            // the modified image is cached by the app
            // system time is added to prevent the cache from serving the wrong image
            callbackContext.success("file://" + modifiedPath + "?" + System.currentTimeMillis());

        } catch (Exception e) {
            e.printStackTrace();
            String msg = "Error outputting image: " + e.toString();
            LOG.e(LOG_TAG, msg);
            callbackContext.error(msg);
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
                bitmap = null;
                System.gc();
            }
        }
    }

    private void closeStream(Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                LOG.d(LOG_TAG, "Exception while closing stream");
            }
        }
    }

    private void readExifData(String uri) {
        // only try to read exif data if the image is a jpeg image

        String mimeType = FileHelper.getMimeType(uriString, cordova);

        if (mimeType != null && JPEG_MIME_TYPE.equalsIgnoreCase(mimeType)) {
            String filePath = uri.replace("file://", ""); // ExifInterface doesn't like the file:// prefix
            try {
                exifData = new ExifHelper();
                exifData.createInFile(filePath);
                exifData.readExifData();
            } catch (IOException e) {
                exifData = null;
                e.printStackTrace();
                LOG.w(LOG_TAG, "Failed to read exif data: " + e.toString());
            }
        }
    }

    private Bitmap getScaledBitmap(String uri_string) throws IOException {

        // figure out the original width and height of the image
        BitmapFactory.Options options = new BitmapFactory.Options();
        
        options.inJustDecodeBounds = true;

        InputStream fileStream = FileHelper.getInputStreamFromUriString(uri_string, cordova);
        try {
            BitmapFactory.decodeStream(fileStream, null, options);
        } finally {
            closeStream(fileStream);
        }

        if (options.outWidth == 0 || options.outHeight == 0) {
            return null;
        }

        // determine the correct aspect ratio
        int[] widthHeight = calculateAspectRatio(options.outWidth, options.outHeight);
        int newWidth = widthHeight[0];
        int newHeight = widthHeight[1];

        // load in the smallest bitmap possible that is closest to the size we want
        options.inJustDecodeBounds = false;
        options.inSampleSize = CameraLauncher.calculateSampleSize(options.outWidth, options.outHeight, newWidth, newHeight);

        fileStream = FileHelper.getInputStreamFromUriString(uri_string, cordova);
        
        Bitmap unscaledBitmap = null;
        try {
            unscaledBitmap = BitmapFactory.decodeStream(fileStream, null, options);
        } finally {
            closeStream(fileStream);
        }

        if (unscaledBitmap == null) {
            return null;
        }

        Bitmap scaledBitmap = Bitmap.createScaledBitmap(unscaledBitmap, newWidth, newHeight, true);

        if (scaledBitmap != unscaledBitmap) {
            unscaledBitmap.recycle();
            unscaledBitmap = null;
            System.gc();
        }

        return scaledBitmap;
    }

    /**
     * Maintain the aspect ratio so the resulting image does not look smooshed
     *
     * @param origWidth
     * @param origHeight
     * @return
     */
    private int[] calculateAspectRatio(int origWidth, int origHeight) {
        int newWidth = width;
        int newHeight = height;

        // If no new width or height were specified return the original bitmap
        if (newWidth <= 0 && newHeight <= 0) {
            newWidth = origWidth;
            newHeight = origHeight;
        }
        // Only the width was specified
        else if (newWidth > 0 && newHeight <= 0) {
            newHeight = (int)((double)(newWidth / (double)origWidth) * origHeight); // camera-plugin
        }
        // only the height was specified
        else if (newWidth <= 0 && newHeight > 0) {
            newWidth = (int)((double)(newHeight / (double)origHeight) * origWidth); // camera-plugin
        }
        // If the user specified both a positive width and height
        // (potentially different aspect ratio) then the width or height is
        // scaled so that the image fits while maintaining aspect ratio.
        // Alternatively, the specified width and height could have been
        // kept and Bitmap.SCALE_TO_FIT specified when scaling, but this
        // would result in whitespace in the new image.
        else {
            double newRatio = newWidth / (double) newHeight;
            double origRatio = origWidth / (double) origHeight;

            if (origRatio > newRatio) {
                newHeight = (newWidth * origHeight) / origWidth;
            } else if (origRatio < newRatio) {
                newWidth = (newHeight * origWidth) / origHeight;
            }
        }

        int[] retval = new int[2];
        retval[0] = newWidth;
        retval[1] = newHeight;
        return retval;
    }

    //////////////////// below adapted from the cordova-plugin-camera code

    private static final int JPEG = 0;
    private static final int PNG = 1;
    private static final String JPEG_TYPE = "jpg";
    private static final String PNG_TYPE = "png";
    private static final String JPEG_EXTENSION = "." + JPEG_TYPE;
    private static final String PNG_EXTENSION = "." + PNG_TYPE;
    private static final String PNG_MIME_TYPE = "image/png";
    private static final String JPEG_MIME_TYPE = "image/jpeg";
    private static final String TIME_FORMAT = "yyyyMMdd_HHmmss";

    private int encodingType = JPEG;
    // XXX: could support encoding PNG images later if needed

    private ExifHelper exifData = null;
    // exf data from the input image


    /**
     * Write an inputstream to local disk
     *
     * @param fis - The InputStream to write
     * @param dest - Destination on disk to write to
     * @throws FileNotFoundException
     * @throws IOException
     */
    private void writeUncompressedImage(InputStream fis, Uri dest) throws FileNotFoundException, IOException {
        OutputStream os = null;
        try {
            os = this.cordova.getActivity().getContentResolver().openOutputStream(dest);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            os.flush();
        } finally {
            closeStream(os);
        }
    }

    private String getTemporaryFileName() {
        String timeStamp = new SimpleDateFormat(TIME_FORMAT).format(new Date());
        String fileName = "IMG_" + timeStamp + (getExtensionForEncodingType());
        return getTempDirectoryPath() + fileName;
    }

    /**
     * Return a scaled and rotated bitmap based on the target width and height
     *
     * @return Bitmap
     * @throws IOException
     */
    private Bitmap copyAndGetScaledBitmap() throws IOException {
        /*

        Copy the inputstream to a temporary file on the device.
        We then use this temporary file to determine the width/height.
        This is the only way to determine the orientation of the photo coming from 3rd party providers (Google Drive, Dropbox,etc)

        */
        File localFile = null;
        InputStream fileStream = FileHelper.getInputStreamFromUriString(uriString, cordova);
        try {
            Uri fileUri = null;

            try {
                localFile = new File(getTemporaryFileName());

                fileUri = Uri.fromFile(localFile);

                writeUncompressedImage(fileStream, fileUri);

            } catch (Exception e) {
                e.printStackTrace();
                LOG.e(LOG_TAG, "Exception copying to temporary file: " + e.toString());
                return null;
            }

            String fileUriString = fileUri.toString();

            readExifData(fileUriString);

            return getScaledBitmap(fileUriString);

        } finally {
            closeStream(fileStream);

            // delete the temporary copy
            if (localFile != null) {
                localFile.delete();
            }
        }
    }

    private String getTempDirectoryPath() {
        File cache = cordova.getActivity().getCacheDir();
        // Create the cache directory if it doesn't exist
        cache.mkdirs();
        return cache.getAbsolutePath();
    }

    private String getExtensionForEncodingType() {
        return this.encodingType == JPEG ? JPEG_EXTENSION : PNG_EXTENSION;
    }

    /**
     * Converts output image format int value to string value of mime type.
     * @return String String value of mime type or empty string if mime type is not supported
     */
    private String getMimetypeForEncodingType() {
        if (encodingType == PNG) return PNG_MIME_TYPE;
        if (encodingType == JPEG) return JPEG_MIME_TYPE;
        return "";
    }

    private String calculateModifiedBitmapOutputFileName(String mimeTypeOfOriginalFile, String realPath) {
        if (realPath == null) {
            return "modified" + getExtensionForEncodingType();
        }
        String fileName = realPath.substring(realPath.lastIndexOf('/') + 1);
        if (getMimetypeForEncodingType().equals(mimeTypeOfOriginalFile)) {
            return fileName;
        }
        // if the picture is not a jpeg or png, (a .heic for example) when processed to a bitmap
        // the file extension is changed to the output format, f.e. an input file my_photo.heic could become my_photo.jpg
        return fileName.substring(fileName.lastIndexOf(".") + 1) + getExtensionForEncodingType();
    }

    private CompressFormat getCompressFormatForEncodingType(int encodingType) {
        return encodingType == JPEG ? CompressFormat.JPEG : CompressFormat.PNG;
    }

    private String outputModifiedBitmap(Bitmap bitmap) throws IOException {

        Uri uri = Uri.parse(uriString);
        String mimeTypeOfOriginalFile = FileHelper.getMimeType(uriString, cordova);

        // Some content: URIs do not map to file paths (e.g. picasa).
        String realPath = FileHelper.getRealPath(uri, this.cordova);
        String fileName = calculateModifiedBitmapOutputFileName(mimeTypeOfOriginalFile, realPath);

        String modifiedPath = getTempDirectoryPath() + "/" + fileName;

        Bitmap whiteBgBitmap = null;
        CompressFormat compressFormat = getCompressFormatForEncodingType(this.encodingType);

        OutputStream os = new FileOutputStream(modifiedPath);
        try {
            if (this.encodingType == JPEG && !JPEG_MIME_TYPE.equals(mimeTypeOfOriginalFile)) {
                // outputting jpeg but the input file isn't a jpeg, so it may have transparency
                // but jpeg's don't support transparency
                // so we get rid of the transparency by using a white background

                whiteBgBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), bitmap.getConfig());

                Canvas canvas = new Canvas(whiteBgBitmap);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(bitmap, 0, 0, null);
                canvas = null;

                whiteBgBitmap.compress(compressFormat, quality, os);

            } else {
                bitmap.compress(compressFormat, quality, os);
            }
        } finally {
            if (whiteBgBitmap != null) {
                whiteBgBitmap.recycle();
                whiteBgBitmap = null;
                System.gc();
            }
            os.close(); // not catching IOException b/c it may mean things don't work
        }

        if (this.encodingType == JPEG && exifData != null) {
            // write the exif data into the new resized image
            try {
                exifData.createOutFile(modifiedPath);
                exifData.writeExifData();
                exifData = null;
            } catch (IOException e) {
                LOG.w(LOG_TAG, "Failed to write exif data: " + e.toString());
            }
        }

        return modifiedPath;
    }
}
