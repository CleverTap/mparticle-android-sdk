package com.mparticle;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.mparticle.Constants.MessageKey;
import com.mparticle.Constants.PrefKeys;
import com.mparticle.com.google.licensing.AESObfuscator;
import com.mparticle.com.google.licensing.LicenseChecker;
import com.mparticle.com.google.licensing.LicenseCheckerCallback;
import com.mparticle.com.google.licensing.Policy;
import com.mparticle.com.google.licensing.ServerManagedPolicy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Map;
import java.util.UUID;

/**
 * Class to provide thread-safe access to the mParticle API. In order to use this class, you must first call {@link #start(android.content.Context)}, which requires
 * configuration via {@link <a href="http://developer.android.com/guide/topics/resources/providing-resources.html">Android Resources</a>}. You can then retrieve a reference
 * to an instance of this class via {@link #getInstance()}
 *
 * It's recommended to keep configuration parameters in a single xml file located within your res/values folder. The full list of configuration options is as follows:
 * <p/>
 * <h4>Required parameters</h4>
 * <ul>
 * <li>mp_key - {@link <a href="http://developer.android.com/guide/topics/resources/string-resource.html#String">String</a>} - This is the key used to authenticate with the mParticle SDK server API</li>
 * <li>mp_secret - {@link <a href="http://developer.android.com/guide/topics/resources/string-resource.html#String">String</a>} - This is the secret used to authenticate with the mParticle SDK server API</li>
 * </ul>
 * <h4>Required for push notifications</h4>
 * <ul>
 * <li> mp_enablePush - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Bool">Bool</a>} - Enable push registration, notifications, and analytics. <i>Default: false</i></li>
 * <li> mp_pushSenderId - {@link <a href="http://developer.android.com/guide/topics/resources/string-resource.html#String">String</a>} - {@link <a href="http://developer.android.com/google/gcm/gcm.html#senderid">GCM Sender ID</a>}</li>
 * </ul>
 * <h4>Required for licensing</h4>
 * <ul>
 * <li> mp_enableLicenseCheck - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Bool">Bool</a>} - By enabling license check, MParticle will automatically validate that the app was downloaded and/or bought via Google Play, or if it was "pirated" or "side-loaded". <i>Default: false</i></li>
 * <li> mp_appLicenseKey - {@link <a href="http://developer.android.com/guide/topics/resources/string-resource.html#String">String</a>} - The {@link <a href="http://developer.android.com/google/play/licensing/adding-licensing.html#account-key">public key</a>} used by your app to verify the user's license with Google Play.</li>
 * </ul>
 * <h4>Optional</h4>
 * <ul>
 * <li>mp_productionUploadInterval - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Integer">Integer</a>} - The length of time in seconds to send batches of messages to mParticle. Setting this too low could have an adverse effect on the device battery. <i>Default: 600</i></li>
 * <li>mp_reportUncaughtExceptions - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Bool">Bool</a>} - By enabling this, the MParticle SDK will automatically log and report any uncaught exceptions, including stack traces. <i>Default: false</i></li>
 * <li>mp_sessionTimeout - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Integer">Integer</a>} - The length of time (in seconds) that a user session will remain valid while application has been paused and put into the background. <i>Default: 60</i></li>
 * <li>mp_enableDebugMode - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Bool">Bool</a>} - Enabling this will provide additional logcat messages to debug your implementation and usage of mParticle <i>Default: false</i></li>
 * <li>mp_debugUploadInterval - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Integer">Integer</a>} - The upload interval (see above) while in debug mode. <i>Default: 10</i></li>
 * <li>mp_enableSandboxMode - {@link <a href="http://developer.android.com/guide/topics/resources/more-resources.html#Bool">Bool</a>} - Enabling this will mark events as sandbox messages for debugging and isolation in the mParticle web application. <i>Default: false</i></li>
 * </ul>
 */
public class MParticle {
    private static final String TAG = Constants.LOG_TAG;
    private static final HandlerThread sTimeoutHandlerThread = new HandlerThread("mParticleSessionTimeoutHandler",
            Process.THREAD_PRIORITY_BACKGROUND);
    private static final byte[] SALT = new byte[]{
            -46, 65, 30, -128, -103, -57, 74, 10, 51, 88, -95, -45, -43, -117, -36, 99, -11, 32, -64,
            89
    };
    static Bundle lastNotificationBundle;
    static boolean appRunning;
    private static volatile MParticle instance;
    private static SharedPreferences sPreferences;
    final ConfigManager mConfigManager;
    AppStateManager mAppStateManager;
    /* package-private */ String mSessionID;
    /* package-private */ long mSessionStartTime = 0;
    /* package-private */ long mLastEventTime = 0;
    /* package-private */ JSONArray mUserIdentities = new JSONArray();
    /* package-private */ JSONObject mUserAttributes = new JSONObject();
    /* package-private */ JSONObject mSessionAttributes;
    /* package-private */ NotificationCompat.Builder customNotification;
    private MessageManager mMessageManager;
    private Handler mTimeoutHandler;
    private MParticleLocationListener mLocationListener;
    private ExceptionHandler mExHandler;
    private Context mAppContext;
    private String mApiKey;
    private boolean mDebugMode = false;
    //private int mSessionTimeout = 30 * 60 * 1000;
    private int mEventCount = 0;
    private String mLaunchUri;
    private LicenseCheckerCallback clientLicensingCallback;

