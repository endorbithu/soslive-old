package net.ossrs.yasea.soslivestream;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;


/**
 * A login screen that offers login via email/password.
 */
public class LoginActivity extends AppCompatActivity /*implements LoaderCallbacks<Cursor> */ {

    private static final String BASE_URL = "soslive.info";

    private Boolean ONLYSOS;
    private String title = "";

    /**
     * A dummy authentication store containing known user names and passwords.
     * TODO: remove after connecting to a real authentication system.
     */
    private static final String[] DUMMY_CREDENTIALS = new String[]{
            "foo@example.com:hello", "bar@example.com:world"
    };
    /**
     * Keep track of the login task to ensure we can cancel it if requested.
     */

    // UI references.
    private AutoCompleteTextView mEmailView;
    private EditText mPasswordView;
    private View mProgressView;
    private View mLoginFormView;

    //sajat

    private String lang;
    private String usernameLabel;
    private String passwordLabel;
    private String loginLabel;

    private AsyncTask<HttpCall, String, String> checkinHttp;

    private static final String APP_TOKEN = "DdZCkKePs19G6rIoMXqBltDQ9oBpgGINgNZC9CSXk6nsZCZCRV1ZBQKzp3ZA28ANrTrReOglWpGZ7KLtyCgb9NiwawaQ1ZBii1aE72LIddowZDZDJBEpW0NXSCFeXHZA";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_login);

        String appName = getResources().getString(R.string.app_name);
        ONLYSOS = (appName.equals("SOSlive"));
        ImageView logo = (ImageView) findViewById(R.id.logo);
        if(ONLYSOS) logo.setImageResource(R.drawable.logosos);
        title = (ONLYSOS ? "AUTÓ KÓRLAP" : "AUTÓ KÓRLAP");
        setTitle(title);


        lang = Locale.getDefault().getLanguage();
        lang = (lang.equals("hu")) ? "hu" : "en";

        usernameLabel = (lang.equals("hu")) ? "Felhasználónév" : "Username";
        passwordLabel = (lang.equals("hu")) ? "Jelszó" : "Password";
        loginLabel = (lang.equals("hu")) ? "Bejelentkezés" : "Sign in";


        mEmailView = (AutoCompleteTextView) findViewById(R.id.email);
        mEmailView.setHint(usernameLabel);

        mPasswordView = (EditText) findViewById(R.id.password);
        mPasswordView.setHint(passwordLabel);

        //populateAutoComplete();


        mPasswordView.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView textView, int id, KeyEvent keyEvent) {
                if (id == R.id.password || id == EditorInfo.IME_NULL) {
                    attemptLogin();
                    return true;
                }
                return false;
            }
        });

        Button mEmailSignInButton = (Button) findViewById(R.id.email_sign_in_button);
        mEmailSignInButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                attemptLogin();
            }
        });

        mLoginFormView = findViewById(R.id.login_form);
        mProgressView = findViewById(R.id.login_progress);
    }

    /*
    private void populateAutoComplete() {
        getLoaderManager().initLoader(0, null, this);
    }
    */


    /**
     * Attempts to sign in or register the account specified by the login form.
     * If there are form errors (invalid email, missing fields, etc.), the
     * errors are presented and no actual login attempt is made.
     */
    private void attemptLogin() {


        //net kapcsolat ellenprzés
        if (!isConnectedToInternet(LoginActivity.this)) {
            Toast.makeText(getApplicationContext(), ((lang.equals("hu")) ? "Nincs internet!" : "No network!"), Toast.LENGTH_SHORT).show();
            return;
        }

        // Reset errors.
        mEmailView.setError(null);
        mPasswordView.setError(null);

        // Store values at the time of the login attempt.
        String email = mEmailView.getText().toString();
        String password = mPasswordView.getText().toString();

        boolean cancel = false;
        View focusView = null;

        // Check for a valid email address.
        if (TextUtils.isEmpty(email)) {
            mEmailView.setError(((lang.equals("hu")) ? "Érvénytelen felhasználónév!" : "This username is incorrect!"));
            focusView = mEmailView;
            cancel = true;
        } else if (TextUtils.isEmpty(password) ) {
            mPasswordView.setError(((lang.equals("hu")) ? "Érvénytelen jelszó!" : "This password is incorrect!"));
            focusView = mPasswordView;
            cancel = true;
        }

        if (cancel) {
            // There was an error; don't attempt login and focus the first
            // form field with an error.
            focusView.requestFocus();
        } else {
            // Show a progress spinner, and kick off a background task to
            // perform the user login attempt.
            try {

                Thread.sleep(1500);

                showProgress(true);
                checkinToWeb(email, password);

            } catch (InterruptedException e) {

            }


        }
    }

    private boolean isEmailValid(String email) {
        //TODO: Replace this with your own logic
        return true;
    }

    private boolean isPasswordValid(String password) {
        //TODO: Replace this with your own logic
        return password.length() > 3;
    }

    /**
     * Shows the progress UI and hides the login form.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
    private void showProgress(final boolean show) {
        // On Honeycomb MR2 we have the ViewPropertyAnimator APIs, which allow
        // for very easy animations. If available, use these APIs to fade-in
        // the progress spinner.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
            int shortAnimTime = getResources().getInteger(android.R.integer.config_shortAnimTime);

            mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
            mLoginFormView.animate().setDuration(shortAnimTime).alpha(
                    show ? 0 : 1).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
                }
            });

            mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
            mProgressView.animate().setDuration(shortAnimTime).alpha(
                    show ? 1 : 0).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
                }
            });
        } else {
            // The ViewPropertyAnimator APIs are not available, so simply show
            // and hide the relevant UI components.
            mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
            mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    /*
    @Override
    public Loader<Cursor> onCreateLoader(int i, Bundle bundle) {
        return new CursorLoader(this,
                // Retrieve data rows for the device user's 'profile' contact.
                Uri.withAppendedPath(ContactsContract.Profile.CONTENT_URI,
                        ContactsContract.Contacts.Data.CONTENT_DIRECTORY), ProfileQuery.PROJECTION,

                // Select only email addresses.
                ContactsContract.Contacts.Data.MIMETYPE +
                        " = ?", new String[]{ContactsContract.CommonDataKinds.Email
                .CONTENT_ITEM_TYPE},

                // Show primary email addresses first. Note that there won't be
                // a primary email address if the user hasn't specified one.
                ContactsContract.Contacts.Data.IS_PRIMARY + " DESC");
    }

    @Override
    public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor cursor) {
        List<String> emails = new ArrayList<>();
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            emails.add(cursor.getString(ProfileQuery.ADDRESS));
            cursor.moveToNext();
        }

        addEmailsToAutoComplete(emails);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> cursorLoader) {

    } */

    private void addEmailsToAutoComplete(List<String> emailAddressCollection) {
        //Create adapter to tell the AutoCompleteTextView what to show in its dropdown list.
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(LoginActivity.this,
                        android.R.layout.simple_dropdown_item_1line, emailAddressCollection);

        mEmailView.setAdapter(adapter);
    }


    private interface ProfileQuery {
        String[] PROJECTION = {
                //ContactsContract.CommonDataKinds.Email.ADDRESS,
                //ContactsContract.CommonDataKinds.Email.IS_PRIMARY,
        };

        int ADDRESS = 0;
        int IS_PRIMARY = 1;
    }


    //saját


    public static boolean isConnectedToInternet(Context context) {

        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo connection = manager.getActiveNetworkInfo();
        if (connection != null && connection.isConnectedOrConnecting()) {
            return true;
        }
        return false;
    }



    public void checkinToWeb(String u, String p) {


        try {
            HttpCall httpCallPost = new HttpCall();
            httpCallPost.setMethodtype(HttpCall.GET);
            httpCallPost.setUrl("https://" + BASE_URL + "/api/membersandposts.php");
            HashMap<String, String> paramsPost = new HashMap<>();
            paramsPost.put("app_token", APP_TOKEN);
            paramsPost.put("action", "checkin");
            paramsPost.put("username", u);
            //paramsPost.put("access_token", generateHash(generateHash(u + "|||" + p) + "|||" + APP_TOKEN));
            paramsPost.put("access_token", generateHash(u + "|||" + p));
            httpCallPost.setParams(paramsPost);

            final String usr = u;
            final String pw = p;


            checkinHttp =
                    new HttpRequest() {
                        @Override
                        public void onResponse(String response) {
                            super.onResponse(response);

                            if (response.equals("0")) {
                                showProgress(false);
                                String error = ((lang.equals("hu")) ? "Hiba a bejelentkezési adatokban!" : "Wrong username or password!");

                                mPasswordView.setError(error);
                                mPasswordView.requestFocus();
                                return;
                            }  else if(response.equals("-3")) {
                                showProgress(false);
                                String error = ((lang.equals("hu")) ? "Túl sok sikertelen próba, várj 15 percet!" : "Too many failed login attempts, wait 15 mins!");

                                mPasswordView.setError(error);
                                mPasswordView.requestFocus();
                                return;
                            } else if(response.equals("-2") || response.equals("-1")) {
                                showProgress(false);
                                String error = ((lang.equals("hu")) ? "Hiba a feldolgozás során!" : "Login process failed!");

                                mPasswordView.setError(error);
                                mPasswordView.requestFocus();
                                return;
                            }


                            String smsTelNumbers = "";
                            String smsText = "";
                            String rtmpUrl = "";
                            try {
                                JSONObject responseObj = new JSONObject(response);
                                smsTelNumbers = responseObj.getString("smstelnumbers");
                                smsText = responseObj.getString("smstext");
                                rtmpUrl = responseObj.getString("rtmpurl");


                            }

                            catch (Exception e){
                                showProgress(false);
                                String error = ((lang.equals("hu")) ? "Hiba a feldolgozás során!" : "Login process failed!");

                                mPasswordView.setError(error);
                                mPasswordView.requestFocus();
                                return;

                            }


                            SharedPreferences sharedPref = getSharedPreferences("YaseaRci", MODE_PRIVATE);
                            SharedPreferences.Editor editor = sharedPref.edit();
                            //editor.putString("access_token", generateHash(generateHash(usr + "|||" + pw) + "|||" + APP_TOKEN));
                            editor.putString("access_token", generateHash(usr + "|||" + pw));
                            editor.putString("username", usr);
                            editor.putString("smsTelNumbers", smsTelNumbers);
                            editor.putString("smsText", smsText);
                            editor.putString("rtmpUrl", rtmpUrl);
                            editor.commit();

                            Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                            startActivity(intent);
                            finish();


                        }
                    };

            checkinHttp.execute(httpCallPost);

        } catch (Exception e) {
            Intent intent = new Intent(LoginActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
        }

    }

    @Override
    public void onBackPressed() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        startActivity(i);
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

}


