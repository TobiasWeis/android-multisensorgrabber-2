package de.weis.multisensor_grabber;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.StatFs;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.util.Xml;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import org.w3c.dom.Text;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES;
import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF;
import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON;
import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF;
import static android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO;
import static android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
import static android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT;
import static android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT;
import static android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT;
import static android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_OFF;
import static android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_SHADE;
import static android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_TWILIGHT;
import static android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT;
import static android.hardware.camera2.CaptureResult.CONTROL_AE_MODE;
import static android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME;

public class MainActivity extends Activity {
    private static final String TAG = "Multisensor_Grabber";
    private ImageButton takePictureButton;
    private TextureView textureView;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private String cameraId;
    protected CameraDevice cameraDevice;
    protected CameraCaptureSession cameraCaptureSessions;
    protected CaptureRequest captureRequest;
    protected CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private ImageReader imageReader;
    private File file;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private boolean mFlashSupported;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    File _dir;
    String _path;
    File _extdir;
    FileOutputStream fileos;
    XmlSerializer serializer = Xml.newSerializer();

    CameraManager manager;
    CameraCharacteristics characteristics;
    ImageReader reader;
    CameraCaptureSession _session;
    CaptureRequest.Builder _capReq; // needs to be global b/c preview-setup

    Long last_pic_ts = new Long(0);
    Long last_pic_rec = new Long(0);

    protected Integer _cnt = 0;
    protected Boolean _recording = false;
    Integer _img_width = 640;
    Integer _img_height = 480;
    boolean _fix_exp;
    boolean _fix_foc;
    boolean _fix_iso;
    Float _foc_dist;
    Long _exp_time;
    int _wb_value;
    int _iso_value;
    Float _fps;
    Long _diff = new Long(0);

    Long _seq_timestamp;

    double _gyro_head = 0.;
    double _gyro_pitch = 0.;
    double _gyro_roll = 0.;

    double _accel_x = 0.;
    double _accel_y = 0.;
    double _accel_z = 0.;

    private Handler sys_handler = new Handler();
    private TextView textview_battery;
    LocationManager mLocationManager;
    Criteria criteria = new Criteria();
    String bestProvider;
    android.location.Location _loc;
    TextView textview_coords;
    TextView textview_fps;
    TextView textview_imu;
    TextView textview_camera;
    ImageButton settingsButton;

    // FIXME: expose to settings
    String _format = "YUV";