    /* package-private */MParticle(Context context, MessageManager messageManager, ConfigManager configManager) {
        appRunning = true;
        mConfigManager = configManager;
        mAppContext = context.getApplicationContext();
        mApiKey = mConfigManager.getApiKey();
        mMessageManager = messageManager;
        mTimeoutHandler = new SessionTimeoutHandler(this, sTimeoutHandlerThread.getLooper());
        mAppStateManager = new AppStateManager(mAppContext);
        String userAttrs = sPreferences.getString(PrefKeys.USER_ATTRS + mApiKey, null);

        if (null != userAttrs) {
            try {
                mUserAttributes = new JSONObject(userAttrs);
            } catch (JSONException e) {
                // carry on without user attributes
            }
        }

        String userIds = sPreferences.getString(PrefKeys.USER_IDENTITIES + mApiKey, null);
        if (null != userIds) {
            try {
                mUserIdentities = new JSONArray(userIds);
            } catch (JSONException e) {
                // carry on without user identities
            }
        }

        if (!sPreferences.contains(PrefKeys.INSTALL_TIME)) {
            sPreferences.edit().putLong(PrefKeys.INSTALL_TIME, System.currentTimeMillis()).commit();
        }

        if (mConfigManager.getLogUnhandledExceptions()) {
            enableUncaughtExceptionLogging();
        }
        logStateTransition(Constants.StateTransitionType.STATE_TRANS_INIT);
    }

    /**
     * Start the mParticle SDK and begin tracking a user session. This method must be called prior to {@link #getInstance()}.
     * This method requires that your API key and secret are contained in your XML configuration.
     *
     * @param context Required reference to a Context object
     */

    public static void start(Context context){
        if (context == null) {
            throw new IllegalArgumentException("mParticle failed to start: context is required.");
        }
        MParticle.getInstance(context, null, null, false);
    }

    /**
     * Start the mParticle SDK and begin tracking a user session.
     *
     * @param context Required reference to a Context object
     * @param apiKey The API key to use for authentication with mParticle
     * @param secret The API secret to use for authentication with mParticle
     * @param sandboxMode
     */

    public static void start(Context context, String apiKey, String secret, boolean sandboxMode){
        if (context == null) {
            throw new IllegalArgumentException("mParticle failed to start: context is required.");
        }
        if (apiKey == null) {
            throw new IllegalArgumentException("mParticle failed to start: apiKey is required.");
        }
        if (secret == null) {
            throw new IllegalArgumentException("mParticle failed to start: secret is required.");
        }
        MParticle.getInstance(context, apiKey, secret, sandboxMode);
    }

    /**
     * Initialize or return a thread-safe instance of the mParticle SDK, specifying the API credentials to use. If this
     * or any other {@link #getInstance()} has already been called in the application's lifecycle, the
     * API credentials will be ignored and the current instance will be returned.
     *
     * @param context     the Activity that is creating the instance
     * @param apiKey      the API key for your account
     * @param secret      the API secret for your account
     * @param sandboxMode set the SDK in sandbox mode, xml configuration will override this value
     * @return An instance of the mParticle SDK configured with your API key
     */
    private static MParticle getInstance(Context context, String apiKey, String secret, boolean sandboxMode) {
        if (instance == null) {
            synchronized (MParticle.class) {
                if (instance == null) {
                    if (PackageManager.PERMISSION_DENIED == context
                            .checkCallingOrSelfPermission(android.Manifest.permission.INTERNET)) {
                        throw new IllegalArgumentException("mParticle requires android.permission.INTERNET permission");
                    }

                    if (!sTimeoutHandlerThread.isAlive()) {
                        sTimeoutHandlerThread.start();
                    }

                    if (null == sPreferences) {
                        sPreferences = context.getSharedPreferences(Constants.PREFS_FILE, Context.MODE_PRIVATE);
                    }

                    ConfigManager appConfigManager = new ConfigManager(context, apiKey, secret, sandboxMode);
                    Context appContext = context.getApplicationContext();

                    Boolean firstRun = sPreferences.getBoolean(PrefKeys.FIRSTRUN + appConfigManager.getApiKey(), true);
                    if (firstRun) {
                        sPreferences.edit().putBoolean(PrefKeys.FIRSTRUN + appConfigManager.getApiKey(), false).commit();
                    }

                    MessageManager messageManager = new MessageManager(appContext, appConfigManager);
                    messageManager.start(appContext, firstRun);

                    instance = new MParticle(appContext, messageManager, appConfigManager);
                    if (context instanceof Activity) {
                        instance.mLaunchUri = ((Activity) context).getIntent().getDataString();
                        if (instance.mLaunchUri != null) {
                            Log.d(TAG, "launchuri: " + instance.mLaunchUri);
                        }
                    }

                    if (appConfigManager.isPushEnabled()) {
                        instance.enablePushNotifications(appConfigManager.getPushSenderId());
                    }
                    if (appConfigManager.isLicensingEnabled()) {
                        instance.performLicenseCheck();
                    }


                }
            }
        }
        return instance;
    }

