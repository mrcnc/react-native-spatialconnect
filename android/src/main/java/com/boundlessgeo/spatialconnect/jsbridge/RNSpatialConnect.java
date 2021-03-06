/**
 * Copyright 2015-2017 Boundless, http://boundlessgeo.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License
 */
package com.boundlessgeo.spatialconnect.jsbridge;

import android.content.Context;
import android.content.res.Resources;
import android.location.Location;
import android.support.annotation.Nullable;
import android.util.Log;

import com.boundlessgeo.spatialconnect.SpatialConnect;
import com.boundlessgeo.spatialconnect.config.SCFormConfig;
import com.boundlessgeo.spatialconnect.config.SCFormField;
import com.boundlessgeo.spatialconnect.geometries.SCBoundingBox;
import com.boundlessgeo.spatialconnect.geometries.SCGeometry;
import com.boundlessgeo.spatialconnect.geometries.SCGeometryFactory;
import com.boundlessgeo.spatialconnect.geometries.SCSpatialFeature;
import com.boundlessgeo.spatialconnect.mqtt.SCNotification;
import com.boundlessgeo.spatialconnect.query.SCGeometryPredicateComparison;
import com.boundlessgeo.spatialconnect.query.SCPredicate;
import com.boundlessgeo.spatialconnect.query.SCQueryFilter;
import com.boundlessgeo.spatialconnect.schema.SCCommand;
import com.boundlessgeo.spatialconnect.services.authService.SCAuthService;
import com.boundlessgeo.spatialconnect.services.SCBackendService;
import com.boundlessgeo.spatialconnect.services.SCSensorService;
import com.boundlessgeo.spatialconnect.services.SCServiceStatusEvent;
import com.boundlessgeo.spatialconnect.stores.GeoPackageStore;
import com.boundlessgeo.spatialconnect.stores.SCDataStore;
import com.boundlessgeo.spatialconnect.stores.SCKeyTuple;
import com.boundlessgeo.spatialconnect.stores.SCRasterStore;
import com.boundlessgeo.spatialconnect.stores.ISCSpatialStore;
import com.boundlessgeo.spatialconnect.stores.SCStoreStatusEvent;
import com.boundlessgeo.spatialconnect.tiles.GpkgRasterSource;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.uimanager.NativeViewHierarchyManager;
import com.facebook.react.uimanager.UIBlock;
import com.facebook.react.uimanager.UIManagerModule;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.TileOverlay;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import rx.Subscriber;
import rx.functions.Action1;
import rx.schedulers.Schedulers;

/**
 * This module handles messages sent from Javascript.
 */
public class RNSpatialConnect extends ReactContextBaseJavaModule {

    private static final String LOG_TAG = RNSpatialConnect.class.getSimpleName();
    private final SpatialConnect sc;
    private final ReactContext reactContext;
    private GoogleMap mapView;
    private HashMap<String, List<TileOverlay>> tileoverlays = new HashMap();

    public RNSpatialConnect(ReactApplicationContext reactContext) {
        super(reactContext);
        this.sc = SpatialConnect.getInstance();
        this.sc.initialize(reactContext.getApplicationContext());
        this.reactContext = reactContext;
        // todo: get GoogleMap instance from react native
    }

    @Override
    public String getName() {
        return "RNSpatialConnect";
    }

    public void setMapView(GoogleMap map) {
        mapView = map;
    }

    @ReactMethod
    public void bindMapView(final int tag, final Callback successCallback) {
        UIManagerModule uiManager = this.reactContext.getNativeModule(UIManagerModule.class);
        uiManager.addUIBlock(new UIBlock() {
            public void execute (NativeViewHierarchyManager nvhm) {
                MapView mapView = (MapView)nvhm.resolveView(tag);
                //mapView.getMapAsync(this);
                mapView.getMapAsync(new OnMapReadyCallback() {
                    @Override
                    public void onMapReady(final GoogleMap googleMap) {
                      setMapView(googleMap);
                      successCallback.invoke(false, "success");
                    }
                });
            }
        });
    }

    @ReactMethod
    public void addRasterLayers(final ReadableArray storeIdArray) {
        UIManagerModule uiManager = this.reactContext.getNativeModule(UIManagerModule.class);
        uiManager.addUIBlock(new UIBlock() {
            public void execute (NativeViewHierarchyManager nvhm) {
                List<String> storeIds = new ArrayList<>(storeIdArray.size());
                for (int index = 0; index < storeIdArray.size(); index++) {
                    storeIds.add(storeIdArray.getString(index));
                }
                List<SCDataStore> stores = sc.getDataService().getActiveStores();
                for (SCDataStore store : stores) {
                    List<TileOverlay> tiles = tileoverlays.get(store.getStoreId());
                    if(storeIds.contains(store.getStoreId())) {
                        if (tiles == null) {
                            if(store instanceof GeoPackageStore && ((GeoPackageStore)store).getTileSources().size() > 0) {
                                SCRasterStore rs = new GpkgRasterSource((GeoPackageStore)store);
                                List<TileOverlay> newTiles = new ArrayList<TileOverlay>();
                                for (String tableName : rs.rasterLayers()) {
                                    TileOverlay t = rs.overlayFromLayer(tableName, mapView);
                                    newTiles.add(t);
                                }
                                tileoverlays.put(store.getStoreId(), newTiles);
                            }
                        }
                    } else {
                        if (tiles != null) {
                            for (TileOverlay tile : tiles) {
                                tile.remove();
                            }
                            tileoverlays.remove(store.getStoreId());
                        }
                    }
                }
            }
        });
    }

