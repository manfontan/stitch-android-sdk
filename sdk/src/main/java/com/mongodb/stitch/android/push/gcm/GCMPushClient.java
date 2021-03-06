package com.mongodb.stitch.android.push.gcm;

import android.content.Context;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.gcm.GcmPubSub;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.android.gms.iid.InstanceID;
import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.mongodb.stitch.android.StitchClient;
import com.mongodb.stitch.android.StitchException;
import com.mongodb.stitch.android.PipelineStage;
import com.mongodb.stitch.android.push.PushClient;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.bson.Document;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * GCMPushClient is the {@link PushClient} for GCM. It handles the logic of registering and
 * deregistering with both GCM and Stitch.
 *
 * It does not actively handle updates to the Instance ID when it is refreshed.
 */
public class GCMPushClient extends PushClient {

    private static final String TAG = "Stitch-Push-GCM";

    // Properties
    private static final String PROP_GCM_SERVICE_NAME = "push.gcm.service";
    private static final String PROP_GCM_SENDER_ID = "push.gcm.senderId";

    private final GCMPushProviderInfo _info;

    /**
     * @param context    The Android {@link Context} that this client should be bound to.
     * @param stitchClient The client to use for talking to Stitch.
     * @param info       The information required to construct this client.
     */
    public GCMPushClient(
            final Context context,
            final StitchClient stitchClient,
            final GCMPushProviderInfo info
    ) {
        super(context, stitchClient);

        final GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        if (apiAvailability.isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
            throw new StitchException.StitchClientException(
                    "Google Play Services is not currently available and is necessary for GCM push notifications");
        }

        if (info.isFromProperties()) {
            if (!stitchClient.getProperties().containsKey(PROP_GCM_SENDER_ID)) {
                throw new StitchException.StitchClientException("No GCM Sender ID in properties");
            }
            if (!stitchClient.getProperties().containsKey(PROP_GCM_SERVICE_NAME)) {
                throw new StitchException.StitchClientException("No GCM service name in properties");
            }
            final String serviceName = stitchClient.getProperties().getProperty(PROP_GCM_SERVICE_NAME);
            final String senderId = stitchClient.getProperties().getProperty(PROP_GCM_SENDER_ID);

            _info = GCMPushProviderInfo.fromSenderId(serviceName, senderId);
        } else {
            _info = info;
        }
    }

    /**
     * @return A task that can be resolved upon completion of registration to both GCM and Stitch.
     */
    @Override
    public Task<Void> register() {
        final InstanceID instanceId = InstanceID.getInstance(getContext());

        return getRegistrationToken(instanceId, _info.getSenderId())
                .continueWithTask(new Continuation<String, Task<Void>>() {
                    @Override
                    public Task<Void> then(@NonNull final Task<String> task) throws Exception {
                        if (!task.isSuccessful()) {
                            throw task.getException();
                        }

                        return registerWithServer(task.getResult());
                    }
                });
    }

    /**
     * @return A task that can be resolved upon completion of deregistration from both GCM and Stitch.
     */
    @Override
    public Task<Void> deregister() {
        final InstanceID instanceId = InstanceID.getInstance(getContext());

        return deleteRegistrationToken(instanceId, _info.getSenderId())
                .continueWithTask(new Continuation<Void, Task<Void>>() {
                    @Override
                    public Task<Void> then(@NonNull final Task<Void> task) throws Exception {
                        if (!task.isSuccessful()) {
                            throw task.getException();
                        }

                        return deregisterWithServer();
                    }
                });
    }

    /**
     * Subscribes the client to a specific topic.
     * /topics/ prefix is not necessary.
     *
     * @param topic The topic to subscribe to
     *
     * @return A task that can resolved upon subscribing.
     */
    public Task<Void> subscribeToTopic(final String topic) {
        final GcmPubSub pubSub = GcmPubSub.getInstance(getContext());
        final String topicKey = String.format("/topics/%s", topic);
        final InstanceID instanceId = InstanceID.getInstance(getContext());

        final TaskCompletionSource<Void> future = new TaskCompletionSource<>();

        getRegistrationToken(instanceId, _info.getSenderId()).addOnCompleteListener(new OnCompleteListener<String>() {
            @Override
            public void onComplete(@NonNull final Task<String> task) {
                if (!task.isSuccessful()) {
                    future.setException(task.getException());
                }

                new AsyncTask<Object, Integer, String>() {
                    @Override
                    protected String doInBackground(final Object[] ignored) {

                        try {
                            pubSub.subscribe(task.getResult(), topicKey, null);
                        } catch (final IOException e) {
                            Log.e(TAG, "Error subscribing to " + topicKey, e);
                            future.setException(e);
                            return null;
                        }

                        future.setResult(null);
                        return null;
                    }
                }.execute();
            }
        });

        return future.getTask();
    }