    /**
     * Retrieve an instance of the MParticle class. {@link #start(android.content.Context)} or {@link #start(android.content.Context, String, String, boolean)} must
     * be called prior to this or a {@link java.lang.IllegalStateException} will be thrown.
     *
     * @return An instance of the mParticle SDK configured with your API key
     */
    public static MParticle getInstance() {
        if (instance == null){
            throw new IllegalStateException("Failed to get MParticle instance, getInstance() called prior to start().");
        }
        return getInstance(null, null, null, false);
    }

    /* package-private */
    static boolean setCheckedAttribute(JSONObject attributes, String key, Object value) {
        if (null == attributes || null == key) {
            return false;
        }
        try {
            if (Constants.LIMIT_ATTR_COUNT == attributes.length() && !attributes.has(key)) {
                Log.w(TAG, "Attribute count exceeds limit. Discarding attribute: " + key);
                return false;
            }
            if (null != value && value.toString().length() > Constants.LIMIT_ATTR_VALUE) {
                Log.w(TAG, "Attribute value length exceeds limit. Discarding attribute: " + key);
                return false;
            }
            if (key.length() > Constants.LIMIT_ATTR_NAME) {
                Log.w(TAG, "Attribute name length exceeds limit. Discarding attribute: " + key);
                return false;
            }
            if (value == null) {
                value = JSONObject.NULL;
            }
            attributes.put(key, value);
        } catch (JSONException e) {
            Log.w(TAG, "JSON error processing attributes. Discarding attribute: " + key);
            return false;
        }
        return true;
    }

    void logStateTransition(String transitionType) {
        if (mConfigManager.getSendOoEvents()) {
            ensureActiveSession();
            mMessageManager.logStateTransition(transitionType, mSessionID, mSessionStartTime, lastNotificationBundle);
            if (Constants.StateTransitionType.STATE_TRANS_BG.equals(transitionType)) {
                lastNotificationBundle = null;
            }
        }
    }

    /**
     * Starts tracking a user session. If a session is already active, it will be resumed.
     * <p/>
     * This method should be called from an Activity's onStart() method.
     */
    public void activityStarted(Activity activity) {
        if (mConfigManager.getSendOoEvents()) {
            ensureActiveSession();
            mAppStateManager.onActivityStarted(activity);
        }
    }

    /**
     * Stop tracking a user session. If the session is restarted before the session timeout it will
     * be resumed.
     * <p/>
     * This method should be called from an Activity's onStop() method.
     * <p/>
     * To explicitly end a session use the endSession() method.
     */
    public void activityStopped(Activity activity) {
        if (mConfigManager.getSendOoEvents()) {
            ensureActiveSession();
            mAppStateManager.onActivityStopped(activity);
        }
    }

    /**
     * Begin tracking a new session. Ends the current session.
     */
    public void beginSession() {
        if (mConfigManager.getSendOoEvents()) {
            endSession();
            newSession();
        }
    }

    /**
     * Explicitly terminates the user session.
     */
    public void endSession() {
        if (mConfigManager.getSendOoEvents()) {
            long sessionEndTime = System.currentTimeMillis();
            endSession(sessionEndTime);
        }
    }

    /**
     * Explicitly end the session at the given time and generate the end-session message
     *
     * @param sessionEndTime
     */
    private void endSession(long sessionEndTime) {
        if (mDebugMode)
            debugLog("Ended session");

        mMessageManager.stopSession(mSessionID, sessionEndTime, sessionEndTime - mSessionStartTime);
        mMessageManager.endSession(mSessionID, sessionEndTime, sessionEndTime - mSessionStartTime);
        // reset agent to unstarted state
        mSessionStartTime = 0;
        mSessionID = "";
    }

    /**
     * Ensures a session is active.
     */
    private void ensureActiveSession() {
        //    checkSessionTimeout();
        mLastEventTime = System.currentTimeMillis();
        if (0 == mSessionStartTime) {
            newSession();
        }
    }

    /**
     * Check current session timeout and end the session if needed. Will not start a new session.
     */
    /* package-private */boolean checkSessionTimeout() {
        long now = System.currentTimeMillis();
        if (0 != mSessionStartTime &&
                mAppStateManager.isBackgrounded() &&
                (mConfigManager.getSessionTimeout() < now - mLastEventTime)) {
            if (mDebugMode)
                debugLog("Session timed out");

            endSession(mLastEventTime);
            return true;
        }
        return false;
    }

