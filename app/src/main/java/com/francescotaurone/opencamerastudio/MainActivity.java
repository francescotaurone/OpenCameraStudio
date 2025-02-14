package com.francescotaurone.opencamerastudio;

import com.francescotaurone.opencamerastudio.cameracontroller.CameraController;
import com.francescotaurone.opencamerastudio.cameracontroller.CameraControllerManager2;
import com.francescotaurone.opencamerastudio.preview.Preview;
import com.francescotaurone.opencamerastudio.preview.VideoProfile;
import com.francescotaurone.opencamerastudio.remotecontrol.BluetoothRemoteControl;
import com.francescotaurone.opencamerastudio.studio.StudioServer;
import com.francescotaurone.opencamerastudio.ui.FolderChooserDialog;
import com.francescotaurone.opencamerastudio.ui.MainUI;
import com.francescotaurone.opencamerastudio.ui.ManualSeekbars;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.renderscript.RenderScript;
import android.speech.tts.TextToSpeech;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.ZoomControls;

import org.json.JSONException;
import org.json.JSONObject;

/** The main Activity for Open Camera.
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    public static final String STUDIO_BROADCAST_ID = "STUDIO_BROADCAST";

    private static int activity_count = 0;

    private SensorManager mSensorManager;
    private Sensor mSensorAccelerometer;

    // components: always non-null (after onCreate())
    private BluetoothRemoteControl bluetoothRemoteControl;
    private PermissionHandler permissionHandler;
    private SettingsManager settingsManager;
    private MainUI mainUI;
    private ManualSeekbars manualSeekbars;
    private MyApplicationInterface applicationInterface;
    private TextFormatter textFormatter;
    private SoundPoolManager soundPoolManager;
    private MagneticSensor magneticSensor;
    private SpeechControl speechControl;
    private StudioServer studioServer;

    private Preview preview;
    private OrientationEventListener orientationEventListener;
    private int large_heap_memory;
    private boolean supports_auto_stabilise;
    private boolean supports_force_video_4k;
    private boolean supports_camera2;
    private SaveLocationHistory save_location_history; // save location for non-SAF
    private SaveLocationHistory save_location_history_saf; // save location for SAF (only initialised when SAF is used)
    private boolean saf_dialog_from_preferences; // if a SAF dialog is opened, this records whether we opened it from the Preferences
    private boolean camera_in_background; // whether the camera is covered by a fragment/dialog (such as settings or folder picker)
    private GestureDetector gestureDetector;
    private boolean screen_is_locked; // whether screen is "locked" - this is Open Camera's own lock to guard against accidental presses, not the standard Android lock
    private final Map<Integer, Bitmap> preloaded_bitmap_resources = new Hashtable<>();
    private ValueAnimator gallery_save_anim;
    private boolean last_continuous_fast_burst; // whether the last photo operation was a continuous_fast_burst

    private TextToSpeech textToSpeech;
    private boolean textToSpeechSuccess;

    private AudioListener audio_listener; // may be null - created when needed

    //private boolean ui_placement_right = true;

    private final ToastBoxer switch_video_toast = new ToastBoxer();
    private final ToastBoxer screen_locked_toast = new ToastBoxer();
    private final ToastBoxer stamp_toast = new ToastBoxer();
    private final ToastBoxer changed_auto_stabilise_toast = new ToastBoxer();
    private final ToastBoxer white_balance_lock_toast = new ToastBoxer();
    private final ToastBoxer exposure_lock_toast = new ToastBoxer();
    private final ToastBoxer audio_control_toast = new ToastBoxer();
    private boolean block_startup_toast = false; // used when returning from Settings/Popup - if we're displaying a toast anyway, don't want to display the info toast too

    // application shortcuts:
    static private final String ACTION_SHORTCUT_CAMERA = "net.sourceforge.opencamera.SHORTCUT_CAMERA";
    static private final String ACTION_SHORTCUT_SELFIE = "net.sourceforge.opencamera.SHORTCUT_SELFIE";
    static private final String ACTION_SHORTCUT_VIDEO = "net.sourceforge.opencamera.SHORTCUT_VIDEO";
    static private final String ACTION_SHORTCUT_GALLERY = "net.sourceforge.opencamera.SHORTCUT_GALLERY";
    static private final String ACTION_SHORTCUT_SETTINGS = "net.sourceforge.opencamera.SHORTCUT_SETTINGS";

    private static final int CHOOSE_SAVE_FOLDER_SAF_CODE = 42;
    private static final int CHOOSE_GHOST_IMAGE_SAF_CODE = 43;
    private static final int CHOOSE_LOAD_SETTINGS_SAF_CODE = 44;

    // for testing; must be volatile for test project reading the state
    // n.b., avoid using static, as static variables are shared between different instances of an application,
    // and won't be reset in subsequent tests in a suite!
    public boolean is_test; // whether called from OpenCamera.test testing
    public volatile Bitmap gallery_bitmap;
    public volatile boolean test_low_memory;
    public volatile boolean test_have_angle;
    public volatile float test_angle;
    public volatile String test_last_saved_image;
    public static boolean test_force_supports_camera2; // okay to be static, as this is set for an entire test suite
    public volatile String test_save_settings_file;

    private boolean has_notification;
    private final String CHANNEL_ID = "open_camera_channel";
    private final int image_saving_notification_id = 1;

    private static final float WATER_DENSITY_FRESHWATER = 1.0f;
    private static final float WATER_DENSITY_SALTWATER = 1.03f;
    private float mWaterDensity = 1.0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long debug_time = 0;
        if( MyDebug.LOG ) {
            Log.d(TAG, "onCreate: " + this);
            debug_time = System.currentTimeMillis();
        }
        activity_count++;
        if( MyDebug.LOG )
            Log.d(TAG, "activity_count: " + activity_count);
        super.onCreate(savedInstanceState);

        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 ) {
            // don't show orientation animations
            WindowManager.LayoutParams layout = getWindow().getAttributes();
            layout.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE;
            getWindow().setAttributes(layout);
        }

        setContentView(R.layout.activity_main);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false); // initialise any unset preferences to their default values
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after setting default preference values: " + (System.currentTimeMillis() - debug_time));

        if( getIntent() != null && getIntent().getExtras() != null ) {
            // whether called from testing
            is_test = getIntent().getExtras().getBoolean("test_project");
            if( MyDebug.LOG )
                Log.d(TAG, "is_test: " + is_test);
        }
        if( getIntent() != null && getIntent().getExtras() != null ) {
            // whether called from Take Photo widget
            if( MyDebug.LOG )
                Log.d(TAG, "take_photo?: " + getIntent().getExtras().getBoolean(TakePhoto.TAKE_PHOTO));
        }
        if( getIntent() != null && getIntent().getAction() != null ) {
            // invoked via the manifest shortcut?
            if( MyDebug.LOG )
                Log.d(TAG, "shortcut: " + getIntent().getAction());
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        // determine whether we should support "auto stabilise" feature
        // risk of running out of memory on lower end devices, due to manipulation of large bitmaps
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if( MyDebug.LOG ) {
            Log.d(TAG, "standard max memory = " + activityManager.getMemoryClass() + "MB");
            Log.d(TAG, "large max memory = " + activityManager.getLargeMemoryClass() + "MB");
        }
        large_heap_memory = activityManager.getLargeMemoryClass();
        if( large_heap_memory >= 128 ) {
            supports_auto_stabilise = true;
        }
        if( MyDebug.LOG )
            Log.d(TAG, "supports_auto_stabilise? " + supports_auto_stabilise);

        // hack to rule out phones unlikely to have 4K video, so no point even offering the option!
        // both S5 and Note 3 have 128MB standard and 512MB large heap (tested via Samsung RTL), as does Galaxy K Zoom
        // also added the check for having 128MB standard heap, to support modded LG G2, which has 128MB standard, 256MB large - see https://sourceforge.net/p/opencamera/tickets/9/
        if( activityManager.getMemoryClass() >= 128 || activityManager.getLargeMemoryClass() >= 512 ) {
            supports_force_video_4k = true;
        }
        if( MyDebug.LOG )
            Log.d(TAG, "supports_force_video_4k? " + supports_force_video_4k);

        // set up components
        bluetoothRemoteControl = new BluetoothRemoteControl(this);
        permissionHandler = new PermissionHandler(this);
        settingsManager = new SettingsManager(this);
        mainUI = new MainUI(this);
        manualSeekbars = new ManualSeekbars();
        applicationInterface = new MyApplicationInterface(this, savedInstanceState);
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after creating application interface: " + (System.currentTimeMillis() - debug_time));
        textFormatter = new TextFormatter(this);
        soundPoolManager = new SoundPoolManager(this);
        magneticSensor = new MagneticSensor(this);
        speechControl = new SpeechControl(this);
        studioServer = new StudioServer(this, 8000);

        // determine whether we support Camera2 API
        initCamera2Support();

        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN ) {
            // no point having talkback care about this - and (hopefully) avoid Google Play pre-launch accessibility warnings
            View container = findViewById(R.id.hide_container);
            container.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        // set up window flags for normal operation
        setWindowFlagsForCamera();
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after setting window flags: " + (System.currentTimeMillis() - debug_time));

        save_location_history = new SaveLocationHistory(this, "save_location_history", getStorageUtils().getSaveLocation());
        if( applicationInterface.getStorageUtils().isUsingSAF() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "create new SaveLocationHistory for SAF");
            save_location_history_saf = new SaveLocationHistory(this, "save_location_history_saf", getStorageUtils().getSaveLocationSAF());
        }
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after updating folder history: " + (System.currentTimeMillis() - debug_time));

        // set up sensors
        mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);

        // accelerometer sensor (for device orientation)
        if( mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "found accelerometer");
            mSensorAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        else {
            if( MyDebug.LOG )
                Log.d(TAG, "no support for accelerometer");
        }
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after creating accelerometer sensor: " + (System.currentTimeMillis() - debug_time));

        // magnetic sensor (for compass direction)
        magneticSensor.initSensor(mSensorManager);
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after creating magnetic sensor: " + (System.currentTimeMillis() - debug_time));

        // clear any seek bars (just in case??)
        mainUI.clearSeekBar();

        // set up the camera and its preview
        preview = new Preview(applicationInterface, ((ViewGroup) this.findViewById(R.id.preview)));
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after creating preview: " + (System.currentTimeMillis() - debug_time));

        // initialise on-screen button visibility
        View switchCameraButton = findViewById(R.id.switch_camera);
        switchCameraButton.setVisibility(preview.getCameraControllerManager().getNumberOfCameras() > 1 ? View.VISIBLE : View.GONE);
        View speechRecognizerButton = findViewById(R.id.audio_control);
        speechRecognizerButton.setVisibility(View.GONE); // disabled by default, until the speech recognizer is created
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after setting button visibility: " + (System.currentTimeMillis() - debug_time));
        View pauseVideoButton = findViewById(R.id.pause_video);
        pauseVideoButton.setVisibility(View.GONE);
        View takePhotoVideoButton = findViewById(R.id.take_photo_when_video_recording);
        takePhotoVideoButton.setVisibility(View.GONE);
        View cancelPanoramaButton = findViewById(R.id.cancel_panorama);
        cancelPanoramaButton.setVisibility(View.GONE);

        // We initialise optional controls to invisible/gone, so they don't show while the camera is opening - the actual visibility is
        // set in cameraSetup().
        // Note that ideally we'd set this in the xml, but doing so for R.id.zoom causes a crash on Galaxy Nexus startup beneath
        // setContentView()!
        // To be safe, we also do so for take_photo and zoom_seekbar (we already know we've had no reported crashes for focus_seekbar,
        // however).
        View takePhotoButton = findViewById(R.id.take_photo);
        takePhotoButton.setVisibility(View.INVISIBLE);
        View zoomControls = findViewById(R.id.zoom);
        zoomControls.setVisibility(View.GONE);
        View zoomSeekbar = findViewById(R.id.zoom_seekbar);
        zoomSeekbar.setVisibility(View.INVISIBLE);

        // initialise state of on-screen icons
        mainUI.updateOnScreenIcons();

        // listen for orientation event change
        orientationEventListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                MainActivity.this.mainUI.onOrientationChanged(orientation);
            }
        };
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after setting orientation event listener: " + (System.currentTimeMillis() - debug_time));

        // set up take photo long click
        takePhotoButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return longClickedTakePhoto();
            }
        });
        // set up on touch listener so we can detect if we've released from a long click
        takePhotoButton.setOnTouchListener(new View.OnTouchListener() {
            // the suppressed warning ClickableViewAccessibility suggests calling view.performClick for ACTION_UP, but this
            // results in an additional call to clickedTakePhoto() - that is, if there is no long press, we get two calls to
            // clickedTakePhoto instead one one; and if there is a long press, we get one call to clickedTakePhoto where
            // there should be none.
            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if( motionEvent.getAction() == MotionEvent.ACTION_UP ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "takePhotoButton ACTION_UP");
                    takePhotoButtonLongClickCancelled();
                    if( MyDebug.LOG )
                        Log.d(TAG, "takePhotoButton ACTION_UP done");
                }
                return false;
            }
        });

        // set up gallery button long click
        View galleryButton = findViewById(R.id.gallery);
        galleryButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                //preview.showToast(null, "Long click");
                longClickedGallery();
                return true;
            }
        });
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after setting long click listeners: " + (System.currentTimeMillis() - debug_time));

        // listen for gestures
        gestureDetector = new GestureDetector(this, new MyGestureDetector());
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after creating gesture detector: " + (System.currentTimeMillis() - debug_time));

        // set up listener to handle immersive mode options
        View decorView = getWindow().getDecorView();
        decorView.setOnSystemUiVisibilityChangeListener
                (new View.OnSystemUiVisibilityChangeListener() {
                    @Override
                    public void onSystemUiVisibilityChange(int visibility) {
                        // Note that system bars will only be "visible" if none of the
                        // LOW_PROFILE, HIDE_NAVIGATION, or FULLSCREEN flags are set.
                        if( !usingKitKatImmersiveMode() )
                            return;
                        if( MyDebug.LOG )
                            Log.d(TAG, "onSystemUiVisibilityChange: " + visibility);
                        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                            if( MyDebug.LOG )
                                Log.d(TAG, "system bars now visible");
                            // The system bars are visible. Make any desired
                            // adjustments to your UI, such as showing the action bar or
                            // other navigational controls.
                            mainUI.setImmersiveMode(false);
                            setImmersiveTimer();
                        }
                        else {
                            if( MyDebug.LOG )
                                Log.d(TAG, "system bars now NOT visible");
                            // The system bars are NOT visible. Make any desired
                            // adjustments to your UI, such as hiding the action bar or
                            // other navigational controls.
                            mainUI.setImmersiveMode(true);
                        }
                    }
                });
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after setting immersive mode listener: " + (System.currentTimeMillis() - debug_time));

        // show "about" dialog for first time use; also set some per-device defaults
        boolean has_done_first_time = sharedPreferences.contains(PreferenceKeys.FirstTimePreferenceKey);
        if( MyDebug.LOG )
            Log.d(TAG, "has_done_first_time: " + has_done_first_time);
        if( !has_done_first_time ) {
            setDeviceDefaults();
        }
        if( !has_done_first_time ) {
            if( !is_test ) {
                AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);
                alertDialog.setTitle(R.string.app_name);
                alertDialog.setMessage(R.string.intro_text);
                alertDialog.setPositiveButton(android.R.string.ok, null);
                alertDialog.setNegativeButton(R.string.preference_online_help, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "online help");
                        launchOnlineHelp();
                    }
                });
                alertDialog.show();
            }

            setFirstTimeFlag();
        }

        {
            // handle What's New dialog
            int version_code = -1;
            try {
                PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                version_code = pInfo.versionCode;
            }
            catch(PackageManager.NameNotFoundException e) {
                if( MyDebug.LOG )
                    Log.d(TAG, "NameNotFoundException exception trying to get version number");
                e.printStackTrace();
            }
            if( version_code != -1 ) {
                int latest_version = sharedPreferences.getInt(PreferenceKeys.LatestVersionPreferenceKey, 0);
                if( MyDebug.LOG ) {
                    Log.d(TAG, "version_code: " + version_code);
                    Log.d(TAG, "latest_version: " + latest_version);
                }
                //final boolean whats_new_enabled = false;
                final boolean whats_new_enabled = true;
                if( whats_new_enabled ) {
                    // whats_new_version is the version code that the What's New text is written for. Normally it will equal the
                    // current release (version_code), but it some cases we may want to leave it unchanged.
                    // E.g., we have a "What's New" for 1.44 (64), but then push out a quick fix for 1.44.1 (65). We don't want to
                    // show the dialog again to people who already received 1.44 (64), but we still want to show the dialog to people
                    // upgrading from earlier versions.
                    int whats_new_version = 71; // 1.47
                    whats_new_version = Math.min(whats_new_version, version_code); // whats_new_version should always be <= version_code, but just in case!
                    if( MyDebug.LOG ) {
                        Log.d(TAG, "whats_new_version: " + whats_new_version);
                    }
                    final boolean force_whats_new = false;
                    //final boolean force_whats_new = true; // for testing
                    boolean allow_show_whats_new = sharedPreferences.getBoolean(PreferenceKeys.ShowWhatsNewPreferenceKey, true);
                    if( MyDebug.LOG )
                        Log.d(TAG, "allow_show_whats_new: " + allow_show_whats_new);
                    // don't show What's New if this is the first time the user has run
                    if( has_done_first_time && allow_show_whats_new && ( force_whats_new || whats_new_version > latest_version ) ) {
                        AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);
                        alertDialog.setTitle(R.string.whats_new);
                        alertDialog.setMessage(R.string.whats_new_text);
                        alertDialog.setPositiveButton(android.R.string.ok, null);
                        alertDialog.setNegativeButton(R.string.donate, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if( MyDebug.LOG )
                                    Log.d(TAG, "donate");
                                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.DonateLink));
                                startActivity(browserIntent);
                            }
                        });
                        alertDialog.show();
                    }
                }
                // We set the latest_version whether or not the dialog is shown - if we showed the first time dialog, we don't
                // want to then show the What's New dialog next time we run! Similarly if the user had disabled showing the dialog,
                // but then enables it, we still shouldn't show the dialog until the new time Open Camera upgrades.
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt(PreferenceKeys.LatestVersionPreferenceKey, version_code);
                editor.apply();
            }
        }

        setModeFromIntents(savedInstanceState);

        // load icons
        preloadIcons(R.array.flash_icons);
        preloadIcons(R.array.focus_mode_icons);
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: time after preloading icons: " + (System.currentTimeMillis() - debug_time));

        // initialise text to speech engine
        textToSpeechSuccess = false;
        // run in separate thread so as to not delay startup time
        new Thread(new Runnable() {
            public void run() {
                textToSpeech = new TextToSpeech(MainActivity.this, new TextToSpeech.OnInitListener() {
                    @Override
                    public void onInit(int status) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "TextToSpeech initialised");
                        if( status == TextToSpeech.SUCCESS ) {
                            textToSpeechSuccess = true;
                            if( MyDebug.LOG )
                                Log.d(TAG, "TextToSpeech succeeded");
                        }
                        else {
                            if( MyDebug.LOG )
                                Log.d(TAG, "TextToSpeech failed");
                        }
                    }
                });
            }
        }).start();

        // create notification channel - only needed on Android 8+
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            CharSequence name = "Open Camera Image Saving";
            String description = "Notification channel for processing and saving images in the background";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }

        initializeServerFiles();

        if( MyDebug.LOG )
            Log.d(TAG, "onCreate: total time for Activity startup: " + (System.currentTimeMillis() - debug_time));
    }

    public static void copy(InputStream in, File dst) throws IOException {
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                // Transfer bytes from in to out
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    void initializeServerFiles() {
        AssetManager manager = getAssets();
        File cacheDir = getCacheDir();
        File websiteCacheDir = new File(cacheDir, "website");

        if (!websiteCacheDir.exists()) {
            websiteCacheDir.mkdirs();
        }

        try {
            String[] files = manager.list("website");

            for (String file : files) {
                File output = new File(websiteCacheDir, file);
                copy(manager.open("website/"+file), output);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /* This method sets the preference defaults which are set specific for a particular device.
     * This method should be called when Open Camera is run for the very first time after installation,
     * or when the user has requested to "Reset settings".
     */
    void setDeviceDefaults() {
        if( MyDebug.LOG )
            Log.d(TAG, "setDeviceDefaults");
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean is_samsung = Build.MANUFACTURER.toLowerCase(Locale.US).contains("samsung");
        boolean is_oneplus = Build.MANUFACTURER.toLowerCase(Locale.US).contains("oneplus");
        //boolean is_nexus = Build.MODEL.toLowerCase(Locale.US).contains("nexus");
        //boolean is_nexus6 = Build.MODEL.toLowerCase(Locale.US).contains("nexus 6");
        //boolean is_pixel_phone = Build.DEVICE != null && Build.DEVICE.equals("sailfish");
        //boolean is_pixel_xl_phone = Build.DEVICE != null && Build.DEVICE.equals("marlin");
        if( MyDebug.LOG ) {
            Log.d(TAG, "is_samsung? " + is_samsung);
            Log.d(TAG, "is_oneplus? " + is_oneplus);
            //Log.d(TAG, "is_nexus? " + is_nexus);
            //Log.d(TAG, "is_nexus6? " + is_nexus6);
            //Log.d(TAG, "is_pixel_phone? " + is_pixel_phone);
            //Log.d(TAG, "is_pixel_xl_phone? " + is_pixel_xl_phone);
        }
        if( is_samsung || is_oneplus ) {
            // workaround needed for Samsung S7 at least (tested on Samsung RTL)
            // workaround needed for OnePlus 3 at least (see http://forum.xda-developers.com/oneplus-3/help/camera2-support-t3453103 )
            // update for v1.37: significant improvements have been made for standard flash and Camera2 API. But OnePlus 3T still has problem
            // that photos come out with a blue tinge if flash is on, and the scene is bright enough not to need it; Samsung devices also seem
            // to work okay, testing on S7 on RTL, but still keeping the fake flash mode in place for these devices, until we're sure of good
            // behaviour
            if( MyDebug.LOG )
                Log.d(TAG, "set fake flash for camera2");
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean(PreferenceKeys.Camera2FakeFlashPreferenceKey, true);
            editor.apply();
        }
		/*if( is_nexus6 ) {
			// Nexus 6 captureBurst() started having problems with Android 7 upgrade - images appeared in wrong order (and with wrong order of shutter speeds in exif info), as well as problems with the camera failing with serious errors
			// we set this even for Nexus 6 devices not on Android 7, as at some point they'll likely be upgraded to Android 7
			// Update: now fixed in v1.37, this was due to bug where we set RequestTag.CAPTURE for all captures in takePictureBurstExpoBracketing(), rather than just the last!
			if( MyDebug.LOG )
				Log.d(TAG, "disable fast burst for camera2");
			SharedPreferences.Editor editor = sharedPreferences.edit();
			editor.putBoolean(PreferenceKeys.getCamera2FastBurstPreferenceKey(), false);
			editor.apply();
		}*/
    }

    /** Switches modes if required, if called from a relevant intent/tile.
     */
    private void setModeFromIntents(Bundle savedInstanceState) {
        if( MyDebug.LOG )
            Log.d(TAG, "setModeFromIntents");
        if( savedInstanceState != null ) {
            // If we're restoring from a saved state, we shouldn't be resetting any modes
            if( MyDebug.LOG )
                Log.d(TAG, "restoring from saved state");
            return;
        }
        boolean done_facing = false;
        String action = this.getIntent().getAction();
        if( MediaStore.INTENT_ACTION_VIDEO_CAMERA.equals(action) || MediaStore.ACTION_VIDEO_CAPTURE.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "launching from video intent");
            applicationInterface.setVideoPref(true);
        }
        else if( MediaStore.ACTION_IMAGE_CAPTURE.equals(action) || MediaStore.ACTION_IMAGE_CAPTURE_SECURE.equals(action) || MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA.equals(action) || MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "launching from photo intent");
            applicationInterface.setVideoPref(false);
        }
        else if( (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && MyTileService.TILE_ID.equals(action)) || ACTION_SHORTCUT_CAMERA.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "launching from quick settings tile or application shortcut for Open Camera: photo mode");
            applicationInterface.setVideoPref(false);
        }
        else if( (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && MyTileServiceVideo.TILE_ID.equals(action)) || ACTION_SHORTCUT_VIDEO.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "launching from quick settings tile or application shortcut for Open Camera: video mode");
            applicationInterface.setVideoPref(true);
        }
        else if( (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && MyTileServiceFrontCamera.TILE_ID.equals(action)) || ACTION_SHORTCUT_SELFIE.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "launching from quick settings tile or application shortcut for Open Camera: selfie mode");
            done_facing = true;
            applicationInterface.switchToCamera(true);
        }
        else if( ACTION_SHORTCUT_GALLERY.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "launching from application shortcut for Open Camera: gallery");
            openGallery();
        }
        else if( ACTION_SHORTCUT_SETTINGS.equals(action) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "launching from application shortcut for Open Camera: settings");
            openSettings();
        }

        Bundle extras = this.getIntent().getExtras();
        if( extras != null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "handle intent extra information");
            if( !done_facing ) {
                int camera_facing = extras.getInt("android.intent.extras.CAMERA_FACING", -1);
                if( camera_facing == 0 || camera_facing == 1 ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "found android.intent.extras.CAMERA_FACING: " + camera_facing);
                    applicationInterface.switchToCamera(camera_facing==1);
                    done_facing = true;
                }
            }
            if( !done_facing ) {
                if( extras.getInt("android.intent.extras.LENS_FACING_FRONT", -1) == 1 ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "found android.intent.extras.LENS_FACING_FRONT");
                    applicationInterface.switchToCamera(true);
                    done_facing = true;
                }
            }
            if( !done_facing ) {
                if( extras.getInt("android.intent.extras.LENS_FACING_BACK", -1) == 1 ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "found android.intent.extras.LENS_FACING_BACK");
                    applicationInterface.switchToCamera(false);
                    done_facing = true;
                }
            }
            if( !done_facing ) {
                if( extras.getBoolean("android.intent.extra.USE_FRONT_CAMERA", false) ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "found android.intent.extra.USE_FRONT_CAMERA");
                    applicationInterface.switchToCamera(true);
                    done_facing = true;
                }
            }
        }

        // N.B., in practice the hasSetCameraId() check is pointless as we don't save the camera ID in shared preferences, so it will always
        // be false when application is started from onCreate(), unless resuming from saved instance (in which case we shouldn't be here anyway)
        if( !done_facing && !applicationInterface.hasSetCameraId() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "initialise to back camera");
            // most devices have first camera as back camera anyway so this wouldn't be needed, but some (e.g., LG G6) have first camera
            // as front camera, so we should explicitly switch to back camera
            applicationInterface.switchToCamera(false);
        }
    }

    /** Determine whether we support Camera2 API.
     */
    private void initCamera2Support() {
        if( MyDebug.LOG )
            Log.d(TAG, "initCamera2Support");
        supports_camera2 = false;
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ) {
            // originally we allowed Camera2 if all cameras support at least LIMITED
            // as of 1.45, we allow Camera2 if at least one camera supports at least LIMITED - this
            // is to support devices that might have a camera with LIMITED or better support, but
            // also a LEGACY camera
            CameraControllerManager2 manager2 = new CameraControllerManager2(this);
            supports_camera2 = false;
            int n_cameras = manager2.getNumberOfCameras();
            if( n_cameras == 0 ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "Camera2 reports 0 cameras");
                supports_camera2 = false;
            }
            for(int i=0;i<n_cameras && !supports_camera2;i++) {
                if( manager2.allowCamera2Support(i) ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "camera " + i + " has at least limited support for Camera2 API");
                    supports_camera2 = true;
                }
            }
        }

        //test_force_supports_camera2 = true; // test
        if( test_force_supports_camera2 ) {
            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "forcing supports_camera2");
                supports_camera2 = true;
            }
        }

        if( MyDebug.LOG )
            Log.d(TAG, "supports_camera2? " + supports_camera2);
    }

    private void preloadIcons(int icons_id) {
        long debug_time = 0;
        if( MyDebug.LOG ) {
            Log.d(TAG, "preloadIcons: " + icons_id);
            debug_time = System.currentTimeMillis();
        }
        String [] icons = getResources().getStringArray(icons_id);
        for(String icon : icons) {
            int resource = getResources().getIdentifier(icon, null, this.getApplicationContext().getPackageName());
            if( MyDebug.LOG )
                Log.d(TAG, "load resource: " + resource);
            Bitmap bm = BitmapFactory.decodeResource(getResources(), resource);
            this.preloaded_bitmap_resources.put(resource, bm);
        }
        if( MyDebug.LOG ) {
            Log.d(TAG, "preloadIcons: total time for preloadIcons: " + (System.currentTimeMillis() - debug_time));
            Log.d(TAG, "size of preloaded_bitmap_resources: " + preloaded_bitmap_resources.size());
        }
    }

    @Override
    protected void onDestroy() {
        if( MyDebug.LOG ) {
            Log.d(TAG, "onDestroy");
            Log.d(TAG, "size of preloaded_bitmap_resources: " + preloaded_bitmap_resources.size());
        }
        activity_count--;
        if( MyDebug.LOG )
            Log.d(TAG, "activity_count: " + activity_count);

        // should do asap before waiting for images to be saved - as risk the application will be killed whilst waiting for that to happen,
        // and we want to avoid notifications hanging around
        cancelImageSavingNotification();

        // reduce risk of losing any images
        // we don't do this in onPause or onStop, due to risk of ANRs
        // note that even if we did call this earlier in onPause or onStop, we'd still want to wait again here: as it can happen
        // that a new image appears after onPause/onStop is called, in which case we want to wait until images are saved,
        // otherwise we can have crash if we need Renderscript after calling releaseAllContexts(), or because rs has been set to
        // null from beneath applicationInterface.onDestroy()
        waitUntilImageQueueEmpty();

        preview.onDestroy();
        if( applicationInterface != null ) {
            applicationInterface.onDestroy();
        }
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && activity_count == 0 ) {
            // See note in HDRProcessor.onDestroy() - but from Android M, renderscript contexts are released with releaseAllContexts()
            // doc for releaseAllContexts() says "If no contexts have been created this function does nothing"
            // Important to only do so if no other activities are running (see activity_count). Otherwise risk
            // of crashes if one activity is destroyed when another instance is still using Renderscript. I've
            // been unable to reproduce this, though such RSInvalidStateException crashes from Google Play.
            if( MyDebug.LOG )
                Log.d(TAG, "release renderscript contexts");
            RenderScript.releaseAllContexts();
        }
        // Need to recycle to avoid out of memory when running tests - probably good practice to do anyway
        for(Map.Entry<Integer, Bitmap> entry : preloaded_bitmap_resources.entrySet()) {
            if( MyDebug.LOG )
                Log.d(TAG, "recycle: " + entry.getKey());
            entry.getValue().recycle();
        }
        preloaded_bitmap_resources.clear();
        if( textToSpeech != null ) {
            // http://stackoverflow.com/questions/4242401/tts-error-leaked-serviceconnection-android-speech-tts-texttospeech-solved
            if( MyDebug.LOG )
                Log.d(TAG, "free textToSpeech");
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }

        super.onDestroy();
        if( MyDebug.LOG )
            Log.d(TAG, "onDestroy done");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    private void setFirstTimeFlag() {
        if( MyDebug.LOG )
            Log.d(TAG, "setFirstTimeFlag");
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PreferenceKeys.FirstTimePreferenceKey, true);
        editor.apply();
    }

    static String getOnlineHelpUrl(String append) {
        if( MyDebug.LOG )
            Log.d(TAG, "getOnlineHelpUrl: " + append);
        return "https://opencamera.sourceforge.io/"+ append;
    }

    void launchOnlineHelp() {
        if( MyDebug.LOG )
            Log.d(TAG, "launchOnlineHelp");
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getOnlineHelpUrl("")));
        startActivity(browserIntent);
    }

    /* Audio trigger - either loud sound, or speech recognition.
     * This performs some additional checks before taking a photo.
     */
    void audioTrigger() {
        if( MyDebug.LOG )
            Log.d(TAG, "ignore audio trigger due to popup open");
        if( popupIsOpen() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "ignore audio trigger due to popup open");
        }
        else if( camera_in_background ) {
            if( MyDebug.LOG )
                Log.d(TAG, "ignore audio trigger due to camera in background");
        }
        else if( preview.isTakingPhotoOrOnTimer() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "ignore audio trigger due to already taking photo or on timer");
        }
        else if( preview.isVideoRecording() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "ignore audio trigger due to already recording video");
        }
        else {
            if( MyDebug.LOG )
                Log.d(TAG, "schedule take picture due to loud noise");
            //takePicture();
            this.runOnUiThread(new Runnable() {
                public void run() {
                    if( MyDebug.LOG )
                        Log.d(TAG, "taking picture due to audio trigger");
                    takePicture(false);
                }
            });
        }
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if( MyDebug.LOG )
            Log.d(TAG, "onKeyDown: " + keyCode);
        boolean handled = mainUI.onKeyDown(keyCode, event);
        if( handled )
            return true;
        return super.onKeyDown(keyCode, event);
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if( MyDebug.LOG )
            Log.d(TAG, "onKeyUp: " + keyCode);
        mainUI.onKeyUp(keyCode, event);
        return super.onKeyUp(keyCode, event);
    }

    public void zoomIn() {
        mainUI.changeSeekbar(R.id.zoom_seekbar, -1);
    }

    public void zoomOut() {
        mainUI.changeSeekbar(R.id.zoom_seekbar, 1);
    }

    public void changeExposure(int change) {
        mainUI.changeSeekbar(R.id.exposure_seekbar, change);
    }

    public void changeISO(int change) {
        mainUI.changeSeekbar(R.id.iso_seekbar, change);
    }

    public void changeFocusDistance(int change, boolean is_target_distance) {
        mainUI.changeSeekbar(is_target_distance ? R.id.focus_bracketing_target_seekbar : R.id.focus_seekbar, change);
    }

    private final SensorEventListener accelerometerListener = new SensorEventListener() {
        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            preview.onAccelerometerSensorChanged(event);
        }
    };

    /* To support https://play.google.com/store/apps/details?id=com.miband2.mibandselfie .
     * Allows using the Mi Band 2 as a Bluetooth remote for Open Camera to take photos or start/stop
     * videos.
     */
    private final BroadcastReceiver cameraReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if( MyDebug.LOG )
                Log.d(TAG, "cameraReceiver.onReceive");
            MainActivity.this.takePicture(false);
        }
    };

    public float getWaterDensity() {
        return this.mWaterDensity;
    }

    @Override
    protected void onResume() {
        long debug_time = 0;
        if( MyDebug.LOG ) {
            Log.d(TAG, "onResume");
            debug_time = System.currentTimeMillis();
        }
        super.onResume();

        cancelImageSavingNotification();

        // Set black window background; also needed if we hide the virtual buttons in immersive mode
        // Note that we do it here rather than customising the theme's android:windowBackground, so this doesn't affect other views - in particular, the MyPreferenceFragment settings
        getWindow().getDecorView().getRootView().setBackgroundColor(Color.BLACK);

        mSensorManager.registerListener(accelerometerListener, mSensorAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        magneticSensor.registerMagneticListener(mSensorManager);
        orientationEventListener.enable();

        registerReceiver(cameraReceiver, new IntentFilter("com.miband2.action.CAMERA"));

        // if BLE remote control is enabled, then start the background BLE service
        bluetoothRemoteControl.startRemoteControl();

        speechControl.initSpeechRecognizer();
        initLocation();
        initGyroSensors();
        soundPoolManager.initSound();
        soundPoolManager.loadSound(R.raw.beep);
        soundPoolManager.loadSound(R.raw.beep_hi);

        mainUI.layoutUI();

        updateGalleryIcon(); // update in case images deleted whilst idle

        applicationInterface.reset(); // should be called before opening the camera in preview.onResume()

        preview.onResume();

        try {
            studioServer.start();
        } catch (IOException e) {
            e.printStackTrace();
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(studioCommandReceiver,
                new IntentFilter(STUDIO_BROADCAST_ID));

        if( MyDebug.LOG ) {
            Log.d(TAG, "onResume: total time to resume: " + (System.currentTimeMillis() - debug_time));
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if( MyDebug.LOG )
            Log.d(TAG, "onWindowFocusChanged: " + hasFocus);
        super.onWindowFocusChanged(hasFocus);
        if( !this.camera_in_background && hasFocus ) {
            // low profile mode is cleared when app goes into background
            // and for Kit Kat immersive mode, we want to set up the timer
            // we do in onWindowFocusChanged rather than onResume(), to also catch when window lost focus due to notification bar being dragged down (which prevents resetting of immersive mode)
            initImmersiveMode();
        }
    }

    @Override
    protected void onPause() {
        long debug_time = 0;
        if( MyDebug.LOG ) {
            Log.d(TAG, "onPause");
            debug_time = System.currentTimeMillis();
        }
        super.onPause(); // docs say to call this before freeing other things
        mainUI.destroyPopup(); // important as user could change/reset settings from Android settings when pausing
        mSensorManager.unregisterListener(accelerometerListener);
        magneticSensor.unregisterMagneticListener(mSensorManager);
        orientationEventListener.disable();
        try {
            unregisterReceiver(cameraReceiver);
        }
        catch(IllegalArgumentException e) {
            // this can happen if not registered - simplest to just catch the exception
            e.printStackTrace();
        }
        bluetoothRemoteControl.stopRemoteControl();
        freeAudioListener(false);
        speechControl.stopSpeechRecognizer();
        applicationInterface.getLocationSupplier().freeLocationListeners();
        applicationInterface.stopPanorama(true); // in practice not needed as we should stop panorama when camera is closed, but good to do it explicitly here, before disabling the gyro sensors
        applicationInterface.getGyroSensor().disableSensors();
        soundPoolManager.releaseSound();
        applicationInterface.clearLastImages(); // this should happen when pausing the preview, but call explicitly just to be safe
        applicationInterface.getDrawPreview().clearGhostImage();
        preview.onPause();

        if( applicationInterface.getImageSaver().getNImagesToSave() > 0) {
            createImageSavingNotification();
        }

        studioServer.stop();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(studioCommandReceiver);

        if( MyDebug.LOG ) {
            Log.d(TAG, "onPause: total time to pause: " + (System.currentTimeMillis() - debug_time));
        }
    }

    private BroadcastReceiver studioCommandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String data = intent.getStringExtra("data");
            try {
                JSONObject obj = new JSONObject(data);
                String type = obj.getString("type");
                JSONObject opt = obj.optJSONObject("opt");
                if (type.equals("start")) {
                    String name = opt.getString("name");
                    String suffix = "_"+name.replace(" ", "_");

                    if (!preview.isVideoRecording()) {
                        preview.setCurrentSuffix(suffix);
                        applicationInterface.getDrawPreview().setCurrentSuffix(suffix);
                        takePicture(false);
                    }
                }else if (type.equals("stop")) {
                    if (preview.isVideoRecording()) {
                        takePicture(false);
                    }
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if( MyDebug.LOG )
            Log.d(TAG, "onConfigurationChanged()");
        // configuration change can include screen orientation (landscape/portrait) when not locked (when settings is open)
        // needed if app is paused/resumed when settings is open and device is in portrait mode
        preview.setCameraDisplayOrientation();
        super.onConfigurationChanged(newConfig);
    }

    public void waitUntilImageQueueEmpty() {
        if( MyDebug.LOG )
            Log.d(TAG, "waitUntilImageQueueEmpty");
        applicationInterface.getImageSaver().waitUntilDone();
    }

    private boolean longClickedTakePhoto() {
        if( MyDebug.LOG )
            Log.d(TAG, "longClickedTakePhoto");
        // need to check whether fast burst is supported (including for the current resolution),
        // in case we're in Standard photo mode
        if( supportsFastBurst() ) {
            CameraController.Size current_size = preview.getCurrentPictureSize();
            if( current_size != null && current_size.supports_burst ) {
                MyApplicationInterface.PhotoMode photo_mode = applicationInterface.getPhotoMode();
                if( photo_mode == MyApplicationInterface.PhotoMode.Standard &&
                        applicationInterface.isRawOnly(photo_mode) ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "fast burst not supported in RAW-only mode");
                    // in JPEG+RAW mode, a continuous fast burst will only produce JPEGs which is fine; but in RAW only mode,
                    // no images at all would be saved! (Or we could switch to produce JPEGs anyway, but this seems misleading
                    // in RAW only mode.)
                }
                else if( photo_mode == MyApplicationInterface.PhotoMode.Standard ||
                        photo_mode == MyApplicationInterface.PhotoMode.FastBurst ) {
                    this.takePicturePressed(false, true);
                    return true;
                }
            }
            else {
                if( MyDebug.LOG )
                    Log.d(TAG, "fast burst not supported for this resolution");
            }
        }
        else {
            if( MyDebug.LOG )
                Log.d(TAG, "fast burst not supported");
        }
        // return false, so a regular click will still be triggered when the user releases the touch
        return false;
    }

    public void clickedTakePhoto(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedTakePhoto");
        this.takePicture(false);
    }

    /** User has clicked button to take a photo snapshot whilst video recording.
     */
    public void clickedTakePhotoVideoSnapshot(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedTakePhotoVideoSnapshot");
        this.takePicture(true);
    }

    public void clickedPauseVideo(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedPauseVideo");
        if( preview.isVideoRecording() ) { // just in case
            preview.pauseVideo();
            mainUI.setPauseVideoContentDescription();
        }
    }

    public void clickedCancelPanorama(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedCancelPanorama");
        applicationInterface.stopPanorama(true);
    }

    public void clickedCycleRaw(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedCycleRaw");

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String new_value = null;
        switch( sharedPreferences.getString(PreferenceKeys.RawPreferenceKey, "preference_raw_no") ) {
            case "preference_raw_no":
                new_value = "preference_raw_yes";
                break;
            case "preference_raw_yes":
                new_value = "preference_raw_only";
                break;
            case "preference_raw_only":
                new_value = "preference_raw_no";
                break;
            default:
                Log.e(TAG, "unrecognised raw preference");
                break;
        }
        if( new_value != null ) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(PreferenceKeys.RawPreferenceKey, new_value);
            editor.apply();

            mainUI.updateCycleRawIcon();
            applicationInterface.getDrawPreview().updateSettings();
            preview.reopenCamera(); // needed for RAW options to take effect
        }
    }

    public void clickedStoreLocation(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedStoreLocation");
        boolean value = applicationInterface.getGeotaggingPref();
        value = !value;

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PreferenceKeys.LocationPreferenceKey, value);
        editor.apply();

        mainUI.updateStoreLocationIcon();
        applicationInterface.getDrawPreview().updateSettings(); // because we cache the geotagging setting
        initLocation(); // required to enable or disable GPS, also requests permission if necessary
        this.closePopup();
    }

    public void clickedTextStamp(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedTextStamp");
        this.closePopup();

        AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);
        alertDialog.setTitle(R.string.preference_textstamp);

        final EditText editText = new EditText(this);
        editText.setText(applicationInterface.getTextStampPref());
        alertDialog.setView(editText);
        alertDialog.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                if( MyDebug.LOG )
                    Log.d(TAG, "custom text stamp clicked okay");

                String custom_text = editText.getText().toString();
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(PreferenceKeys.TextStampPreferenceKey, custom_text);
                editor.apply();

                mainUI.updateTextStampIcon();
            }
        });
        alertDialog.setNegativeButton(android.R.string.cancel, null);

        final AlertDialog alert = alertDialog.create();
        alert.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface arg0) {
                if( MyDebug.LOG )
                    Log.d(TAG, "custom stamp text dialog dismissed");
                setWindowFlagsForCamera();
                showPreview(true);
            }
        });

        showPreview(false);
        setWindowFlagsForSettings();
        showAlert(alert);
    }

    public void clickedStamp(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedStamp");

        this.closePopup();

        boolean value = applicationInterface.getStampPref().equals("preference_stamp_yes");
        value = !value;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PreferenceKeys.StampPreferenceKey, value ? "preference_stamp_yes" : "preference_stamp_no");
        editor.apply();

        mainUI.updateStampIcon();
        applicationInterface.getDrawPreview().updateSettings();
        preview.showToast(stamp_toast, value ? R.string.stamp_enabled : R.string.stamp_disabled);
    }

    public void clickedAutoLevel(View view) {
        clickedAutoLevel();
    }

    public void clickedAutoLevel() {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedAutoLevel");
        boolean value = applicationInterface.getAutoStabilisePref();
        value = !value;

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PreferenceKeys.AutoStabilisePreferenceKey, value);
        editor.apply();

        boolean done_dialog = false;
        if( value ) {
            boolean done_auto_stabilise_info = sharedPreferences.contains(PreferenceKeys.AutoStabiliseInfoPreferenceKey);
            if( !done_auto_stabilise_info ) {
                mainUI.showInfoDialog(R.string.preference_auto_stabilise, R.string.auto_stabilise_info, PreferenceKeys.AutoStabiliseInfoPreferenceKey);
                done_dialog = true;
            }
        }

        if( !done_dialog ) {
            String message = getResources().getString(R.string.preference_auto_stabilise) + ": " + getResources().getString(value ? R.string.on : R.string.off);
            preview.showToast(this.getChangedAutoStabiliseToastBoxer(), message);
        }

        mainUI.updateAutoLevelIcon();
        applicationInterface.getDrawPreview().updateSettings(); // because we cache the auto-stabilise setting
        this.closePopup();
    }

    public void clickedCycleFlash(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedCycleFlash");

        preview.cycleFlash(true, true);
        mainUI.updateCycleFlashIcon();
    }

    public void clickedFaceDetection(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedFaceDetection");

        this.closePopup();

        boolean value = applicationInterface.getFaceDetectionPref();
        value = !value;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PreferenceKeys.FaceDetectionPreferenceKey, value);
        editor.apply();

        mainUI.updateFaceDetectionIcon();
        preview.showToast(stamp_toast, value ? R.string.face_detection_enabled : R.string.face_detection_disabled);
        block_startup_toast = true; // so the toast from reopening camera is suppressed, otherwise it conflicts with the face detection toast
        preview.reopenCamera();
    }

    public void clickedAudioControl(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedAudioControl");
        // check hasAudioControl just in case!
        if( !hasAudioControl() ) {
            if( MyDebug.LOG )
                Log.e(TAG, "clickedAudioControl, but hasAudioControl returns false!");
            return;
        }
        this.closePopup();
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String audio_control = sharedPreferences.getString(PreferenceKeys.AudioControlPreferenceKey, "none");
        if( audio_control.equals("voice") && speechControl.hasSpeechRecognition() ) {
            if( speechControl.isStarted() ) {
                speechControl.stopListening();
            }
            else {
                boolean has_audio_permission = true;
                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
                    // we restrict the checks to Android 6 or later just in case, see note in LocationSupplier.setupLocationListener()
                    if( MyDebug.LOG )
                        Log.d(TAG, "check for record audio permission");
                    if( ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "record audio permission not available");
                        applicationInterface.requestRecordAudioPermission();
                        has_audio_permission = false;
                    }
                }
                if( has_audio_permission ) {
                    preview.showToast(audio_control_toast, R.string.speech_recognizer_started);
                    speechControl.startSpeechRecognizerIntent();
                    speechControl.speechRecognizerStarted();
                }
            }
        }
        else if( audio_control.equals("noise") ){
            if( audio_listener != null ) {
                freeAudioListener(false);
            }
            else {
                startAudioListener();
            }
        }
    }

    /* Returns the cameraId that the "Switch camera" button will switch to.
     */
    public int getNextCameraId() {
        if( MyDebug.LOG )
            Log.d(TAG, "getNextCameraId");
        int cameraId = preview.getCameraId();
        if( MyDebug.LOG )
            Log.d(TAG, "current cameraId: " + cameraId);
        if( this.preview.canSwitchCamera() ) {
            int n_cameras = preview.getCameraControllerManager().getNumberOfCameras();
            cameraId = (cameraId+1) % n_cameras;
        }
        if( MyDebug.LOG )
            Log.d(TAG, "next cameraId: " + cameraId);
        return cameraId;
    }

    /**
     * Selects the next camera on the phone - in practice, switches between
     * front and back cameras
     * @param view
     */
    public void clickedSwitchCamera(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedSwitchCamera");
        if( preview.isOpeningCamera() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "already opening camera in background thread");
            return;
        }
        this.closePopup();
        if( this.preview.canSwitchCamera() ) {
            int cameraId = getNextCameraId();
            if( preview.getCameraControllerManager().getNumberOfCameras() > 2 ) {
                // telling the user which camera is pointless for only two cameras, but on devices that now
                // expose many cameras it can be confusing, so show a toast to at least display the id
                String toast_string = getResources().getString(
                        preview.getCameraControllerManager().isFrontFacing(cameraId) ? R.string.front_camera : R.string.back_camera ) +
                        " : " + getResources().getString(R.string.camera_id) + " " + cameraId;
                preview.showToast(null, toast_string);
            }

            View switchCameraButton = findViewById(R.id.switch_camera);
            switchCameraButton.setEnabled(false); // prevent slowdown if user repeatedly clicks
            applicationInterface.reset();
            this.preview.setCamera(cameraId);
            switchCameraButton.setEnabled(true);
            // no need to call mainUI.setSwitchCameraContentDescription - this will be called from PreviewcameraSetup when the
            // new camera is opened
        }
    }

    /**
     * Toggles Photo/Video mode
     * @param view
     */
    public void clickedSwitchVideo(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedSwitchVideo");
        this.closePopup();
        mainUI.destroyPopup(); // important as we don't want to use a cached popup, as we can show different options depending on whether we're in photo or video mode

        // In practice stopping the gyro sensor shouldn't be needed as (a) we don't show the switch
        // photo/video icon when recording, (b) at the time of writing switching to video mode
        // reopens the camera, which will stop panorama recording anyway, but we do this just to be
        // safe.
        applicationInterface.stopPanorama(true);

        View switchVideoButton = findViewById(R.id.switch_video);
        switchVideoButton.setEnabled(false); // prevent slowdown if user repeatedly clicks
        applicationInterface.reset();
        this.preview.switchVideo(false, true);
        switchVideoButton.setEnabled(true);

        mainUI.setTakePhotoIcon();
        mainUI.setPopupIcon(); // needed as turning to video mode or back can turn flash mode off or back on

        // ensure icons invisible if they're affected by being in video mode or not
        // (if enabling them, we'll make the icon visible later on)
        checkDisableGUIIcons();

        if( !block_startup_toast ) {
            this.showPhotoVideoToast(true);
        }
    }

    public void clickedWhiteBalanceLock(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedWhiteBalanceLock");
        this.preview.toggleWhiteBalanceLock();
        mainUI.updateWhiteBalanceLockIcon();
        preview.showToast(white_balance_lock_toast, preview.isWhiteBalanceLocked() ? R.string.white_balance_locked : R.string.white_balance_unlocked);
    }

    public void clickedExposureLock(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedExposureLock");
        this.preview.toggleExposureLock();
        mainUI.updateExposureLockIcon();
        preview.showToast(exposure_lock_toast, preview.isExposureLocked() ? R.string.exposure_locked : R.string.exposure_unlocked);
    }

    public void clickedExposure(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedExposure");
        mainUI.toggleExposureUI();
    }

    public void clickedSettings(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedSettings");
        openSettings();
    }

    public boolean popupIsOpen() {
        return mainUI.popupIsOpen();
    }

    // for testing
    public View getUIButton(String key) {
        return mainUI.getUIButton(key);
    }

    public void closePopup() {
        mainUI.closePopup();
    }

    public Bitmap getPreloadedBitmap(int resource) {
        return this.preloaded_bitmap_resources.get(resource);
    }

    public void clickedPopupSettings(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedPopupSettings");
        mainUI.togglePopupSettings();
    }

    private final PreferencesListener preferencesListener = new PreferencesListener();

    /** Keeps track of changes to SharedPreferences.
     */
    class PreferencesListener implements SharedPreferences.OnSharedPreferenceChangeListener {
        private static final String TAG = "PreferencesListener";

        private boolean any_significant_change; // whether any changes that require updateForSettings have been made since startListening()
        private boolean any_change; // whether any changes that require updateForSettings have been made since startListening()

        void startListening() {
            if( MyDebug.LOG )
                Log.d(TAG, "startListening");
            any_significant_change = false;
            any_change = false;

            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
            // n.b., registerOnSharedPreferenceChangeListener warns that we must keep a reference to the listener (which
            // is this class) as long as we want to listen for changes, otherwise the listener may be garbage collected!
            sharedPreferences.registerOnSharedPreferenceChangeListener(this);
        }

        void stopListening() {
            if( MyDebug.LOG )
                Log.d(TAG, "stopListening");
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if( MyDebug.LOG )
                Log.d(TAG, "onSharedPreferenceChanged: " + key);

            any_change = true;

            switch( key ) {
                // we whitelist preferences where we're sure that we don't need to call updateForSettings() if they've changed
                case "preference_timer":
                case "preference_burst_mode":
                case "preference_burst_interval":
                    //case "preference_ghost_image": // don't whitelist this, as may need to reload ghost image (at fullscreen resolution) if "last" is enabled
                case "preference_touch_capture":
                case "preference_pause_preview":
                case "preference_shutter_sound":
                case "preference_timer_beep":
                case "preference_timer_speak":
                case "preference_volume_keys":
                case "preference_audio_noise_control_sensitivity":
                case "preference_using_saf":
                case "preference_save_photo_prefix":
                case "preference_save_video_prefix":
                case "preference_save_zulu_time":
                case "preference_show_when_locked":
                case "preference_startup_focus":
                case "preference_show_zoom":
                case "preference_show_angle":
                case "preference_show_angle_line":
                case "preference_show_pitch_lines":
                case "preference_angle_highlight_color":
                    //case "preference_show_geo_direction": // don't whitelist these, as if enabled we need to call checkMagneticAccuracy()
                    //case "preference_show_geo_direction_lines": // as above
                case "preference_show_battery":
                case "preference_show_time":
                case "preference_free_memory":
                case "preference_show_iso":
                case "preference_grid":
                case "preference_crop_guide":
                case "preference_show_toasts":
                case "preference_thumbnail_animation":
                case "preference_take_photo_border":
                case "preference_keep_display_on":
                case "preference_max_brightness":
                    //case "preference_hdr_save_expo": // we need to update if this is changed, as it affects whether we request RAW or not in HDR mode when RAW is enabled
                case "preference_front_camera_mirror":
                case "preference_stamp":
                case "preference_stamp_dateformat":
                case "preference_stamp_timeformat":
                case "preference_stamp_gpsformat":
                case "preference_textstamp":
                case "preference_stamp_fontsize":
                case "preference_stamp_font_color":
                case "preference_stamp_style":
                case "preference_background_photo_saving":
                case "preference_record_audio":
                case "preference_record_audio_src":
                case "preference_record_audio_channels":
                case "preference_lock_video":
                case "preference_video_subtitle":
                case "preference_require_location":
                    if( MyDebug.LOG )
                        Log.d(TAG, "this change doesn't require update");
                    break;
                case PreferenceKeys.EnableRemote:
                    bluetoothRemoteControl.startRemoteControl();
                    break;
                case PreferenceKeys.RemoteName:
                    // The remote address changed, restart the service
                    if (bluetoothRemoteControl.remoteEnabled())
                        bluetoothRemoteControl.stopRemoteControl();
                    bluetoothRemoteControl.startRemoteControl();
                    break;
                case PreferenceKeys.WaterType:
                    boolean wt = sharedPreferences.getBoolean(PreferenceKeys.WaterType, true);
                    mWaterDensity = wt ? WATER_DENSITY_SALTWATER : WATER_DENSITY_FRESHWATER;
                    break;
                default:
                    if( MyDebug.LOG )
                        Log.d(TAG, "this change does require update");
                    any_significant_change = true;
                    break;
            }
        }

        boolean anyChange() {
            return any_change;
        }

        boolean anySignificantChange() {
            return any_significant_change;
        }
    }

    public void openSettings() {
        if( MyDebug.LOG )
            Log.d(TAG, "openSettings");
        closePopup();
        preview.cancelTimer(); // best to cancel any timer, in case we take a photo while settings window is open, or when changing settings
        preview.cancelRepeat(); // similarly cancel the auto-repeat mode!
        preview.stopVideo(false); // important to stop video, as we'll be changing camera parameters when the settings window closes
        applicationInterface.stopPanorama(true); // important to stop panorama recording, as we might end up as we'll be changing camera parameters when the settings window closes
        stopAudioListeners();

        Bundle bundle = new Bundle();
        bundle.putInt("cameraId", this.preview.getCameraId());
        bundle.putInt("nCameras", preview.getCameraControllerManager().getNumberOfCameras());
        bundle.putString("camera_api", this.preview.getCameraAPI());
        bundle.putBoolean("using_android_l", this.preview.usingCamera2API());
        bundle.putBoolean("supports_auto_stabilise", this.supports_auto_stabilise);
        bundle.putBoolean("supports_flash", this.preview.supportsFlash());
        bundle.putBoolean("supports_force_video_4k", this.supports_force_video_4k);
        bundle.putBoolean("supports_camera2", this.supports_camera2);
        bundle.putBoolean("supports_face_detection", this.preview.supportsFaceDetection());
        bundle.putBoolean("supports_raw", this.preview.supportsRaw());
        bundle.putBoolean("supports_burst_raw", this.supportsBurstRaw());
        bundle.putBoolean("supports_hdr", this.supportsHDR());
        bundle.putBoolean("supports_nr", this.supportsNoiseReduction());
        bundle.putBoolean("supports_panorama", this.supportsPanorama());
        bundle.putBoolean("supports_expo_bracketing", this.supportsExpoBracketing());
        bundle.putBoolean("supports_preview_bitmaps", this.supportsPreviewBitmaps());
        bundle.putInt("max_expo_bracketing_n_images", this.maxExpoBracketingNImages());
        bundle.putBoolean("supports_exposure_compensation", this.preview.supportsExposures());
        bundle.putInt("exposure_compensation_min", this.preview.getMinimumExposure());
        bundle.putInt("exposure_compensation_max", this.preview.getMaximumExposure());
        bundle.putBoolean("supports_iso_range", this.preview.supportsISORange());
        bundle.putInt("iso_range_min", this.preview.getMinimumISO());
        bundle.putInt("iso_range_max", this.preview.getMaximumISO());
        bundle.putBoolean("supports_exposure_time", this.preview.supportsExposureTime());
        bundle.putBoolean("supports_exposure_lock", this.preview.supportsExposureLock());
        bundle.putBoolean("supports_white_balance_lock", this.preview.supportsWhiteBalanceLock());
        bundle.putLong("exposure_time_min", this.preview.getMinimumExposureTime());
        bundle.putLong("exposure_time_max", this.preview.getMaximumExposureTime());
        bundle.putBoolean("supports_white_balance_temperature", this.preview.supportsWhiteBalanceTemperature());
        bundle.putInt("white_balance_temperature_min", this.preview.getMinimumWhiteBalanceTemperature());
        bundle.putInt("white_balance_temperature_max", this.preview.getMaximumWhiteBalanceTemperature());
        bundle.putBoolean("supports_video_stabilization", this.preview.supportsVideoStabilization());
        bundle.putBoolean("can_disable_shutter_sound", this.preview.canDisableShutterSound());
        bundle.putInt("tonemap_max_curve_points", this.preview.getTonemapMaxCurvePoints());
        bundle.putBoolean("supports_tonemap_curve", this.preview.supportsTonemapCurve());
        bundle.putBoolean("supports_photo_video_recording", this.preview.supportsPhotoVideoRecording());
        bundle.putFloat("camera_view_angle_x", preview.getViewAngleX(false));
        bundle.putFloat("camera_view_angle_y", preview.getViewAngleY(false));

        putBundleExtra(bundle, "color_effects", this.preview.getSupportedColorEffects());
        putBundleExtra(bundle, "scene_modes", this.preview.getSupportedSceneModes());
        putBundleExtra(bundle, "white_balances", this.preview.getSupportedWhiteBalances());
        putBundleExtra(bundle, "isos", this.preview.getSupportedISOs());
        bundle.putInt("magnetic_accuracy", magneticSensor.getMagneticAccuracy());
        bundle.putString("iso_key", this.preview.getISOKey());
        if( this.preview.getCameraController() != null ) {
            bundle.putString("parameters_string", preview.getCameraController().getParametersString());
        }
        List<String> antibanding = this.preview.getSupportedAntiBanding();
        putBundleExtra(bundle, "antibanding", antibanding);
        if( antibanding != null ) {
            String [] entries_arr = new String[antibanding.size()];
            int i=0;
            for(String value: antibanding) {
                entries_arr[i] = getMainUI().getEntryForAntiBanding(value);
                i++;
            }
            bundle.putStringArray("antibanding_entries", entries_arr);
        }
        List<String> edge_modes = this.preview.getSupportedEdgeModes();
        putBundleExtra(bundle, "edge_modes", edge_modes);
        if( edge_modes != null ) {
            String [] entries_arr = new String[edge_modes.size()];
            int i=0;
            for(String value: edge_modes) {
                entries_arr[i] = getMainUI().getEntryForNoiseReductionMode(value);
                i++;
            }
            bundle.putStringArray("edge_modes_entries", entries_arr);
        }
        List<String> noise_reduction_modes = this.preview.getSupportedNoiseReductionModes();
        putBundleExtra(bundle, "noise_reduction_modes", noise_reduction_modes);
        if( noise_reduction_modes != null ) {
            String [] entries_arr = new String[noise_reduction_modes.size()];
            int i=0;
            for(String value: noise_reduction_modes) {
                entries_arr[i] = getMainUI().getEntryForNoiseReductionMode(value);
                i++;
            }
            bundle.putStringArray("noise_reduction_modes_entries", entries_arr);
        }

        List<CameraController.Size> preview_sizes = this.preview.getSupportedPreviewSizes();
        if( preview_sizes != null ) {
            int [] widths = new int[preview_sizes.size()];
            int [] heights = new int[preview_sizes.size()];
            int i=0;
            for(CameraController.Size size: preview_sizes) {
                widths[i] = size.width;
                heights[i] = size.height;
                i++;
            }
            bundle.putIntArray("preview_widths", widths);
            bundle.putIntArray("preview_heights", heights);
        }
        bundle.putInt("preview_width", preview.getCurrentPreviewSize().width);
        bundle.putInt("preview_height", preview.getCurrentPreviewSize().height);

        // Note that we set check_burst to false, as the Settings always displays all supported resolutions (along with the "saved"
        // resolution preference, even if that doesn't support burst and we're in a burst mode).
        // This is to be consistent with other preferences, e.g., we still show RAW settings even though that might not be supported
        // for the current photo mode.
        List<CameraController.Size> sizes = this.preview.getSupportedPictureSizes(false);
        if( sizes != null ) {
            int [] widths = new int[sizes.size()];
            int [] heights = new int[sizes.size()];
            boolean [] supports_burst = new boolean[sizes.size()];
            int i=0;
            for(CameraController.Size size: sizes) {
                widths[i] = size.width;
                heights[i] = size.height;
                supports_burst[i] = size.supports_burst;
                i++;
            }
            bundle.putIntArray("resolution_widths", widths);
            bundle.putIntArray("resolution_heights", heights);
            bundle.putBooleanArray("resolution_supports_burst", supports_burst);
        }
        if( preview.getCurrentPictureSize() != null ) {
            bundle.putInt("resolution_width", preview.getCurrentPictureSize().width);
            bundle.putInt("resolution_height", preview.getCurrentPictureSize().height);
        }

        //List<String> video_quality = this.preview.getVideoQualityHander().getSupportedVideoQuality();
        String fps_value = applicationInterface.getVideoFPSPref(); // n.b., this takes into account slow motion mode putting us into a high frame rate
        if( MyDebug.LOG )
            Log.d(TAG, "fps_value: " + fps_value);
        List<String> video_quality = this.preview.getSupportedVideoQuality(fps_value);
        if( video_quality == null || video_quality.size() == 0 ) {
            Log.e(TAG, "can't find any supported video sizes for current fps!");
            // fall back to unfiltered list
            video_quality = this.preview.getVideoQualityHander().getSupportedVideoQuality();
        }
        if( video_quality != null && this.preview.getCameraController() != null ) {
            String [] video_quality_arr = new String[video_quality.size()];
            String [] video_quality_string_arr = new String[video_quality.size()];
            int i=0;
            for(String value: video_quality) {
                video_quality_arr[i] = value;
                video_quality_string_arr[i] = this.preview.getCamcorderProfileDescription(value);
                i++;
            }
            bundle.putStringArray("video_quality", video_quality_arr);
            bundle.putStringArray("video_quality_string", video_quality_string_arr);

            boolean is_high_speed = this.preview.fpsIsHighSpeed(fps_value);
            bundle.putBoolean("video_is_high_speed", is_high_speed);
            String video_quality_preference_key = PreferenceKeys.getVideoQualityPreferenceKey(this.preview.getCameraId(), is_high_speed);
            if( MyDebug.LOG )
                Log.d(TAG, "video_quality_preference_key: " + video_quality_preference_key);
            bundle.putString("video_quality_preference_key", video_quality_preference_key);
        }

        if( preview.getVideoQualityHander().getCurrentVideoQuality() != null ) {
            bundle.putString("current_video_quality", preview.getVideoQualityHander().getCurrentVideoQuality());
        }
        VideoProfile camcorder_profile = preview.getVideoProfile();
        bundle.putInt("video_frame_width", camcorder_profile.videoFrameWidth);
        bundle.putInt("video_frame_height", camcorder_profile.videoFrameHeight);
        bundle.putInt("video_bit_rate", camcorder_profile.videoBitRate);
        bundle.putInt("video_frame_rate", camcorder_profile.videoFrameRate);
        bundle.putDouble("video_capture_rate", camcorder_profile.videoCaptureRate);
        bundle.putBoolean("video_high_speed", preview.isVideoHighSpeed());
        bundle.putFloat("video_capture_rate_factor", applicationInterface.getVideoCaptureRateFactor());

        List<CameraController.Size> video_sizes = this.preview.getVideoQualityHander().getSupportedVideoSizes();
        if( video_sizes != null ) {
            int [] widths = new int[video_sizes.size()];
            int [] heights = new int[video_sizes.size()];
            int i=0;
            for(CameraController.Size size: video_sizes) {
                widths[i] = size.width;
                heights[i] = size.height;
                i++;
            }
            bundle.putIntArray("video_widths", widths);
            bundle.putIntArray("video_heights", heights);
        }

        // set up supported fps values
        if( preview.usingCamera2API() ) {
            // with Camera2, we know what frame rates are supported
            int [] candidate_fps = {15, 24, 25, 30, 60, 96, 100, 120, 240};
            List<Integer> video_fps = new ArrayList<>();
            List<Boolean> video_fps_high_speed = new ArrayList<>();
            for(int fps : candidate_fps) {
                if( preview.fpsIsHighSpeed("" + fps) ) {
                    video_fps.add(fps);
                    video_fps_high_speed.add(true);
                }
                else if( this.preview.getVideoQualityHander().videoSupportsFrameRate(fps) ) {
                    video_fps.add(fps);
                    video_fps_high_speed.add(false);
                }
            }
            int [] video_fps_array = new int[video_fps.size()];
            for(int i=0;i<video_fps.size();i++) {
                video_fps_array[i] = video_fps.get(i);
            }
            bundle.putIntArray("video_fps", video_fps_array);
            boolean [] video_fps_high_speed_array = new boolean[video_fps_high_speed.size()];
            for(int i=0;i<video_fps_high_speed.size();i++) {
                video_fps_high_speed_array[i] = video_fps_high_speed.get(i);
            }
            bundle.putBooleanArray("video_fps_high_speed", video_fps_high_speed_array);
        }
        else {
            // with old API, we don't know what frame rates are supported, so we make it up and let the user try
            // probably shouldn't allow 120fps, but we did in the past, and there may be some devices where this did work?
            int [] video_fps = {15, 24, 25, 30, 60, 96, 100, 120};
            bundle.putIntArray("video_fps", video_fps);
            boolean [] video_fps_high_speed_array = new boolean[video_fps.length];
            for(int i=0;i<video_fps.length;i++) {
                video_fps_high_speed_array[i] = false; // no concept of high speed frame rates in old API
            }
            bundle.putBooleanArray("video_fps_high_speed", video_fps_high_speed_array);
        }

        putBundleExtra(bundle, "flash_values", this.preview.getSupportedFlashValues());
        putBundleExtra(bundle, "focus_values", this.preview.getSupportedFocusValues());

        preferencesListener.startListening();

        showPreview(false);
        setWindowFlagsForSettings();
        MyPreferenceFragment fragment = new MyPreferenceFragment();
        fragment.setArguments(bundle);
        // use commitAllowingStateLoss() instead of commit(), does to "java.lang.IllegalStateException: Can not perform this action after onSaveInstanceState" crash seen on Google Play
        // see http://stackoverflow.com/questions/7575921/illegalstateexception-can-not-perform-this-action-after-onsaveinstancestate-wit
        getFragmentManager().beginTransaction().add(android.R.id.content, fragment, "PREFERENCE_FRAGMENT").addToBackStack(null).commitAllowingStateLoss();
    }

    public void updateForSettings() {
        updateForSettings(null, false);
    }

    public void updateForSettings(String toast_message) {
        updateForSettings(toast_message, false);
    }

    /** Must be called when an settings (as stored in SharedPreferences) are made, so we can update the
     *  camera, and make any other necessary changes.
     * @param keep_popup If false, the popup will be closed and destroyed. Set to true if you're sure
     *                   that the changed setting isn't one that requires the PopupView to be recreated
     */
    public void updateForSettings(String toast_message, boolean keep_popup) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "updateForSettings()");
            if( toast_message != null ) {
                Log.d(TAG, "toast_message: " + toast_message);
            }
        }
        long debug_time = 0;
        if( MyDebug.LOG ) {
            debug_time = System.currentTimeMillis();
        }
        // make sure we're into continuous video mode
        // workaround for bug on Samsung Galaxy S5 with UHD, where if the user switches to another (non-continuous-video) focus mode, then goes to Settings, then returns and records video, the preview freezes and the video is corrupted
        // so to be safe, we always reset to continuous video mode, and then reset it afterwards
    	/*String saved_focus_value = preview.updateFocusForVideo(); // n.b., may be null if focus mode not changed
		if( MyDebug.LOG )
			Log.d(TAG, "saved_focus_value: " + saved_focus_value);*/

        if( MyDebug.LOG )
            Log.d(TAG, "update folder history");
        save_location_history.updateFolderHistory(getStorageUtils().getSaveLocation(), true); // this also updates the last icon for ghost image, if that pref has changed
        // no need to update save_location_history_saf, as we always do this in onActivityResult()
        if( MyDebug.LOG ) {
            Log.d(TAG, "updateForSettings: time after update folder history: " + (System.currentTimeMillis() - debug_time));
        }

        imageQueueChanged(); // needed at least for changing photo mode, but might as well call it always

        if( !keep_popup ) {
            mainUI.destroyPopup(); // important as we don't want to use a cached popup
            if( MyDebug.LOG ) {
                Log.d(TAG, "updateForSettings: time after destroy popup: " + (System.currentTimeMillis() - debug_time));
            }
        }

        // update camera for changes made in prefs - do this without closing and reopening the camera app if possible for speed!
        // but need workaround for Nexus 7 bug on old camera API, where scene mode doesn't take effect unless the camera is restarted - I can reproduce this with other 3rd party camera apps, so may be a Nexus 7 issue...
        // doesn't happen if we allow using Camera2 API on Nexus 7, but reopen for consistency (and changing scene modes via
        // popup menu no longer should be calling updateForSettings() for Camera2, anyway)
        boolean need_reopen = false;
        if( preview.getCameraController() != null ) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
            String scene_mode = preview.getCameraController().getSceneMode();
            if( MyDebug.LOG )
                Log.d(TAG, "scene mode was: " + scene_mode);
            String key = PreferenceKeys.SceneModePreferenceKey;
            String value = sharedPreferences.getString(key, CameraController.SCENE_MODE_DEFAULT);
            if( !value.equals(scene_mode) ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "scene mode changed to: " + value);
                need_reopen = true;
            }
            else {
                if( applicationInterface.useCamera2() ) {
                    // need to reopen if fake flash mode changed, as it changes the available camera features, and we can only set this after opening the camera
                    boolean camera2_fake_flash = preview.getCameraController().getUseCamera2FakeFlash();
                    if( MyDebug.LOG )
                        Log.d(TAG, "camera2_fake_flash was: " + camera2_fake_flash);
                    if( applicationInterface.useCamera2FakeFlash() != camera2_fake_flash ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "camera2_fake_flash changed");
                        need_reopen = true;
                    }
                }
            }
        }
        if( MyDebug.LOG ) {
            Log.d(TAG, "updateForSettings: time after check need_reopen: " + (System.currentTimeMillis() - debug_time));
        }

        mainUI.layoutUI(); // needed in case we've changed UI placement; or in "top" mode, if we've enabled/disabled on-screen UI icons
        if( MyDebug.LOG ) {
            Log.d(TAG, "updateForSettings: time after layoutUI: " + (System.currentTimeMillis() - debug_time));
        }

        // ensure icons invisible if disabling them from showing from the Settings
        // (if enabling them, we'll make the icon visible later on)
        checkDisableGUIIcons();

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        if( sharedPreferences.getString(PreferenceKeys.AudioControlPreferenceKey, "none").equals("none") ) {
            View speechRecognizerButton = findViewById(R.id.audio_control);
            speechRecognizerButton.setVisibility(View.GONE);
        }

        speechControl.initSpeechRecognizer(); // in case we've enabled or disabled speech recognizer
        initLocation(); // in case we've enabled or disabled GPS
        initGyroSensors(); // in case we've entered or left panoram
        if( MyDebug.LOG ) {
            Log.d(TAG, "updateForSettings: time after init speech and location: " + (System.currentTimeMillis() - debug_time));
        }
        if( toast_message != null )
            block_startup_toast = true;
        if( need_reopen || preview.getCameraController() == null ) { // if camera couldn't be opened before, might as well try again
            preview.reopenCamera();
            if( MyDebug.LOG ) {
                Log.d(TAG, "updateForSettings: time after reopen: " + (System.currentTimeMillis() - debug_time));
            }
        }
        else {
            preview.setCameraDisplayOrientation(); // need to call in case the preview rotation option was changed
            if( MyDebug.LOG ) {
                Log.d(TAG, "updateForSettings: time after set display orientation: " + (System.currentTimeMillis() - debug_time));
            }
            preview.pausePreview(true);
            if( MyDebug.LOG ) {
                Log.d(TAG, "updateForSettings: time after pause: " + (System.currentTimeMillis() - debug_time));
            }
            preview.setupCamera(false);
            if( MyDebug.LOG ) {
                Log.d(TAG, "updateForSettings: time after setup: " + (System.currentTimeMillis() - debug_time));
            }
        }
        // don't set block_startup_toast to false yet, as camera might be closing/opening on background thread
        if( toast_message != null && toast_message.length() > 0 )
            preview.showToast(null, toast_message);

        // don't need to reset to saved_focus_value, as we'll have done this when setting up the camera (or will do so when the camera is reopened, if need_reopen)
    	/*if( saved_focus_value != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "switch focus back to: " + saved_focus_value);
    		preview.updateFocus(saved_focus_value, true, false);
    	}*/

        magneticSensor.registerMagneticListener(mSensorManager); // check whether we need to register or unregister the magnetic listener
        magneticSensor.checkMagneticAccuracy();

        if( MyDebug.LOG ) {
            Log.d(TAG, "updateForSettings: done: " + (System.currentTimeMillis() - debug_time));
        }
    }

    private void checkDisableGUIIcons() {
        if( MyDebug.LOG )
            Log.d(TAG, "checkDisableGUIIcons");
        if( !mainUI.showExposureLockIcon() ) {
            View button = findViewById(R.id.exposure_lock);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showWhiteBalanceLockIcon() ) {
            View button = findViewById(R.id.white_balance_lock);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showCycleRawIcon() ) {
            View button = findViewById(R.id.cycle_raw);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showStoreLocationIcon() ) {
            View button = findViewById(R.id.store_location);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showTextStampIcon() ) {
            View button = findViewById(R.id.text_stamp);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showStampIcon() ) {
            View button = findViewById(R.id.stamp);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showAutoLevelIcon() ) {
            View button = findViewById(R.id.auto_level);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showCycleFlashIcon() ) {
            View button = findViewById(R.id.cycle_flash);
            button.setVisibility(View.GONE);
        }
        if( !mainUI.showFaceDetectionIcon() ) {
            View button = findViewById(R.id.face_detection);
            button.setVisibility(View.GONE);
        }
    }

    private MyPreferenceFragment getPreferenceFragment() {
        return (MyPreferenceFragment)getFragmentManager().findFragmentByTag("PREFERENCE_FRAGMENT");
    }

    private boolean settingsIsOpen() {
        return getPreferenceFragment() != null;
    }

    /** Call when the settings is going to be closed.
     */
    private void settingsClosing() {
        if( MyDebug.LOG )
            Log.d(TAG, "close settings");
        setWindowFlagsForCamera();
        showPreview(true);

        preferencesListener.stopListening();

        // Update the cached settings in DrawPreview
        // Note that some GUI related settings won't trigger preferencesListener.anyChanges(), so
        // we always call this. Perhaps we could add more classifications to PreferencesListener
        // to mark settings that need us to update DrawPreview but not call updateForSettings().
        // However, DrawPreview.updateSettings() should be a quick function (the main point is
        // to avoid reading the preferences in every single frame).
        applicationInterface.getDrawPreview().updateSettings();

        if( preferencesListener.anyChange() ) {
            // in case face detection etc enabled/disabled in settings:
            mainUI.updateOnScreenIcons();
        }

        if( preferencesListener.anySignificantChange() ) {
            updateForSettings();
        }
        else {
            if( MyDebug.LOG )
                Log.d(TAG, "no need to call updateForSettings() for changes made to preferences");
            if( preferencesListener.anyChange() ) {
                // however we should still destroy cached popup, in case UI settings need to be kept in
                // sync (e.g., changing the Repeat Mode)
                mainUI.destroyPopup();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if( MyDebug.LOG )
            Log.d(TAG, "onBackPressed");
        if( screen_is_locked ) {
            preview.showToast(screen_locked_toast, R.string.screen_is_locked);
            return;
        }
        if( settingsIsOpen() ) {
            settingsClosing();
        }
        else if( preview != null && preview.isPreviewPaused() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "preview was paused, so unpause it");
            preview.startCameraPreview();
            return;
        }
        else {
            if( popupIsOpen() ) {
                closePopup();
                return;
            }
        }
        super.onBackPressed();
    }

    public boolean usingKitKatImmersiveMode() {
        // whether we are using a Kit Kat style immersive mode (either hiding GUI, or everything)
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
            String immersive_mode = sharedPreferences.getString(PreferenceKeys.ImmersiveModePreferenceKey, "immersive_mode_low_profile");
            if( immersive_mode.equals("immersive_mode_gui") || immersive_mode.equals("immersive_mode_everything") )
                return true;
        }
        return false;
    }
    public boolean usingKitKatImmersiveModeEverything() {
        // whether we are using a Kit Kat style immersive mode for everything
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
            String immersive_mode = sharedPreferences.getString(PreferenceKeys.ImmersiveModePreferenceKey, "immersive_mode_low_profile");
            if( immersive_mode.equals("immersive_mode_everything") )
                return true;
        }
        return false;
    }


    private Handler immersive_timer_handler = null;
    private Runnable immersive_timer_runnable = null;

    private void setImmersiveTimer() {
        if( immersive_timer_handler != null && immersive_timer_runnable != null ) {
            immersive_timer_handler.removeCallbacks(immersive_timer_runnable);
        }
        immersive_timer_handler = new Handler();
        immersive_timer_handler.postDelayed(immersive_timer_runnable = new Runnable(){
            @Override
            public void run(){
                if( MyDebug.LOG )
                    Log.d(TAG, "setImmersiveTimer: run");
                if( !camera_in_background && !popupIsOpen() && usingKitKatImmersiveMode() )
                    setImmersiveMode(true);
            }
        }, 5000);
    }

    public void initImmersiveMode() {
        if( !usingKitKatImmersiveMode() ) {
            setImmersiveMode(true);
        }
        else {
            // don't start in immersive mode, only after a timer
            setImmersiveTimer();
        }
    }

    void setImmersiveMode(boolean on) {
        if( MyDebug.LOG )
            Log.d(TAG, "setImmersiveMode: " + on);
        // n.b., preview.setImmersiveMode() is called from onSystemUiVisibilityChange()
        if( on ) {
            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && usingKitKatImmersiveMode() ) {
                if( applicationInterface.getPhotoMode() == MyApplicationInterface.PhotoMode.Panorama ) {
                    // don't allow the kitkat-style immersive mode for panorama mode (problem that in "full" immersive mode, the gyro spot can't be seen - we could fix this, but simplest to just disallow)
                    getWindow().getDecorView().setSystemUiVisibility(0);
                }
                else {
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
                }
            }
            else {
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                String immersive_mode = sharedPreferences.getString(PreferenceKeys.ImmersiveModePreferenceKey, "immersive_mode_low_profile");
                if( immersive_mode.equals("immersive_mode_low_profile") )
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
                else
                    getWindow().getDecorView().setSystemUiVisibility(0);
            }
        }
        else
            getWindow().getDecorView().setSystemUiVisibility(0);
    }

    /** Sets the brightness level for normal operation (when camera preview is visible).
     *  If force_max is true, this always forces maximum brightness; otherwise this depends on user preference.
     */
    public void setBrightnessForCamera(boolean force_max) {
        if( MyDebug.LOG )
            Log.d(TAG, "setBrightnessForCamera");
        // set screen to max brightness - see http://stackoverflow.com/questions/11978042/android-screen-brightness-max-value
        // done here rather than onCreate, so that changing it in preferences takes effect without restarting app
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        final WindowManager.LayoutParams layout = getWindow().getAttributes();
        if( force_max || sharedPreferences.getBoolean(PreferenceKeys.getMaxBrightnessPreferenceKey(), true) ) {
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
        }
        else {
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }

        // this must be called from the ui thread
        // sometimes this method may be called not on UI thread, e.g., Preview.takePhotoWhenFocused->CameraController2.takePicture
        // ->CameraController2.runFakePrecapture->Preview/onFrontScreenTurnOn->MyApplicationInterface.turnFrontScreenFlashOn
        // -> this.setBrightnessForCamera
        this.runOnUiThread(new Runnable() {
            public void run() {
                getWindow().setAttributes(layout);
            }
        });
    }

    /**
     * Set the brightness to minimal in case the preference key is set to do it
     */
    public void setBrightnessToMinimumIfWanted() {
        if( MyDebug.LOG )
            Log.d(TAG, "setBrightnessToMinimum");
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        final WindowManager.LayoutParams layout = getWindow().getAttributes();
        if( sharedPreferences.getBoolean(PreferenceKeys.DimWhenDisconnectedPreferenceKey, false) ) {
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
        }
        else {
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }

        this.runOnUiThread(new Runnable() {
            public void run() {
                getWindow().setAttributes(layout);
            }
        });

    }

    /** Sets the window flags for normal operation (when camera preview is visible).
     */
    public void setWindowFlagsForCamera() {
        if( MyDebug.LOG )
            Log.d(TAG, "setWindowFlagsForCamera");
    	/*{
    		Intent intent = new Intent(this, MyWidgetProvider.class);
    		intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
    		AppWidgetManager widgetManager = AppWidgetManager.getInstance(this);
    		ComponentName widgetComponent = new ComponentName(this, MyWidgetProvider.class);
    		int[] widgetIds = widgetManager.getAppWidgetIds(widgetComponent);
    		intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds);
    		sendBroadcast(intent);
    	}*/
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        // force to landscape mode
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        //setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE); // testing for devices with unusual sensor orientation (e.g., Nexus 5X)
        if( preview != null ) {
            // also need to call setCameraDisplayOrientation, as this handles if the user switched from portrait to reverse landscape whilst in settings/etc
            // as switching from reverse landscape back to landscape isn't detected in onConfigurationChanged
            preview.setCameraDisplayOrientation();
        }
        if( preview != null && mainUI != null ) {
            // layoutUI() is needed because even though we call layoutUI from MainUI.onOrientationChanged(), certain things
            // (ui_rotation) depend on the system orientation too.
            // Without this, going to Settings, then changing orientation, then exiting settings, would show the icons with the
            // wrong orientation.
            // We put this here instead of onConfigurationChanged() as onConfigurationChanged() isn't called when switching from
            // reverse landscape to landscape orientation: so it's needed to fix if the user starts in portrait, goes to settings
            // or a dialog, then switches to reverse landscape, then exits settings/dialog - the system orientation will switch
            // to landscape (which Open Camera is forced to).
            mainUI.layoutUI();
        }


        // keep screen active - see http://stackoverflow.com/questions/2131948/force-screen-on
        if( sharedPreferences.getBoolean(PreferenceKeys.getKeepDisplayOnPreferenceKey(), true) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "do keep screen on");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        else {
            if( MyDebug.LOG )
                Log.d(TAG, "don't keep screen on");
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if( sharedPreferences.getBoolean(PreferenceKeys.getShowWhenLockedPreferenceKey(), true) ) {
            if( MyDebug.LOG )
                Log.d(TAG, "do show when locked");
            // keep Open Camera on top of screen-lock (will still need to unlock when going to gallery or settings)
            showWhenLocked(true);
        }
        else {
            if( MyDebug.LOG )
                Log.d(TAG, "don't show when locked");
            showWhenLocked(false);
        }

        setBrightnessForCamera(false);

        initImmersiveMode();
        camera_in_background = false;

        magneticSensor.clearDialog(); // if the magnetic accuracy was opened, it must have been closed now
    }

    private void setWindowFlagsForSettings() {
        setWindowFlagsForSettings(true);
    }

    /** Sets the window flags for when the settings window is open.
     * @param set_lock_protect If true, then window flags will be set to protect by screen lock, no
     *                         matter what the preference setting
     *                         PreferenceKeys.getShowWhenLockedPreferenceKey() is set to. This
     *                         should be true for the Settings window, and anything else that might
     *                         need protecting. But some callers use this method for opening other
     *                         things (such as info dialogs).
     */
    public void setWindowFlagsForSettings(boolean set_lock_protect) {
        if( MyDebug.LOG )
            Log.d(TAG, "setWindowFlagsForSettings: " + set_lock_protect);
        // allow screen rotation
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

        // revert to standard screen blank behaviour
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if( set_lock_protect ) {
            // settings should still be protected by screen lock
            showWhenLocked(false);
        }

        {
            WindowManager.LayoutParams layout = getWindow().getAttributes();
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(layout);
        }

        setImmersiveMode(false);
        camera_in_background = true;
    }

    private void showWhenLocked(boolean show) {
        if( MyDebug.LOG )
            Log.d(TAG, "showWhenLocked: " + show);
        // although FLAG_SHOW_WHEN_LOCKED is deprecated, setShowWhenLocked(false) does not work
        // correctly: if we turn screen off and on when camera is open (so we're now running above
        // the lock screen), going to settings does not show the lock screen, i.e.,
        // setShowWhenLocked(false) does not take effect!
		/*if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
			if( MyDebug.LOG )
				Log.d(TAG, "use setShowWhenLocked");
			setShowWhenLocked(show);
		}
		else*/ {
            if( show ) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            }
            else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            }
        }
    }

    /** Use this is place of simply alert.show(), if the orientation has just been set to allow
     *  rotation via setWindowFlagsForSettings(). On some devices (e.g., OnePlus 3T with Android 8),
     *  the dialog doesn't show properly if the phone is held in portrait. A workaround seems to be
     *  to use postDelayed. Note that postOnAnimation() doesn't work.
     */
    public void showAlert(final AlertDialog alert) {
        if( MyDebug.LOG )
            Log.d(TAG, "showAlert");
        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            public void run() {
                alert.show();
            }
        }, 20);
        // note that 1ms usually fixes the problem, but not always; 10ms seems fine, have set 20ms
        // just in case
    }

    public void showPreview(boolean show) {
        if( MyDebug.LOG )
            Log.d(TAG, "showPreview: " + show);
        final ViewGroup container = findViewById(R.id.hide_container);
        container.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    /** Shows the default "blank" gallery icon, when we don't have a thumbnail available.
     */
    private void updateGalleryIconToBlank() {
        if( MyDebug.LOG )
            Log.d(TAG, "updateGalleryIconToBlank");
        ImageButton galleryButton = this.findViewById(R.id.gallery);
        int bottom = galleryButton.getPaddingBottom();
        int top = galleryButton.getPaddingTop();
        int right = galleryButton.getPaddingRight();
        int left = galleryButton.getPaddingLeft();
	    /*if( MyDebug.LOG )
			Log.d(TAG, "padding: " + bottom);*/
        galleryButton.setImageBitmap(null);
        galleryButton.setImageResource(R.drawable.baseline_photo_library_white_48);
        // workaround for setImageResource also resetting padding, Android bug
        galleryButton.setPadding(left, top, right, bottom);
        gallery_bitmap = null;
    }

    /** Shows a thumbnail for the gallery icon.
     */
    void updateGalleryIcon(Bitmap thumbnail) {
        if( MyDebug.LOG )
            Log.d(TAG, "updateGalleryIcon: " + thumbnail);
        ImageButton galleryButton = this.findViewById(R.id.gallery);
        galleryButton.setImageBitmap(thumbnail);
        gallery_bitmap = thumbnail;
    }

    /** Updates the gallery icon by searching for the most recent photo.
     *  Launches the task in a separate thread.
     */
    public void updateGalleryIcon() {
        long debug_time = 0;
        if( MyDebug.LOG ) {
            Log.d(TAG, "updateGalleryIcon");
            debug_time = System.currentTimeMillis();
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String ghost_image_pref = sharedPreferences.getString(PreferenceKeys.GhostImagePreferenceKey, "preference_ghost_image_off");
        final boolean ghost_image_last = ghost_image_pref.equals("preference_ghost_image_last");
        new AsyncTask<Void, Void, Bitmap>() {
            private static final String TAG = "MainActivity/AsyncTask";
            private boolean is_video;

            /** The system calls this to perform work in a worker thread and
             * delivers it the parameters given to AsyncTask.execute() */
            protected Bitmap doInBackground(Void... params) {
                if( MyDebug.LOG )
                    Log.d(TAG, "doInBackground");
                StorageUtils.Media media = applicationInterface.getStorageUtils().getLatestMedia();
                Bitmap thumbnail = null;
                KeyguardManager keyguard_manager = (KeyguardManager)MainActivity.this.getSystemService(Context.KEYGUARD_SERVICE);
                boolean is_locked = keyguard_manager != null && keyguard_manager.inKeyguardRestrictedInputMode();
                if( MyDebug.LOG )
                    Log.d(TAG, "is_locked?: " + is_locked);
                if( media != null && getContentResolver() != null && !is_locked ) {
                    // check for getContentResolver() != null, as have had reported Google Play crashes
                    if( ghost_image_last && !media.video ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "load full size bitmap for ghost image last photo");
                        try {
                            //thumbnail = MediaStore.Images.Media.getBitmap(getContentResolver(), media.uri);
                            // only need to load a bitmap as large as the screen size
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            InputStream is = getContentResolver().openInputStream(media.uri);
                            // get dimensions
                            options.inJustDecodeBounds = true;
                            BitmapFactory.decodeStream(is, null, options);
                            int bitmap_width = options.outWidth;
                            int bitmap_height = options.outHeight;
                            Point display_size = new Point();
                            Display display = getWindowManager().getDefaultDisplay();
                            display.getSize(display_size);
                            if( MyDebug.LOG ) {
                                Log.d(TAG, "bitmap_width: " + bitmap_width);
                                Log.d(TAG, "bitmap_height: " + bitmap_height);
                                Log.d(TAG, "display width: " + display_size.x);
                                Log.d(TAG, "display height: " + display_size.y);
                            }
                            // align dimensions
                            if( display_size.x < display_size.y ) {
                                display_size.set(display_size.y, display_size.x);
                            }
                            if( bitmap_width < bitmap_height ) {
                                int dummy = bitmap_width;
                                bitmap_width = bitmap_height;
                                bitmap_height = dummy;
                            }
                            if( MyDebug.LOG ) {
                                Log.d(TAG, "bitmap_width: " + bitmap_width);
                                Log.d(TAG, "bitmap_height: " + bitmap_height);
                                Log.d(TAG, "display width: " + display_size.x);
                                Log.d(TAG, "display height: " + display_size.y);
                            }
                            // only care about height, to save worrying about different aspect ratios
                            options.inSampleSize = 1;
                            while( bitmap_height / (2*options.inSampleSize) >= display_size.y ) {
                                options.inSampleSize *= 2;
                            }
                            if( MyDebug.LOG ) {
                                Log.d(TAG, "inSampleSize: " + options.inSampleSize);
                            }
                            options.inJustDecodeBounds = false;
                            // need a new inputstream, see https://stackoverflow.com/questions/2503628/bitmapfactory-decodestream-returning-null-when-options-are-set
                            is.close();
                            is = getContentResolver().openInputStream(media.uri);
                            thumbnail = BitmapFactory.decodeStream(is, null, options);
                            if( thumbnail == null ) {
                                Log.e(TAG, "decodeStream returned null bitmap for ghost image last");
                            }
                            is.close();
                        }
                        catch(IOException e) {
                            Log.e(TAG, "failed to load bitmap for ghost image last");
                            e.printStackTrace();
                        }
                    }
                    if( thumbnail == null ) {
                        try {
                            if( media.video ) {
                                if( MyDebug.LOG )
                                    Log.d(TAG, "load thumbnail for video");
                                thumbnail = MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), media.id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                                is_video = true;
                            }
                            else {
                                if( MyDebug.LOG )
                                    Log.d(TAG, "load thumbnail for photo");
                                thumbnail = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(), media.id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                            }
                        }
                        catch(Throwable exception) {
                            // have had Google Play NoClassDefFoundError crashes from getThumbnail() for Galaxy Ace4 (vivalto3g), Galaxy S Duos3 (vivalto3gvn)
                            // also NegativeArraySizeException - best to catch everything
                            if( MyDebug.LOG )
                                Log.e(TAG, "exif orientation exception");
                            exception.printStackTrace();
                        }
                    }
                    if( thumbnail != null ) {
                        if( media.orientation != 0 ) {
                            if( MyDebug.LOG )
                                Log.d(TAG, "thumbnail size is " + thumbnail.getWidth() + " x " + thumbnail.getHeight());
                            Matrix matrix = new Matrix();
                            matrix.setRotate(media.orientation, thumbnail.getWidth() * 0.5f, thumbnail.getHeight() * 0.5f);
                            try {
                                Bitmap rotated_thumbnail = Bitmap.createBitmap(thumbnail, 0, 0, thumbnail.getWidth(), thumbnail.getHeight(), matrix, true);
                                // careful, as rotated_thumbnail is sometimes not a copy!
                                if( rotated_thumbnail != thumbnail ) {
                                    thumbnail.recycle();
                                    thumbnail = rotated_thumbnail;
                                }
                            }
                            catch(Throwable t) {
                                if( MyDebug.LOG )
                                    Log.d(TAG, "failed to rotate thumbnail");
                            }
                        }
                    }
                }
                return thumbnail;
            }

            /** The system calls this to perform work in the UI thread and delivers
             * the result from doInBackground() */
            protected void onPostExecute(Bitmap thumbnail) {
                if( MyDebug.LOG )
                    Log.d(TAG, "onPostExecute");
                // since we're now setting the thumbnail to the latest media on disk, we need to make sure clicking the Gallery goes to this
                applicationInterface.getStorageUtils().clearLastMediaScanned();
                if( thumbnail != null ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "set gallery button to thumbnail");
                    updateGalleryIcon(thumbnail);
                    applicationInterface.getDrawPreview().updateThumbnail(thumbnail, is_video, false); // needed in case last ghost image is enabled
                }
                else {
                    if( MyDebug.LOG )
                        Log.d(TAG, "set gallery button to blank");
                    updateGalleryIconToBlank();
                }
            }
        }.execute();

        if( MyDebug.LOG )
            Log.d(TAG, "updateGalleryIcon: total time to update gallery icon: " + (System.currentTimeMillis() - debug_time));
    }

    void savingImage(final boolean started) {
        if( MyDebug.LOG )
            Log.d(TAG, "savingImage: " + started);

        this.runOnUiThread(new Runnable() {
            public void run() {
                final ImageButton galleryButton = findViewById(R.id.gallery);
                if( started ) {
                    //galleryButton.setColorFilter(0x80ffffff, PorterDuff.Mode.MULTIPLY);
                    if( gallery_save_anim == null ) {
                        gallery_save_anim = ValueAnimator.ofInt(Color.argb(200, 255, 255, 255), Color.argb(63, 255, 255, 255));
                        gallery_save_anim.setEvaluator(new ArgbEvaluator());
                        gallery_save_anim.setRepeatCount(ValueAnimator.INFINITE);
                        gallery_save_anim.setRepeatMode(ValueAnimator.REVERSE);
                        gallery_save_anim.setDuration(500);
                    }
                    gallery_save_anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            galleryButton.setColorFilter((Integer)animation.getAnimatedValue(), PorterDuff.Mode.MULTIPLY);
                        }
                    });
                    gallery_save_anim.start();
                }
                else
                if( gallery_save_anim != null ) {
                    gallery_save_anim.cancel();
                }
                galleryButton.setColorFilter(null);
            }
        });
    }

    /** Called when the number of images being saved in ImageSaver changes (or otherwise something
     *  that changes our calculation of whether we can take a new photo, e.g., changing photo mode).
     */
    void imageQueueChanged() {
        if( MyDebug.LOG )
            Log.d(TAG, "imageQueueChanged");
        applicationInterface.getDrawPreview().setImageQueueFull( !applicationInterface.canTakeNewPhoto() );

        if( applicationInterface.getImageSaver().getNImagesToSave() == 0) {
            cancelImageSavingNotification();
        }
        else if( has_notification) {
            // call again to update the text of remaining images
            createImageSavingNotification();
        }
    }

    /** Creates a notification to indicate still saving images (or updates an existing one).
     */
    private void createImageSavingNotification() {
        if( MyDebug.LOG )
            Log.d(TAG, "createImageSavingNotification");
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            int n_images_to_save = applicationInterface.getImageSaver().getNRealImagesToSave();
            Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.take_photo)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.image_saving_notification) + " " + n_images_to_save + " " + getString(R.string.remaining))
                    //.setStyle(new Notification.BigTextStyle()
                    //        .bigText("Much longer text that cannot fit one line..."))
                    //.setPriority(Notification.PRIORITY_DEFAULT)
                    ;
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.notify(image_saving_notification_id, builder.build());
            has_notification = true;
        }
    }

    /** Cancels the notification for saving images.
     */
    private void cancelImageSavingNotification() {
        if( MyDebug.LOG )
            Log.d(TAG, "cancelImageSavingNotification");
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.cancel(image_saving_notification_id);
            has_notification = false;
        }
    }

    public void clickedGallery(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedGallery");
        openGallery();
    }

    private void openGallery() {
        if( MyDebug.LOG )
            Log.d(TAG, "openGallery");
        //Intent intent = new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        Uri uri = applicationInterface.getStorageUtils().getLastMediaScanned();
        boolean is_raw = false; // note that getLastMediaScanned() will never return RAW images, as we only record JPEGs
        if( uri == null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "go to latest media");
            StorageUtils.Media media = applicationInterface.getStorageUtils().getLatestMedia();
            if( media != null ) {
                uri = media.uri;
                is_raw = media.path != null && media.path.toLowerCase(Locale.US).endsWith(".dng");
            }
        }

        if( uri != null ) {
            // check uri exists
            if( MyDebug.LOG ) {
                Log.d(TAG, "found most recent uri: " + uri);
                Log.d(TAG, "is_raw: " + is_raw);
            }
            try {
                ContentResolver cr = getContentResolver();
                ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
                if( pfd == null ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "uri no longer exists (1): " + uri);
                    uri = null;
                    is_raw = false;
                }
                else {
                    pfd.close();
                }
            }
            catch(IOException e) {
                if( MyDebug.LOG )
                    Log.d(TAG, "uri no longer exists (2): " + uri);
                uri = null;
                is_raw = false;
            }
        }
        if( uri == null ) {
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            is_raw = false;
        }
        if( !is_test ) {
            // don't do if testing, as unclear how to exit activity to finish test (for testGallery())
            if( MyDebug.LOG )
                Log.d(TAG, "launch uri:" + uri);
            final String REVIEW_ACTION = "com.android.camera.action.REVIEW";
            boolean done = false;
            if( !is_raw ) {
                // REVIEW_ACTION means we can view video files without autoplaying
                // however, Google Photos at least has problems with going to a RAW photo (in RAW only mode),
                // unless we first pause and resume Open Camera
                if( MyDebug.LOG )
                    Log.d(TAG, "try REVIEW_ACTION");
                try {
                    Intent intent = new Intent(REVIEW_ACTION, uri);
                    this.startActivity(intent);
                    done = true;
                }
                catch(ActivityNotFoundException e) {
                    e.printStackTrace();
                }
            }
            if( !done ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "try ACTION_VIEW");
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                // from http://stackoverflow.com/questions/11073832/no-activity-found-to-handle-intent - needed to fix crash if no gallery app installed
                //Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("blah")); // test
                if( intent.resolveActivity(getPackageManager()) != null ) {
                    try {
                        this.startActivity(intent);
                    }
                    catch(SecurityException e2) {
                        // have received this crash from Google Play - don't display a toast, simply do nothing
                        Log.e(TAG, "SecurityException from ACTION_VIEW startActivity");
                        e2.printStackTrace();
                    }
                }
                else{
                    preview.showToast(null, R.string.no_gallery_app);
                }
            }
        }
    }

    /** Opens the Storage Access Framework dialog to select a folder for save location.
     * @param from_preferences Whether called from the Preferences
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    void openFolderChooserDialogSAF(boolean from_preferences) {
        if( MyDebug.LOG )
            Log.d(TAG, "openFolderChooserDialogSAF: " + from_preferences);
        this.saf_dialog_from_preferences = from_preferences;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        //Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        //intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, CHOOSE_SAVE_FOLDER_SAF_CODE);
    }

    /** Opens the Storage Access Framework dialog to select a file for ghost image.
     * @param from_preferences Whether called from the Preferences
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    void openGhostImageChooserDialogSAF(boolean from_preferences) {
        if( MyDebug.LOG )
            Log.d(TAG, "openGhostImageChooserDialogSAF: " + from_preferences);
        this.saf_dialog_from_preferences = from_preferences;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, CHOOSE_GHOST_IMAGE_SAF_CODE);
        }
        catch(ActivityNotFoundException e) {
            // see https://stackoverflow.com/questions/34021039/action-open-document-not-working-on-miui/34045627
            preview.showToast(null, R.string.open_files_saf_exception_ghost);
            Log.e(TAG, "ActivityNotFoundException from startActivityForResult");
            e.printStackTrace();
        }
    }

    /** Opens the Storage Access Framework dialog to select a file for loading settings.
     * @param from_preferences Whether called from the Preferences
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    void openLoadSettingsChooserDialogSAF(boolean from_preferences) {
        if( MyDebug.LOG )
            Log.d(TAG, "openLoadSettingsChooserDialogSAF: " + from_preferences);
        this.saf_dialog_from_preferences = from_preferences;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/xml"); // note that application/xml doesn't work (can't select the xml files)!
        try {
            startActivityForResult(intent, CHOOSE_LOAD_SETTINGS_SAF_CODE);
        }
        catch(ActivityNotFoundException e) {
            // see https://stackoverflow.com/questions/34021039/action-open-document-not-working-on-miui/34045627
            preview.showToast(null, R.string.open_files_saf_exception_generic);
            Log.e(TAG, "ActivityNotFoundException from startActivityForResult");
            e.printStackTrace();
        }
    }

    /** Call when the SAF save history has been updated.
     *  This is only public so we can call from testing.
     * @param save_folder The new SAF save folder Uri.
     */
    public void updateFolderHistorySAF(String save_folder) {
        if( MyDebug.LOG )
            Log.d(TAG, "updateSaveHistorySAF");
        if( save_location_history_saf == null ) {
            save_location_history_saf = new SaveLocationHistory(this, "save_location_history_saf", save_folder);
        }
        save_location_history_saf.updateFolderHistory(save_folder, true);
    }

    /** Listens for the response from the Storage Access Framework dialog to select a folder
     *  (as opened with openFolderChooserDialogSAF()).
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        if( MyDebug.LOG )
            Log.d(TAG, "onActivityResult: " + requestCode);
        switch( requestCode ) {
            case CHOOSE_SAVE_FOLDER_SAF_CODE:
                if( resultCode == RESULT_OK && resultData != null ) {
                    Uri treeUri = resultData.getData();
                    if( MyDebug.LOG )
                        Log.d(TAG, "returned treeUri: " + treeUri);
                    // from https://developer.android.com/guide/topics/providers/document-provider.html#permissions :
                    final int takeFlags = resultData.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    try {
					/*if( true )
						throw new SecurityException(); // test*/
                        // Check for the freshest data.
                        getContentResolver().takePersistableUriPermission(treeUri, takeFlags);

                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString(PreferenceKeys.getSaveLocationSAFPreferenceKey(), treeUri.toString());
                        editor.apply();

                        if( MyDebug.LOG )
                            Log.d(TAG, "update folder history for saf");
                        updateFolderHistorySAF(treeUri.toString());

                        File file = applicationInterface.getStorageUtils().getImageFolder();
                        if( file != null ) {
                            preview.showToast(null, getResources().getString(R.string.changed_save_location) + "\n" + file.getAbsolutePath());
                        }
                    }
                    catch(SecurityException e) {
                        Log.e(TAG, "SecurityException failed to take permission");
                        e.printStackTrace();
                        preview.showToast(null, R.string.saf_permission_failed);
                        // failed - if the user had yet to set a save location, make sure we switch SAF back off
                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                        String uri = sharedPreferences.getString(PreferenceKeys.getSaveLocationSAFPreferenceKey(), "");
                        if( uri.length() == 0 ) {
                            if( MyDebug.LOG )
                                Log.d(TAG, "no SAF save location was set");
                            SharedPreferences.Editor editor = sharedPreferences.edit();
                            editor.putBoolean(PreferenceKeys.getUsingSAFPreferenceKey(), false);
                            editor.apply();
                        }
                    }
                }
                else {
                    if( MyDebug.LOG )
                        Log.d(TAG, "SAF dialog cancelled");
                    // cancelled - if the user had yet to set a save location, make sure we switch SAF back off
                    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                    String uri = sharedPreferences.getString(PreferenceKeys.getSaveLocationSAFPreferenceKey(), "");
                    if( uri.length() == 0 ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "no SAF save location was set");
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putBoolean(PreferenceKeys.getUsingSAFPreferenceKey(), false);
                        editor.apply();
                        preview.showToast(null, R.string.saf_cancelled);
                    }
                }

                if( !saf_dialog_from_preferences ) {
                    setWindowFlagsForCamera();
                    showPreview(true);
                }
                break;
            case CHOOSE_GHOST_IMAGE_SAF_CODE:
                if( resultCode == RESULT_OK && resultData != null ) {
                    Uri fileUri = resultData.getData();
                    if( MyDebug.LOG )
                        Log.d(TAG, "returned single fileUri: " + fileUri);
                    // persist permission just in case?
                    final int takeFlags = resultData.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    try {
					/*if( true )
						throw new SecurityException(); // test*/
                        // Check for the freshest data.
                        getContentResolver().takePersistableUriPermission(fileUri, takeFlags);

                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString(PreferenceKeys.GhostSelectedImageSAFPreferenceKey, fileUri.toString());
                        editor.apply();
                    }
                    catch(SecurityException e) {
                        Log.e(TAG, "SecurityException failed to take permission");
                        e.printStackTrace();
                        preview.showToast(null, R.string.saf_permission_failed_open_image);
                        // failed - if the user had yet to set a ghost image
                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                        String uri = sharedPreferences.getString(PreferenceKeys.GhostSelectedImageSAFPreferenceKey, "");
                        if( uri.length() == 0 ) {
                            if( MyDebug.LOG )
                                Log.d(TAG, "no SAF ghost image was set");
                            SharedPreferences.Editor editor = sharedPreferences.edit();
                            editor.putString(PreferenceKeys.GhostImagePreferenceKey, "preference_ghost_image_off");
                            editor.apply();
                        }
                    }
                }
                else {
                    if( MyDebug.LOG )
                        Log.d(TAG, "SAF dialog cancelled");
                    // cancelled - if the user had yet to set a ghost image, make sure we switch the option back off
                    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                    String uri = sharedPreferences.getString(PreferenceKeys.GhostSelectedImageSAFPreferenceKey, "");
                    if( uri.length() == 0 ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "no SAF ghost image was set");
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString(PreferenceKeys.GhostImagePreferenceKey, "preference_ghost_image_off");
                        editor.apply();
                    }
                }

                if( !saf_dialog_from_preferences ) {
                    setWindowFlagsForCamera();
                    showPreview(true);
                }
                break;
            case CHOOSE_LOAD_SETTINGS_SAF_CODE:
                if( resultCode == RESULT_OK && resultData != null ) {
                    Uri fileUri = resultData.getData();
                    if( MyDebug.LOG )
                        Log.d(TAG, "returned single fileUri: " + fileUri);
                    // persist permission just in case?
                    final int takeFlags = resultData.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    try {
					/*if( true )
						throw new SecurityException(); // test*/
                        // Check for the freshest data.
                        getContentResolver().takePersistableUriPermission(fileUri, takeFlags);

                        settingsManager.loadSettings(fileUri);
                    }
                    catch(SecurityException e) {
                        Log.e(TAG, "SecurityException failed to take permission");
                        e.printStackTrace();
                        preview.showToast(null, R.string.restore_settings_failed);
                    }
                }
                else {
                    if( MyDebug.LOG )
                        Log.d(TAG, "SAF dialog cancelled");
                }

                if( !saf_dialog_from_preferences ) {
                    setWindowFlagsForCamera();
                    showPreview(true);
                }
                break;
        }
    }

    void updateSaveFolder(String new_save_location) {
        if( MyDebug.LOG )
            Log.d(TAG, "updateSaveFolder: " + new_save_location);
        if( new_save_location != null ) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
            String orig_save_location = this.applicationInterface.getStorageUtils().getSaveLocation();

            if( !orig_save_location.equals(new_save_location) ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "changed save_folder to: " + this.applicationInterface.getStorageUtils().getSaveLocation());
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(PreferenceKeys.getSaveLocationPreferenceKey(), new_save_location);
                editor.apply();

                this.save_location_history.updateFolderHistory(this.getStorageUtils().getSaveLocation(), true);
                this.preview.showToast(null, getResources().getString(R.string.changed_save_location) + "\n" + this.applicationInterface.getStorageUtils().getSaveLocation());
            }
        }
    }

    public static class MyFolderChooserDialog extends FolderChooserDialog {
        @Override
        public void onDismiss(DialogInterface dialog) {
            if( MyDebug.LOG )
                Log.d(TAG, "FolderChooserDialog dismissed");
            // n.b., fragments have to be static (as they might be inserted into a new Activity - see http://stackoverflow.com/questions/15571010/fragment-inner-class-should-be-static),
            // so we access the MainActivity via the fragment's getActivity().
            MainActivity main_activity = (MainActivity)this.getActivity();
            // activity may be null, see https://stackoverflow.com/questions/13116104/best-practice-to-reference-the-parent-activity-of-a-fragment
            // have had Google Play crashes from this
            if( main_activity != null ) {
                main_activity.setWindowFlagsForCamera();
                main_activity.showPreview(true);
                String new_save_location = this.getChosenFolder();
                main_activity.updateSaveFolder(new_save_location);
            }
            else {
                if( MyDebug.LOG )
                    Log.e(TAG, "activity no longer exists!");
            }
            super.onDismiss(dialog);
        }
    }

    /** Opens Open Camera's own (non-Storage Access Framework) dialog to select a folder.
     */
    private void openFolderChooserDialog() {
        if( MyDebug.LOG )
            Log.d(TAG, "openFolderChooserDialog");
        showPreview(false);
        setWindowFlagsForSettings();

        File start_folder = getStorageUtils().getImageFolder();

        FolderChooserDialog fragment = new MyFolderChooserDialog();
        fragment.setStartFolder(start_folder);
        // use commitAllowingStateLoss() instead of fragment.show(), does to "java.lang.IllegalStateException: Can not perform this action after onSaveInstanceState" crash seen on Google Play
        // see https://stackoverflow.com/questions/14262312/java-lang-illegalstateexception-can-not-perform-this-action-after-onsaveinstanc
        //fragment.show(getFragmentManager(), "FOLDER_FRAGMENT");
        getFragmentManager().beginTransaction().add(fragment, "FOLDER_FRAGMENT").commitAllowingStateLoss();
    }

    /** User can long-click on gallery to select a recent save location from the history, of if not available,
     *  go straight to the file dialog to pick a folder.
     */
    private void longClickedGallery() {
        if( MyDebug.LOG )
            Log.d(TAG, "longClickedGallery");
        if( applicationInterface.getStorageUtils().isUsingSAF() ) {
            if( save_location_history_saf == null || save_location_history_saf.size() <= 1 ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "go straight to choose folder dialog for SAF");
                openFolderChooserDialogSAF(false);
                return;
            }
        }
        else {
            if( save_location_history.size() <= 1 ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "go straight to choose folder dialog");
                openFolderChooserDialog();
                return;
            }
        }

        final SaveLocationHistory history = applicationInterface.getStorageUtils().isUsingSAF() ? save_location_history_saf : save_location_history;
        showPreview(false);
        AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);
        alertDialog.setTitle(R.string.choose_save_location);
        CharSequence [] items = new CharSequence[history.size()+2];
        int index=0;
        // history is stored in order most-recent-last
        for(int i=0;i<history.size();i++) {
            String folder_name = history.get(history.size() - 1 - i);
            if( applicationInterface.getStorageUtils().isUsingSAF() ) {
                // try to get human readable form if possible
                File file = applicationInterface.getStorageUtils().getFileFromDocumentUriSAF(Uri.parse(folder_name), true);
                if( file != null ) {
                    folder_name = file.getAbsolutePath();
                }
            }
            items[index++] = folder_name;
        }
        final int clear_index = index;
        items[index++] = getResources().getString(R.string.clear_folder_history);
        final int new_index = index;
        items[index++] = getResources().getString(R.string.choose_another_folder);
        alertDialog.setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if( which == clear_index ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "selected clear save history");
                    new AlertDialog.Builder(MainActivity.this)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setTitle(R.string.clear_folder_history)
                            .setMessage(R.string.clear_folder_history_question)
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if( MyDebug.LOG )
                                        Log.d(TAG, "confirmed clear save history");
                                    if( applicationInterface.getStorageUtils().isUsingSAF() )
                                        clearFolderHistorySAF();
                                    else
                                        clearFolderHistory();
                                    setWindowFlagsForCamera();
                                    showPreview(true);
                                }
                            })
                            .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if( MyDebug.LOG )
                                        Log.d(TAG, "don't clear save history");
                                    setWindowFlagsForCamera();
                                    showPreview(true);
                                }
                            })
                            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                @Override
                                public void onCancel(DialogInterface arg0) {
                                    if( MyDebug.LOG )
                                        Log.d(TAG, "cancelled clear save history");
                                    setWindowFlagsForCamera();
                                    showPreview(true);
                                }
                            })
                            .show();
                }
                else if( which == new_index ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "selected choose new folder");
                    if( applicationInterface.getStorageUtils().isUsingSAF() ) {
                        openFolderChooserDialogSAF(false);
                    }
                    else {
                        openFolderChooserDialog();
                    }
                }
                else {
                    if( MyDebug.LOG )
                        Log.d(TAG, "selected: " + which);
                    if( which >= 0 && which < history.size() ) {
                        String save_folder = history.get(history.size() - 1 - which);
                        if( MyDebug.LOG )
                            Log.d(TAG, "changed save_folder from history to: " + save_folder);
                        String save_folder_name = save_folder;
                        if( applicationInterface.getStorageUtils().isUsingSAF() ) {
                            // try to get human readable form if possible
                            File file = applicationInterface.getStorageUtils().getFileFromDocumentUriSAF(Uri.parse(save_folder), true);
                            if( file != null ) {
                                save_folder_name = file.getAbsolutePath();
                            }
                        }
                        preview.showToast(null, getResources().getString(R.string.changed_save_location) + "\n" + save_folder_name);
                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        if( applicationInterface.getStorageUtils().isUsingSAF() )
                            editor.putString(PreferenceKeys.getSaveLocationSAFPreferenceKey(), save_folder);
                        else
                            editor.putString(PreferenceKeys.getSaveLocationPreferenceKey(), save_folder);
                        editor.apply();
                        history.updateFolderHistory(save_folder, true); // to move new selection to most recent
                    }
                    setWindowFlagsForCamera();
                    showPreview(true);
                }
            }
        });
        alertDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface arg0) {
                setWindowFlagsForCamera();
                showPreview(true);
            }
        });
        //getWindow().setLayout(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT);
        setWindowFlagsForSettings();
        showAlert(alertDialog.create());
    }

    /** Clears the non-SAF folder history.
     */
    public void clearFolderHistory() {
        if( MyDebug.LOG )
            Log.d(TAG, "clearFolderHistory");
        save_location_history.clearFolderHistory(getStorageUtils().getSaveLocation());
    }

    /** Clears the SAF folder history.
     */
    public void clearFolderHistorySAF() {
        if( MyDebug.LOG )
            Log.d(TAG, "clearFolderHistorySAF");
        save_location_history_saf.clearFolderHistory(getStorageUtils().getSaveLocationSAF());
    }

    static private void putBundleExtra(Bundle bundle, String key, List<String> values) {
        if( values != null ) {
            String [] values_arr = new String[values.size()];
            int i=0;
            for(String value: values) {
                values_arr[i] = value;
                i++;
            }
            bundle.putStringArray(key, values_arr);
        }
    }

    public void clickedShare(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedShare");
        applicationInterface.shareLastImage();
    }

    public void clickedTrash(View view) {
        if( MyDebug.LOG )
            Log.d(TAG, "clickedTrash");
        applicationInterface.trashLastImage();
    }

    /** User has pressed the take picture button, or done an equivalent action to request this (e.g.,
     *  volume buttons, audio trigger).
     * @param photo_snapshot If true, then the user has requested taking a photo whilst video
     *                       recording. If false, either take a photo or start/stop video depending
     *                       on the current mode.
     */
    public void takePicture(boolean photo_snapshot) {
        if( MyDebug.LOG )
            Log.d(TAG, "takePicture");

        if( applicationInterface.getPhotoMode() == MyApplicationInterface.PhotoMode.Panorama ) {
            if( preview.isTakingPhoto() ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "ignore whilst taking panorama photo");
            }
            else if( applicationInterface.getGyroSensor().isRecording() ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "panorama complete");
                applicationInterface.finishPanorama();
                return;
            }
            else if( !applicationInterface.canTakeNewPhoto() ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "can't start new panoroma, still saving in background");
                // we need to test here, otherwise the Preview won't take a new photo - but we'll think we've
                // started the panorama!
            }
            else {
                if( MyDebug.LOG )
                    Log.d(TAG, "start panorama");
                applicationInterface.startPanorama();
            }
        }

        this.takePicturePressed(photo_snapshot, false);
    }

    /** Returns whether the last photo operation was a continuous fast burst.
     */
    boolean lastContinuousFastBurst() {
        return this.last_continuous_fast_burst;
    }

    /**
     * @param photo_snapshot If true, then the user has requested taking a photo whilst video
     *                       recording. If false, either take a photo or start/stop video depending
     *                       on the current mode.
     * @param continuous_fast_burst If true, then start a continuous fast burst.
     */
    void takePicturePressed(boolean photo_snapshot, boolean continuous_fast_burst) {
        if( MyDebug.LOG )
            Log.d(TAG, "takePicturePressed");

        closePopup();

        this.last_continuous_fast_burst = continuous_fast_burst;
        this.preview.takePicturePressed(photo_snapshot, continuous_fast_burst);
    }

    /** Lock the screen - this is Open Camera's own lock to guard against accidental presses,
     *  not the standard Android lock.
     */
    void lockScreen() {
        findViewById(R.id.locker).setOnTouchListener(new View.OnTouchListener() {
            @SuppressLint("ClickableViewAccessibility") @Override
            public boolean onTouch(View arg0, MotionEvent event) {
                return gestureDetector.onTouchEvent(event);
                //return true;
            }
        });
        screen_is_locked = true;
    }

    /** Unlock the screen (see lockScreen()).
     */
    void unlockScreen() {
        findViewById(R.id.locker).setOnTouchListener(null);
        screen_is_locked = false;
    }

    /** Whether the screen is locked (see lockScreen()).
     */
    public boolean isScreenLocked() {
        return screen_is_locked;
    }

    /** Listen for gestures.
     *  Doing a swipe will unlock the screen (see lockScreen()).
     */
    private class MyGestureDetector extends SimpleOnGestureListener {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            try {
                if( MyDebug.LOG )
                    Log.d(TAG, "from " + e1.getX() + " , " + e1.getY() + " to " + e2.getX() + " , " + e2.getY());
                final ViewConfiguration vc = ViewConfiguration.get(MainActivity.this);
                //final int swipeMinDistance = 4*vc.getScaledPagingTouchSlop();
                final float scale = getResources().getDisplayMetrics().density;
                final int swipeMinDistance = (int) (160 * scale + 0.5f); // convert dps to pixels
                final int swipeThresholdVelocity = vc.getScaledMinimumFlingVelocity();
                if( MyDebug.LOG ) {
                    Log.d(TAG, "from " + e1.getX() + " , " + e1.getY() + " to " + e2.getX() + " , " + e2.getY());
                    Log.d(TAG, "swipeMinDistance: " + swipeMinDistance);
                }
                float xdist = e1.getX() - e2.getX();
                float ydist = e1.getY() - e2.getY();
                float dist2 = xdist*xdist + ydist*ydist;
                float vel2 = velocityX*velocityX + velocityY*velocityY;
                if( dist2 > swipeMinDistance*swipeMinDistance && vel2 > swipeThresholdVelocity*swipeThresholdVelocity ) {
                    preview.showToast(screen_locked_toast, R.string.unlocked);
                    unlockScreen();
                }
            }
            catch(Exception e) {
                e.printStackTrace();
            }
            return false;
        }

        @Override
        public boolean onDown(MotionEvent e) {
            preview.showToast(screen_locked_toast, R.string.screen_is_locked);
            return true;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if( MyDebug.LOG )
            Log.d(TAG, "onSaveInstanceState");
        super.onSaveInstanceState(state);
        if( this.preview != null ) {
            preview.onSaveInstanceState(state);
        }
        if( this.applicationInterface != null ) {
            applicationInterface.onSaveInstanceState(state);
        }
    }

    public boolean supportsExposureButton() {
        if( preview.getCameraController() == null )
            return false;
        if( preview.isVideoHighSpeed() ) {
            // manuai ISO/exposure not supported for high speed video mode
            // it's safer not to allow opening the panel at all (otherwise the user could open it, and switch to manual)
            return false;
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String iso_value = sharedPreferences.getString(PreferenceKeys.ISOPreferenceKey, CameraController.ISO_DEFAULT);
        boolean manual_iso = !iso_value.equals(CameraController.ISO_DEFAULT);
        return preview.supportsExposures() || (manual_iso && preview.supportsISORange() );
    }

    void cameraSetup() {
        long debug_time = 0;
        if( MyDebug.LOG ) {
            Log.d(TAG, "cameraSetup");
            debug_time = System.currentTimeMillis();
        }
        if( this.supportsForceVideo4K() && preview.usingCamera2API() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "using Camera2 API, so can disable the force 4K option");
            this.disableForceVideo4K();
        }
        if( this.supportsForceVideo4K() && preview.getVideoQualityHander().getSupportedVideoSizes() != null ) {
            for(CameraController.Size size : preview.getVideoQualityHander().getSupportedVideoSizes()) {
                if( size.width >= 3840 && size.height >= 2160 ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "camera natively supports 4K, so can disable the force option");
                    this.disableForceVideo4K();
                }
            }
        }
        if( MyDebug.LOG )
            Log.d(TAG, "cameraSetup: time after handling Force 4K option: " + (System.currentTimeMillis() - debug_time));

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        {
            if( MyDebug.LOG )
                Log.d(TAG, "set up zoom");
            if( MyDebug.LOG )
                Log.d(TAG, "has_zoom? " + preview.supportsZoom());
            ZoomControls zoomControls = findViewById(R.id.zoom);
            SeekBar zoomSeekBar = findViewById(R.id.zoom_seekbar);

            if( preview.supportsZoom() ) {
                if( sharedPreferences.getBoolean(PreferenceKeys.ShowZoomControlsPreferenceKey, false) ) {
                    zoomControls.setIsZoomInEnabled(true);
                    zoomControls.setIsZoomOutEnabled(true);
                    zoomControls.setZoomSpeed(20);

                    zoomControls.setOnZoomInClickListener(new View.OnClickListener(){
                        public void onClick(View v){
                            zoomIn();
                        }
                    });
                    zoomControls.setOnZoomOutClickListener(new View.OnClickListener(){
                        public void onClick(View v){
                            zoomOut();
                        }
                    });
                    if( !mainUI.inImmersiveMode() ) {
                        zoomControls.setVisibility(View.VISIBLE);
                    }
                }
                else {
                    zoomControls.setVisibility(View.GONE);
                }

                zoomSeekBar.setOnSeekBarChangeListener(null); // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
                zoomSeekBar.setMax(preview.getMaxZoom());
                zoomSeekBar.setProgress(preview.getMaxZoom()-preview.getCameraController().getZoom());
                zoomSeekBar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "zoom onProgressChanged: " + progress);
                        // note we zoom even if !fromUser, as various other UI controls (multitouch, volume key zoom, -/+ zoomcontrol)
                        // indirectly set zoom via this method, from setting the zoom slider
                        preview.zoomTo(preview.getMaxZoom() - progress);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                });

                if( sharedPreferences.getBoolean(PreferenceKeys.ShowZoomSliderControlsPreferenceKey, true) ) {
                    if( !mainUI.inImmersiveMode() ) {
                        zoomSeekBar.setVisibility(View.VISIBLE);
                    }
                }
                else {
                    zoomSeekBar.setVisibility(View.INVISIBLE); // should be INVISIBLE not GONE, as the focus_seekbar is aligned to be left to this
                }
            }
            else {
                zoomControls.setVisibility(View.GONE);
                zoomSeekBar.setVisibility(View.INVISIBLE); // should be INVISIBLE not GONE, as the focus_seekbar is aligned to be left to this
            }
            if( MyDebug.LOG )
                Log.d(TAG, "cameraSetup: time after setting up zoom: " + (System.currentTimeMillis() - debug_time));

            View takePhotoButton = findViewById(R.id.take_photo);
            if( sharedPreferences.getBoolean(PreferenceKeys.ShowTakePhotoPreferenceKey, true) ) {
                if( !mainUI.inImmersiveMode() ) {
                    takePhotoButton.setVisibility(View.VISIBLE);
                }
            }
            else {
                takePhotoButton.setVisibility(View.INVISIBLE);
            }
        }
        {
            if( MyDebug.LOG )
                Log.d(TAG, "set up manual focus");
            setManualFocusSeekbar(false);
            setManualFocusSeekbar(true);
        }
        if( MyDebug.LOG )
            Log.d(TAG, "cameraSetup: time after setting up manual focus: " + (System.currentTimeMillis() - debug_time));
        {
            if( preview.supportsISORange()) {
                if( MyDebug.LOG )
                    Log.d(TAG, "set up iso");
                final SeekBar iso_seek_bar = findViewById(R.id.iso_seekbar);
                iso_seek_bar.setOnSeekBarChangeListener(null); // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
                //setProgressSeekbarExponential(iso_seek_bar, preview.getMinimumISO(), preview.getMaximumISO(), preview.getCameraController().getISO());
                manualSeekbars.setProgressSeekbarISO(iso_seek_bar, preview.getMinimumISO(), preview.getMaximumISO(), preview.getCameraController().getISO());
                iso_seek_bar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "iso seekbar onProgressChanged: " + progress);
						/*double frac = progress/(double)iso_seek_bar.getMax();
						if( MyDebug.LOG )
							Log.d(TAG, "exposure_time frac: " + frac);
						double scaling = MainActivity.seekbarScaling(frac);
						if( MyDebug.LOG )
							Log.d(TAG, "exposure_time scaling: " + scaling);
						int min_iso = preview.getMinimumISO();
						int max_iso = preview.getMaximumISO();
						int iso = min_iso + (int)(scaling * (max_iso - min_iso));*/
						/*int min_iso = preview.getMinimumISO();
						int max_iso = preview.getMaximumISO();
						int iso = (int)exponentialScaling(frac, min_iso, max_iso);*/
                        // n.b., important to update even if fromUser==false (e.g., so this works when user changes ISO via clicking
                        // the ISO buttons rather than moving the slider directly, see MainUI.setupExposureUI())
                        preview.setISO( manualSeekbars.getISO(progress) );
                        mainUI.updateSelectedISOButton();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                });
                if( preview.supportsExposureTime() ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "set up exposure time");
                    final SeekBar exposure_time_seek_bar = findViewById(R.id.exposure_time_seekbar);
                    exposure_time_seek_bar.setOnSeekBarChangeListener(null); // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
                    //setProgressSeekbarExponential(exposure_time_seek_bar, preview.getMinimumExposureTime(), preview.getMaximumExposureTime(), preview.getCameraController().getExposureTime());
                    manualSeekbars.setProgressSeekbarShutterSpeed(exposure_time_seek_bar, preview.getMinimumExposureTime(), preview.getMaximumExposureTime(), preview.getCameraController().getExposureTime());
                    exposure_time_seek_bar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                            if( MyDebug.LOG )
                                Log.d(TAG, "exposure_time seekbar onProgressChanged: " + progress);
							/*double frac = progress/(double)exposure_time_seek_bar.getMax();
							if( MyDebug.LOG )
								Log.d(TAG, "exposure_time frac: " + frac);
							long min_exposure_time = preview.getMinimumExposureTime();
							long max_exposure_time = preview.getMaximumExposureTime();
							long exposure_time = exponentialScaling(frac, min_exposure_time, max_exposure_time);*/
                            preview.setExposureTime( manualSeekbars.getExposureTime(progress) );
                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar seekBar) {
                        }

                        @Override
                        public void onStopTrackingTouch(SeekBar seekBar) {
                        }
                    });
                }
            }
        }
        setManualWBSeekbar();
        if( MyDebug.LOG )
            Log.d(TAG, "cameraSetup: time after setting up iso: " + (System.currentTimeMillis() - debug_time));
        {
            if( preview.supportsExposures() ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "set up exposure compensation");
                final int min_exposure = preview.getMinimumExposure();
                SeekBar exposure_seek_bar = findViewById(R.id.exposure_seekbar);
                exposure_seek_bar.setOnSeekBarChangeListener(null); // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
                exposure_seek_bar.setMax( preview.getMaximumExposure() - min_exposure );
                exposure_seek_bar.setProgress( preview.getCurrentExposure() - min_exposure );
                exposure_seek_bar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "exposure seekbar onProgressChanged: " + progress);
                        preview.setExposure(min_exposure + progress);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                });

                ZoomControls seek_bar_zoom = findViewById(R.id.exposure_seekbar_zoom);
                seek_bar_zoom.setOnZoomInClickListener(new View.OnClickListener(){
                    public void onClick(View v){
                        changeExposure(1);
                    }
                });
                seek_bar_zoom.setOnZoomOutClickListener(new View.OnClickListener(){
                    public void onClick(View v){
                        changeExposure(-1);
                    }
                });
            }
        }
        if( MyDebug.LOG )
            Log.d(TAG, "cameraSetup: time after setting up exposure: " + (System.currentTimeMillis() - debug_time));

        // On-screen icons such as exposure lock, white balance lock, face detection etc are made visible if necessary in
        // MainUI.showGUI()
        // However still nee to update visibility of icons where visibility depends on camera setup - e.g., exposure button
        // not supported for high speed video frame rates - see testTakeVideoFPSHighSpeedManual().
        View exposureButton = findViewById(R.id.exposure);
        exposureButton.setVisibility(supportsExposureButton() && !mainUI.inImmersiveMode() ? View.VISIBLE : View.GONE);

        // need to update some icons, e.g., white balance and exposure lock due to them being turned off when pause/resuming
        mainUI.updateOnScreenIcons();

        mainUI.setPopupIcon(); // needed so that the icon is set right even if no flash mode is set when starting up camera (e.g., switching to front camera with no flash)
        if( MyDebug.LOG )
            Log.d(TAG, "cameraSetup: time after setting popup icon: " + (System.currentTimeMillis() - debug_time));

        mainUI.setTakePhotoIcon();
        mainUI.setSwitchCameraContentDescription();
        if( MyDebug.LOG )
            Log.d(TAG, "cameraSetup: time after setting take photo icon: " + (System.currentTimeMillis() - debug_time));

        if( !block_startup_toast ) {
            this.showPhotoVideoToast(false);
        }
        block_startup_toast = false;
        if( MyDebug.LOG )
            Log.d(TAG, "cameraSetup: total time for cameraSetup: " + (System.currentTimeMillis() - debug_time));
    }

    private void setManualFocusSeekbar(final boolean is_target_distance) {
        if( MyDebug.LOG )
            Log.d(TAG, "setManualFocusSeekbar");
        final SeekBar focusSeekBar = findViewById(is_target_distance ? R.id.focus_bracketing_target_seekbar : R.id.focus_seekbar);
        focusSeekBar.setOnSeekBarChangeListener(null); // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
        ManualSeekbars.setProgressSeekbarScaled(focusSeekBar, 0.0, preview.getMinimumFocusDistance(), is_target_distance ? preview.getCameraController().getFocusBracketingTargetDistance() : preview.getCameraController().getFocusDistance());
        focusSeekBar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            private boolean has_saved_zoom;
            private int saved_zoom_factor;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                double frac = progress/(double)focusSeekBar.getMax();
                double scaling = ManualSeekbars.seekbarScaling(frac);
                float focus_distance = (float)(scaling * preview.getMinimumFocusDistance());
                preview.setFocusDistance(focus_distance, is_target_distance);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if( MyDebug.LOG )
                    Log.d(TAG, "manual focus seekbar: onStartTrackingTouch");
                has_saved_zoom = false;
                if( preview.supportsZoom() ) {
                    int focus_assist = applicationInterface.getFocusAssistPref();
                    if( focus_assist > 0 && preview.getCameraController() != null ) {
                        has_saved_zoom = true;
                        saved_zoom_factor = preview.getCameraController().getZoom();
                        if( MyDebug.LOG )
                            Log.d(TAG, "zoom by " + focus_assist + " for focus assist, zoom factor was: " + saved_zoom_factor);
                        int new_zoom_factor = preview.getScaledZoomFactor(focus_assist);
                        preview.getCameraController().setZoom(new_zoom_factor);
                    }
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if( MyDebug.LOG )
                    Log.d(TAG, "manual focus seekbar: onStopTrackingTouch");
                if( has_saved_zoom && preview.getCameraController() != null ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "unzoom for focus assist, zoom factor was: " + saved_zoom_factor);
                    preview.getCameraController().setZoom(saved_zoom_factor);
                }
                preview.stoppedSettingFocusDistance(is_target_distance);
            }
        });
        setManualFocusSeekBarVisibility(is_target_distance);
    }

    void setManualFocusSeekBarVisibility(final boolean is_target_distance) {
        SeekBar focusSeekBar = findViewById(is_target_distance ? R.id.focus_bracketing_target_seekbar : R.id.focus_seekbar);
        boolean is_visible = preview.getCurrentFocusValue() != null && this.getPreview().getCurrentFocusValue().equals("focus_mode_manual2");
        if( is_target_distance ) {
            is_visible = is_visible && (applicationInterface.getPhotoMode() == MyApplicationInterface.PhotoMode.FocusBracketing) && !preview.isVideo();
        }
        final int visibility = is_visible ? View.VISIBLE : View.GONE;
        focusSeekBar.setVisibility(visibility);
    }

    public void setManualWBSeekbar() {
        if( MyDebug.LOG )
            Log.d(TAG, "setManualWBSeekbar");
        if( preview.getSupportedWhiteBalances() != null && preview.supportsWhiteBalanceTemperature() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "set up manual white balance");
            SeekBar white_balance_seek_bar = findViewById(R.id.white_balance_seekbar);
            white_balance_seek_bar.setOnSeekBarChangeListener(null); // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
            final int minimum_temperature = preview.getMinimumWhiteBalanceTemperature();
            final int maximum_temperature = preview.getMaximumWhiteBalanceTemperature();
			/*
			// white balance should use linear scaling
			white_balance_seek_bar.setMax(maximum_temperature - minimum_temperature);
			white_balance_seek_bar.setProgress(preview.getCameraController().getWhiteBalanceTemperature() - minimum_temperature);
			*/
            manualSeekbars.setProgressSeekbarWhiteBalance(white_balance_seek_bar, minimum_temperature, maximum_temperature, preview.getCameraController().getWhiteBalanceTemperature());
            white_balance_seek_bar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "white balance seekbar onProgressChanged: " + progress);
                    //int temperature = minimum_temperature + progress;
                    //preview.setWhiteBalanceTemperature(temperature);
                    preview.setWhiteBalanceTemperature( manualSeekbars.getWhiteBalanceTemperature(progress) );
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }
    }

    public boolean supportsAutoStabilise() {
        if( applicationInterface.isRawOnly() )
            return false; // if not saving JPEGs, no point having auto-stabilise mode, as it won't affect the RAW images
        if( applicationInterface.getPhotoMode() == MyApplicationInterface.PhotoMode.Panorama )
            return false; // not supported in panorama mode
        return this.supports_auto_stabilise;
    }

    public boolean supportsDRO() {
        if( applicationInterface.isRawOnly(MyApplicationInterface.PhotoMode.DRO) )
            return false; // if not saving JPEGs, no point having DRO mode, as it won't affect the RAW images
        // require at least Android 5, for the Renderscript support in HDRProcessor
        return( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP );
    }

    public boolean supportsHDR() {
        // we also require the device have sufficient memory to do the processing
        // also require at least Android 5, for the Renderscript support in HDRProcessor
        return( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && large_heap_memory >= 128 && preview.supportsExpoBracketing() );
    }

    public boolean supportsExpoBracketing() {
        if( applicationInterface.isImageCaptureIntent() )
            return false; // don't support expo bracketing mode if called from image capture intent
        return preview.supportsExpoBracketing();
    }

    public boolean supportsFocusBracketing() {
        if( applicationInterface.isImageCaptureIntent() )
            return false; // don't support focus bracketing mode if called from image capture intent
        return preview.supportsFocusBracketing();
    }

    public boolean supportsPanorama() {
        // don't support panorama mode if called from image capture intent
        // in theory this works, but problem that currently we'd end up doing the processing on the UI thread, so risk ANR
        if( applicationInterface.isImageCaptureIntent() )
            return false;
        // require 256MB just to be safe, due to the large number of images that may be created
        // also require at least Android 5, for Renderscript
        return( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && large_heap_memory >= 256 && applicationInterface.getGyroSensor().hasSensors() );
        //return false; // currently blocked for release
    }

    public boolean supportsFastBurst() {
        if( applicationInterface.isImageCaptureIntent() )
            return false; // don't support burst mode if called from image capture intent
        // require 512MB just to be safe, due to the large number of images that may be created
        return( preview.usingCamera2API() && large_heap_memory >= 512 && preview.supportsBurst() );
    }

    public boolean supportsNoiseReduction() {
        // require at least Android 5, for the Renderscript support in HDRProcessor, but we require
        // Android 7 to limit to more modern devices (for performance reasons)
        return( Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && preview.usingCamera2API() && large_heap_memory >= 512 && preview.supportsBurst() && preview.supportsExposureTime() );
        //return false; // currently blocked for release
    }

    /** Whether RAW mode would be supported for various burst modes (expo bracketing etc).
     *  Note that caller should still separately check preview.supportsRaw() if required.
     */
    public boolean supportsBurstRaw() {
        return( large_heap_memory >= 512 );
    }

    public boolean supportsPreviewBitmaps() {
        // In practice we only use TextureView on Android 5+ (with Camera2 API enabled) anyway, but have put an explicit check here -
        // even if in future we allow TextureView pre-Android 5, we still need Android 5+ for Renderscript.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && preview.getView() instanceof TextureView && large_heap_memory >= 128;
    }

    private int maxExpoBracketingNImages() {
        return preview.maxExpoBracketingNImages();
    }

    public boolean supportsForceVideo4K() {
        return this.supports_force_video_4k;
    }

    public boolean supportsCamera2() {
        return this.supports_camera2;
    }

    private void disableForceVideo4K() {
        this.supports_force_video_4k = false;
    }

    public static final String DonateLink = "https://play.google.com/store/apps/details?id=harman.mark.donation";

    /*public static String getDonateMarketLink() {
    	return "market://details?id=harman.mark.donation";
    }*/

    public Preview getPreview() {
        return this.preview;
    }

    public boolean isCameraInBackground() {
        return this.camera_in_background;
    }

    public BluetoothRemoteControl getBluetoothRemoteControl() {
        return bluetoothRemoteControl;
    }

    public PermissionHandler getPermissionHandler() {
        return permissionHandler;
    }

    public SettingsManager getSettingsManager() {
        return settingsManager;
    }

    public MainUI getMainUI() {
        return this.mainUI;
    }

    public ManualSeekbars getManualSeekbars() {
        return this.manualSeekbars;
    }

    public MyApplicationInterface getApplicationInterface() {
        return this.applicationInterface;
    }

    public TextFormatter getTextFormatter() {
        return this.textFormatter;
    }

    SoundPoolManager getSoundPoolManager() {
        return this.soundPoolManager;
    }

    public LocationSupplier getLocationSupplier() {
        return this.applicationInterface.getLocationSupplier();
    }

    public StorageUtils getStorageUtils() {
        return this.applicationInterface.getStorageUtils();
    }

    public File getImageFolder() {
        return this.applicationInterface.getStorageUtils().getImageFolder();
    }

    public ToastBoxer getChangedAutoStabiliseToastBoxer() {
        return changed_auto_stabilise_toast;
    }

    /** Displays a toast with information about the current preferences.
     *  If always_show is true, the toast is always displayed; otherwise, we only display
     *  a toast if it's important to notify the user (i.e., unusual non-default settings are
     *  set). We want a balance between not pestering the user too much, whilst also reminding
     *  them if certain settings are on.
     */
    private void showPhotoVideoToast(boolean always_show) {
        if( MyDebug.LOG ) {
            Log.d(TAG, "showPhotoVideoToast");
            Log.d(TAG, "always_show? " + always_show);
        }
        CameraController camera_controller = preview.getCameraController();
        if( camera_controller == null || this.camera_in_background ) {
            if( MyDebug.LOG )
                Log.d(TAG, "camera not open or in background");
            return;
        }
        String toast_string;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean simple = true;
        boolean video_high_speed = preview.isVideoHighSpeed();
        MyApplicationInterface.PhotoMode photo_mode = applicationInterface.getPhotoMode();
        if( preview.isVideo() ) {
            VideoProfile profile = preview.getVideoProfile();

            String extension_string = profile.fileExtension;
            if( !profile.fileExtension.equals("mp4") ) {
                simple = false;
            }

            String bitrate_string;
            if( profile.videoBitRate >= 10000000 )
                bitrate_string = profile.videoBitRate/1000000 + "Mbps";
            else if( profile.videoBitRate >= 10000 )
                bitrate_string = profile.videoBitRate/1000 + "Kbps";
            else
                bitrate_string = profile.videoBitRate + "bps";
            String bitrate_value = applicationInterface.getVideoBitratePref();
            if( !bitrate_value.equals("default") ) {
                simple = false;
            }

            double capture_rate = profile.videoCaptureRate;
            String capture_rate_string = (capture_rate < 9.5f) ? new DecimalFormat("#0.###").format(capture_rate) : "" + (int)(profile.videoCaptureRate+0.5);
            toast_string = getResources().getString(R.string.video) + ": " + profile.videoFrameWidth + "x" + profile.videoFrameHeight + ", " + capture_rate_string + getResources().getString(R.string.fps) + (video_high_speed ? " [" + getResources().getString(R.string.high_speed) + "]" : "") + ", " + bitrate_string + " (" + extension_string + ")";

            String fps_value = applicationInterface.getVideoFPSPref();
            if( !fps_value.equals("default") || video_high_speed ) {
                simple = false;
            }

            float capture_rate_factor = applicationInterface.getVideoCaptureRateFactor();
            if( Math.abs(capture_rate_factor - 1.0f) > 1.0e-5 ) {
                toast_string += "\n" + getResources().getString(R.string.preference_video_capture_rate) + ": " + capture_rate_factor + "x";
                simple = false;
            }

            if( applicationInterface.useVideoLogProfile() && preview.supportsTonemapCurve() ) {
                simple = false;
                toast_string += "\n" + getResources().getString(R.string.video_log);
            }

            boolean record_audio = applicationInterface.getRecordAudioPref();
            if( !record_audio ) {
                toast_string += "\n" + getResources().getString(R.string.audio_disabled);
                simple = false;
            }
            String max_duration_value = sharedPreferences.getString(PreferenceKeys.getVideoMaxDurationPreferenceKey(), "0");
            if( max_duration_value.length() > 0 && !max_duration_value.equals("0") ) {
                String [] entries_array = getResources().getStringArray(R.array.preference_video_max_duration_entries);
                String [] values_array = getResources().getStringArray(R.array.preference_video_max_duration_values);
                int index = Arrays.asList(values_array).indexOf(max_duration_value);
                if( index != -1 ) { // just in case!
                    String entry = entries_array[index];
                    toast_string += "\n" + getResources().getString(R.string.max_duration) +": " + entry;
                    simple = false;
                }
            }
            long max_filesize = applicationInterface.getVideoMaxFileSizeUserPref();
            if( max_filesize != 0 ) {
                toast_string += "\n" + getResources().getString(R.string.max_filesize) +": ";
                if( max_filesize >= 1024*1024*1024 ) {
                    long max_filesize_gb = max_filesize/(1024*1024*1024);
                    toast_string += max_filesize_gb + getResources().getString(R.string.gb_abbreviation);
                }
                else {
                    long max_filesize_mb = max_filesize/(1024*1024);
                    toast_string += max_filesize_mb + getResources().getString(R.string.mb_abbreviation);
                }
                simple = false;
            }
            if( applicationInterface.getVideoFlashPref() && preview.supportsFlash() ) {
                toast_string += "\n" + getResources().getString(R.string.preference_video_flash);
                simple = false;
            }
        }
        else {
            if( photo_mode == MyApplicationInterface.PhotoMode.Panorama ) {
                // don't show resolution in panorama mode
                toast_string = "";
            }
            else {
                toast_string = getResources().getString(R.string.photo);
                CameraController.Size current_size = preview.getCurrentPictureSize();
                toast_string += " " + current_size.width + "x" + current_size.height;
            }

            String photo_mode_string = null;
            switch( photo_mode ) {
                case DRO:
                    photo_mode_string = getResources().getString(R.string.photo_mode_dro);
                    break;
                case HDR:
                    photo_mode_string = getResources().getString(R.string.photo_mode_hdr);
                    break;
                case ExpoBracketing:
                    photo_mode_string = getResources().getString(R.string.photo_mode_expo_bracketing_full);
                    break;
                case FocusBracketing: {
                    photo_mode_string = getResources().getString(R.string.photo_mode_focus_bracketing_full);
                    int n_images = applicationInterface.getFocusBracketingNImagesPref();
                    photo_mode_string += " (" + n_images + ")";
                    break;
                }
                case FastBurst: {
                    photo_mode_string = getResources().getString(R.string.photo_mode_fast_burst_full);
                    int n_images = applicationInterface.getBurstNImages();
                    photo_mode_string += " (" + n_images + ")";
                    break;
                }
                case NoiseReduction:
                    photo_mode_string = getResources().getString(R.string.photo_mode_noise_reduction_full);
                    break;
                case Panorama:
                    photo_mode_string = getResources().getString(R.string.photo_mode_panorama_full);
                    break;
            }
            if( photo_mode_string != null ) {
                toast_string += (toast_string.length()==0 ? "" : "\n") + getResources().getString(R.string.photo_mode) + ": " + photo_mode_string;
                simple = false;
            }

            if( preview.supportsFocus() && preview.getSupportedFocusValues().size() > 1 && photo_mode != MyApplicationInterface.PhotoMode.FocusBracketing ) {
                String focus_value = preview.getCurrentFocusValue();
                if( focus_value != null && !focus_value.equals("focus_mode_auto") && !focus_value.equals("focus_mode_continuous_picture") ) {
                    String focus_entry = preview.findFocusEntryForValue(focus_value);
                    if( focus_entry != null ) {
                        toast_string += "\n" + focus_entry;
                    }
                }
            }

            if( applicationInterface.getAutoStabilisePref() ) {
                // important as users are sometimes confused at the behaviour if they don't realise the option is on
                toast_string += (toast_string.length()==0 ? "" : "\n") + getResources().getString(R.string.preference_auto_stabilise);
                simple = false;
            }
        }
        if( applicationInterface.getFaceDetectionPref() ) {
            // important so that the user realises why touching for focus/metering areas won't work - easy to forget that face detection has been turned on!
            toast_string += "\n" + getResources().getString(R.string.preference_face_detection);
            simple = false;
        }
        if( !video_high_speed ) {
            //manual ISO only supported for high speed video
            String iso_value = applicationInterface.getISOPref();
            if( !iso_value.equals(CameraController.ISO_DEFAULT) ) {
                toast_string += "\nISO: " + iso_value;
                if( preview.supportsExposureTime() ) {
                    long exposure_time_value = applicationInterface.getExposureTimePref();
                    toast_string += " " + preview.getExposureTimeString(exposure_time_value);
                }
                simple = false;
            }
            int current_exposure = camera_controller.getExposureCompensation();
            if( current_exposure != 0 ) {
                toast_string += "\n" + preview.getExposureCompensationString(current_exposure);
                simple = false;
            }
        }
        try {
            String scene_mode = camera_controller.getSceneMode();
            String white_balance = camera_controller.getWhiteBalance();
            String color_effect = camera_controller.getColorEffect();
            if( scene_mode != null && !scene_mode.equals(CameraController.SCENE_MODE_DEFAULT) ) {
                toast_string += "\n" + getResources().getString(R.string.scene_mode) + ": " + mainUI.getEntryForSceneMode(scene_mode);
                simple = false;
            }
            if( white_balance != null && !white_balance.equals(CameraController.WHITE_BALANCE_DEFAULT) ) {
                toast_string += "\n" + getResources().getString(R.string.white_balance) + ": " + mainUI.getEntryForWhiteBalance(white_balance);
                if( white_balance.equals("manual") && preview.supportsWhiteBalanceTemperature() ) {
                    toast_string += " " + camera_controller.getWhiteBalanceTemperature();
                }
                simple = false;
            }
            if( color_effect != null && !color_effect.equals(CameraController.COLOR_EFFECT_DEFAULT) ) {
                toast_string += "\n" + getResources().getString(R.string.color_effect) + ": " + mainUI.getEntryForColorEffect(color_effect);
                simple = false;
            }
        }
        catch(RuntimeException e) {
            // catch runtime error from camera_controller old API from camera.getParameters()
            e.printStackTrace();
        }
        String lock_orientation = applicationInterface.getLockOrientationPref();
        if( !lock_orientation.equals("none") && photo_mode != MyApplicationInterface.PhotoMode.Panorama ) {
            // panorama locks to portrait, but don't want to display that in the toast
            String [] entries_array = getResources().getStringArray(R.array.preference_lock_orientation_entries);
            String [] values_array = getResources().getStringArray(R.array.preference_lock_orientation_values);
            int index = Arrays.asList(values_array).indexOf(lock_orientation);
            if( index != -1 ) { // just in case!
                String entry = entries_array[index];
                toast_string += "\n" + entry;
                simple = false;
            }
        }
        String timer = sharedPreferences.getString(PreferenceKeys.getTimerPreferenceKey(), "0");
        if( !timer.equals("0") && photo_mode != MyApplicationInterface.PhotoMode.Panorama ) {
            String [] entries_array = getResources().getStringArray(R.array.preference_timer_entries);
            String [] values_array = getResources().getStringArray(R.array.preference_timer_values);
            int index = Arrays.asList(values_array).indexOf(timer);
            if( index != -1 ) { // just in case!
                String entry = entries_array[index];
                toast_string += "\n" + getResources().getString(R.string.preference_timer) + ": " + entry;
                simple = false;
            }
        }
        String repeat = applicationInterface.getRepeatPref();
        if( !repeat.equals("1") ) {
            String [] entries_array = getResources().getStringArray(R.array.preference_burst_mode_entries);
            String [] values_array = getResources().getStringArray(R.array.preference_burst_mode_values);
            int index = Arrays.asList(values_array).indexOf(repeat);
            if( index != -1 ) { // just in case!
                String entry = entries_array[index];
                toast_string += "\n" + getResources().getString(R.string.preference_burst_mode) + ": " + entry;
                simple = false;
            }
        }
		/*if( audio_listener != null ) {
			toast_string += "\n" + getResources().getString(R.string.preference_audio_noise_control);
		}*/

        if( MyDebug.LOG ) {
            Log.d(TAG, "toast_string: " + toast_string);
            Log.d(TAG, "simple?: " + simple);
        }
        if( !simple || always_show )
            preview.showToast(switch_video_toast, toast_string);
    }

    private void freeAudioListener(boolean wait_until_done) {
        if( MyDebug.LOG )
            Log.d(TAG, "freeAudioListener");
        if( audio_listener != null ) {
            audio_listener.release(wait_until_done);
            audio_listener = null;
        }
        mainUI.audioControlStopped();
    }

    private void startAudioListener() {
        if( MyDebug.LOG )
            Log.d(TAG, "startAudioListener");
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
            // we restrict the checks to Android 6 or later just in case, see note in LocationSupplier.setupLocationListener()
            if( MyDebug.LOG )
                Log.d(TAG, "check for record audio permission");
            if( ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "record audio permission not available");
                applicationInterface.requestRecordAudioPermission();
                return;
            }
        }

        MyAudioTriggerListenerCallback callback = new MyAudioTriggerListenerCallback(this);
        audio_listener = new AudioListener(callback);
        if( audio_listener.status() ) {
            preview.showToast(audio_control_toast, R.string.audio_listener_started);

            audio_listener.start();
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
            String sensitivity_pref = sharedPreferences.getString(PreferenceKeys.AudioNoiseControlSensitivityPreferenceKey, "0");
            int audio_noise_sensitivity;
            switch(sensitivity_pref) {
                case "3":
                    audio_noise_sensitivity = 50;
                    break;
                case "2":
                    audio_noise_sensitivity = 75;
                    break;
                case "1":
                    audio_noise_sensitivity = 125;
                    break;
                case "-1":
                    audio_noise_sensitivity = 150;
                    break;
                case "-2":
                    audio_noise_sensitivity = 200;
                    break;
                case "-3":
                    audio_noise_sensitivity = 400;
                    break;
                default:
                    // default
                    audio_noise_sensitivity = 100;
                    break;
            }
            callback.setAudioNoiseSensitivity(audio_noise_sensitivity);
            mainUI.audioControlStarted();
        }
        else {
            audio_listener.release(true); // shouldn't be needed, but just to be safe
            audio_listener = null;
            preview.showToast(null, R.string.audio_listener_failed);
        }
    }

    public boolean hasAudioControl() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String audio_control = sharedPreferences.getString(PreferenceKeys.AudioControlPreferenceKey, "none");
        if( audio_control.equals("voice") ) {
            return speechControl.hasSpeechRecognition();
        }
        else if( audio_control.equals("noise") ) {
            return true;
        }
        return false;
    }

	/*void startAudioListeners() {
		initAudioListener();
		// no need to restart speech recognizer, as we didn't free it in stopAudioListeners(), and it's controlled by a user button
	}*/

    public void stopAudioListeners() {
        freeAudioListener(true);
        if( speechControl.hasSpeechRecognition() ) {
            // no need to free the speech recognizer, just stop it
            speechControl.stopListening();
        }
    }

    void initLocation() {
        if( MyDebug.LOG )
            Log.d(TAG, "initLocation");
        if( !applicationInterface.getLocationSupplier().setupLocationListener() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "location permission not available, so request permission");
            permissionHandler.requestLocationPermission();
        }
    }

    private void initGyroSensors() {
        if( MyDebug.LOG )
            Log.d(TAG, "initGyroSensors");
        if( applicationInterface.getPhotoMode() == MyApplicationInterface.PhotoMode.Panorama ) {
            applicationInterface.getGyroSensor().enableSensors();
        }
        else {
            applicationInterface.getGyroSensor().disableSensors();
        }
    }

    void speak(String text) {
        if( textToSpeech != null && textToSpeechSuccess ) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if( MyDebug.LOG )
            Log.d(TAG, "onRequestPermissionsResult: requestCode " + requestCode);
        permissionHandler.onRequestPermissionsResult(requestCode, grantResults);
    }

    public void restartOpenCamera() {
        if( MyDebug.LOG )
            Log.d(TAG, "restartOpenCamera");
        this.waitUntilImageQueueEmpty();
        // see http://stackoverflow.com/questions/2470870/force-application-to-restart-on-first-activity
        Intent i = this.getBaseContext().getPackageManager().getLaunchIntentForPackage( this.getBaseContext().getPackageName() );
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }

    public void takePhotoButtonLongClickCancelled() {
        if( MyDebug.LOG )
            Log.d(TAG, "takePhotoButtonLongClickCancelled");
        if( preview.getCameraController() != null && preview.getCameraController().isContinuousBurstInProgress() ) {
            preview.getCameraController().stopContinuousBurst();
        }
    }

    ToastBoxer getAudioControlToast() {
        return this.audio_control_toast;
    }

    // for testing:
    public SaveLocationHistory getSaveLocationHistory() {
        return this.save_location_history;
    }

    public SaveLocationHistory getSaveLocationHistorySAF() {
        return this.save_location_history_saf;
    }

    public void usedFolderPicker() {
        if( applicationInterface.getStorageUtils().isUsingSAF() ) {
            save_location_history_saf.updateFolderHistory(getStorageUtils().getSaveLocationSAF(), true);
        }
        else {
            save_location_history.updateFolderHistory(getStorageUtils().getSaveLocation(), true);
        }
    }

    public boolean hasThumbnailAnimation() {
        return this.applicationInterface.hasThumbnailAnimation();
    }

    public boolean testHasNotification() {
        return has_notification;
    }
}