    /**
     * Subscribes the client to a specific topic.
     * /topics/ prefix is not necessary.
     *
     * @param topic The topic to unsubscribe from
     *
     * @return A task that can resolved upon subscribing.
     */
    public Task<Void> unsubscribeFromTopic(final String topic) {
        final GcmPubSub pubSub = GcmPubSub.getInstance(getContext());
        final String topicKey = String.format("/topics/%s", topic);
        final InstanceID instanceId = InstanceID.getInstance(getContext());

        final TaskCompletionSource<Void> future = new TaskCompletionSource<>();

        getRegistrationToken(instanceId, _info.getSenderId()).addOnCompleteListener(new OnCompleteListener<String>() {
            @Override
            public void onComplete(@NonNull final Task<String> task) {
                if (!task.isSuccessful()) {
                    future.setException(task.getException());
                }

                new AsyncTask<Object, Integer, String>() {
                    @Override
                    protected String doInBackground(final Object[] ignored) {

                        try {
                            pubSub.unsubscribe(task.getResult(), topicKey);
                        } catch (final IOException e) {
                            Log.e(TAG, "Error unsubscribing from " + topicKey, e);
                            future.setException(e);
                            return null;
                        }

                        future.setResult(null);
                        return null;
                    }
                }.execute();
            }
        });

        return future.getTask();
    }

    /**
     * @param other The other client to compare against.
     * @return Whether or not these clients are the same.
     */
    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof GCMPushClient)) {
            return false;
        }

        final GCMPushClient otherClient = (GCMPushClient) other;
        return new EqualsBuilder()
                .append(_info.getService(), otherClient._info.getService())
                .append(_info.getSenderId(), otherClient._info.getSenderId())
                .build();
    }

    /**
     * @return A hash code for this client.
     */
    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(_info.getProvider().getTypeName())
                .append(_info.getService())
                .append(_info.getSenderId())
                .build();
    }

    /**
     * Deletes the current registration token.
     *
     * @param instanceId The instance ID which generated the registration token.
     * @param senderId   The sender ID to revoke the registration token from.
     * @return A task that can be resolved upon deletion of the token.
     */
    private Task<Void> deleteRegistrationToken(final InstanceID instanceId, final String senderId) {
        final TaskCompletionSource<Void> future = new TaskCompletionSource<>();
        new AsyncTask<Object, Integer, String>() {
            @Override
            protected String doInBackground(final Object[] ignored) {

                try {
                    instanceId.deleteToken(senderId, GoogleCloudMessaging.INSTANCE_ID_SCOPE);
                } catch (final IOException e) {
                    Log.e(TAG, "Error deleting GCM registration token", e);
                    future.setException(e);
                    return null;
                }

                future.setResult(null);
                return null;
            }
        }.execute();

        return future.getTask();
    }

    /**
     * Gets or creates a registration token.
     *
     * @param instanceId The instance ID which should generate the registration token.
     * @param senderId   The sender ID to generate the registration token for.
     * @return A task that can be resolved upon creating/retrieval of the token.
     */
    private Task<String> getRegistrationToken(final InstanceID instanceId, final String senderId) {
        final TaskCompletionSource<String> future = new TaskCompletionSource<>();
        new AsyncTask<Object, Integer, String>() {
            @Override
            protected String doInBackground(final Object[] ignored) {

                final String registrationToken;
                try {
                    registrationToken = instanceId.getToken(senderId, GoogleCloudMessaging.INSTANCE_ID_SCOPE);
                } catch (final IOException e) {
                    Log.e(TAG, "Error getting GCM registration token", e);
                    future.setException(e);
                    return null;
                }

                future.setResult(registrationToken);
                return null;
            }
        }.execute();

        return future.getTask();
    }

    /**
     * Register the registration token with Stitch.
     *
     * @param registrationToken The registration token generated for the sender.
     * @return A task that can be resolved upon registering the token with Stitch.
     */
    private Task<Void> registerWithServer(final String registrationToken) {
        final Map<String, Object> request = getRegisterPushDeviceRequest(registrationToken);
        return getStitchClient().executePipeline(new PipelineStage(
                Actions.REGISTER_PUSH, request))
                .continueWith(new Continuation<List<Object>, Void>() {
                    @Override
                    public Void then(@NonNull Task<List<Object>> task) throws Exception {
                        if (!task.isSuccessful()) {
                            throw task.getException();
                        }

                        addInfoToConfigs(_info);
                        return null;
                    }
                });
    }

    /**
     * Deregister the device associated with the registration token from Stitch.
     *
     * @return A task that can be resolved upon deregistering the device from Stitch.
     */
    private Task<Void> deregisterWithServer() {
        final Map<String, Object> request = getDeregisterPushDeviceRequest();
        return getStitchClient().executePipeline(new PipelineStage(
                Actions.DEREGISTER_PUSH, request))
                .continueWith(new Continuation<List<Object>, Void>() {
                    @Override
                    public Void then(@NonNull Task<List<Object>> task) throws Exception {
                        if (!task.isSuccessful()) {
                            throw task.getException();
                        }

                        removeInfoFromConfigs(_info);
                        return null;
                    }
                });
    }

    /**
     * @param registrationToken The registration token from GCM.
     * @return The request payload for registering for push for GCM.
     */
    private Document getRegisterPushDeviceRequest(final String registrationToken) {

        final Document request = getBaseRegisterPushRequest(_info.getService());
        final Document data = (Document) request.get(PushClient.DeviceFields.DATA);
        data.put(DeviceFields.REGISTRATION_TOKEN, registrationToken);

        return request;
    }

    /**
     * @return The request payload for deregistering from push for GCM.
     */
    private Document getDeregisterPushDeviceRequest() {
        return getBaseDeregisterPushDeviceRequest(_info.getService());
    }

    private static class DeviceFields {
        public static final String REGISTRATION_TOKEN = "registrationToken";
    }
}