    /**
     * Creates a new session and generates the start-session message.
     */
    private void newSession() {
        endSession();
        mLastEventTime = mSessionStartTime = System.currentTimeMillis();
        mSessionID = UUID.randomUUID().toString();
        mEventCount = 0;
        mSessionAttributes = new JSONObject();
        mMessageManager.startSession(mSessionID, mSessionStartTime, mLaunchUri);
        mTimeoutHandler.sendEmptyMessageDelayed(0, mConfigManager.getSessionTimeout());
        if (mDebugMode)
            debugLog("Started new session");
        // clear the launch URI so it isn't sent on future sessions
        mLaunchUri = null;
    }

    /**
     * Upload queued messages to the mParticle server.
     */
    public void upload() {
        mMessageManager.doUpload();
    }

    /**
     * Manually set the install referrer
     */
    public void setInstallReferrer(String referrer) {
        sPreferences.edit().putString(PrefKeys.INSTALL_REFERRER, referrer).commit();
        if (mDebugMode)
            debugLog("Set installReferrer: " + referrer);
    }

    /**
     * Log an event
     *
     * @param eventName the name of the event to be tracked
     * @param eventType the type of the event to be tracked
     */
    public void logEvent(String eventName, EventType eventType) {
        logEvent(eventName, eventType, null);
    }

    /**
     * Log an event
     *
     * @param eventName   the name of the event to be tracked
     * @param eventType   the type of the event to be tracked
     * @param eventLength the duration of the event in milliseconds
     */
    public void logEvent(String eventName, EventType eventType, long eventLength) {
        logEvent(eventName, eventType, null, eventLength);
    }

    /**
     * Log an event with data attributes
     *
     * @param eventName the name of the event to be tracked
     * @param eventType the type of the event to be tracked
     * @param eventInfo a Map of data attributes
     */
    public void logEvent(String eventName, EventType eventType, Map<String, String> eventInfo) {
        logEvent(eventName, eventType, eventInfo, 0);
    }

    /**
     * Log an event with data attributes
     *
     * @param eventName   the name of the event to be tracked
     * @param eventType   the type of the event to be tracked
     * @param eventInfo   a Map of data attributes
     * @param eventLength the duration of the event in milliseconds
     */
    public void logEvent(String eventName, EventType eventType, Map<String, String> eventInfo, long eventLength) {
        if (mConfigManager.getSendOoEvents()) {
            if (null == eventName) {
                Log.w(TAG, "eventName is required for logEvent");
                return;
            }
            if (eventName.length() > Constants.LIMIT_NAME) {
                Log.w(TAG, "The event name was too long. Discarding event.");
                return;
            }
            ensureActiveSession();
            if (checkEventLimit()) {
                JSONObject eventDataJSON = enforceAttributeConstraints(eventInfo);
                mMessageManager.logEvent(mSessionID, mSessionStartTime, mLastEventTime, eventName, eventType, eventDataJSON, eventLength);
                if (mDebugMode)
                    if (null == eventDataJSON) {
                        debugLog("Logged event: " + eventName);
                    } else {
                        debugLog("Logged event: " + eventName + " with data " + eventDataJSON);
                    }
            }
        }
    }

    public void logTransaction(MPTransaction transaction) {
        if (mConfigManager.getSendOoEvents()) {
            if (transaction == null) {
                throw new IllegalArgumentException("transaction is required for logTransaction");
            }

            if (transaction.getData() == null) {
                throw new IllegalArgumentException("Transaction data was null, please check that the transaction was built properly.");
            }

            ensureActiveSession();
            if (checkEventLimit()) {
                mMessageManager.logEvent(mSessionID, mSessionStartTime, mLastEventTime, "Ecommerce", EventType.Transaction, transaction.getData(), 0);
                if (mDebugMode) {
                    try {
                        debugLog("Logged transaction with data: " + transaction.getData().toString(4));
                    } catch (JSONException jse) {

                    }
                }
            }
        }
    }

    void logScreen(String screenName, Map<String, String> eventData, boolean started) {
        if (mConfigManager.getSendOoEvents()) {
            if (null == screenName) {
                Log.w(TAG, "screenName is required for logScreenView");
                return;
            }
            if (screenName.length() > Constants.LIMIT_NAME) {
                Log.w(TAG, "The screen name was too long. Discarding event.");
                return;
            }
            ensureActiveSession();
            if (checkEventLimit()) {
                JSONObject eventDataJSON = enforceAttributeConstraints(eventData);
                mMessageManager.logScreen(mSessionID, mSessionStartTime, mLastEventTime, screenName, eventDataJSON, started);
                if (mDebugMode)
                    if (null == eventDataJSON) {
                        debugLog("Logged screen: " + screenName);
                    } else {
                        debugLog("Logged screen: " + screenName + " with data " + eventDataJSON);
                    }
            }
        }
    }