    public static float GbAvailable(File f) {
        StatFs stat = new StatFs(f.getPath());
        long bytesAvailable = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2)
            bytesAvailable = (long) stat.getBlockSizeLong() * (long) stat.getAvailableBlocksLong();
        else
            bytesAvailable = (long) stat.getBlockSize() * (long) stat.getAvailableBlocks();
        return bytesAvailable / (1024.f * 1024.f * 1024.f);
    }

    private Runnable grab_system_data = new Runnable() {
        @Override
        public void run() {
            textview_battery.setText("BAT: " + String.format("%.0f", getBatteryLevel()) + "%");
            // FIXME: if we have gps-permission, but gps is off, this fails!
            try {
                textview_coords.setText("Coordinates: " + String.format("%.03f", _loc.getLatitude()) + ", " + String.format("%.03f", _loc.getLongitude()) + ", Acc:" + _loc.getAccuracy());
            }catch(Exception e){}

            try{
                bestProvider = mLocationManager.getBestProvider(criteria, false);
                mLocationManager.requestLocationUpdates(bestProvider, 1,0.01f, locationListener);
            }catch(Exception e) {}

            textview_imu.setText("head: " + String.format("%.01f", _gyro_head) +
                    " pitch: " + String.format("%.01f", _gyro_pitch) +
                    " roll: " + String.format("%.01f", _gyro_roll) +
                    " ax " + String.format("%.01f", _accel_x) +
                    " ay " + String.format("%.01f", _accel_y) +
                    " az " + String.format("%.01f", _accel_z));
            textview_fps.setText(String.format("%.1f", 1000. / _diff) + " f/s");

            /*
            _fix_exp=prefs.getBoolean("pref_fix_exp",false);
            _exp_time=Long.parseLong(prefs.getString("pref_exposure","0")) * 1000000;
            _fix_foc = prefs.getBoolean("pref_fix_foc", false);
            _wb_value = Integer.parseInt(prefs.getString("pref_wb", ""+CONTROL_AWB_MODE_AUTO));
            _iso_value = Integer.parseInt(prefs.getString("pref_iso", "-1"));
            */
            String camstring = "EXP: ";
            if(_fix_exp){
                camstring += "Fixed: " + _exp_time/1000000 + "ms";
            }else{
                camstring += "Auto";
            }

            camstring += ", FOC: ";
            if(_fix_foc){
                camstring += "Fixed: " + _foc_dist;
            }else{
                camstring += "Auto";
            }

            camstring += ", ISO: ";
            if(_fix_iso){
                camstring += ""+_iso_value;
            }else{
                camstring += "Auto";
            }

            camstring += ", WB: "+ wb2string(_wb_value);

            camstring += ", Free space: " + String.format("%.02f", GbAvailable(_extdir)) + " Gb";

            textview_camera.setText(camstring);

            sys_handler.postDelayed(grab_system_data, 500);
        }
    };

    // FIXME: double use in SettingsFragment.java
    public String wb2string(int wb){
        if(wb == CONTROL_AWB_MODE_CLOUDY_DAYLIGHT) return "Cloudy daylight";
        if(wb == CONTROL_AWB_MODE_DAYLIGHT) return "Daylight";
        if(wb == CONTROL_AWB_MODE_FLUORESCENT) return "Fluorescent";
        if(wb == CONTROL_AWB_MODE_INCANDESCENT) return "Incandescent";
        if(wb == CONTROL_AWB_MODE_SHADE) return "Shade";
        if(wb == CONTROL_AWB_MODE_TWILIGHT) return "Twilight";
        if(wb == CONTROL_AWB_MODE_WARM_FLUORESCENT) return "Warm Fluorescent";
        if(wb == CONTROL_AWB_MODE_OFF) return "Off";
        if(wb == CONTROL_AWB_MODE_AUTO) return "Auto";
        if(wb == -1) return "Not available";
        return "N/A: "+wb;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textview_battery = (TextView) findViewById(R.id.textview_battery);
        textview_coords = (TextView) findViewById(R.id.textview_coords);
        textview_imu = (TextView) findViewById(R.id.textview_imu);
        textview_fps = (TextView) findViewById(R.id.textview_fps);
        textview_camera = (TextView) findViewById(R.id.textview_camera);

        textureView = (TextureView) findViewById(R.id.texture);
        assert textureView != null;
        textureView.setSurfaceTextureListener(textureListener);

        takePictureButton = (ImageButton) findViewById(R.id.btn_takepicture);
        assert takePictureButton != null;
        takePictureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (_recording) {
                    try {
                        _session.stopRepeating();
                        _session.close();
                        createCameraPreview();
                        //mBackgroundHandler.removeCallbacksAndMessages(null);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                    _recording = false;
                    try {
                        serializer.endTag(null, "sequence");
                        serializer.flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    try {
                        fileos.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    takePictureButton.setImageResource(R.mipmap.icon_rec);
                    settingsButton.setEnabled(true);
                    Toast.makeText(getApplicationContext(), "Stopped", Toast.LENGTH_LONG).show();
                    /*
                    _pichandler.removeCallbacks(_picrunner);
                    */
                } else {
                    Toast.makeText(getApplicationContext(), "Started", Toast.LENGTH_LONG).show();
                    takePictureButton.setImageResource(R.mipmap.icon_rec_on);
                    settingsButton.setEnabled(false);
                    _recording = true;
                    takePicture();
                    /*_pichandler.postDelayed(_picrunner, 1000);*/
                }
            }
        });

        settingsButton = (ImageButton) findViewById(R.id.btn_settings);
        settingsButton.setOnClickListener(
                new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                        // on android 6.0, camera needs to be closed before starting this new intent

                        startActivity(intent);
                    }
                }
        );

        manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //open your camera here
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            //Log.e(TAG, "onOpened");
            cameraDevice = camera;
            try {
                characteristics = manager.getCameraCharacteristics(cameraDevice.getId());

                // read preferences
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                _fps = Float.parseFloat(prefs.getString("pref_framerates", "10."));

                // ------------ resolution and setup reader and output surfaces
                String selected_res = prefs.getString("pref_resolutions", ""); // this gives the value
                if (selected_res != "") {
                    // FIXME: expose to preferences!
                    // FIXME: max framerate with JPEG is 150ms == ca. 6.6 fps on GS5
                    // GS6: 36-40ms
                    Size[] sizes;
                    if(_format == "JPEG") {
                        sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
                    }else{
                        sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.YUV_420_888);
                    }
                    /*
                    Size[] sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
                    // get min frame durations for diff. image sizes
                    for(Size size : sizes) {
                        long dur = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputMinFrameDuration(ImageFormat.JPEG, size);
                        Log.d("StreamMap", "MinFrameDuration for "+size.getWidth()+"x"+size.getHeight()+": "+dur/1000000.);
                    }
                    */

                    /*
                    Size[] sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.YUV_420_888);
                    // get min frame durations for diff. image sizes
                    for(Size size : sizes) {
                        //long dur = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputMinFrameDuration(ImageFormat.YUV_420_888, size);
                        long dur = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputStallDuration(ImageFormat.YUV_420_888, size);
                        Log.d("StreamMap", "StallDuration for "+size.getWidth()+"x"+size.getHeight()+": "+dur/1000000.);
                    }
                    */

                    _img_width = sizes[Integer.parseInt(selected_res)].getWidth();
                    _img_height = sizes[Integer.parseInt(selected_res)].getHeight();
                }

                //reader = ImageReader.newInstance(_img_width, _img_height, ImageFormat.JPEG, 3);
                if(_format == "JPEG") {
                    reader = ImageReader.newInstance(_img_width, _img_height, ImageFormat.JPEG, 5); // YUV is way faster
                }else{
                    reader = ImageReader.newInstance(_img_width, _img_height, ImageFormat.YUV_420_888, 5); // YUV is way faster
                }
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
            createCameraPreview();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    final CameraCaptureSession.CaptureCallback previewCallbackListener = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            _diff = System.currentTimeMillis() - last_pic_ts;
            last_pic_ts = System.currentTimeMillis();
        }
    };

    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    protected void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected void takePicture() {
        if (null == cameraDevice) {
            return;
        }

        _seq_timestamp = System.currentTimeMillis();
        // this is SD-storage, android/data/de.weis.multisensor_grabber/files/
        // TOBI
        DateFormat sdf = new SimpleDateFormat("yyyy_MM_dd-HH_mm");
        Date netDate = (new Date(_seq_timestamp));

        //_dir = new File(_extdir + File.separator + _seq_timestamp);
        _dir = new File(_extdir + File.separator + sdf.format(netDate));
        _dir.mkdirs();
        _path = _dir.getPath() + File.separator;

        try {
            fileos = new FileOutputStream(new File(_path + _seq_timestamp + ".xml"));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        try {
            serializer.setOutput(fileos, "UTF-8");
            serializer.startDocument(null, Boolean.valueOf(true));
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startTag(null, "sequence");
            serializer.attribute(null, "folder", _dir.getAbsolutePath() + File.separator);
            String manufacturer = Build.MANUFACTURER;
            String model = Build.MODEL;
            serializer.attribute(null, "sensor", manufacturer + model);
            serializer.attribute(null, "ts", "" + _seq_timestamp);
            //serializer.attribute(null, "whitebalance", mCamera.getParameters().get("whitebalance").toString());
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            texture.setDefaultBufferSize(_img_width, _img_height);
            configureTransform(textureView.getWidth(), textureView.getHeight());

            Surface surface = new Surface(texture);

            List<android.view.Surface> surfaces = new ArrayList<Surface>(1);
            surfaces.add(surface);
            surfaces.add(reader.getSurface());
            _capReq = get_captureBuilder(surfaces);

            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader myreader) {
                    Image image = null;

                        image = myreader.acquireNextImage();
                        if (image == null) {
                            return;
                        }

                        long curr = image.getTimestamp();

                        if ((curr - last_pic_ts) >=  1000000000. / (_fps+1.)) {
                            _diff = (curr - last_pic_ts) / 1000000;
                            //Log.d("___DIFF", ""+_diff);

                            //Log.d("diff: ", "" + _diff);
                            last_pic_ts = curr;

                            String fname= "";

                            try{
                                if(_format == "JPEG") {
                                    fname = "pic" + String.format("%08d", _cnt) + ".jpg";
                                    File file = new File(_path + fname);
                                    FileOutputStream output = null;
                                    try {
                                        output = new FileOutputStream(file);
                                    } catch (FileNotFoundException e) {
                                        e.printStackTrace();
                                    }

                                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                    byte[] bytes = new byte[buffer.capacity()];
                                    buffer.get(bytes);
                                    output.write(bytes);
                                    output.close();
                                }else {
                                    fname = "pic" + String.format("%08d", _cnt) + ".yuv";
                                    File file = new File(_path + fname);
                                    FileOutputStream output = new FileOutputStream(file);

                                    ByteBuffer buffer;
                                    byte[] bytes;
                                    ByteBuffer prebuffer = ByteBuffer.allocate(16);
                                    prebuffer.putInt(image.getWidth())
                                            .putInt(image.getHeight())
                                            .putInt(image.getPlanes()[1].getPixelStride())
                                            .putInt(image.getPlanes()[1].getRowStride());

                                    output.write(prebuffer.array()); // write meta information to file
                                    // Now write the actual planes.
                                    for (int i = 0; i < 3; i++) {
                                        buffer = image.getPlanes()[i].getBuffer();
                                        bytes = new byte[buffer.remaining()]; // makes byte array large enough to hold image
                                        buffer.get(bytes); // copies image from buffer to byte array
                                        output.write(bytes);    // write the byte array to file
                                    }
                                    output.close();
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }

                            try {
                                save_xml(fname);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    image.close();
                }

                private void save_xml(String fname) throws IOException {
                    //Long st = System.currentTimeMillis();
                    OutputStream output = null;
                    try {

                        try {
                            serializer.startTag(null, "Frame");
                            serializer.attribute(null, "uri", fname);
                            try {
                                serializer.attribute(null, "lat", "" + _loc.getLatitude());
                                serializer.attribute(null, "lon", "" + _loc.getLongitude());
                                serializer.attribute(null, "acc", "" + _loc.getAccuracy());
                                serializer.attribute(null, "speed", "" + _loc.getSpeed());
                                serializer.attribute(null, "bearing", "" + _loc.getBearing());
                                serializer.attribute(null, "ts_gps", "" + _loc.getTime());
                            }catch(Exception e){
                                serializer.attribute(null, "lat", "-1");
                                serializer.attribute(null, "lon", "-1");
                                serializer.attribute(null, "acc", "-1");
                                serializer.attribute(null, "speed", "-1");
                            }
                            serializer.attribute(null, "img_w", "" + _img_width);
                            serializer.attribute(null, "img_h", "" + _img_height);

                            serializer.attribute(null, "wb_value", ""+ _wb_value);
                            if(_fix_iso) {
                                serializer.attribute(null, "iso_value", "" + _iso_value);
                            }else {
                                serializer.attribute(null, "iso_value", "-1");
                            }

                            serializer.attribute(null, "ts_cam", "" + last_pic_ts);
                            if (_fix_exp) {
                                serializer.attribute(null, "exp_time", "" + _exp_time);
                            } else {
                                //FIXME: is it possible to get the exposure time of each single image if auto-exposure is on?
                                //FIXME: look at the callback, for some cellphones we can get these values
                                serializer.attribute(null, "exp_time", "-1");
                            }

                            if(_fix_foc) {
                                serializer.attribute(null, "foc_dist", "" + _foc_dist);
                            }else {
                                serializer.attribute(null, "foc_dist", "-1");
                            }
                            serializer.attribute(null, "avelx", ""+_gyro_roll);
                            serializer.attribute(null, "avely", ""+_gyro_head);
                            serializer.attribute(null, "avelz", ""+_gyro_pitch);
                            serializer.attribute(null, "accx", ""+_accel_x);
                            serializer.attribute(null, "accy", ""+_accel_y);
                            serializer.attribute(null, "accz", ""+_accel_z);

                            serializer.endTag(null, "Frame");
                            //serializer.flush(); // FIXME: a flush at the end should be enough!
                        } catch (IOException e) {
                            Toast.makeText(MainActivity.this, "Serializer IOExcept: " + e, Toast.LENGTH_LONG);
                        }
                        _cnt += 1;
                    } finally {
                        if (null != output) {
                            output.close();
                        }
                    }

                    //Log.d("TIME-SAVE", "TOOK " +(System.currentTimeMillis() - st));
                }
            };

            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);
            final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    // does not work on GS5
                    // FIXME: these infos can be used on supporting phones if they are in auto mode
                    /*
                    Log.e(TAG, "Available keys = " + result.getKeys().toString());
                    Log.e(TAG, "Exposure time = " + result.get(CaptureResult.SENSOR_EXPOSURE_TIME));
                    */
			        //Log.e(TAG, "Frame duration = " + result.get(CaptureResult.SENSOR_FRAME_DURATION));
                    /*
                    Log.e(TAG, "Sensor sensitivity = " + result.get(CaptureResult.SENSOR_SENSITIVITY));
                    */

                }
            };

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    try {
                        _session = session;
                        // use the same captureRequest builder as for the preview,
                        // this has already been built from user preferences!
                        session.setRepeatingRequest(_capReq.build(), captureListener, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
            }, mBackgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    CaptureRequest.Builder get_captureBuilder(List<android.view.Surface> outputsurfaces){
        CaptureRequest.Builder captureBuilder = null;
        try {
            captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        for(Surface s:outputsurfaces){
            captureBuilder.addTarget(s);
        }

        captureBuilder.set(CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO);

        /* user prefs */
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
        _fix_exp=prefs.getBoolean("pref_fix_exp",false);
        _exp_time=Long.parseLong(prefs.getString("pref_exposure","0")) * 1000000;
        _fix_foc = prefs.getBoolean("pref_fix_foc", false);
        _fix_iso = prefs.getBoolean("pref_fix_iso", false);

        _foc_dist = Float.parseFloat(prefs.getString("pref_focus_dist", "-1"));
        _wb_value = Integer.parseInt(prefs.getString("pref_wb", ""+CONTROL_AWB_MODE_AUTO));
        _iso_value = Integer.parseInt(prefs.getString("pref_iso", "-1"));

        /************************************** EXPOSURE TIME ********************************/
        if(_fix_exp){
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CONTROL_AE_MODE_OFF);
            captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, _exp_time);
            captureBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
        }

        /************************************** ISO ********************************/
        if(_fix_iso) {
            if (_iso_value != -1) {
                captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, _iso_value);
            }
        }


        /************************************** FOCUS ********************************/
        if(_fix_foc) {
            captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, _foc_dist);
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CONTROL_AF_MODE_OFF);
        }

        /************************************** WHITEBALANCE ********************************/
        https://developer.android.com/reference/android/hardware/camera2/CaptureRequest.html
        // When set to the OFF mode, the camera device's auto-white balance routine is disabled.
        // The application manually controls the white balance by
        // android.colorCorrection.transform, (https://developer.android.com/reference/android/hardware/camera2/CaptureRequest.html#COLOR_CORRECTION_TRANSFORM)
        // android.colorCorrection.gains (https://developer.android.com/reference/android/hardware/camera2/CaptureRequest.html#COLOR_CORRECTION_GAINS)
        // android.colorCorrection.mode. (https://developer.android.com/reference/android/hardware/camera2/CaptureRequest.html#COLOR_CORRECTION_MODE)
        // FIXME: implement setting the manual stuff for OFF-Mode
        captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, _wb_value);
        //captureBuilder.set(CaptureRequest.CONTROL_AWB_LOCK,true);

        /************************************** REST ********************************/
        // http://stackoverflow.com/questions/29265126/android-camera2-capture-burst-is-too-slow
        captureBuilder.set(CaptureRequest.EDGE_MODE,CaptureRequest.EDGE_MODE_OFF);
        captureBuilder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);
        captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
        captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
        //captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,);

        // this command does not seem to have any effect?
        //captureBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, (long)100*1000000);

        // Orientation
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION,ORIENTATIONS.get(rotation));

        return captureBuilder;
    }

    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            texture.setDefaultBufferSize(_img_width, _img_height);

            //Log.d("textureView", "========================= calling configureTransform");
            configureTransform(textureView.getWidth(),textureView.getHeight());

            // for the preview, we only want the preview-surface as output
            Surface surface = new Surface(texture);
            List<android.view.Surface> surfaces = new ArrayList<Surface>(1);
            surfaces.add(surface);

            _capReq = get_captureBuilder(surfaces);

            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == cameraDevice) {
                        return;
                    }
                    // When the session is ready, we start displaying the preview.
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private void openCamera() {
        //Log.e(TAG, "is camera open");
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;

            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_CAMERA_PERMISSION);
                return;
            }

            manager.openCamera(cameraId, stateCallback, null);
            mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            bestProvider = mLocationManager.getBestProvider(criteria, false);
            _loc = mLocationManager.getLastKnownLocation(bestProvider);
            mLocationManager.requestLocationUpdates(bestProvider, 1,0.01f, locationListener);

            SensorManager sm = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
            sm.registerListener(sel,
                    sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                    SensorManager.SENSOR_DELAY_UI);
            sm.registerListener(sel,
                    sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                    SensorManager.SENSOR_DELAY_UI);

            sys_handler.postDelayed(grab_system_data, 1);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        //Log.e(TAG, "openCamera X");
    }

    protected void updatePreview() {
        if(null == cameraDevice) {
            //Log.e(TAG, "updatePreview error, return");
        }

        try {
            cameraCaptureSessions.setRepeatingRequest(_capReq.build(), previewCallbackListener, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == textureView){
            //Log.d("configTrans", "------------------------------textureView is null!");
            return;
        }

        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        //Log.d("imageDim", "=============================== height: " + _img_height + ", width: " + _img_width);
        RectF bufferRect = new RectF(0, 0, _img_height, _img_width);
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / _img_height,
                    (float) viewWidth / _img_width);
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    private void closeCamera() {
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }

    final LocationListener locationListener = new LocationListener() {
        public void onLocationChanged(Location location) {
            _loc = location;
        }
        public void onProviderDisabled(String provider){}
        public void onProviderEnabled(String provider){ }
        public void onStatusChanged(String provider, int status, Bundle extras){
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(MainActivity.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        //Log.e(TAG, "onResume");
        startBackgroundThread();

        try {
            _extdir = getExternalFilesDirs(null)[1];
        } catch (Exception e) {
            _extdir = getExternalFilesDirs(null)[0];
        }

        /* put it in the prefs so the user can find the files later */
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
        try {
            prefs.edit().putString("pref_dir", _extdir.toString()).apply();
        }catch (Exception e){
            Toast.makeText(this, "Setting external directory failed", Toast.LENGTH_LONG);
        }

        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }
    @Override
    protected void onPause() {
        //Log.e(TAG, "onPause");
        _recording = false;
        takePictureButton.setImageResource(R.mipmap.icon_rec);
        settingsButton.setEnabled(true);
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    public float getBatteryLevel() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        // Error checking that probably isn't needed but I added just in case.
        if(level == -1 || scale == -1) {
            return 50.0f;
        }

        return ((float)level / (float)scale) * 100.0f;
    }

    private final SensorEventListener sel = new SensorEventListener() {
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                updateOrientation(event.values[0], event.values[1], event.values[2]);
            }
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                updateAccels(event.values[0], event.values[1], event.values[2]);
            }
        }

        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    // FIXME: are semaphores needed here?
    private void updateOrientation(float heading, float pitch, float roll) {
        _gyro_head = heading;
        _gyro_pitch = pitch;
        _gyro_roll = roll;
    }

    private void updateAccels(float x, float y, float z){
        _accel_x = x;
        _accel_y = y;
        _accel_z = z;
    }
}