    @ReactMethod
    public void addConfigFilepath(final String path) {
      try {
          Resources resources = getReactApplicationContext().getResources();
          //remove ext from file name
          String fileName = path.substring(0, path.indexOf("."));
          InputStream is = resources.openRawResource(resources.getIdentifier(fileName,
                          "raw", getReactApplicationContext().getPackageName()));
          // write the file to the internal storage location
          FileOutputStream fos = reactContext.openFileOutput("config.scfg", Context.MODE_PRIVATE);
          byte[] data = new byte[is.available()];
          is.read(data);
          fos.write(data);
          is.close();
          fos.close();
      }
      catch (IOException e) {
          e.printStackTrace();
          Log.e(LOG_TAG, "error in addConfigFilepath " + e.getMessage());
      }
    }

    /**
     * Sends an event to Javascript.
     *
     * @param eventType the name of the event
     * @param params    a map of the key/value pairs associated with the event
     * @see <a href="https://facebook.github.io/react-native/docs/native-modules-android
     * .html#sending-events-to-javascript"> https://facebook.github.io/react-native/docs/native-modules-android
     * .html#sending-events-to-javascript</a>
     */
    public void sendEvent(Integer eventType, @Nullable WritableMap params) {
        Log.v(LOG_TAG, String.format("Sending {\"type\": %s, \"payload\": %s} to Javascript",
                        eventType.toString(),
                        params.toString())
        );
        WritableMap newAction = Arguments.createMap();
        newAction.putInt("type", eventType);
        newAction.putMap("payload", params);
        this.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventType.toString(), newAction);
    }

    /**
     * Sends an event to Javascript.
     *
     * @param eventType  the name of the event
     * @param responseId the responseId used to identify the observable that is listening for a response
     * @param params     a map of the key/value pairs associated with the event
     * @see <a href="https://facebook.github.io/react-native/docs/native-modules-android
     * .html#sending-events-to-javascript"> https://facebook.github.io/react-native/docs/native-modules-android
     * .html#sending-events-to-javascript</a>
     */
    public void sendEvent(Integer eventType, String responseId, @Nullable WritableMap params) {
        if (eventType != 113) {
            Log.v(LOG_TAG, String.format("Sending {\"responseID\": \"%s\", \"type\": %s, \"payload\": %s} to Javascript",
                            responseId,
                            eventType.toString(),
                            params.toString())
            );
        }
        WritableMap newAction = Arguments.createMap();
        newAction.putInt("type", eventType);
        newAction.putMap("payload", params);
        this.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(responseId, newAction);
    }

    /**
     * Sends an event to Javascript.
     *
     * @param eventType     the name of the event
     * @param responseId    the responseId used to identify the observable that is listening for a response
     * @param payloadString a payload string associated with the event
     * @see <a href="https://facebook.github.io/react-native/docs/native-modules-android
     * .html#sending-events-to-javascript"> https://facebook.github.io/react-native/docs/native-modules-android
     * .html#sending-events-to-javascript</a>
     */
    public void sendEvent(Integer eventType, String responseId, @Nullable String payloadString) {
        if (eventType != 113) {
            Log.v(LOG_TAG, String.format("Sending {\"responseID\": \"%s\", \"type\": %s, \"payload\": %s} to Javascript",
                            responseId,
                            eventType.toString(),
                            payloadString)
            );
        }
        WritableMap newAction = Arguments.createMap();
        newAction.putInt("type", eventType);
        newAction.putString("payload", payloadString);
        this.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(responseId, newAction);
    }

    /**
     * Sends an event to Javascript.
     *
     * @param eventType     the name of the event
     * @param payloadString a payload string associated with the event
     * @see <a href="https://facebook.github.io/react-native/docs/native-modules-android
     * .html#sending-events-to-javascript"> https://facebook.github.io/react-native/docs/native-modules-android
     * .html#sending-events-to-javascript</a>
     */
    public void sendEvent(Integer eventType, @Nullable String payloadString) {
        Log.v(LOG_TAG, String.format("Sending {\"type\": %s, \"payload\": \"%s\"} to Javascript",
                        eventType.toString(),
                        payloadString)
        );
        WritableMap newAction = Arguments.createMap();
        newAction.putInt("type", eventType);
        newAction.putString("payload", payloadString);
        this.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventType.toString(), newAction);
    }

    /**
     * Sends an event to Javascript.
     *
     * @param eventType      the name of the event
     * @param payloadInteger a payload integer associated with the event
     * @see <a href="https://facebook.github.io/react-native/docs/native-modules-android
     * .html#sending-events-to-javascript"> https://facebook.github.io/react-native/docs/native-modules-android
     * .html#sending-events-to-javascript</a>
     */
    public void sendEvent(Integer eventType, String responseId, @Nullable Integer payloadInteger) {
        Log.v(LOG_TAG, String.format("Sending {\"type\": %s, \"payload\": %d} to Javascript",
                        eventType.toString(),
                        payloadInteger)
        );
        WritableMap newAction = Arguments.createMap();
        newAction.putInt("type", eventType);
        newAction.putInt("payload", payloadInteger);
        this.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(responseId, newAction);
    }

    public void sendEvent(Integer eventType, @Nullable Integer payloadInteger) {
        Log.v(LOG_TAG, String.format("Sending {\"type\": %s, \"payload\": %d} to Javascript",
                eventType.toString(),
                payloadInteger)
        );
        WritableMap newAction = Arguments.createMap();
        newAction.putInt("type", eventType);
        newAction.putInt("payload", payloadInteger);
        this.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventType.toString(), newAction);
    }

    /**
     * Handles a message sent from Javascript.  Expects the message envelope to look like:
     * <code>{"type":<integer>,"payload":<JSON Object>}</code>
     *
     * @param message
     */
    @ReactMethod
    public void handler(ReadableMap message) {
        Log.d(LOG_TAG, "Received message from JS: " + message.toString());

        if (message == null && message.equals("undefined")) {
            Log.w(LOG_TAG, "data message was null or undefined");
            return;
        }
        else {
            // parse bridge message to determine command
            Integer actionNumber = message.getInt("type");
            SCCommand command = SCCommand.fromActionNumber(actionNumber);
            if (command.equals(SCCommand.START_ALL_SERVICES)) {
                handleStartAllServices();
            }
            if (command.equals(SCCommand.SENSORSERVICE_GPS)) {
                handleSensorServiceGps(message);
            }
            if (command.equals(SCCommand.DATASERVICE_ACTIVESTORESLIST)) {
                handleActiveStoresList(message);
            }
            if (command.equals(SCCommand.DATASERVICE_ACTIVESTOREBYID)) {
                handleActiveStoreById(message);
            }
            if (command.equals(SCCommand.DATASERVICE_STORELIST)) {
                handleStoreList(message);
            }
            if (command.equals(SCCommand.DATASERVICE_QUERY)
                    || command.equals(SCCommand.DATASERVICE_SPATIALQUERY)) {
                handleQuery(message);
            }
            if (command.equals(SCCommand.DATASERVICE_UPDATEFEATURE)) {
                handleUpdateFeature(message);
            }
            if (command.equals(SCCommand.DATASERVICE_DELETEFEATURE)) {
                handleDeleteFeature(message);
            }
            if (command.equals(SCCommand.DATASERVICE_CREATEFEATURE)) {
                handleCreateFeature(message);
            }
            if (command.equals(SCCommand.DATASERVICE_FORMLIST)) {
                handleFormsList(message);
            }
            if (command.equals(SCCommand.AUTHSERVICE_AUTHENTICATE)) {
                handleAuthenticate(message);
            }
            if (command.equals(SCCommand.AUTHSERVICE_ACCESS_TOKEN)) {
                handleAccessToken(message);
            }
            if (command.equals(SCCommand.AUTHSERVICE_LOGIN_STATUS)) {
                handleLoginStatus(message);
            }
            if (command.equals(SCCommand.AUTHSERVICE_LOGOUT)) {
                handleLogout(message);
            }
            if (command.equals(SCCommand.NOTIFICATIONS)) {
                handleNotificationSubscribe(message);
            }
            if (command.equals(SCCommand.BACKENDSERVICE_HTTP_URI)) {
                handleBackendServiceHTTPUri(message);
            }
            if (command.equals(SCCommand.BACKENDSERVICE_MQTT_CONNECTED)) {
                handleMqttConnectionStatus(message);
            }
        }
    }

    private void handleNotificationSubscribe(final ReadableMap message) {
        Log.d(LOG_TAG, "Subscribing to notifications");
        SCSensorService sensorService = SpatialConnect.getInstance().getSensorService();
        sensorService.isConnected().subscribe(new Action1<Boolean>() {
            @Override
            public void call(Boolean connected) {
                if (connected) {
                    final SpatialConnect sc = SpatialConnect.getInstance();
                    sc.serviceRunning(SCBackendService.serviceId())
                        .subscribe(new Action1<SCServiceStatusEvent>() {
                            @Override
                            public void call(SCServiceStatusEvent scServiceStatusEvent) {
                                sc.getBackendService()
                                    .getNotifications()
                                    .subscribe(new Action1<SCNotification>() {
                                        @Override
                                        public void call(SCNotification scNotification) {
                                            try {
                                                sendEvent(message.getInt("type"), convertJsonToMap(scNotification.toJson()));
                                            }
                                            catch (JSONException e) {
                                                Log.w(LOG_TAG, "Could not parse notification");
                                            }
                                        }
                                    });
                            }
                    });
                }
            }
        });

    }

    private void handleLogout(ReadableMap message) {
        Log.d(LOG_TAG, "Handling AUTHSERVICE_LOGOUT message " + message.toString());
        SpatialConnect.getInstance().getAuthService().logout();
    }

    /**
     * Handles the {@link SCCommand#AUTHSERVICE_LOGIN_STATUS} command.
     *
     * @param message
     */
    private void handleLoginStatus(final ReadableMap message) {
        Log.d(LOG_TAG, "Handling AUTHSERVICE_LOGIN_STATUS message " + message.toString());
        sc.serviceRunning(SCAuthService.serviceId())
                .subscribe(new Action1<SCServiceStatusEvent>() {
                    @Override
                    public void call(SCServiceStatusEvent scServiceStatusEvent) {
                      SCAuthService authService = SpatialConnect.getInstance().getAuthService();
                      authService.getLoginStatus().subscribe(new Action1<Integer>() {
                          @Override
                          public void call(Integer status) {
                              sendEvent(message.getInt("type"), status);
                          }
                      });
                    }
                });
    }

    private void handleAccessToken(ReadableMap message) {
        Log.d(LOG_TAG, "Handling AUTHSERVICE_ACCESS_TOKEN message " + message.toString());
        SCAuthService authService = SpatialConnect.getInstance().getAuthService();
        String accessToken = authService.getAccessToken();
        if (accessToken != null) {
            sendEvent(message.getInt("type"), accessToken);
        }
    }

    private void handleAuthenticate(ReadableMap message) {
        Log.d(LOG_TAG, "Handling AUTHSERVICE_AUTHENTICATE message " + message.toString());
        String email = message.getMap("payload").getString("email");
        String password = message.getMap("payload").getString("password");
        SCAuthService authService = SpatialConnect.getInstance().getAuthService();
        authService.authenticate(email, password);
    }


    /**
     * Handles the {@link SCCommand#START_ALL_SERVICES} command.
     */
    private void handleStartAllServices() {
        Log.d(LOG_TAG, "Handling START_ALL_SERVICES message");
        sc.startAllServices();
    }

    /**
     * Handles all the {@link SCCommand#SENSORSERVICE_GPS} commands.
     *
     * @param message
     */
    private void handleSensorServiceGps(final ReadableMap message) {
        Log.d(LOG_TAG, "Handling SENSORSERVICE_GPS message :" + message.toString());
        SCSensorService sensorService = sc.getSensorService();
        Integer payloadNumber = message.getInt("payload");
        if (payloadNumber == 1) {
            sensorService.enableGPS();
            sensorService.getLastKnownLocation()
                    .subscribeOn(Schedulers.newThread())
                    .subscribe(new Action1<Location>() {
                        @Override
                        public void call(Location location) {
                            WritableMap params = Arguments.createMap();
                            params.putString("lat", String.valueOf(location.getLatitude()));
                            params.putString("lon", String.valueOf(location.getLongitude()));
                            sendEvent(message.getInt("type"), params);
                        }
                    });
        }
        if (payloadNumber == 0) {
            sensorService.disableGPS();
        }
    }

    /**
     * Handles the {@link SCCommand#DATASERVICE_ACTIVESTORESLIST} command.
     */
    private void handleActiveStoresList(final ReadableMap message) {
        Log.d(LOG_TAG, "Handling DATASERVICE_ACTIVESTORESLIST message");
        sc.getDataService()
                .hasStores
                .subscribe(new Action1<Boolean>() {
                    @Override
                    public void call(Boolean hasStores) {
                        if (hasStores) {
                            sendEvent(message.getInt("type"), message.getString("responseId"), getActiveStoresPayload());
                        }
                    }
                });
    }

    private WritableMap getActiveStoresPayload() {
        List<SCDataStore> stores = sc.getDataService().getActiveStores();
        WritableMap eventPayload = Arguments.createMap();
        WritableArray storesArray = Arguments.createArray();
        for (SCDataStore store : stores) {
            storesArray.pushMap(getStoreMap(store));
        }
        eventPayload.putArray("stores", storesArray);
        return eventPayload;
    }

    private WritableMap getAllStoresPayload() {
        List<SCDataStore> stores = sc.getDataService().getStoreList();
        WritableMap eventPayload = Arguments.createMap();
        WritableArray storesArray = Arguments.createArray();
        for (SCDataStore store : stores) {
            storesArray.pushMap(getStoreMap(store));
        }
        eventPayload.putArray("stores", storesArray);
        return eventPayload;
    }

    /**
     * Handles the {@link SCCommand#DATASERVICE_FORMLIST} command.
     */
    private void handleFormsList(final ReadableMap message) {
        Log.d(LOG_TAG, "Handling DATASERVICE_FORMSLIST message");
        sc.getDataService()
                .getFormStore()
                .hasForms
                .subscribe(new Action1<Boolean>() {
                      @Override
                      public void call(Boolean hasForms) {
                         if (hasForms)  {
                             List < SCFormConfig > formConfigs =
                                     sc.getDataService().getFormStore().getFormConfigs();
                             WritableMap eventPayload = Arguments.createMap();
                             WritableArray formsArray = Arguments.createArray();
                             for (SCFormConfig config : formConfigs) {
                                 formsArray.pushMap(getFormMap(config));
                             }
                             eventPayload.putArray("forms", formsArray);
                             sendEvent(message.getInt("type"), message.getString("responseId"), eventPayload);
                         }
                      }
                  });

    }

    /**
     * Handles all the {@link SCCommand#DATASERVICE_ACTIVESTOREBYID} commands.
     *
     * @param message
     */
    private void handleActiveStoreById(ReadableMap message) {
        Log.d(LOG_TAG, "Handling ACTIVESTOREBYID message :" + message.toString());
        String storeId = message.getMap("payload").getString("storeId");
        SCDataStore store = sc.getDataService().getStoreByIdentifier(storeId);
        sendEvent(message.getInt("type"), getStoreMap(store));
    }

    /**
     * Handles all the {@link SCCommand#DATASERVICE_STORELIST} commands.
     *
     * @param message
     */
    private void handleStoreList(final ReadableMap message) {
        Log.d(LOG_TAG, "Handling STORELIST message :" + message.toString());
        sendEvent(message.getInt("type"), message.getString("responseId"), getAllStoresPayload());

        sc.getDataService().getStoreEvents().subscribe(new Action1<SCStoreStatusEvent>() {
            @Override
            public void call(SCStoreStatusEvent scStoreStatusEvent) {
                sendEvent(message.getInt("type"), message.getString("responseId"), getAllStoresPayload());
            }
        });
    }

    /**
     * Handles the {@link SCCommand#DATASERVICE_QUERYALL} and
     * {@link SCCommand#DATASERVICE_SPATIALQUERYALL} commands.
     *
     * @param message
     */
    private void handleQuery(final ReadableMap message) {
        Log.d(LOG_TAG, "Handling *QUERYALL message :" + message.toString());
        SCQueryFilter filter = getFilter(message);
        ReadableArray readableArray = message.getMap("payload").getArray("storeId");
        List<String> storeIds = new ArrayList<>(readableArray.size());
        for (int index = 0; index < readableArray.size(); index++) {
            storeIds.add(readableArray.getString(index));
        }
        if (filter != null) {
            sc.getDataService().queryStoresByIds(storeIds, filter)
                    .subscribeOn(Schedulers.io())
                    .subscribe(
                            new Subscriber<SCSpatialFeature>() {
                                @Override
                                public void onCompleted() {
                                    Log.d(LOG_TAG, "query observable completed");
                                }

                                @Override
                                public void onError(Throwable e) {
                                    Log.e(LOG_TAG, "Could not complete query on all stores\n" + e.getMessage());
                                    e.printStackTrace();
                                }

                                @Override
                                public void onNext(SCSpatialFeature feature) {
                                    try {
                                        // base64 encode id and set it before sending across wire
                                        String encodedId = ((SCGeometry) feature).getKey().encodedCompositeKey();
                                        feature.setId(encodedId);
                                        sendEvent(
                                                message.getInt("type"),
                                                message.getString("responseId"),
                                                convertJsonToMap(new JSONObject(feature.toJson()))
                                        );
                                    }
                                    catch (UnsupportedEncodingException e) {
                                        e.printStackTrace();
                                    }
                                    catch (JSONException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                    );
        }
    }

    /**
     * Handles the {@link SCCommand#DATASERVICE_UPDATEFEATURE} command.
     *
     * @param message
     */
    private void handleUpdateFeature(ReadableMap message) {
        Log.d(LOG_TAG, "Handling UPDATEFEATURE message :" + message.toString());
        try {
            SCSpatialFeature featureToUpdate = getFeatureToUpdate(
                    convertMapToJson(message.getMap("payload").getMap("feature")).toString()
            );
            SCDataStore store = sc.getDataService().getStoreByIdentifier(featureToUpdate.getKey().getStoreId());
            ((ISCSpatialStore) store).update(featureToUpdate)
                    .subscribeOn(Schedulers.io())
                    .subscribe(
                            new Subscriber<SCSpatialFeature>() {
                                @Override
                                public void onCompleted() {
                                    Log.d(LOG_TAG, "update completed");
                                }

                                @Override
                                public void onError(Throwable e) {
                                    Log.e(LOG_TAG, "onError()\n" + e.getLocalizedMessage());
                                }

                                @Override
                                public void onNext(SCSpatialFeature updated) {
                                    Log.d(LOG_TAG, "feature updated!");
                                }
                            }
                    );
        }
        catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    /**
     * Handles the {@link SCCommand#DATASERVICE_DELETEFEATURE} command.
     *
     * @param message
     */
    private void handleDeleteFeature(ReadableMap message) {
        Log.d(LOG_TAG, "Handling DELETEFEATURE message :" + message.toString());
        try {
            SCKeyTuple featureKey = new SCKeyTuple(message.getString("payload"));
            SCDataStore store = sc.getDataService().getStoreByIdentifier(featureKey.getStoreId());
            ((ISCSpatialStore) store).delete(featureKey)
                    .subscribeOn(Schedulers.io())
                    .subscribe(
                            new Subscriber<Boolean>() {
                                @Override
                                public void onCompleted() {
                                    Log.d(LOG_TAG, "delete completed");
                                }

                                @Override
                                public void onError(Throwable e) {
                                    Log.e(LOG_TAG, "onError()\n" + e.getLocalizedMessage());
                                }

                                @Override
                                public void onNext(Boolean deleted) {
                                    Log.d(LOG_TAG, "feature deleted!");
                                }
                            }
                    );
        }
        catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }


    /**
     * Handles the {@link SCCommand#DATASERVICE_CREATEFEATURE} command.
     *
     * @param message
     */
    private void handleCreateFeature(final ReadableMap message) {
        Log.d(LOG_TAG, "Handling CREATEFEATURE message :" + message.toString());
        try {
            SCSpatialFeature newFeature = getNewFeature(message.getMap("payload"));
            // if no store was specified, use the default store
            if (newFeature.getKey().getStoreId() == null || newFeature.getKey().getStoreId().isEmpty()) {
                newFeature.setStoreId(newFeature.getKey().getStoreId());
            }
            SCDataStore store = sc.getDataService().getStoreByIdentifier(newFeature.getKey().getStoreId());
            ((ISCSpatialStore) store).create(newFeature)
                    .subscribeOn(Schedulers.io())
                    .subscribe(
                            new Subscriber<SCSpatialFeature>() {
                                @Override
                                public void onCompleted() {
                                    Log.d(LOG_TAG, "create completed");
                                }

                                @Override
                                public void onError(Throwable e) {
                                    e.printStackTrace();
                                    Log.e(LOG_TAG, "onError()\n" + e.getLocalizedMessage());
                                }

                                @Override
                                public void onNext(SCSpatialFeature feature) {
                                    try {
                                        // base64 encode id and set it before sending across wire
                                        String encodedId = feature.getKey().encodedCompositeKey();
                                        feature.setId(encodedId);
                                        sendEvent(message.getInt("type"), message.getString("responseId"),
                                                feature.toJson());
                                    }
                                    catch (UnsupportedEncodingException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                    );
        }
        catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    /**
     * Handles the {@link SCCommand#BACKENDSERVICE_HTTP_URI} command.
     *
     * @param message the message received from the Javascript
     */
    private void handleBackendServiceHTTPUri(final ReadableMap message) {
        sc.serviceRunning(SCBackendService.serviceId())
                .subscribe(new Action1<SCServiceStatusEvent>() {
                    @Override
                    public void call(SCServiceStatusEvent scServiceStatusEvent) {
                        String backendUri = sc.getBackendService().backendUri + "/api/";
                        WritableMap eventPayload = Arguments.createMap();
                        eventPayload.putString("backendUri", backendUri);
                        sendEvent(message.getInt("type"), message.getString("responseId"), eventPayload);
                    }
                });

    }

    /**
     * Handles the {@link SCCommand#BACKENDSERVICE_MQTT_CONNECTED} command.
     *
     * @param message the message received from the Javascript
     */
    private void handleMqttConnectionStatus(final ReadableMap message) {
        SpatialConnect sc = SpatialConnect.getInstance();
        SCBackendService backendService = sc.getBackendService();
        if (backendService != null) {
            backendService
                .connectedToBroker
                .subscribeOn(Schedulers.io())
                .subscribe(new Subscriber<Boolean>() {
                    @Override
                    public void onCompleted() {
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        Log.e(LOG_TAG, "onError()\n" + e.getLocalizedMessage());
                    }

                    @Override
                    public void onNext(Boolean connected) {
                        WritableMap eventPayload = Arguments.createMap();
                        eventPayload.putBoolean("connected", connected);
                        sendEvent(message.getInt("type"), message.getString("responseId"), eventPayload);
                    }
                });
        } else {
            WritableMap eventPayload = Arguments.createMap();
            eventPayload.putBoolean("connected", false);
            sendEvent(message.getInt("type"), message.getString("responseId"), eventPayload);
        }
    }

    /**
     * Returns an SCSpatialFeature instance based on the message from the bridge to create a new feature.
     *
     * @param message the message received from the Javascript
     * @return
     * @throws UnsupportedEncodingException
     */
    private SCSpatialFeature getNewFeature(ReadableMap message) throws UnsupportedEncodingException {
        String featureString = convertMapToJson(message.getMap("feature")).toString();
        Log.d(LOG_TAG, "new feature: " + featureString);
        return new SCGeometryFactory().getSpatialFeatureFromFeatureJson(featureString);
    }

    /**
     * Returns an SCSpatialFeature instance based on the GeoJSON Feature string sent from the bridge for update.
     *
     * @param featureString the GeoJSON string representing the feature
     * @return
     * @throws UnsupportedEncodingException
     */
    private SCSpatialFeature getFeatureToUpdate(String featureString) throws UnsupportedEncodingException {
        SCSpatialFeature feature = new SCGeometryFactory().getSpatialFeatureFromFeatureJson(featureString);
        SCKeyTuple decodedTuple = new SCKeyTuple(feature.getId());
        // update feature with decoded values
        feature.setStoreId(decodedTuple.getStoreId());
        feature.setLayerId(decodedTuple.getLayerId());
        feature.setId(decodedTuple.getFeatureId());
        return feature;
    }

    // builds a query filter based on the filter in payload
    private SCQueryFilter getFilter(ReadableMap message) {
        SCQueryFilter filter = new SCQueryFilter();
        if (message.getMap("payload").getMap("filter").hasKey("$geocontains")) {
            ReadableArray extent = message.getMap("payload").getMap("filter").getArray("$geocontains");
            SCBoundingBox bbox = new SCBoundingBox(
                    extent.getDouble(0),
                    extent.getDouble(1),
                    extent.getDouble(2),
                    extent.getDouble(3)
            );
            filter = new SCQueryFilter(
                    new SCPredicate(bbox, SCGeometryPredicateComparison.SCPREDICATE_OPERATOR_WITHIN)
            );
        } else {
            // add a bbox for everything
            SCBoundingBox bbox = new SCBoundingBox(-180, -90, 180, 90);
            filter = new SCQueryFilter(
                    new SCPredicate(bbox, SCGeometryPredicateComparison.SCPREDICATE_OPERATOR_WITHIN)
            );
        }
        // add layers to filter
        if (message.getMap("payload").getMap("filter").hasKey("layers")) {
            ReadableArray layers = message.getMap("payload").getMap("filter").getArray("layers");
            for (int i = 0; i < layers.size(); i++) {
                filter.addLayerId(layers.getString(i));
            }
        }
        // add limit
        if (message.getMap("payload").getMap("filter").hasKey("limit")) {
            filter.setLimit(message.getMap("payload").getMap("filter").getInt("limit"));
        }
        return filter;
    }

    // creates a WriteableMap of the SCDataStore attributes
    private WritableMap getStoreMap(SCDataStore store) {
        WritableMap params = Arguments.createMap();
        params.putString("storeId", store.getStoreId());
        params.putString("name", store.getName());
        params.putString("type", store.getType());
        params.putString("version", store.getVersion());
        params.putString("key", store.getKey());
        params.putInt("status", store.getStatus().ordinal());
        params.putDouble("downloadProgress", store.getDownloadProgress());
        if (store instanceof ISCSpatialStore) {
            WritableArray a = Arguments.createArray();
            for (String vectorLayerName : ((ISCSpatialStore) store).vectorLayers()) {
                a.pushString(vectorLayerName);
            }
            params.putArray("vectorLayers", a);
        }
        if (store instanceof SCRasterStore) {
            WritableArray a = Arguments.createArray();
            for (String vectorLayerName : ((SCRasterStore) store).rasterLayers()) {
                a.pushString(vectorLayerName);
            }
            params.putArray("rasterLayers", a);
        }
        return params;
    }

    // creates a WriteableMap of the SCFormConfig attributes
    private WritableMap getFormMap(SCFormConfig formConfig) {
        WritableMap params = Arguments.createMap();
        params.putString("id", formConfig.getId());
        params.putString("form_key", formConfig.getFormKey());  // same as layer name
        params.putString("form_label", formConfig.getFormLabel());
        params.putString("version", formConfig.getVersion());
        // TODO: put username in metadatadata
        WritableArray fields = Arguments.createArray();
        // for each field, create a WriteableMap with all the SCFormField params
        for (SCFormField field : formConfig.getFields()) {
            WritableMap fieldMap = Arguments.createMap();
            fieldMap.putString("id", field.getId());
            fieldMap.putString("field_key", field.getKey());
            fieldMap.putString("field_label", field.getLabel());
            if (field.isRequired() != null) {
                fieldMap.putBoolean("is_required", field.isRequired());
            }
            if (field.getPosition() != null) {
                fieldMap.putInt("order", field.getPosition());
            }
            if (field.getType() != null) {
                fieldMap.putString("type", field.getType());
            }
            if (field.getInitialValue() != null) {
                fieldMap.putString("initial_value", String.valueOf(field.getInitialValue()));
            }
            if (field.getMaximum() != null) {
                fieldMap.putDouble("minimum", Double.valueOf(String.valueOf(field.getMinimum())));
            }
            if (field.getMaximum() != null) {
                fieldMap.putDouble("maximum", Double.valueOf(String.valueOf(field.getMaximum())));
            }
            if (field.isExclusiveMaximum() != null) {
                fieldMap.putBoolean("exclusive_maximum", field.isExclusiveMaximum());
            }
            if (field.isExclusiveMinimum() != null) {
                fieldMap.putBoolean("exclusive_minimum", field.isExclusiveMinimum());
            }
            if (field.isInteger() != null) {
                fieldMap.putBoolean("is_integer", field.isInteger());
            }
            if (field.getMaximumLength() != null) {
                fieldMap.putInt("maximum_length", field.getMaximumLength());
            }
            if (field.getMinimumLength() != null) {
                fieldMap.putInt("minimum_length", field.getMinimumLength());
            }
            if (field.getPattern() != null) {
                fieldMap.putString("pattern", field.getPattern());
            }
            fields.pushMap(fieldMap);
        }
        params.putArray("fields", fields);
        return params;
    }

    private static WritableMap convertJsonToMap(JSONObject jsonObject) throws JSONException {
        WritableMap map = new WritableNativeMap();

        Iterator<String> iterator = jsonObject.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            Object value = jsonObject.get(key);
            if (value instanceof JSONObject) {
                map.putMap(key, convertJsonToMap((JSONObject) value));
            }
            else if (value instanceof JSONArray) {
                map.putArray(key, convertJsonToArray((JSONArray) value));
            }
            else if (value instanceof Boolean) {
                map.putBoolean(key, (Boolean) value);
            }
            else if (value instanceof Integer) {
                map.putInt(key, (Integer) value);
            }
            else if (value instanceof Double) {
                map.putDouble(key, (Double) value);
            }
            else if (value instanceof String) {
                map.putString(key, (String) value);
            }
            else {
                map.putString(key, value.toString());
            }
        }
        return map;
    }

    private static WritableArray convertJsonToArray(JSONArray jsonArray) throws JSONException {
        WritableArray array = new WritableNativeArray();

        for (int i = 0; i < jsonArray.length(); i++) {
            Object value = jsonArray.get(i);
            if (value instanceof JSONObject) {
                array.pushMap(convertJsonToMap((JSONObject) value));
            }
            else if (value instanceof JSONArray) {
                array.pushArray(convertJsonToArray((JSONArray) value));
            }
            else if (value instanceof Boolean) {
                array.pushBoolean((Boolean) value);
            }
            else if (value instanceof Integer) {
                array.pushInt((Integer) value);
            }
            else if (value instanceof Double) {
                array.pushDouble((Double) value);
            }
            else if (value instanceof String) {
                array.pushString((String) value);
            }
            else {
                array.pushString(value.toString());
            }
        }
        return array;
    }

    private static JSONObject convertMapToJson(ReadableMap readableMap) {
        JSONObject object = new JSONObject();
        ReadableMapKeySetIterator iterator = readableMap.keySetIterator();
        try {

            while (iterator.hasNextKey()) {
                String key = iterator.nextKey();
                switch (readableMap.getType(key)) {
                    case Null:
                        object.put(key, JSONObject.NULL);
                        break;
                    case Boolean:
                        object.put(key, readableMap.getBoolean(key));
                        break;
                    case Number:
                        object.put(key, readableMap.getDouble(key));
                        break;
                    case String:
                        object.put(key, readableMap.getString(key));
                        break;
                    case Map:
                        object.put(key, convertMapToJson(readableMap.getMap(key)));
                        break;
                    case Array:
                        object.put(key, convertArrayToJson(readableMap.getArray(key)));
                        break;
                }
            }
        }
        catch (JSONException e) {
            Log.e(LOG_TAG, "Could not convert to json");
            e.printStackTrace();
        }
        return object;
    }

    private static JSONArray convertArrayToJson(ReadableArray readableArray) throws JSONException {
        JSONArray array = new JSONArray();
        for (int i = 0; i < readableArray.size(); i++) {
            switch (readableArray.getType(i)) {
                case Null:
                    break;
                case Boolean:
                    array.put(readableArray.getBoolean(i));
                    break;
                case Number:
                    array.put(readableArray.getDouble(i));
                    break;
                case String:
                    array.put(readableArray.getString(i));
                    break;
                case Map:
                    array.put(convertMapToJson(readableArray.getMap(i)));
                    break;
                case Array:
                    array.put(convertArrayToJson(readableArray.getArray(i)));
                    break;
            }
        }
        return array;
    }
}
