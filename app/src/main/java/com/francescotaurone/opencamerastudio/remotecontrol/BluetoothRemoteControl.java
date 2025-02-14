package com.francescotaurone.opencamerastudio.remotecontrol;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.RequiresApi;
import android.util.Log;

import com.francescotaurone.opencamerastudio.MainActivity;
import com.francescotaurone.opencamerastudio.MyApplicationInterface;
import com.francescotaurone.opencamerastudio.MyDebug;
import com.francescotaurone.opencamerastudio.PreferenceKeys;
import com.francescotaurone.opencamerastudio.ui.MainUI;

/** Class for handling the Bluetooth LE remote control functionality.
 */
public class BluetoothRemoteControl {
    private final static String TAG = "BluetoothRemoteControl";

    private final MainActivity main_activity;

    private BluetoothLeService mBluetoothLeService;
    private String mRemoteDeviceAddress;
    private String mRemoteDeviceType;
    private boolean mRemoteConnected;

    public BluetoothRemoteControl(MainActivity main_activity) {
        this.main_activity = main_activity;
    }

    // Code to manage Service lifecycle for remote control.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            if( Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2 ) {
                // BluetoothLeService requires Android 4.3+
                return;
            }
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                stopRemoteControl();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mRemoteDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                public void run() {
                    if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 ) {
                        // BluetoothLeService requires Android 4.3+
                        mBluetoothLeService.connect(mRemoteDeviceAddress);
                    }
                }
            }, 5000);

        }

    };

    /**
     * Receives event from the remote command handler through intents
     * Handles various events fired by the Service.
     */
    private final BroadcastReceiver remoteControlCommandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if( Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2 ) {
                // BluetoothLeService requires Android 4.3+
                return;
            }
            final String action = intent.getAction();
            MyApplicationInterface applicationInterface = main_activity.getApplicationInterface();
            MainUI mainUI = main_activity.getMainUI();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                if( MyDebug.LOG )
                    Log.d(TAG, "Remote connected");
                // Tell the Bluetooth service what type of remote we want to use
                mBluetoothLeService.setRemoteDeviceType(mRemoteDeviceType);
                main_activity.setBrightnessForCamera(false);
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                if( MyDebug.LOG )
                    Log.d(TAG, "Remote disconnected");
                mRemoteConnected = false;
                applicationInterface.getDrawPreview().onExtraOSDValuesChanged("-- \u00B0C", "-- m");
                mainUI.updateRemoteConnectionIcon();
                main_activity.setBrightnessToMinimumIfWanted();
                if (mainUI.isExposureUIOpen())
                    mainUI.toggleExposureUI();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                if( MyDebug.LOG )
                    Log.d(TAG, "Remote services discovered");
                /*
                We let the BluetoothLEService subscribe to what is relevant, so we
                do nothing here, but we wait until this is done to update the UI
                icon
                */
                mRemoteConnected = true;
                mainUI.updateRemoteConnectionIcon();
            } else if (BluetoothLeService.ACTION_SENSOR_VALUE.equals(action)) {
                double temp = intent.getDoubleExtra(BluetoothLeService.SENSOR_TEMPERATURE, -1);
                double depth = intent.getDoubleExtra(BluetoothLeService.SENSOR_DEPTH, -1) / main_activity.getWaterDensity();
                depth = (Math.round(depth* 10)) / 10.0; // Round to 1 decimal
                if( MyDebug.LOG )
                    Log.d(TAG, "Sensor values: depth: " + depth + " - temp: " + temp);
                // Create two OSD lines
                String line1 = "" + temp + " \u00B0C";
                String line2 = "" + depth + " m";
                applicationInterface.getDrawPreview().onExtraOSDValuesChanged(line1, line2);
            } else if (BluetoothLeService.ACTION_REMOTE_COMMAND.equals(action)) {
                int command = intent.getIntExtra(BluetoothLeService.EXTRA_DATA, -1);
                // TODO: we could abstract this into a method provided by each remote control model
                switch (command) {
                    case BluetoothLeService.COMMAND_SHUTTER:
                        // Easiest - just take a picture (or start/stop camera)
                        main_activity.takePicture(false);
                        break;
                    case BluetoothLeService.COMMAND_MODE:
                        // "Mode" key :either toggles photo/video mode, or
                        // closes the settings screen that is currently open
                        if (mainUI.popupIsOpen()) {
                            mainUI.togglePopupSettings();
                        } else if (mainUI.isExposureUIOpen()) {
                            mainUI.toggleExposureUI();
                        } else {
                            main_activity.clickedSwitchVideo(null);
                        }
                        break;
                    case BluetoothLeService.COMMAND_MENU:
                        // Open the exposure UI (ISO/Exposure) or
                        // select the current line on an open UI or
                        // select the current option on a button on a selected line
                        if (!mainUI.popupIsOpen()) {
                            if (! mainUI.isExposureUIOpen()) {
                                mainUI.toggleExposureUI();
                            } else {
                                mainUI.commandMenuExposure();
                            }
                        } else {
                            mainUI.commandMenuPopup();
                        }
                        break;
                    case BluetoothLeService.COMMAND_UP:
                        if (!mainUI.processRemoteUpButton()) {
                            // Default up behaviour:
                            // - if we are on manual focus, then adjust focus.
                            // - if we are on autofocus, then adjust zoom.
                            if (main_activity.getPreview().getCurrentFocusValue() != null && main_activity.getPreview().getCurrentFocusValue().equals("focus_mode_manual2")) {
                                main_activity.changeFocusDistance(-25, false);
                            } else {
                                // Adjust zoom
                                main_activity.zoomIn();
                            }
                        }
                        break;
                    case BluetoothLeService.COMMAND_DOWN:
                        if (!mainUI.processRemoteDownButton()) {
                            if (main_activity.getPreview().getCurrentFocusValue() != null && main_activity.getPreview().getCurrentFocusValue().equals("focus_mode_manual2")) {
                                main_activity.changeFocusDistance(25, false);
                            } else {
                                // Adjust zoom
                                main_activity.zoomOut();
                            }
                        }
                        break;
                    case BluetoothLeService.COMMAND_AFMF:
                        // Open the camera settings popup menu (not the app settings)
                        // or selects the current line/icon in the popup menu, and finally
                        // clicks the icon
                        //if (!mainUI.popupIsOpen()) {
                        mainUI.togglePopupSettings();
                        //}
                        break;
                    default:
                        break;
                }
            } else {
                if( MyDebug.LOG )
                    Log.d(TAG, "Other remote event");
            }
        }
    };

    public boolean remoteConnected() {
		/*if( true )
			return true; // test*/
        return mRemoteConnected;
    }

    // TODO: refactor for a filter than receives generic remote control intents
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    private static IntentFilter makeRemoteCommandIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothLeService.ACTION_REMOTE_COMMAND);
        intentFilter.addAction(BluetoothLeService.ACTION_SENSOR_VALUE);
        return intentFilter;
    }

    /**
     * Starts or stops the remote control layer
     */
    public void startRemoteControl() {
        if( MyDebug.LOG )
            Log.d(TAG, "BLE Remote control service start check...");
        if( Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2 ) {
            // BluetoothLeService requires Android 4.3+
            return;
        }
        Intent gattServiceIntent = new Intent(main_activity, BluetoothLeService.class);
        if ( remoteEnabled()) {
            if( MyDebug.LOG )
                Log.d(TAG, "Remote enabled, starting service");
            main_activity.bindService(gattServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
            main_activity.registerReceiver(remoteControlCommandReceiver, makeRemoteCommandIntentFilter());
        } else {
            if( MyDebug.LOG )
                Log.d(TAG, "Remote disabled, stopping service");
            // Stop the service if necessary
            try{
                main_activity.unregisterReceiver(remoteControlCommandReceiver);
                main_activity.unbindService(mServiceConnection);
                mRemoteConnected = false; // Unbinding closes the connection, of course
                main_activity.getMainUI().updateRemoteConnectionIcon();
            } catch (IllegalArgumentException e){
                if( MyDebug.LOG )
                    Log.d(TAG, "Remote Service was not running, that's fine");
            }
        }
    }

    public void stopRemoteControl() {
        if( MyDebug.LOG )
            Log.d(TAG, "BLE Remote control service shutdown...");
        if ( remoteEnabled()) {
            // Stop the service if necessary
            try{
                main_activity.unregisterReceiver(remoteControlCommandReceiver);
                main_activity.unbindService(mServiceConnection);
                mRemoteConnected = false; // Unbinding closes the connection, of course
                main_activity.getMainUI().updateRemoteConnectionIcon();
            } catch (IllegalArgumentException e){
                Log.e(TAG, "Remote Service was not running, that's strange");
                e.printStackTrace();
            }
        }
    }

    /**
     * Checks if remote control is enabled in the settings, and the remote control address
     * is also defined
     * @return true if this is the case
     */
    public boolean remoteEnabled() {
        if( Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2 ) {
            // BluetoothLeService requires Android 4.3+
            return false;
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        boolean remote_enabled = sharedPreferences.getBoolean(PreferenceKeys.EnableRemote, false);
        mRemoteDeviceType = sharedPreferences.getString(PreferenceKeys.RemoteType, "undefined");
        mRemoteDeviceAddress = sharedPreferences.getString(PreferenceKeys.RemoteName, "undefined");
        return remote_enabled && !mRemoteDeviceAddress.equals("undefined");
    }
}
