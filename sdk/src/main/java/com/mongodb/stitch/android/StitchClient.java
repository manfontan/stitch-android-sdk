package com.mongodb.stitch.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.support.annotation.NonNull;
import android.util.Log;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.mongodb.stitch.android.auth.Auth;
import com.mongodb.stitch.android.auth.AuthProvider;
import com.mongodb.stitch.android.auth.AvailableAuthProviders;
import com.mongodb.stitch.android.auth.UserProfile;
import com.mongodb.stitch.android.auth.emailpass.EmailPasswordAuthProvider;
import com.mongodb.stitch.android.auth.emailpass.EmailPasswordAuthProviderInfo;
import com.mongodb.stitch.android.auth.RefreshTokenHolder;
import com.mongodb.stitch.android.auth.anonymous.AnonymousAuthProviderInfo;
import com.mongodb.stitch.android.auth.oauth2.facebook.FacebookAuthProviderInfo;
import com.mongodb.stitch.android.auth.oauth2.google.GoogleAuthProviderInfo;
import com.mongodb.stitch.android.http.Headers;
import com.mongodb.stitch.android.http.Volley.AuthenticatedJsonStringRequest;
import com.mongodb.stitch.android.http.Volley.JsonStringRequest;
import com.mongodb.stitch.android.push.AvailablePushProviders;
import com.mongodb.stitch.android.push.PushClient;
import com.mongodb.stitch.android.push.PushManager;

import org.bson.Document;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import static com.mongodb.stitch.android.StitchError.ErrorCode;
import static com.mongodb.stitch.android.StitchError.parseRequestError;
import static com.mongodb.stitch.android.StitchException.StitchAuthException;
import static com.mongodb.stitch.android.http.Headers.GetAuthorizationBearer;

/**
 * A StitchClient is responsible for handling the overall interaction with all Stitch services.
 */
public class StitchClient {

    private static final String PLATFORM = "android";
    private static final String TAG = "Stitch";
    private static final String DEFAULT_BASE_URL = "https://stitch.mongodb.com";

    // Properties
    private static final String STITCH_PROPERTIES_FILE_NAME = "stitch.properties";
    private static final String PROP_APP_ID = "appId";
    private static final String PROP_BASE_URL = "baseUrl";

    // Preferences
    private static final String SHARED_PREFERENCES_NAME = "com.mongodb.stitch.sdk.SharedPreferences.%s";
    private static final String PREF_AUTH_JWT_NAME = "auth_token";
    private static final String PREF_AUTH_REFRESH_TOKEN_NAME = "refresh_token";
    private static final String PREF_DEVICE_ID_NAME = "deviceId";
    private final Properties _properties;

    // Members
    private final Context _context;
    private final String _baseUrl;
    private final String _clientAppId;
    private final RequestQueue _queue;
    private final ObjectMapper _objMapper;
    private final SharedPreferences _preferences;
    private final PushManager _pushManager;
    private final List<AuthListener> _authListeners;

    private Auth _auth;
    private UserProfile _userProfile;

    /**
     * @param context     The Android {@link Context} that this client should be bound to.
     * @param clientAppId The App ID for the Stitch app.
     * @param baseUrl     The base URL of the Stitch Client API server.
     */
    public StitchClient(final Context context, final String clientAppId, final String baseUrl) {
        _context = context;
        _queue = Volley.newRequestQueue(context);
        _objMapper = CustomObjectMapper.createObjectMapper();

        final String prefPath = String.format(SHARED_PREFERENCES_NAME, clientAppId);
        _preferences = context.getSharedPreferences(prefPath, Context.MODE_PRIVATE);
        _authListeners = new ArrayList<>();
        _pushManager = new PushManager(context, this);

        // Only attempt to load properties
        _properties = new Properties();
        try {
            final InputStream propInput = context.getAssets().open(STITCH_PROPERTIES_FILE_NAME);
            _properties.load(propInput);
        } catch (final IOException ignored) {
        }

        if (clientAppId != null) {
            _clientAppId = clientAppId;
        } else {
            if (!_properties.containsKey(PROP_APP_ID)) {
                throw new StitchException.StitchClientException("No App ID in properties");
            }
            _clientAppId = _properties.getProperty(PROP_APP_ID);
        }

        if (baseUrl != null) {
            _baseUrl = baseUrl;
        } else if (!_properties.containsKey(PROP_BASE_URL)) {
            _baseUrl = DEFAULT_BASE_URL;
        } else {
            _baseUrl = _properties.getProperty(PROP_BASE_URL);
        }
    }

