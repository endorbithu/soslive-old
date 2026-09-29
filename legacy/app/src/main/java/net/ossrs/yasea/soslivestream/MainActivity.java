package net.ossrs.yasea.soslivestream;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.AnimationDrawable;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.faucamp.simplertmp.RtmpHandler;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResult;
import com.google.android.gms.location.LocationSettingsStates;
import com.google.android.gms.location.LocationSettingsStatusCodes;

import net.ossrs.yasea.SrsCameraView;
import net.ossrs.yasea.SrsEncodeHandler;
import net.ossrs.yasea.SrsPublisher;
import net.ossrs.yasea.SrsRecordHandler;

import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;


public class MainActivity extends AppCompatActivity implements RtmpHandler.RtmpListener,
        SrsRecordHandler.SrsRecordListener, SrsEncodeHandler.SrsEncodeListener,
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, LocationListener {


    private static final String APP_TOKEN = "DdZCkKePs19G6rIoMXqBltDQ9oBpgGINgNZC9CSXk6nsZCZCRV1ZBQKzp3ZA28ANrTrReOglWpGZ7KLtyCgb9NiwawaQ1ZBii1aE72LIddowZDZDJBEpW0NXSCFeXHZA";
    private static final String BASE_URL = "soslive.info";


    private static final String TAG = "RCI_SOSLIVE";
    private SharedPreferences sp; //ebben lehet megőrizni az adatokat kb mint a db-ben

    private Boolean ONLYSOS;
    private String title = "";

    //rtmp-s cuccok
    private String rtmpUrl = "";
    private SrsPublisher mPublisher;
    private Boolean isStopped = true;
    private String streamComplexName;


    //design
    private ImageButton startSosLiveBtn;
    private ImageButton startPhotoBtn;
    private ImageButton startVideoBtn;
    private ImageButton switchCamBtn;
    private ImageButton switchLightBtn;
    private ImageButton stopBtn;
    private ImageButton commentOk;
    private ImageButton refreshBtn;
    private ImageButton littleCamera;
    private ImageView liveImage;
    private TextView info;
    private TextView comments;
    private TextView nrOfNewComment;
    private EditText addComment;
    private ScrollView commentScroll;

    private Menu optionsMenu;
    private MenuItem historybtn;
    private MenuItem newPostbtn;
    private MenuItem logoutOpt;
    private MenuItem exitOpt;
    private MenuItem usernameopt;

    //duplakattintás az soslivehoz
    private static final long DOUBLE_PRESS_INTERVAL = 750; // in millis hány ms belül kell a dupla katt
    private long lastPressTime;
    private boolean mHasDoubleClicked = false;

    //comments
    private String commentElems;
    private int actualCommentNr = 0;

    //location
    private Location mLastLocation;
    private GoogleApiClient mGoogleApiClient;
    private String coord = "";
    private LocationRequest mLocationRequest;
    private boolean islocation;
    private boolean askedLocation;

    //asynkron cuccok, nem baj ha több van belőlük, lehetnek átfedések, és akkor nem lehetne eggyel megoldani
    private AsyncTask<HttpCall, String, String> updateLocAsync;
    private AsyncTask<HttpCall, String, String> startVideoAsync;
    private AsyncTask<HttpCall, String, String> stopVideoAsync;
    private AsyncTask<HttpCall, String, String> checkinHttp;


    //a periodikus cuccokhoz (comments)
    private Handler m_Handler;
    private Runnable mRunnable;

    private String lang;
    private String historyText;
    private String fbLogoutText;
    private String exitOptText;
    private String commentText;
    private String noNetwork;
    private String weakNetwork;
    private String cancelText;
    private String cameraChangeText;
    private String flashChangeText;
    private String tryFlashLater;
    private String newPostText;
    private String smsTelNumbers = "";
    private String smsText = "";


    //authentikáció
    private String aToken;
    private String uName;
    private int id = 0;
    private long lastiddatetime;

    //képfeltötléshez
    int serverResponseCode = 0;
    String upLoadServerUri = "http://" + BASE_URL + "/api/photos.php";
    private String uploadFilePath = "path";
    private String uploadFileName = "name";
    private boolean isKeepOldId = false;
    private String imageLocation;
    private boolean isCoordSent = false;


    //fényérzékelő
    private SensorManager mSensorManager;
    private Sensor mLightSensor;
    private float mLightQuantity;
    private boolean autoSwitchedLamp = false;
    private Long nowSec;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);



        if(!(android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M)) {



            int PERMISSION_ALL = 1;
            String[] PERMISSIONS = {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.INTERNET
            };

            if (!hasPermissions(this, PERMISSIONS)) {
                ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_ALL);
            }
        }



        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        mPublisher = new SrsPublisher((SrsCameraView) findViewById(R.id.glsurfaceview_camera));

        try {
            mPublisher.setEncodeHandler(new SrsEncodeHandler(this));
            mPublisher.setRtmpHandler(new RtmpHandler(this));
            mPublisher.setRecordHandler(new SrsRecordHandler(this));
            mPublisher.setPreviewResolution(640, 360);
            mPublisher.setOutputResolution(720, 1280);
            mPublisher.setVideoHDMode();


            mPublisher.startCamera();
            mPublisher.switchCameraFace(0);


        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "No-camera", Toast.LENGTH_SHORT).show();
            Log.e("nocamera", e.getMessage());
        }

        String appName = getResources().getString(R.string.app_name);
        ONLYSOS = (appName.equals("SOSlive"));

        title = (ONLYSOS ? "SOSlive" : "RCI");
        setTitle(title);

        //net kapcsolat ellenprzés
        if (!isConnectedToInternet(MainActivity.this)) {
            Toast.makeText(getApplicationContext(), noNetwork, Toast.LENGTH_SHORT).show();
        }

        sp = getSharedPreferences("YaseaRci", MODE_PRIVATE);

        //sharedpreferencből behozzuk a token és a username-t
        aToken = (sp.getString("access_token", ""));
        uName = (sp.getString("username", ""));
        smsTelNumbers = (sp.getString("smsTelNumbers", smsTelNumbers));
        smsText = (sp.getString("smsText", smsText));
        isStopped = (sp.getBoolean("isStopped", isStopped));
        rtmpUrl = (sp.getString("rtmpUrl", rtmpUrl));


        //leteszteljük, hogy él-e az account
        checkinToWeb();

        //ha van id fényképezős eseményből akkor azt is behozzuk (vagy 0 ha nincs)
        id = sp.getInt("id", 0);


        //layout beállítása-------------------


        lang = Locale.getDefault().getLanguage();
        lang = (lang.equals("hu")) ? "hu" : "en";
        historyText = (lang.equals("hu")) ? "Beküldött eseményeim" : "My incidents";
        fbLogoutText = (lang.equals("hu")) ? "Kijelentkezés" : "Logout";
        commentText = (lang.equals("hu")) ? "Hozzászólások" : "Comments";
        noNetwork = (lang.equals("hu")) ? "Nincs netkapcsolat" : "No network";
        weakNetwork = (lang.equals("hu")) ? "Nincs netkapcsolat, vagy nincs jogosultság!" : "Network weak or no permission!";
        cancelText = (lang.equals("hu")) ? "Mégse" : "Cancel";
        cameraChangeText = (lang.equals("hu")) ? "Kameraváltás?" : "Change camera?";
        flashChangeText = (lang.equals("hu")) ? "Vaku ki/be kapcsolás?" : "Change flash On/Off?";
        tryFlashLater = (lang.equals("hu")) ? "Csak akkor működik, ha fut a live!" : "It works only under live!";
        newPostText = (lang.equals("hu")) ? "Új esemény" : "New incident";
        exitOptText = (lang.equals("hu")) ? "Bezárás" : "Exit";



        // response screen rotation event
        //setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);

        //update cucc hgoy X mpként frissítse a cuccot magától
        createLocationRequest();

        info = (TextView) findViewById(R.id.info);
        comments = (TextView) findViewById(R.id.comments);
        comments.setText(commentText + ": \n---------------------------------\n");
        nrOfNewComment = (TextView) findViewById(R.id.howManyNewComment);
        addComment = (EditText) findViewById(R.id.addComment);
        commentScroll = (ScrollView) findViewById(R.id.textAreaScroller);


        if (mGoogleApiClient == null) {
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(LocationServices.API)
                    .build();
        }

        startSosLiveBtn = (ImageButton) findViewById(R.id.publish);
        startVideoBtn = (ImageButton) findViewById(R.id.startVideo);
        startPhotoBtn = (ImageButton) findViewById(R.id.startPhoto);
        switchCamBtn = (ImageButton) findViewById(R.id.switchCamBtn);
        switchLightBtn = (ImageButton) findViewById(R.id.switchLightBtn);
        stopBtn = (ImageButton) findViewById(R.id.stopBtn);
        commentOk = (ImageButton) findViewById(R.id.commentOk);
        refreshBtn = (ImageButton) findViewById(R.id.refreshBtn);
        littleCamera = (ImageButton) findViewById(R.id.littleCamera);

        if(ONLYSOS) {
            startPhotoBtn.setVisibility(View.INVISIBLE);
            startVideoBtn.setVisibility(View.INVISIBLE);
        }

        stopBtn.setVisibility(View.INVISIBLE);

        //megnézzük, hogy va-e X órán belüli ID, ha nics akkor eredeti kinézet
        checkIsNeedNewId();


        liveImage = (ImageView) findViewById(R.id.imageLive);
        liveImage.setImageResource(R.drawable.spin_animation);
        AnimationDrawable frameAnimation = (AnimationDrawable) liveImage.getDrawable();
        frameAnimation.start();
        liveImage.setVisibility(View.INVISIBLE);


        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null) {

            mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

            // Implement a listener to receive updates
            SensorEventListener listener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {

                    if(true) return;

                    mLightQuantity = event.values[0];


                    Long tsTime = System.currentTimeMillis() / 1000;

                    if(nowSec == null) nowSec = tsTime;

                    if(nowSec + 1 != tsTime) return;

                    nowSec = tsTime;
                    //Toast.makeText(getApplicationContext(), "feny: " + mLightQuantity, Toast.LENGTH_SHORT).show();
                    //Log.i("feny", " " + mLightQuantity);


                    if (!autoSwitchedLamp  && mLightQuantity < 9) { //TODO: ezt kell belőni a 60-nál már félhomályban felkapcsolt azzal lehet kezdeni

                        try {
                            mPublisher.switchFlash(-1);
                            Toast.makeText(getApplicationContext(), (lang.equals("hu") ? "Vaku bekapcsolása" : "Flash ON"), Toast.LENGTH_SHORT).show();

                        } catch (Exception e) {
                            Toast.makeText(getApplicationContext(), tryFlashLater, Toast.LENGTH_SHORT).show();
                        }
                    }
                    autoSwitchedLamp = true; // az onpauseban is használom!
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {

                }
            };

            // Register the listener with the light sensor -- choosing
            mSensorManager.registerListener(listener, mLightSensor, SensorManager.SENSOR_DELAY_FASTEST);
        }