    /**
     * Log a screen view event
     *
     * @param screenName the name of the screen to be tracked
     */
    public void logScreen(String screenName) {
        logScreen(screenName, null);
    }

    /**
     * Log a screen view event with data attributes
     *
     * @param screenName the name of the screen to be tracked
     * @param eventData  a Map of data attributes
     */
    public void logScreen(String screenName, Map<String, String> eventData) {
        logScreen(screenName, eventData, true);
    }

    /**
     * Log an error event with just a Message
     *
     * @param message the name of the error event to be tracked
     */
    public void logError(String message) {
        logError(message, null);
    }

    /**
     * Log an error event with a Message, Exception, and any additional data
     *
     * @param message   the name of the error event to be tracked
     * @param eventData a Map of data attributes
     */
    public void logError(String message, Map<String, String> eventData) {
        if (mConfigManager.getSendOoEvents()) {
            if (null == message) {
                Log.w(TAG, "message is required for logErrorEvent");
                return;
            }
            ensureActiveSession();
            if (checkEventLimit()) {
                JSONObject eventDataJSON = enforceAttributeConstraints(eventData);
                mMessageManager.logErrorEvent(mSessionID, mSessionStartTime, mLastEventTime, message, null, eventDataJSON);
                if (mDebugMode)
                    debugLog(
                            "Logged error with message: " + (message == null ? "<none>" : message) +
                                    " with data: " + (eventDataJSON == null ? "<none>" : eventDataJSON.toString()));
            }
        }
    }

    /**
     * Log an error event with just an Exception
     *
     * @param exception an Exception
     */
    public void logException(Exception exception) {
        logException(exception, null, null);
    }

    /**
     * Log an error event with an Exception and any additional data
     *
     * @param exception an Exception
     * @param eventData a Map of data attributes
     */
    public void logException(Exception exception, Map<String, String> eventData) {
        logException(exception, eventData, null);
    }

    /**
     * Log an error event with a Message, Exception, and any additional data
     *
     * @param exception an Exception
     * @param eventData a Map of data attributes
     * @param message   the name of the error event to be tracked
     */
    public void logException(Exception exception, Map<String, String> eventData, String message) {
        if (mConfigManager.getSendOoEvents()) {
            if (null == message) {
                Log.w(TAG, "message is required for logErrorEvent");
                return;
            }
            ensureActiveSession();
            if (checkEventLimit()) {
                JSONObject eventDataJSON = enforceAttributeConstraints(eventData);
                mMessageManager.logErrorEvent(mSessionID, mSessionStartTime, mLastEventTime, message, exception, eventDataJSON);
                if (mDebugMode)
                    debugLog(
                            "Logged exception with message: " + (message == null ? "<none>" : message) +
                                    " with data: " + (eventDataJSON == null ? "<none>" : eventDataJSON.toString()) +
                                    " with exception: " + (exception == null ? "<none>" : exception.getMessage()));
            }
        }
    }

    void logUnhandledError(Throwable t) {
        if (mConfigManager.getSendOoEvents()) {
            ensureActiveSession();
            mMessageManager.logErrorEvent(mSessionID, mSessionStartTime, mLastEventTime, t != null ? t.getMessage() : null, t, null, false);
            //we know that the app is about to crash and therefore exit
            logStateTransition(Constants.StateTransitionType.STATE_TRANS_EXIT);
            endSession(System.currentTimeMillis());
        }
    }

    /**
     * Enables location tracking given a provider and update frequency criteria. The provider must
     * be available and the correct permissions must have been requested during installation.
     *
     * @param provider    the provider key
     * @param minTime     the minimum time (in milliseconds) to trigger an update
     * @param minDistance the minimum distance (in meters) to trigger an update
     */
    public void enableLocationTracking(String provider, long minTime, long minDistance) {
        if (mConfigManager.getSendOoEvents()) {
            try {
                LocationManager locationManager = (LocationManager) mAppContext.getSystemService(Context.LOCATION_SERVICE);
                if (!locationManager.isProviderEnabled(provider)) {
                    Log.w(TAG, "That requested location provider is not available");
                    return;
                }

                if (null == mLocationListener) {
                    mLocationListener = new MParticleLocationListener(this);
                } else {
                    // clear the location listener, so it can be added again
                    locationManager.removeUpdates(mLocationListener);
                }
                locationManager.requestLocationUpdates(provider, minTime, minDistance, mLocationListener);
            } catch (SecurityException e) {
                Log.w(TAG, "The app must require the appropriate permissions to track location using this provider");
            }
        }
    }

    /**
     * Disables any mParticle location tracking that had been started
     */
    public void disableLocationTracking() {
        if (null != mLocationListener) {
            LocationManager locationManager = (LocationManager) mAppContext.getSystemService(Context.LOCATION_SERVICE);
            locationManager.removeUpdates(mLocationListener);
            mLocationListener = null;
        }
    }