    /**
     * @param context     The Android {@link Context} that this client should be bound to.
     * @param clientAppId The App ID for the Stitch app.
     */
    public StitchClient(final Context context, final String clientAppId) {
        this(context, clientAppId, DEFAULT_BASE_URL);
    }

    /**
     * @param context The Android {@link Context} that this client should be bound to.
     * @return A client derived from the properties file.
     */
    public static StitchClient fromProperties(final Context context) {
        return new StitchClient(context, null, null);
    }

    // Public Methods

    // General Methods

    /**
     * @return The client's App ID
     */
    public String getAppId() {
        return _clientAppId;
    }

    /**
     * @return The Android {@link Context} that this client is bound to.
     */
    public Context getContext() {
        return _context;
    }

    // Auth Methods

    /**
     * Gets the currently authenticated user. Must only be used when the client has been
     * previously authenticated.
     *
     * @return The currently Authenticated user.
     */
    public Auth getAuth() {
        if (!isAuthenticated()) {
            throw new StitchAuthException("Must first authenticate");
        }
        return _auth;
    }

    /**
     * @return Whether or not the client is authenticated.
     */
    public boolean isAuthenticated() {
        if (_auth != null) {
            return true;
        }

        if (_preferences.contains(PREF_AUTH_JWT_NAME)) {
            try {
                _auth = _objMapper.readValue(_preferences.getString(PREF_AUTH_JWT_NAME, ""), Auth.class);
            } catch (final IOException e) {
                throw new StitchException(e);
            }
            onLogin();
            return true;
        }

        return false;
    }

    /**
     * Logs out the current user.
     *
     * @return A task that can be resolved upon completion of logout.
     */
    public Task<Void> logout() {
        if (!isAuthenticated()) {
            return Tasks.forResult(null);
        }
        return executeRequest(Request.Method.DELETE, Paths.AUTH, null, false, true).continueWith(new Continuation<String, Void>() {
            @Override
            public Void then(@NonNull final Task<String> task) throws Exception {
                if (task.isSuccessful()) {
                    clearAuth();
                    return null;
                }
                throw task.getException();
            }
        });
    }

    /**
     * Fetch the current user profile
     * @return profile of the given user
     */
    public Task<UserProfile> getUserProfile() {
        if (!isAuthenticated()) {
            Log.d(TAG, "Must log in before fetching user profile");
            return Tasks.forException(
                    new StitchAuthException("Must log in before fetching user profile")
            );
        }

        return executeRequest(Request.Method.GET, Paths.USER_PROFILE).continueWith(new Continuation<String, UserProfile>() {
            @Override
            public UserProfile then(@NonNull Task<String> task) throws Exception {
                if (!task.isSuccessful()) {
                    throw task.getException();
                }

                try {
                    _userProfile = _objMapper.readValue(task.getResult(), UserProfile.class);
                } catch (final IOException e) {
                    Log.e(TAG, "Error parsing user response", e);
                    throw e;
                }

                return _userProfile;
            }
        });
    }