//** GOMB KAMERAVÁLTÁS
        switchCamBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int choice) {
                        switch (choice) {
                            case DialogInterface.BUTTON_POSITIVE:

                                mPublisher.switchCameraFace((mPublisher.getCamraId() + 1) % Camera.getNumberOfCameras());
                                if (mPublisher.getCamraId() == 0) {
                                    switchLightBtn.setEnabled(true);
                                } else {
                                    switchLightBtn.setEnabled(false);
                                }

                                break;
                            case DialogInterface.BUTTON_NEGATIVE:
                                break;
                        }
                    }
                };

                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setMessage(cameraChangeText)
                        .setPositiveButton("Ok", dialogClickListener)
                        .setNegativeButton(cancelText, dialogClickListener)
                        .show();
            }
        });


//** GOMB VAKU KI-BE KAPCSOLÁSA
        switchLightBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int choice) {
                        // mPublisher.startCamera();

                        switch (choice) {
                            case DialogInterface.BUTTON_POSITIVE:
                                try {
                                    mPublisher.switchFlash(-1);
                                } catch (Exception e) {
                                    Toast.makeText(getApplicationContext(), tryFlashLater, Toast.LENGTH_SHORT).show();
                                }
                                break;
                            case DialogInterface.BUTTON_NEGATIVE:
                                break;
                        }
                    }
                };

                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setMessage(flashChangeText)
                        .setPositiveButton("Ok", dialogClickListener)
                        .setNegativeButton(cancelText, dialogClickListener)
                        .show();
            }


        });