    /**
     * Set the current location of the active session.
     *
     * @param location
     */
    public void setLocation(Location location) {
        mMessageManager.setLocation(location);
    }

    /**
     * Set a single session attribute. The attribute will combined with any existing attributes.
     *
     * @param key   the attribute key
     * @param value the attribute value
     */
    public void setSessionAttribute(String key, String value) {
        if (mConfigManager.getSendOoEvents()) {
            ensureActiveSession();
            if (mDebugMode)
                debugLog("Set session attribute: " + key + "=" + value);
            if (setCheckedAttribute(mSessionAttributes, key, value)) {
                mMessageManager.setSessionAttributes(mSessionID, mSessionAttributes);
            }
        }
    }

    /**
     * Set a single user attribute. The attribute will combined with any existing attributes.
     *
     * @param key   the attribute key
     * @param value the attribute value
     */
    public void setUserAttribute(String key, String value) {
        if (mConfigManager.getSendOoEvents()) {
            if (mDebugMode)
                if (value != null) {
                    debugLog("Set user attribute: " + key + " with value " + value);
                } else {
                    debugLog("Set user attribute: " + key);
                }
            if (setCheckedAttribute(mUserAttributes, key, value)) {
                sPreferences.edit().putString(PrefKeys.USER_ATTRS + mApiKey, mUserAttributes.toString()).commit();
            }
        }
    }

    /**
     * Set a single user tag, it will be combined with any existing tags.
     *
     * @param tag a tag assigned to a user
     */
    public void setUserTag(String tag) {
        setUserAttribute(tag, null);
    }

    public void setUserIdentity(String id, IdentityType identityType) {
        if (mConfigManager.getSendOoEvents()) {
            if (mDebugMode)
                debugLog("Setting user identity: " + id);

            if (null != id && id.length() > Constants.LIMIT_ATTR_VALUE) {
                Log.w(TAG, "Id value length exceeds limit. Discarding id: " + id);
                return;
            }

            try {
                JSONObject identity = new JSONObject();
                identity.put(MessageKey.IDENTITY_NAME, identityType.value);
                identity.put(MessageKey.IDENTITY_VALUE, id);

                // verify there is not another IDENTITY_VALUE...if so, remove it first...to do this, copy the
                //   existing array to a new one
                JSONArray newUserIdentities = new JSONArray();

                for (int i = 0; i < mUserIdentities.length(); i++) {
                    JSONObject testid = mUserIdentities.getJSONObject(i);
                    if (testid.get(MessageKey.IDENTITY_NAME).equals(identityType.value)) {
                        // remove this one by not copying it
                        continue;
                    }
                    newUserIdentities.put(testid);
                }
                // now add this one...only if the id is not null
                if ((id != null) && (id.length() > 0)) {
                    newUserIdentities.put(identity);
                }
                // now make the new array the saved one
                mUserIdentities = newUserIdentities;
            } catch (JSONException e) {
                Log.w(TAG, "Error setting identity: " + id);
                return;
            }

            sPreferences.edit().putString(PrefKeys.USER_IDENTITIES + mApiKey, mUserIdentities.toString()).commit();
        }
    }

    /* package-private */void clearUserAttributes() {
        mUserAttributes = new JSONObject();
        sPreferences.edit().putString(PrefKeys.USER_ATTRS + mApiKey, mUserAttributes.toString()).commit();
    }

    /**
     * Get the current opt-out status for the application.
     *
     * @return the opt-out status
     */
    public boolean getOptOut() {
        return mConfigManager.getOptedOut();
    }

    /**
     * Control the opt-in/opt-out status for the application.
     *
     * @param optOutStatus set to <code>true</code> to opt out of event tracking
     */
    public void setOptOut(boolean optOutStatus) {
        if (optOutStatus != mConfigManager.getOptedOut()) {
            if (!optOutStatus) {
                ensureActiveSession();
            }
            mMessageManager.optOut(mSessionID, mSessionStartTime, System.currentTimeMillis(), optOutStatus);
            if (optOutStatus && mSessionStartTime > 0) {
                endSession();
            }

            mConfigManager.setOptOut(optOutStatus);

            if (mDebugMode)
                debugLog("Set opt-out: " + optOutStatus);
        }
    }

    /**
     * Turn on or off debug mode for mParticle. In debug mode, the mParticle SDK will output
     * informational messages to LogCat.
     *
     * @param debugMode
     */
    public void setDebugMode(boolean debugMode) {
        mConfigManager.setDebug(debugMode);
    }

    public boolean getDebugMode() {
        return mConfigManager.isDebug();
    }

    /**
     * Set the upload interval period to control how frequently uploads occur.
     *
     * @param uploadInterval the number of seconds between uploads
     */
    public void setUploadInterval(int uploadInterval) {
        mConfigManager.setUploadInterval(uploadInterval);
    }

    /**
     * Set the upload interval period to control how frequently uploads occur when in debug mode.
     *
     * @param uploadInterval the number of seconds between uploads
     */
    public void setDebugUploadInterval(int uploadInterval) {
        mConfigManager.setDebugUploadInterval(uploadInterval);
    }