    /**
     * Logs the current user in using a specific auth provider.
     *
     * @param authProvider The provider that will handle the login.
     * @return A task containing an {@link Auth} session that can be resolved on completion of log in.
     */
    public Task<Auth> logInWithProvider(AuthProvider authProvider) {

        if (isAuthenticated()) {
            Log.d(TAG, "Already logged in. Returning cached token");
            return Tasks.forResult(_auth);
        }

        final TaskCompletionSource<Auth> future = new TaskCompletionSource<>();
        final String url = String.format(
                "%s/%s/%s",
                getResourcePath(Paths.AUTH),
                authProvider.getType(),
                authProvider.getName());

        final JsonStringRequest request = new JsonStringRequest(
                Request.Method.POST,
                url,
                getAuthRequest(authProvider).toJson(),
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(final String response) {
                        try {
                            _auth = _objMapper.readValue(response, Auth.class);
                            final RefreshTokenHolder refreshToken =
                                    _objMapper.readValue(response, RefreshTokenHolder.class);
                            _preferences.edit().putString(PREF_AUTH_JWT_NAME, response).apply();
                            _preferences.edit().putString(PREF_AUTH_REFRESH_TOKEN_NAME, refreshToken.getToken()).apply();
                            _preferences.edit().putString(PREF_DEVICE_ID_NAME, _auth.getDeviceId()).apply();
                            future.setResult(_auth);
                            onLogin();
                        } catch (final IOException e) {
                            Log.e(TAG, "Error parsing auth response", e);
                            future.setException(new StitchException(e));
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(final VolleyError error) {
                        Log.e(TAG, "Error while logging in with auth provider", error);
                        future.setException(parseRequestError(error));
                    }
                });
        request.setTag(this);
        _queue.add(request);

        return future.getTask();
    }

    /**
     * Registers the current user using email and password.
     *
     * @param email    email for the given user
     * @param password password for the given user
     * @return A task containing whether or not registration was successful.
     */
    public Task<Boolean> register(@NonNull String email, @NonNull String password) {
        final EmailPasswordAuthProvider provider = new EmailPasswordAuthProvider(email, password);

        final TaskCompletionSource<Boolean> future = new TaskCompletionSource<>();
        final String url = String.format(
                "%s/%s/%s",
                getResourcePath(Paths.AUTH),
                provider.getType(),
                Paths.USERPASS_REGISTER
        );

        final JsonStringRequest request = new JsonStringRequest(
                Request.Method.POST,
                url,
                getAuthRequest(provider.getRegistrationPayload()).toJson(),
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(final String response) {
                        future.setResult(response != null);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(final VolleyError error) {
                        Log.e(TAG, "Error while logging in with auth provider", error);
                        future.setException(parseRequestError(error));
                    }
                }
        );

        request.setTag(this);
        _queue.add(request);

        return future.getTask();
    }

    /**
     * Confirm a newly registered email in this context
     * @param token confirmation token emailed to new user
     * @param tokenId confirmation tokenId emailed to new user
     * @return A task containing whether or not the email was confirmed successfully
     */
    public Task<Boolean> emailConfirm(@NonNull final String token, @NonNull final String tokenId) {
        final TaskCompletionSource<Boolean> future = new TaskCompletionSource<>();

        final String url = String.format(
                "%s/%s/%s",
                getResourcePath(Paths.AUTH),
                "",
                Paths.USERPASS_CONFIRM
        );

        final Document params = new Document();

        params.put("token", token);
        params.put("tokenId", tokenId);

        final JsonStringRequest request = new JsonStringRequest(
                Request.Method.POST,
                url,
                params.toJson(),
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(final String response) {
                        future.setResult(response != null);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(final VolleyError error) {
                        Log.e(TAG, "Error while confirming email", error);
                        future.setException(parseRequestError(error));
                    }
                }
        );

        request.setTag(this);
        _queue.add(request);

        return future.getTask();
    }

    /**
     * Send a confirmation email for a newly registered user
     * @param email email address of user
     * @return A task containing whether or not the email was sent successfully.
     */
    public Task<Boolean> sendEmailConfirm(@NonNull final String email) {
        final TaskCompletionSource<Boolean> future = new TaskCompletionSource<>();

        final String url = String.format(
                "%s/%s/%s",
                getResourcePath(Paths.AUTH),
                "",
                Paths.USERPASS_CONFIRM_SEND
        );

        final JsonStringRequest request = new JsonStringRequest(
                Request.Method.POST,
                url,
                new Document("email", email).toJson(),
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(final String response) {
                        future.setResult(response != null);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(final VolleyError error) {
                        Log.e(TAG, "Error while sending confirmation email", error);
                        future.setException(parseRequestError(error));
                    }
                }
        );

        request.setTag(this);
        _queue.add(request);

        return future.getTask();
    }

    /**
     * Reset a given user's password
     * @param token token associated with this user
     * @param tokenId id of the token associated with this user
     * @return A task containing whether or not the reset was successful
     */
    public Task<Boolean> resetPassword(@NonNull final String token, @NonNull final String tokenId) {
        final TaskCompletionSource<Boolean> future = new TaskCompletionSource<>();

        final String url = String.format(
                "%s/%s/%s",
                getResourcePath(Paths.AUTH),
                "",
                Paths.USERPASS_RESET
        );

        final Document params = new Document();

        params.put(RegistrationFields.TOKEN, token);
        params.put(RegistrationFields.TOKEN_ID, tokenId);

        final JsonStringRequest request = new JsonStringRequest(
                Request.Method.POST,
                url,
                params.toJson(),
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(final String response) {
                        future.setResult(response != null);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(final VolleyError error) {
                        Log.e(TAG, "Error while reseting password", error);
                        future.setException(parseRequestError(error));
                    }
                }
        );

        request.setTag(this);
        _queue.add(request);

        return future.getTask();
    }

    /**
     * Send a reset password email to a given email address
     * @param email email address to reset password for
     * @return A task containing whether or not the reset email was sent successfully
     */
    public Task<Boolean> sendResetPassword(@NonNull final String email) {
        final TaskCompletionSource<Boolean> future = new TaskCompletionSource<>();

        final String url = String.format(
                "%s/%s/%s",
                getResourcePath(Paths.AUTH),
                "",
                Paths.USERPASS_RESET_SEND
        );

        final JsonStringRequest request = new JsonStringRequest(
                Request.Method.POST,
                url,
                new Document("email", email).toJson(),
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(final String response) {
                        future.setResult(response != null);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(final VolleyError error) {
                        Log.e(TAG, "Error while sending reset password email", error);
                        future.setException(parseRequestError(error));
                    }
                }
        );

        request.setTag(this);
        _queue.add(request);

        return future.getTask();
    }

    /**
     * Adds a listener for auth events.
     *
     * @param authListener The listener that will receive auth events.
     */
    public synchronized void addAuthListener(final AuthListener authListener) {
        _authListeners.add(authListener);
    }

    /**
     * Removes a listener for auth events.
     *
     * @param authListener The listener that will no longer receive auth events.
     */
    public synchronized void removeAuthListener(final AuthListener authListener) {
        _authListeners.remove(authListener);
    }

    /**
     * Gets all available auth providers for the current app.
     *
     * @return A task containing {@link AvailableAuthProviders} that can be resolved on completion
     * of the request.
     */
    public Task<AvailableAuthProviders> getAuthProviders() {

        final TaskCompletionSource<AvailableAuthProviders> future = new TaskCompletionSource<>();
        final String url = getResourcePath(Paths.AUTH);

        final JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.GET,
                url,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(final JSONObject response) {

                        final AvailableAuthProviders.Builder builder = new AvailableAuthProviders.Builder();
                        // Build provider info
                        for (final Iterator<String> keyItr = response.keys(); keyItr.hasNext(); ) {
                            final String authProviderName = keyItr.next();

                            try {
                                final JSONObject info = response.getJSONObject(authProviderName);

                                switch (authProviderName) {
                                    case FacebookAuthProviderInfo.FQ_NAME:
                                        final FacebookAuthProviderInfo fbInfo =
                                                _objMapper.readValue(info.toString(), FacebookAuthProviderInfo.class);
                                        builder.withFacebook(fbInfo);
                                        break;
                                    case GoogleAuthProviderInfo.FQ_NAME:
                                        final GoogleAuthProviderInfo googleInfo =
                                                _objMapper.readValue(info.toString(), GoogleAuthProviderInfo.class);
                                        builder.withGoogle(googleInfo);
                                        break;
                                    case AnonymousAuthProviderInfo.FQ_NAME:
                                        final AnonymousAuthProviderInfo anonInfo =
                                                _objMapper.readValue(info.toString(), AnonymousAuthProviderInfo.class);
                                        builder.withAnonymous(anonInfo);
                                        break;
                                    case EmailPasswordAuthProviderInfo.FQ_NAME:
                                        final EmailPasswordAuthProviderInfo emailPassInfo =
                                                _objMapper.readValue(info.toString(), EmailPasswordAuthProviderInfo.class);
                                        builder.withEmailPass(emailPassInfo);
                                        break;

                                }
                            } catch (final JSONException | IOException e) {
                                Log.e(
                                        TAG,
                                        String.format("Error while getting auth provider info for %s", authProviderName),
                                        e);
                                future.setException(e);
                                return;
                            }
                        }
                        future.setResult(builder.build());
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(final VolleyError error) {
                        Log.e(TAG, "Error while getting auth provider info", error);
                        future.setException(parseRequestError(error));
                    }
                });
        request.setTag(this);
        _queue.add(request);

        return future.getTask();
    }