//** GOMB Fényképezés nagygomb
        startPhotoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                try {

                    HttpCall httpCallPost = new HttpCall();
                    httpCallPost.setMethodtype(HttpCall.GET);
                    httpCallPost.setUrl("https://" + BASE_URL + "/api/membersandposts.php");
                    HashMap<String, String> paramsPost = new HashMap<>();
                    paramsPost.put("app_token", APP_TOKEN);
                    paramsPost.put("action", "new_event");
                    paramsPost.put("coord", coord);
                    paramsPost.put("username", uName);
                    paramsPost.put("access_token", aToken);
                    paramsPost.put("event_type", "PHOTO");

                    httpCallPost.setParams(paramsPost);
                    startVideoAsync = new HttpRequest() {
                        @Override
                        public void onResponse(String response) {
                            super.onResponse(response);

                            int responseId;
                            try {
                                JSONObject responseObj = new JSONObject(response);
                                responseId = Integer.parseInt(responseObj.getString("id"));
                            } catch (Exception e) {
                                responseId = 0;
                            }

                            if (responseId == 0) {
                                Toast.makeText(getApplicationContext(), ((lang.equals("hu")) ? "Hiba! 468" : "Error!"), Toast.LENGTH_LONG).show();
                            } else {
                                id = responseId;

                                Long tsLong = System.currentTimeMillis() / 1000;

                                SharedPreferences.Editor editor = sp.edit();
                                editor.putInt("id", id);
                                editor.putLong("lastiddatetime", tsLong);
                                editor.commit();

                                checkIsNeedNewId();

                                takePicture();

                            }
                        }

                    };
                    startVideoAsync.execute(httpCallPost);

                } catch (Exception e) {
                    Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_LONG).show();

                }


            }
        });

//** GOMB Fényképezés kisgomb
        littleCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    takePicture();

                } catch (Exception e) {
                    Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_LONG).show();

                }


            }
        });


//** GOMB Hozzászólás elküldése
        commentOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String comi = addComment.getText().toString();
                addComment.setText("");
                sendComment(comi);
            }
        });

//** GOMB Refresh comment btn
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshComment(false);

            }
        });


//** GOMB STOP GOMB
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int choice) {
                        switch (choice) {
                            case DialogInterface.BUTTON_POSITIVE:


                                mPublisher.stopPublish();

                                isStopped = true;

                                stopVideo();

                                SharedPreferences.Editor editor = sp.edit();
                                editor.putBoolean("isStopped", isStopped);
                                editor.commit();
                                checkIsNeedNewId();

                                break;

                            case DialogInterface.BUTTON_NEGATIVE:
                                break;
                        }
                    }
                };

                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setMessage("STOP?")
                        .setPositiveButton("STOP", dialogClickListener)
                        .setNegativeButton(cancelText, dialogClickListener)
                        .show();
            }
        });


//**GOMB SOSLIVE GOMB MEGNYOMÁSA
        startSosLiveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if(ONLYSOS) {
                    startVideo("SOSLIVE");
                } else {

                    long pressTime = System.currentTimeMillis();

                    // If double click...
                    if (pressTime - lastPressTime <= DOUBLE_PRESS_INTERVAL) {
                        mHasDoubleClicked = true;


                        startVideo("SOSLIVE");


                    } else {
                        mHasDoubleClicked = false;
                        if (!mHasDoubleClicked) {
                            Toast.makeText(getApplicationContext(), (lang.equals("hu") ? "DUPLA kattintásra indul!" : "It starts by DOUBLE click!"), Toast.LENGTH_SHORT).show();
                        }


                    }
                    // record the last time the menu button was pressed.
                    lastPressTime = pressTime;
                }

            }


        });

//** GOMB VIDEO GOMB MEGNYOMÁSA
        startVideoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startVideo("LIVE");
            }

        });


        m_Handler = new Handler();
        mRunnable = new Runnable() {
            @Override
            public void run() {


                if (commentScroll.getVisibility() == View.VISIBLE) {
                    if (commentScroll.getScrollY() == 0) {
                        refreshComment(false);
                    } else {
                        refreshComment(true);

                    }
                }

                m_Handler.postDelayed(mRunnable, 10000);// move this inside the run method
            }
        };
        mRunnable.run();

    }