    /**
     * Enable mParticle exception handling to automatically log events on uncaught exceptions
     */
    public void enableUncaughtExceptionLogging() {
        if (null == mExHandler) {
            UncaughtExceptionHandler currentUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
            if (!(currentUncaughtExceptionHandler instanceof ExceptionHandler)) {
                mExHandler = new ExceptionHandler(mMessageManager, currentUncaughtExceptionHandler);
                Thread.setDefaultUncaughtExceptionHandler(mExHandler);
            }
        }
    }

    /**
     * Disables mParticle exception handling and restores the original UncaughtExceptionHandler
     */
    public void disableUncaughtExceptionLogging() {
        if (null != mExHandler) {
            UncaughtExceptionHandler currentUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
            if (currentUncaughtExceptionHandler instanceof ExceptionHandler) {
                Thread.setDefaultUncaughtExceptionHandler(mExHandler.getOriginalExceptionHandler());
                mExHandler = null;
            }
        }
    }

    /**
     * Register the application for GCM notifications
     *
     * @param senderId the SENDER_ID for the application
     */
    public void enablePushNotifications(String senderId) {
        mConfigManager.setPushSenderId(senderId);
        PushRegistrationHelper.enablePushNotifications(mAppContext, senderId);
    }

    /**
     * Unregister the application for GCM notifications
     */
    public void disablePushNotifications() {
        PushRegistrationHelper.disablePushNotifications(mAppContext);
    }

    /**
     * Enable the default notification sound for push notifications
     *
     * @param enabled
     */
    public void setNotificationSoundEnabled(boolean enabled) {
        mConfigManager.setPushSoundEnabled(enabled);
    }

    /**
     * Enable the default notification vibration for push notifications
     *
     * @param enabled
     */
    public void setNotificationVibrationEnabled(boolean enabled) {
        mConfigManager.setPushVibrationEnabled(enabled);
    }

    /**
     * Set a custom notification style to use for push notification. This will override
     * the values set by the {@link #setNotificationSoundEnabled(boolean)}
     * and the {@link #setNotificationVibrationEnabled(boolean)}
     * methods.
     *
     * @param notification
     * @see <a href="http://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html">NotificationCompat.Builder</a>
     */
    public void setCustomPushNotification(NotificationCompat.Builder notification) {
        customNotification = notification;
    }

    void clearPushNotificationId() {
        PushRegistrationHelper.clearPushRegistrationId(mAppContext, registrationListener);
    }

    void setPushRegistrationId(String registrationId) {
        PushRegistrationHelper.storeRegistrationId(mAppContext, registrationId, registrationListener);
    }

    /**
     * This method checks the event count is below the limit and increments the event count. A
     * warning is logged if the limit has been reached.
     *
     * @return true if event count is below limit
     */
    private boolean checkEventLimit() {
        if (mEventCount < Constants.EVENT_LIMIT) {
            mEventCount++;
            return true;
        } else {
            Log.w(TAG, "The event limit has been exceeded for this session.");
            return false;
        }
    }