    // Pipelines

    /**
     * Executes a pipeline with the current app.
     *
     * @param pipeline The pipeline to execute.
     * @return A task containing the result of the pipeline that can be resolved on completion
     * of the execution.
     */
    @SuppressWarnings("unchecked")
    public Task<List<Object>> executePipeline(final List<PipelineStage> pipeline) {
        ensureAuthenticated();
        final String pipeStr;
        try {
            pipeStr = _objMapper.writeValueAsString(pipeline);
        } catch (final IOException e) {
            return Tasks.forException(e);
        }

        return executeRequest(Request.Method.POST, Paths.PIPELINE, pipeStr).continueWith(new Continuation<String, List<Object>>() {
            @Override
            public List<Object> then(@NonNull final Task<String> task) throws Exception {
                if (task.isSuccessful()) {
                    final Document doc = Document.parse(task.getResult());
                    return (List<Object>) doc.get(PipelineResponseFields.RESULT);
                } else {
                    Log.e(TAG, "Error while executing pipeline", task.getException());
                    throw task.getException();
                }
            }
        });
    }

    /**
     * Executes a pipeline with the current app.
     *
     * @param stages The stages to execute as a contiguous pipeline.
     * @return A task containing the result of the pipeline that can be resolved on completion
     * of the execution.
     */
    public Task<List<Object>> executePipeline(final PipelineStage... stages) {
        return executePipeline(Arrays.asList(stages));
    }