//==PHOTO-------------------------------------------------------------------------------------------------

    //a gyári fényképprogit hívjuk meg és a visszatértésnél átadja a fénykép referenciáját
    final static int CAMERA_REQUEST = 1987;

    public void takePicture() {
        mPublisher.stopCamera();
        Intent cameraIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        startActivityForResult(cameraIntent, CAMERA_REQUEST);
    }


    final static int REQUEST_LOCATION = 199;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQUEST_LOCATION:
                switch (resultCode) {
                    case Activity.RESULT_OK: {
                        islocation = true;
                        // All required changes were successfully made
                        break;
                    }
                    case Activity.RESULT_CANCELED: {
                        // The user was asked to change settings, but chose not to
                        islocation = false;
                        break;
                    }


                    default: {
                        break;
                    }
                }
                break;
            //ezt küldi vissza a fényképezőgép app:
            case CAMERA_REQUEST:
                if (resultCode == RESULT_OK) {
                    Bitmap photo = (Bitmap) data.getExtras().get("data");


                    // Find the last picture
                    String[] projection = new String[]{
                            MediaStore.Images.ImageColumns._ID,
                            MediaStore.Images.ImageColumns.DATA,
                            MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
                            MediaStore.Images.ImageColumns.DATE_TAKEN,
                            MediaStore.Images.ImageColumns.MIME_TYPE
                    };
                    final Cursor cursor = this.getContentResolver()
                            .query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null,
                                    null, MediaStore.Images.ImageColumns.DATE_TAKEN + " DESC");

                    //a lefrissebb fénykép:
                    if (cursor.moveToFirst()) {
                        imageLocation = cursor.getString(1);
                        File sourceFile = new File(imageLocation);
                        if (sourceFile.isFile()) {
                            //aszinkronon küldjük a cuccot
                            new UploadImageByHttp().execute();

                        }


                    }

                }
                break;
        }


    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        optionsMenu = menu;
        getMenuInflater().inflate(R.menu.menu_main, menu);

        newPostbtn = optionsMenu.findItem(R.id.newPost);
        historybtn = optionsMenu.findItem(R.id.sosliveprofile);
        logoutOpt = optionsMenu.findItem(R.id.logout);
        exitOpt = optionsMenu.findItem(R.id.exit);
        usernameopt = optionsMenu.findItem(R.id.usernameopt);

        newPostbtn.setTitle(newPostText);
        historybtn.setTitle(historyText);
        logoutOpt.setTitle(fbLogoutText);
        exitOpt.setTitle(exitOptText);

        usernameopt.setTitle(uName);
        usernameopt.setEnabled(false);


        return true;
    }


    protected void popupLocation() {
        if (isLocationServiceEnabled() || askedLocation) return;

        askedLocation = true;
        if (mGoogleApiClient == null) {
            mGoogleApiClient = new GoogleApiClient.Builder(MainActivity.this)
                    .addApi(LocationServices.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this).build();
            mGoogleApiClient.connect();
        }

        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(30 * 1000);
        locationRequest.setFastestInterval(5 * 1000);
        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest);

        //**************************
        builder.setAlwaysShow(true); //this is the key ingredient
        //**************************

        PendingResult<LocationSettingsResult> result =
                LocationServices.SettingsApi.checkLocationSettings(mGoogleApiClient, builder.build());
        result.setResultCallback(new ResultCallback<LocationSettingsResult>() {
            @Override
            public void onResult(LocationSettingsResult result) {
                final Status status = result.getStatus();
                final LocationSettingsStates state = result.getLocationSettingsStates();
                switch (status.getStatusCode()) {
                    case LocationSettingsStatusCodes.SUCCESS:
                        //coorWarning = false;
                        // All location settings are satisfied. The client can initialize location
                        // requests here.
                        break;
                    case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
                        // Location settings are not satisfied. But could be fixed by showing the user
                        // a dialog.
                        try {
                            // Show the dialog by calling startResolutionForResult(),
                            // and check the result in onActivityResult().
                            status.startResolutionForResult(MainActivity.this, 199);


                        } catch (IntentSender.SendIntentException e) {
                            // Ignore the error.
                        }


                        break;
                    case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
                        // Location settings are not satisfied. However, we have no way to fix the
                        // settings so we won't show the dialog.
                        break;
                }
            }
        });


        /////////////////////////gps bekapcsolás popup vége


    }


    public boolean isLocationServiceEnabled() {
        return islocation;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int optionId = item.getItemId();

        //noinspection SimplifiableIfStatement
        switch (optionId) {


            case R.id.newPost:
                isStopped = true;
                SharedPreferences.Editor editor = sp.edit();
                editor.putInt("id", 0);
                editor.putBoolean("isStopped",isStopped);
                editor.commit();

                mPublisher.stopPublish();
                mPublisher.stopCamera();

                Intent intent = getIntent();
                finish();
                startActivity(intent);

                break;

            case R.id.sosliveprofile:
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://" + BASE_URL + "/login"));
                startActivity(browserIntent);

                break;
            case R.id.logout:

                logout();

                break;
            case R.id.exit:
                mPublisher.stopCamera();
                MainActivity.this.finish();
                break;

            default:
                break;
        }

        return super.onOptionsItemSelected(item);
    }


    @Override
    protected void onResume() {
        super.onResume();

        checkIsNeedNewId();


        try {
            mPublisher.startCamera();
        } catch (Exception e) {

        }

        if (!isConnectedToInternet(MainActivity.this)) {
            Toast.makeText(getApplicationContext(), noNetwork, Toast.LENGTH_SHORT).show();
            //startSosLiveBtn.setVisibility(View.VISIBLE);
            //startSosLiveBtn.bringToFront();
            return;
        }

        //mPublisher.resumeRecord();
    }

    @Override
    protected void onPause() {
        try {
            if (updateLocAsync != null) {
                updateLocAsync.cancel(true);
            }
            if (startVideoAsync != null) {
                startVideoAsync.cancel(true);
            }
            if (stopVideoAsync != null) {
                stopVideoAsync.cancel(true);
            }
            autoSwitchedLamp = false;

        } finally {

        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        //mPublisher.stopEncode();
        //mPublisher.stopRecord();

        if(isStopped) {
            mPublisher.stopCamera();
            mPublisher.setScreenOrientation(newConfig.orientation);
            mPublisher.startCamera();
        }

    }


    private void handleException(Exception e) {
        liveImage.setVisibility(View.INVISIBLE);

        try {


            Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_LONG).show();

            mPublisher.stopPublish();
            //mPublisher.stopRecord();

        } catch (Exception e1) {
            //
        }
    }

    // Implementation of SrsRtmpListener.

    @Override
    public void onRtmpConnecting(String msg) {
        //Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRtmpConnected(String msg) {
        liveImage.setVisibility(View.VISIBLE);
        stopBtn.setVisibility(View.VISIBLE);
        comments.setVisibility(View.VISIBLE);
        commentOk.setVisibility(View.VISIBLE);
        addComment.setVisibility(View.VISIBLE);
        refreshBtn.setVisibility(View.VISIBLE);
        nrOfNewComment.setVisibility(View.VISIBLE);

        Toast.makeText(getApplicationContext(), (lang.equals("hu") ? "Stream elindult!" : "Stream has started!"), Toast.LENGTH_SHORT).show();

        //Toast.makeText(getApplicationContext(), "[START] FACEBOOK LIVE VIDEO!", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRtmpVideoStreaming() {
    }

    @Override
    public void onRtmpAudioStreaming() {
    }

    @Override
    public void onRtmpStopped() {
        isStopped = true;

        stopBtn.setVisibility(View.INVISIBLE);

        id = 0;
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt("id", id);
        editor.putBoolean("isStopped",isStopped);
        editor.commit();
        checkIsNeedNewId();

        liveImage.setVisibility(View.INVISIBLE);
        Toast.makeText(getApplicationContext(), (lang.equals("hu") ? "MEGÁLLÍTVA" : "STOPPED"), Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRtmpDisconnected() {
        liveImage.setVisibility(View.INVISIBLE);

        mPublisher.stopPublish();
        id = 0;
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt("id", id);
        editor.putBoolean("isStopped",isStopped);
        editor.commit();
        checkIsNeedNewId();
        stopBtn.setVisibility(View.INVISIBLE);

        Toast.makeText(getApplicationContext(), (lang.equals("hu") ? "Kapcsolat megszakadt" : "Disconnected"), Toast.LENGTH_SHORT).show();




    }

    @Override
    public void onRtmpVideoFpsChanged(double fps) {
        Log.i(TAG, String.format("Output Fps: %f", fps));
    }

    @Override
    public void onRtmpVideoBitrateChanged(double bitrate) {
        int rate = (int) bitrate;
        if (rate / 1000 > 0) {
            Log.i(TAG, String.format("Video bitrate: %f kbps", bitrate / 1000));
        } else {
            Log.i(TAG, String.format("Video bitrate: %d bps", rate));
        }
    }

    @Override
    public void onRtmpAudioBitrateChanged(double bitrate) {
        int rate = (int) bitrate;
        if (rate / 1000 > 0) {
            Log.i(TAG, String.format("Audio bitrate: %f kbps", bitrate / 1000));
        } else {
            Log.i(TAG, String.format("Audio bitrate: %d bps", rate));
        }
    }

    @Override
    public void onRtmpSocketException(SocketException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIOException(IOException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIllegalArgumentException(IllegalArgumentException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIllegalStateException(IllegalStateException e) {
        handleException(e);
    }

    // Implementation of SrsRecordHandler.

    @Override
    public void onRecordPause() {
        Toast.makeText(getApplicationContext(), "Record paused", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRecordResume() {
        Toast.makeText(getApplicationContext(), "Record resumed", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRecordStarted(String msg) {
        Toast.makeText(getApplicationContext(), "Recording file: " + msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRecordFinished(String msg) {
        Toast.makeText(getApplicationContext(), "MP4 file saved: " + msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRecordIOException(IOException e) {
        handleException(e);
    }

    @Override
    public void onRecordIllegalArgumentException(IllegalArgumentException e) {
        handleException(e);
    }

    // Implementation of SrsEncodeHandler.

    @Override
    public void onNetworkWeak() {

        Long tsTime = System.currentTimeMillis() / 1000;

        if(nowSec == null) nowSec = tsTime;

        if(nowSec + 3 != tsTime) return;

        nowSec = tsTime;

        if (!isStopped) {
            Toast.makeText(getApplicationContext(), weakNetwork, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onNetworkResume() {
        //Toast.makeText(getApplicationContext(), "Hálózat javul / Network resume", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onEncodeIllegalArgumentException(IllegalArgumentException e) {
        handleException(e);
    }

    @Override
    public void onConnected(Bundle connectionHint) {

        Location lastLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);

        if (lastLocation != null) {
            coord = (String.valueOf(lastLocation.getLatitude()) + "," + String.valueOf(lastLocation.getLongitude()));
            islocation = true;
        } else {
            popupLocation();
            //Toast.makeText(getApplicationContext(), turnOnGps, Toast.LENGTH_LONG).show();
        }

        startLocationUpdates();
    }


    protected void createLocationRequest() {
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(30000);
        mLocationRequest.setFastestInterval(5000);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    protected void onStart() {
        mGoogleApiClient.connect();
        super.onStart();
    }

    protected void onStop() {

        try {
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, this);
            mGoogleApiClient.disconnect();
        } catch (IllegalStateException e) {

        }

        super.onStop();
    }


    protected void startLocationUpdates() {
        LocationServices.FusedLocationApi.requestLocationUpdates(
                mGoogleApiClient, mLocationRequest, this);
    }


    @Override
    public void onLocationChanged(Location location) {

        if (!(String.valueOf(location.getLatitude()).length() > 0)) return;

        coord = (String.valueOf(location.getLatitude()) + "," + String.valueOf(location.getLongitude()));


        if (id != 0 && (!isStopped || !isCoordSent)) {

            try {
                HttpCall httpCallPost = new HttpCall();
                httpCallPost.setMethodtype(HttpCall.GET);
                httpCallPost.setUrl("https://" + BASE_URL + "/api/membersandposts.php");
                HashMap<String, String> paramsPost = new HashMap<>();
                paramsPost.put("app_token", APP_TOKEN);
                paramsPost.put("username", uName);
                paramsPost.put("access_token", aToken);
                paramsPost.put("id", String.valueOf(id));
                paramsPost.put("action", "updatelocation");
                paramsPost.put("coord", coord);
                httpCallPost.setParams(paramsPost);

                updateLocAsync =
                        new HttpRequest() {
                            @Override
                            public void onResponse(String response) {
                                super.onResponse(response);
                                if (response.equals("1")) {
                                    Toast.makeText(getApplicationContext(), (lang.equals("hu") ? "Helyadat frissítve!": "Location has updated!"), Toast.LENGTH_SHORT).show();
                                    isCoordSent = true;
                                } else {
                                    //Toast.makeText(getApplicationContext(), response, Toast.LENGTH_SHORT).show();
                                }
                            }
                        };

                updateLocAsync.execute(httpCallPost);

            } catch (Exception e) {
                // Toast.makeText(getApplicationContext(), "Figyelmeztetés / Warning: a helyadat NEM lett elküldve  az SOSlive.hu-nak", Toast.LENGTH_SHORT).show();

            }

        }

    }


    final Activity mainAct = this;


    public static boolean isConnectedToInternet(Context context) {

        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo connection = manager.getActiveNetworkInfo();
        if (connection != null && connection.isConnectedOrConnecting()) {
            return true;
        }
        return false;
    }

    private void refreshComment(boolean count) {
        if (id == 0) return;

        final boolean onlyCount = count;

        //APIval lekérjük és a onreturn() {


        try {
            /////////soslive api
            HttpCall httpCallPost = new HttpCall();
            httpCallPost.setMethodtype(HttpCall.GET);
            httpCallPost.setUrl("https://" + BASE_URL + "/api/membersandposts.php");
            HashMap<String, String> paramsPost = new HashMap<>();
            paramsPost.put("action", "getcomments");
            paramsPost.put("app_token", APP_TOKEN);
            paramsPost.put("username", uName);
            paramsPost.put("access_token", aToken);
            paramsPost.put("id", String.valueOf(id));

            httpCallPost.setParams(paramsPost);
            startVideoAsync = new HttpRequest() {
                @Override
                public void onResponse(String response) {

                    super.onResponse(response);

                    if (!response.equals("0") && !response.equals("[]")) {
                        try {
                            JSONObject countComment = new JSONObject(response);
                            String totalStr = countComment.getString("total_count");
                            int total = Integer.parseInt(totalStr);


                            if (onlyCount) {
                                int nrOfNew = total - actualCommentNr;

                                if (nrOfNew > 0) {
                                    String nrOfNewStr = String.valueOf(nrOfNew);
                                    nrOfNewComment.setText("(" + nrOfNewStr + ")");
                                }

                            } else {

                                JSONObject commentsData = new JSONObject(response);


                                commentElems = "";

                                for (int i = 0; i < total; i++) {

                                    commentElems = new JSONObject(commentsData.getString(String.valueOf(i))).getString("datetime")
                                            + " - " + new JSONObject(commentsData.getString(String.valueOf(i))).getString("fullname") + ":\n"
                                            + new JSONObject(commentsData.getString(String.valueOf(i))).getString("message") + "\n"
                                            + "----------------------------\n"
                                            + commentElems;

                                }


                                comments.setText(commentText + ": \n---------------------------------\n" + commentElems);

                                actualCommentNr = total;
                                nrOfNewComment.setText("");
                            }
                        } catch (Exception e) {
                            Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_LONG).show();

                        }


                    } else {

                    }
                }

            };

            startVideoAsync.execute(httpCallPost);


        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_LONG).show();
        }


    }

    private void sendComment(String comi) {
        if (comi.equals("")) return;

        try {
            HttpCall httpCallPost = new HttpCall();
            httpCallPost.setMethodtype(HttpCall.GET);
            httpCallPost.setUrl("https://" + BASE_URL + "/api/membersandposts.php");
            HashMap<String, String> paramsPost = new HashMap<>();
            paramsPost.put("app_token", APP_TOKEN);
            paramsPost.put("action", "addcomment");
            paramsPost.put("username", uName);
            paramsPost.put("access_token", aToken);
            paramsPost.put("id", String.valueOf(id));
            paramsPost.put("message", comi);
            httpCallPost.setParams(paramsPost);

            checkinHttp =
                    new HttpRequest() {
                        @Override
                        public void onResponse(String response) {
                            super.onResponse(response);
                            if (response.equals("1")) {
                                // Toast.makeText(getApplicationContext(), "comment added", Toast.LENGTH_LONG).show();

                            } else {

                                //Toast.makeText(getApplicationContext(), response, Toast.LENGTH_LONG).show();
                            }

                            //APIval elküldjük az új commentet és utána ez kell:
                            refreshComment(false);
                        }
                    };

            checkinHttp.execute(httpCallPost);

        } catch (Exception e) {

        }


    }



    //necces mert ez akkor kéne amikor nincs stop gomnbnyomás, de akkor általában onpause van ahol a https hívások
    //neccesen kivitelezhetőek valamiért bedönti
    private void stopVideo() {
        if (!(id > 0)) return;

        try {
            HttpCall httpCallPost = new HttpCall();
            httpCallPost.setMethodtype(HttpCall.GET);
            httpCallPost.setUrl("https://" + BASE_URL + "/api/membersandposts.php");
            HashMap<String, String> paramsPost = new HashMap<>();
            paramsPost.put("app_token", APP_TOKEN);
            paramsPost.put("action", "stopvideo");
            paramsPost.put("username", uName);
            paramsPost.put("access_token", aToken);
            paramsPost.put("id", String.valueOf(id));
            httpCallPost.setParams(paramsPost);

            checkinHttp = new HttpRequest() {};

            checkinHttp.execute(httpCallPost);

        } catch (Exception e) {

        }


    }



    public void checkinToWeb() {

        if (uName == null || aToken == null) {
            mPublisher.stopCamera();
            Intent intent = new Intent(MainActivity.this, LoginActivity.class);
            startActivity(intent);
            finish();
        }

        try {
            HttpCall httpCallPost = new HttpCall();
            httpCallPost.setMethodtype(HttpCall.GET);
            httpCallPost.setUrl("https://" + BASE_URL + "/api/membersandposts.php");
            HashMap<String, String> paramsPost = new HashMap<>();
            paramsPost.put("app_token", APP_TOKEN);
            paramsPost.put("action", "checkin");
            paramsPost.put("username", uName);
            paramsPost.put("access_token", aToken);
            httpCallPost.setParams(paramsPost);

            checkinHttp =
                    new HttpRequest() {
                        @Override
                        public void onResponse(String response) {
                            super.onResponse(response);




                            if (response.equals("0")) {
                                mPublisher.stopCamera();
                                Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                                startActivity(intent);
                                finish();
                            } else {
                                try {
                                    JSONObject responseObj = new JSONObject(response);
                                    smsTelNumbers = responseObj.getString("smstelnumbers");
                                    smsText = responseObj.getString("smstext");
                                    rtmpUrl = responseObj.getString("rtmpurl");

                                    SharedPreferences.Editor editor = sp.edit();
                                    editor.putString("smsTelNumbers", smsTelNumbers);
                                    editor.putString("smsText", smsText);
                                    editor.putString("rtmpurl", rtmpUrl);
                                    editor.commit();
                                }
                                catch (Exception e){

                                    if(response.equals("-3")) {
                                        Toast.makeText(getApplicationContext(), (lang.equals("hu") ? "Túl sok sikertelen próbálkozás, 15perc múlva próbálja újra" : "Too many failed login attempts, wait 15 mins"), Toast.LENGTH_LONG).show();
                                        Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                                        startActivity(intent);
                                        finish();
                                    }
                                   // Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                                    Toast.makeText(getApplicationContext(), (lang.equals("hu") ? "SMS beállítás probléma" : "SMS settings failed"), Toast.LENGTH_LONG).show();
                                }

                                Toast.makeText(getApplicationContext(), (lang.equals("hu") ? "Felhasználó: " : "User: ") + uName, Toast.LENGTH_SHORT).show();


                            }
                        }
                    };

            checkinHttp.execute(httpCallPost);

        } catch (Exception e) {
            // Toast.makeText(getApplicationContext(), "Figyelmeztetés / Warning: a helyadat NEM lett elküldve  az SOSlive.hu-nak", Toast.LENGTH_SHORT).show();

        }

    }

    public void startVideo(final String type) {
        startSosLiveBtn.setVisibility(View.INVISIBLE);
        startPhotoBtn.setVisibility(View.INVISIBLE);
        startVideoBtn.setVisibility(View.INVISIBLE);

        comments.setText(commentText + ": \n---------------------------------\n");

        if (!isConnectedToInternet(MainActivity.this)) {
            Toast.makeText(getApplicationContext(), noNetwork, Toast.LENGTH_SHORT).show();
            return;
        } else {

            try {
                mPublisher.startCamera();
            } catch(Exception e) {

               checkIsNeedNewId();

                return;

            }

            isStopped = false;


            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("isStopped",isStopped);
            editor.commit();

            mLastLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);

            if (mLastLocation != null) {
                coord = (String.valueOf(mLastLocation.getLatitude()) + "," + String.valueOf(mLastLocation.getLongitude()));
            } else {
                coord = "";
            }


            try {

                HttpCall httpCallPost = new HttpCall();
                httpCallPost.setMethodtype(HttpCall.GET);
                httpCallPost.setUrl("https://" + BASE_URL + "/api/membersandposts.php");
                HashMap<String, String> paramsPost = new HashMap<>();
                paramsPost.put("app_token", APP_TOKEN);
                paramsPost.put("action", "new_event");
                paramsPost.put("coord", coord);
                paramsPost.put("username", uName);
                paramsPost.put("access_token", aToken);
                paramsPost.put("event_type", type);

                httpCallPost.setParams(paramsPost);
                startVideoAsync = new HttpRequest() {
                    @Override
                    public void onResponse(String response) {
                        super.onResponse(response);
                        Toast.makeText(getApplicationContext(), response, Toast.LENGTH_LONG).show();
                        Log.i("sosliveLog", response);

                        int responseId;
                        try {

                            JSONObject responseObj = new JSONObject(response);
                            responseId = Integer.parseInt(responseObj.getString("id"));

                            String vHash = generateHash(uName + "||32p4rifgjdfgo324i23jrigf||" + responseId);
                            vHash = vHash.substring(33,81);


                            //$postDate->format('Ymd') . "_"  . $postDetails['companies_id'] . "_" . $postDetails['username'] . "_" . $postDetails['id'] . "_" . $shortHash
                            streamComplexName = responseObj.getString("datetime")  + "_" + responseObj.getString("company") + "_" + responseObj.getString("username") + "_" + responseId + "_" + vHash;



                        } catch (Exception e) {
                            responseId = 0;
                        }

                        if (responseId == 0) {
                            Toast.makeText(getApplicationContext(), ((lang.equals("hu")) ? "Hiba! 1555" : "Error!"), Toast.LENGTH_LONG).show();
                        } else {
                            id = responseId;

                            Long tsLong = System.currentTimeMillis() / 1000;

                            SharedPreferences.Editor editor = sp.edit();
                            editor.putInt("id", id);
                            editor.putLong("lastiddatetime", tsLong);

                            editor.commit();

                            setTitle((ONLYSOS ? "SOSlive" : "RCI") + " - #" + id);


                            try {

                                //rtmpUrl = rtmpUrl.equals("") ? "rtmp://185.51.191.156:1935" : rtmpUrl;
                                //rtmpUrl = "rtmp://185.51.191.156:1935";
                                String rtmpAddress = rtmpUrl + "/rcilive/" + streamComplexName + "?action=rtmpauthandstart&username=" + uName + "&id=" + id;
                                mPublisher.startPublish(rtmpAddress);

                                //SMS küldés:

                                if(type.equals("SOSLIVE")) {
                                    SmsManager smsManager = SmsManager.getDefault();
                                    String foo = smsTelNumbers;

                                    String[] split = foo.split(",");
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < split.length; i++) {

                                        //úgy lesz, hogy a netről olyan üzenet jön aminek a vége a webhely címe,
                                        // és így a + "/"  + id -vel jön ki az URL
                                        if (split[i].matches("^\\+[0-9]{10,13}$")) {
                                            smsManager.sendTextMessage(split[i], null, smsText + " - https://" + BASE_URL + "/" + id, null, null);
                                        }


                                    }
                                }




                            } catch (Exception e) {
                                startSosLiveBtn.setVisibility(View.VISIBLE);
                                startSosLiveBtn.bringToFront();
                                Toast.makeText(getApplicationContext(), ((lang.equals("hu")) ? "Hiba! 1601" : "Error!"), Toast.LENGTH_SHORT).show();
                            }


                        }
                    }

                };
                startVideoAsync.execute(httpCallPost);

            } catch (Exception e) {
                Toast.makeText(getApplicationContext(), "Error:: " + e.getMessage(), Toast.LENGTH_LONG).show();

            }

        }


    }

    private void logout() {

        mPublisher.stopCamera();
        SharedPreferences.Editor editor = sp.edit();
        editor.putString("access_token", "");
        editor.putString("username", "");
        editor.putInt("id", 0);
        editor.commit();

        Intent intent = new Intent(MainActivity.this, LoginActivity.class);
        startActivity(intent);
        finish();


    }


    public String generateHash(String toHash) {
        MessageDigest md = null;
        byte[] hash = null;
        try {
            md = MessageDigest.getInstance("SHA-512");
            hash = md.digest(toHash.getBytes("UTF-8"));
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return convertToHex(hash);
    }

    /**
     * Converts the given byte[] to a hex string.
     *
     * @param raw the byte[] to convert
     * @return the string the given byte[] represents
     */
    private String convertToHex(byte[] raw) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < raw.length; i++) {
            sb.append(Integer.toString((raw[i] & 0xff) + 0x100, 16).substring(1));
        }
        return sb.toString();
    }


    public int uploadFile(String sourceFileUri) {

        // dialog = ProgressDialog.show(MainActivity.this, "", "Uploading file...", true);

        String fileName = sourceFileUri;

        HttpURLConnection conn = null;
        DataOutputStream dos = null;
        String lineEnd = "\r\n";
        String twoHyphens = "--";
        String boundary = "*****";
        int bytesRead, bytesAvailable, bufferSize;
        byte[] buffer;
        int maxBufferSize = 1 * 1024 * 1024;
        File sourceFile = new File(sourceFileUri);

        if (!sourceFile.isFile()) {

            //dialog.dismiss();

            Log.e("uploadFile", "Source File not exist :"
                    + uploadFilePath + "" + uploadFileName);

            runOnUiThread(new Runnable() {
                public void run() {
                    Toast.makeText(MainActivity.this, (lang.equals("hu") ? "Fájl nem létezik: " : "Source File not exist: " ) + uploadFilePath + "" + uploadFileName,
                            Toast.LENGTH_LONG).show();
                }
            });

            return 0;

        } else {
            try {


                // open a URL connection to the Servlet
                FileInputStream fileInputStream = new FileInputStream(sourceFile);
                URL url = new URL(upLoadServerUri);


                // Open a HTTP  connection to  the URL
                conn = (HttpURLConnection) url.openConnection();
                conn.setDoInput(true); // Allow Inputs
                conn.setDoOutput(true); // Allow Outputs
                conn.setUseCaches(false); // Don't use a Cached Copy
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Connection", "Keep-Alive");
                conn.setRequestProperty("ENCTYPE", "multipart/form-data");
                conn.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);


                dos = new DataOutputStream(conn.getOutputStream());

                dos.writeBytes(twoHyphens + boundary + lineEnd);
                dos.writeBytes("Content-Disposition: form-data; name=\"username\"" + lineEnd + lineEnd + uName + lineEnd);

                dos.writeBytes(twoHyphens + boundary + lineEnd);
                dos.writeBytes("Content-Disposition: form-data; name=\"id\"" + lineEnd + lineEnd + id + lineEnd);

                dos.writeBytes(twoHyphens + boundary + lineEnd);
                dos.writeBytes("Content-Disposition: form-data; name=\"access_token\"" + lineEnd + lineEnd + generateHash(uName + "||" + "32p4rifgjdfgo324i23jrigf" + "||" + id) + lineEnd);

                dos.writeBytes(twoHyphens + boundary + lineEnd);
                dos.writeBytes("Content-Disposition: form-data; name=\"app_token\"" + lineEnd + lineEnd + APP_TOKEN + lineEnd);


                dos.writeBytes(twoHyphens + boundary + lineEnd);
                dos.writeBytes("Content-Disposition: form-data; name=\"uploaded_file\";filename=\"" + fileName + "\"" + lineEnd);


                dos.writeBytes(lineEnd);

                // create a buffer of  maximum size
                bytesAvailable = fileInputStream.available();

                bufferSize = Math.min(bytesAvailable, maxBufferSize);
                buffer = new byte[bufferSize];

                // read file and write it into form...
                bytesRead = fileInputStream.read(buffer, 0, bufferSize);

                while (bytesRead > 0) {

                    dos.write(buffer, 0, bufferSize);
                    bytesAvailable = fileInputStream.available();
                    bufferSize = Math.min(bytesAvailable, maxBufferSize);
                    bytesRead = fileInputStream.read(buffer, 0, bufferSize);

                }

                // send multipart form data necesssary after file data...
                dos.writeBytes(lineEnd);
                dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);

                // Responses from the server (code and message)
                serverResponseCode = conn.getResponseCode();
                String serverResponseMessage = conn.getResponseMessage();

                Log.i("uploadFile", "HTTP Response is : "
                        + serverResponseMessage + ": " + serverResponseCode);

                if (serverResponseCode == 200) {

                    runOnUiThread(new Runnable() {
                        public void run() {


                            Toast.makeText(MainActivity.this, (lang.equals("hu") ? "Sikeres feltöltés!" : "File Upload Complete!" ),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }

                //close the streams //
                fileInputStream.close();
                dos.flush();
                dos.close();

            } catch (MalformedURLException ex) {

                // dialog.dismiss();
                ex.printStackTrace();

                runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(MainActivity.this, (lang.equals("hu") ? "Hiba: MalformedURLException" : "Error: MalformedURLException" ),
                                Toast.LENGTH_SHORT).show();
                    }
                });

                Log.e("Upload file to server", "error: " + ex.getMessage(), ex);
            } catch (Exception e) {

                // dialog.dismiss();
                e.printStackTrace();

                runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(MainActivity.this, (lang.equals("hu") ? "Hiba: (see logcat)" : "Error: (see logcat)" ),
                                Toast.LENGTH_SHORT).show();
                    }
                });
                Log.e("Upload file to server", "Exception : " + e.getMessage(), e);
            }
            // dialog.dismiss();
            return serverResponseCode;

        } // End else block
    }


    class UploadImageByHttp extends AsyncTask<String, Void, Integer> {

        private Exception exception;

        protected Integer doInBackground(String... urls) {
            try {
                return uploadFile(imageLocation);

            } catch (Exception e) {
                this.exception = e;
                Log.e("Upload file to server", "error: " + e.getMessage(), e);
                return 0;
            }
        }

        protected void onPostExecute() {
        }
    }


    //** ha fotózás van és 2 órán blül volt az esemény, jó eséllyel
    // a két órán belüli kpek is ugyanahhoz az eseményhez tartoznak, ezért nem vetjük el az ID-t
    // csak két óra utén, egyébként mg lehet bármikor úat insítani
    public void checkIsNeedNewId() {
        lastiddatetime = sp.getLong("lastiddatetime", 0);
        Long tsLong = System.currentTimeMillis() / 1000;

        if (id > 0 && 7200 > (tsLong - lastiddatetime)) {
            isKeepOldId = true;
            setTitle(title + " - #" + id);
        } else {
            isKeepOldId = false;
            id = 0;
            setTitle(title);
        }


        comments.setVisibility(isKeepOldId ? View.VISIBLE : View.INVISIBLE);
        commentOk.setVisibility(isKeepOldId ? View.VISIBLE : View.INVISIBLE);
        addComment.setVisibility(isKeepOldId ? View.VISIBLE : View.INVISIBLE);
        refreshBtn.setVisibility(isKeepOldId ? View.VISIBLE : View.INVISIBLE);
        nrOfNewComment.setVisibility(isKeepOldId ? View.VISIBLE : View.INVISIBLE);
        littleCamera.setVisibility(isStopped && isKeepOldId ? View.VISIBLE : View.INVISIBLE);
        startVideoBtn.setVisibility(!isKeepOldId && !ONLYSOS ? View.VISIBLE : View.INVISIBLE);
        startPhotoBtn.setVisibility(isStopped && !isKeepOldId && !ONLYSOS ? View.VISIBLE : View.INVISIBLE);
        startSosLiveBtn.setVisibility(!isKeepOldId ? View.VISIBLE : View.INVISIBLE);

    }


    public static boolean hasPermissions(Context context, String... permissions) {
        if (!(android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M)) {
            if (context != null && permissions != null) {
                for (String permission : permissions) {
                    if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                        return false;
                    }
                }
            }

        }

        return true;
    }


}