    /**
     * This method makes sure the constraints on event attributes are enforced. A JSONObject version
     * of the attributes is return with data that exceeds the limits removed. NOTE: Non-string
     * attributes are not converted to strings, currently.
     *
     * @param attributes the user-provided JSONObject
     * @return a cleansed copy of the JSONObject
     */
    /* package-private */JSONObject enforceAttributeConstraints(Map<String, String> attributes) {
        if (null == attributes) {
            return null;
        }
        JSONObject checkedAttributes = new JSONObject();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            setCheckedAttribute(checkedAttributes, key, value);
        }
        return checkedAttributes;
    }

    void performLicenseCheck() {
        String deviceId = Settings.Secure.getString(mAppContext.getContentResolver(), Settings.Secure.ANDROID_ID);

        MPLicenseCheckerCallback licenseCheckerCallback = new MPLicenseCheckerCallback();

        LicenseChecker checker = new LicenseChecker(
                mAppContext, new ServerManagedPolicy(mAppContext,
                new AESObfuscator(SALT, mAppContext.getPackageName(), deviceId)),
                mConfigManager.getLicenseKey());
        checker.checkAccess(licenseCheckerCallback);
    }

    /**
     * This method makes sure the constraints on event attributes are enforced. A JSONObject version
     * of the attributes is return with data that exceeds the limits removed. NOTE: Non-string
     * attributes are not converted to strings, currently.
     *
     * @param encodedPublicKey  Base64-encoded RSA public key of your application
     * @param policy            <b>Optional</b> {@link com.mparticle.com.google.licensing.Policy}, will default to {@link com.mparticle.com.google.licensing.ServerManagedPolicy}
     * @param licensingCallback <b>Optional</b> {@link com.mparticle.com.google.licensing.LicenseCheckerCallback} callback for licensing checking
     */
    public void performLicenseCheck(String encodedPublicKey, Policy policy, LicenseCheckerCallback licensingCallback) {
        if (encodedPublicKey == null || encodedPublicKey.length() == 0) {
            throw new IllegalArgumentException("LicenseKey null or invalid.");
        }

        if (licensingCallback == null) {
            Log.w(TAG, "No licensing callback specified, using MParticle default.");
        }

        clientLicensingCallback = licensingCallback;

        if (policy == null) {
            Log.w(TAG, "No policy specified, using default ServerManagedPolicy");
            String deviceId = Settings.Secure.getString(mAppContext.getContentResolver(), Settings.Secure.ANDROID_ID);
            policy = new ServerManagedPolicy(mAppContext,
                    new AESObfuscator(SALT, mAppContext.getPackageName(), deviceId));
        }

        MPLicenseCheckerCallback licenseCheckerCallback = new MPLicenseCheckerCallback();
        LicenseChecker checker = new LicenseChecker(
                mAppContext, policy, encodedPublicKey);
        checker.checkAccess(licenseCheckerCallback);
    }

    private void debugLog(String message) {
        if (null != mSessionID) {
            Log.d(TAG, mApiKey + ": " + mSessionID + ": " + message);
        } else {
            Log.d(TAG, mApiKey + ": " + message);
        }
    }

    public boolean isAutoTrackingEnabled() {
        return mConfigManager.isAutoTrackingEnabled();
    }

    public long getSessionTimeout() {
        return mConfigManager.getSessionTimeout();
    }

    /**
     * Set the user session timeout interval.
     * <p/>
     * A session is ended when no events (logged events or start/stop events) has occurred within
     * the session timeout interval.
     *
     * @param sessionTimeout
     */
    public void setSessionTimeout(int sessionTimeout) {
        mConfigManager.setSessionTimeout(sessionTimeout);

    }

    void logNotification(Intent intent) {
        if (mConfigManager.getSendOoEvents()) {
            lastNotificationBundle = intent.getExtras().getBundle(MessageKey.PAYLOAD);
            ensureActiveSession();
            mMessageManager.logNotification(mSessionID, mSessionStartTime, lastNotificationBundle, intent.getExtras().getString(MessageKey.APP_STATE));
        }
    }

    public enum EventType {
        Unknown, Navigation, Location, Search, Transaction, UserContent, UserPreference, Social, Other;

        public String toString() {
            return name();
        }
    }

    public enum IdentityType {
        Other(0),
        CustomId(1),
        Facebook(2),
        Twitter(3),
        Google(4),
        Microsoft(5),
        Yahoo(6),
        Email(7);

        private final int value;

        private IdentityType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    private static final class SessionTimeoutHandler extends Handler {
        private final MParticle mParticle;

        public SessionTimeoutHandler(MParticle mParticle, Looper looper) {
            super(looper);
            this.mParticle = mParticle;
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (!mParticle.checkSessionTimeout()) {
                sendEmptyMessageDelayed(0, mParticle.getSessionTimeout());
            }
        }
    }

    private static final class MParticleLocationListener implements LocationListener {
        private final MParticle mParticle;

        public MParticleLocationListener(MParticle mParticle) {
            this.mParticle = mParticle;
        }

        @Override
        public void onLocationChanged(Location location) {
            mParticle.setLocation(location);
        }

        @Override
        public void onProviderDisabled(String provider) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

    }

    private class MPLicenseCheckerCallback implements LicenseCheckerCallback {
        public void allow(int policyReason) {
            if (policyReason == Policy.LICENSED) {
                sPreferences.edit().putBoolean(PrefKeys.PIRATED, false).commit();
            }
            if (clientLicensingCallback != null) {
                clientLicensingCallback.allow(policyReason);
            }
        }

        public void dontAllow(int policyReason) {
            if (policyReason == ServerManagedPolicy.NOT_LICENSED) {
                sPreferences.edit().putBoolean(PrefKeys.PIRATED, true).commit();
            }
            if (clientLicensingCallback != null) {
                clientLicensingCallback.dontAllow(policyReason);
            }
        }

        public void applicationError(int errorCode) {
            if (errorCode == LicenseCheckerCallback.ERROR_MISSING_PERMISSION) {
                Log.w(TAG, "Licensing enabled but app is missing com.android.vending.CHECK_LICENSE permission.");
            }
            if (clientLicensingCallback != null) {
                clientLicensingCallback.applicationError(errorCode);
            }
        }
    }

    private PushRegistrationListener registrationListener = new PushRegistrationListener() {

        @Override
        public void onRegistered(String regId) {
            mMessageManager.setPushRegistrationId(mSessionID, mSessionStartTime, System.currentTimeMillis(), regId, true);
        }

        @Override
        public void onCleared(String regId) {
            mMessageManager.setPushRegistrationId(mSessionID, mSessionStartTime, System.currentTimeMillis(), null, true);
        }
    };

}