    // Network

    private static class Paths {
        private static final String AUTH = "auth";
        private static final String USER_PROFILE = AUTH + "/me";
        private static final String NEW_ACCESS_TOKEN = String.format("%s/newAccessToken", AUTH);
        private static final String PIPELINE = "pipeline";
        private static final String PUSH = "push";
        private static final String USERPASS_REGISTER = "userpass/register";
        private static final String USERPASS_CONFIRM = "local/userpass/confirm";
        private static final String USERPASS_CONFIRM_SEND = "local/userpass/confirm/send";
        private static final String USERPASS_RESET = "local/userpass/reset";
        private static final String USERPASS_RESET_SEND = "local/userpass/reset/send";

    }

    /**
     * @param resource The target resource.
     * @return A path to the given resource.
     */
    private String getResourcePath(final String resource) {
        return String.format("%s/api/client/v1.0/app/%s/%s", _baseUrl, _clientAppId, resource);
    }

    /**
     * Executes a network request against the app. The request will be retried if there
     * is an access token expiration.
     *
     * @param method   The HTTP method to use.
     * @param resource The resource to target.
     * @return A task containing the body of the network response that can be resolved on completion
     * of the network request.
     */
    private Task<String> executeRequest(
            final int method,
            final String resource
    ) {
        return executeRequest(method, resource, null, true, false);
    }

    /**
     * Executes a network request against the app. The request will be retried if there
     * is an access token expiration.
     *
     * @param method   The HTTP method to use.
     * @param resource The resource to target.
     * @param body     The JSON body to include in the request.
     * @return A task containing the body of the network response that can be resolved on completion
     * of the network request.
     */
    private Task<String> executeRequest(
            final int method,
            final String resource,
            final String body
    ) {
        return executeRequest(method, resource, body, true, false);
    }

    /**
     * Executes a network request against the app.
     *
     * @param method           The HTTP method to use.
     * @param resource         The resource to target.
     * @param body             The JSON body to include in the request.
     * @param refreshOnFailure Whether or not to refresh the access token if it expires.
     * @param useRefreshToken  Whether or not to use the refresh token over the access token.
     * @return A task containing the body of the network response that can be resolved on completion
     * of the network request.
     */
    private Task<String> executeRequest(
            final int method,
            final String resource,
            final String body,
            final boolean refreshOnFailure,
            final boolean useRefreshToken
    ) {
        ensureAuthenticated();
        final String url = getResourcePath(resource);
        final String token = useRefreshToken ? getRefreshToken() : _auth.getAccessToken();
        final TaskCompletionSource<String> future = new TaskCompletionSource<>();
        final AuthenticatedJsonStringRequest request = new AuthenticatedJsonStringRequest(
                method,
                url,
                body,
                Collections.singletonMap(
                        Headers.AUTHORIZATION,
                        GetAuthorizationBearer(token)),
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(final String response) {
                        future.setResult(response);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(final VolleyError error) {
                        final StitchException.StitchRequestException e = parseRequestError(error);
                        if (e instanceof StitchException.StitchServiceException) {
                            if (((StitchException.StitchServiceException) e).getErrorCode() == ErrorCode.INVALID_SESSION) {
                                if (!refreshOnFailure) {
                                    clearAuth();
                                    future.setException(e);
                                    return;
                                }
                                handleInvalidSession(method, resource, body, future);
                                return;
                            }
                        }
                        future.setException(e);
                    }
                });
        request.setTag(this);
        _queue.add(request);

        return future.getTask();
    }

    // Pipelines

    private static class PipelineResponseFields {
        private static final String RESULT = "result";
    }

    // Push

    /**
     * @return The manager for {@link PushClient}s.
     */
    public PushManager getPush() {
        return _pushManager;
    }

    /**
     * Gets all available push providers for the current app.
     *
     * @return A task containing {@link AvailablePushProviders} that can be resolved on completion
     * of the request.
     */
    public Task<AvailablePushProviders> getPushProviders() {

        return executeRequest(Request.Method.GET, Paths.PUSH).continueWith(new Continuation<String, AvailablePushProviders>() {
            @Override
            public AvailablePushProviders then(@NonNull final Task<String> task) throws Exception {
                return AvailablePushProviders.fromQuery(task.getResult());
            }
        });
    }

    // Internal Public Methods

    /**
     * @return The properties for all Stitch clients.
     */
    public Properties getProperties() {
        return _properties;
    }

    // Private Methods

    // Auth

    /**
     * Checks if the client is authenticated and if it isn't it throws.
     */
    private void ensureAuthenticated() {
        if (!isAuthenticated()) {
            throw new StitchAuthException("Must first authenticate");
        }
    }

    /**
     * Called when a user logs in with this client.
     */
    private synchronized void onLogin() {
        for (final AuthListener listener : _authListeners) {
            listener.onLogin();
        }
    }

    /**
     * Called when a user is logged out from this client.
     */
    private synchronized void onLogout(final String lastProvider) {
        for (final AuthListener listener : _authListeners) {
            listener.onLogout(lastProvider);
        }
    }

    /**
     * @return The refresh token for the current user if authenticated; throws otherwise.
     */
    private String getRefreshToken() {
        if (!isAuthenticated()) {
            throw new StitchAuthException("Must first authenticate");
        }

        return _preferences.getString(PREF_AUTH_REFRESH_TOKEN_NAME, "");
    }

    /**
     * Clears all authentication material that has been persisted.
     */
    private void clearAuth() {
        if (_auth == null) {
            return;
        }
        final String lastProvider = _auth.getProvider();
        _auth = null;
        _preferences.edit().remove(PREF_AUTH_JWT_NAME).apply();
        _preferences.edit().remove(PREF_AUTH_REFRESH_TOKEN_NAME).apply();
        _queue.cancelAll(this);
        onLogout(lastProvider);
    }

    /**
     * Handles an invalid session error from Stitch by refreshing the access token and
     * retrying the original request.
     *
     * @param method   The original HTTP method.
     * @param resource The original resource.
     * @param body     The original body.
     * @param future   The task to resolve upon completion of this handler.
     */
    private void handleInvalidSession(
            final int method,
            final String resource,
            final String body,
            final TaskCompletionSource<String> future
    ) {
        refreshAccessToken().addOnCompleteListener(new OnCompleteListener<Void>() {
            @Override
            public void onComplete(@NonNull final Task<Void> task) {
                if (!task.isSuccessful()) {
                    future.setException(task.getException());
                    return;
                }

                // Retry one more time
                executeRequest(method, resource, body, false, false).addOnCompleteListener(new OnCompleteListener<String>() {
                    @Override
                    public void onComplete(@NonNull final Task<String> task) {
                        if (task.isSuccessful()) {
                            future.setResult(task.getResult());
                            return;
                        }

                        future.setException(task.getException());
                    }
                });
            }
        });
    }

    /**
     * Refreshes the current access token using the current refresh token.
     *
     * @return A task that can resolved upon completion of refreshing the access token.
     */
    private Task<Void> refreshAccessToken() {
        return executeRequest(Request.Method.POST, Paths.NEW_ACCESS_TOKEN, null, false, true)
                .continueWith(new Continuation<String, Void>() {
                    @Override
                    public Void then(@NonNull Task<String> task) throws Exception {
                        if (!task.isSuccessful()) {
                            throw task.getException();
                        }

                        final String newAccessToken;
                        try {
                            final JSONObject response = new JSONObject(task.getResult());
                            newAccessToken = response.getString(AuthFields.ACCESS_TOKEN);
                        } catch (final JSONException e) {
                            Log.e(TAG, "Error parsing access token response", e);
                            throw new StitchException(e);
                        }

                        _auth = _auth.withNewAccessToken(newAccessToken);

                        final String authJson;
                        try {
                            authJson = _objMapper.writeValueAsString(_auth);
                        } catch (final IOException e) {
                            Log.e(TAG, "Error parsing auth response", e);
                            throw new StitchException(e);
                        }

                        _preferences.edit().putString(PREF_AUTH_JWT_NAME, authJson).apply();
                        return null;
                    }
                });
    }

    /**
     * @param provider The provider that will handle authentication.
     * @return A {@link Document} representing all information required for
     * an auth request against a specific provider.
     */
    private Document getAuthRequest(final AuthProvider provider) {
        return getAuthRequest(provider.getAuthPayload());
    }

    /**
     * @param request Arbitrary document for authentication
     * @return A {@link Document} representing all information required for
     * an auth request against a specific provider.
     */
    private Document getAuthRequest(final Document request) {
        final Document options = new Document();
        options.put(AuthFields.DEVICE, getDeviceInfo());
        request.put(AuthFields.OPTIONS, options);
        return request;
    }

    private static class AuthFields {
        private static final String ACCESS_TOKEN = "accessToken";
        static final String OPTIONS = "options";
        static final String DEVICE = "device";
    }

    // Device

    /**
     * @return Whether or not this client has stored a device ID.
     */
    private boolean hasDeviceId() {
        return _preferences.contains(PREF_DEVICE_ID_NAME);
    }

    /**
     * @return The client's device ID if there is one.
     */
    private String getDeviceId() {
        return _preferences.getString(PREF_DEVICE_ID_NAME, "");
    }

    /**
     * @return A {@link Document} representing the information for this device
     * from the context of this app.
     */
    private Document getDeviceInfo() {
        final Document info = new Document();

        if (hasDeviceId()) {
            info.put(DeviceFields.DEVICE_ID, getDeviceId());
        }

        final String packageName = _context.getPackageName();
        final PackageManager manager = _context.getPackageManager();

        try {
            final PackageInfo pkgInfo = manager.getPackageInfo(packageName, 0);
            info.put(DeviceFields.APP_VERSION, pkgInfo.versionName);
        } catch (final PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Error while getting info for app package", e);
            throw new StitchException.StitchClientException(e);
        }

        info.put(DeviceFields.APP_ID, packageName);
        info.put(DeviceFields.PLATFORM, PLATFORM);
        info.put(DeviceFields.PLATFORM_VERSION, Build.VERSION.RELEASE);

        return info;
    }

    private static class RegistrationFields {
        private static final String TOKEN = "token";
        private static final String TOKEN_ID = "tokenId";
    }

    private static class DeviceFields {
        static final String DEVICE_ID = "deviceId";
        static final String APP_ID = "appId";
        static final String APP_VERSION = "appVersion";
        static final String PLATFORM = "platform";
        static final String PLATFORM_VERSION = "platformVersion";
    }
}
