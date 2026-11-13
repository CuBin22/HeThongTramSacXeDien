package vn.edu.campuscharge.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A bounded MQTT connection; callbacks leave Paho's network thread before touching the database. */
public class MqttEndpoint implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(MqttEndpoint.class);
    private final MqttClient client;
    private final MqttConnectOptions options=new MqttConnectOptions();
    private final ObjectMapper json;
    private final ExecutorService incoming=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(512),
        Thread.ofPlatform().daemon().name("mqtt-incoming-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService maintenance=Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("mqtt-maintenance-",0).factory());
    private ScheduledFuture<?> maintenanceTask;
    private volatile String[] filters=new String[0];
    private volatile BiConsumer<String,byte[]> handler=(topic,payload)->{};
    private volatile boolean subscriptionsReady;
    private volatile boolean closed;
    public MqttEndpoint(String url,String clientId,String username,String password,ObjectMapper json) {
        this.json=json;
        try { client=new MqttClient(url,clientId,new MemoryPersistence()); client.setTimeToWait(2500); }
        catch(MqttException e) { throw new IllegalStateException("Cannot create MQTT client",e); }
        options.setConnectionTimeout(2); options.setKeepAliveInterval(5); options.setAutomaticReconnect(true); options.setCleanSession(true);
        if(username!=null && !username.isBlank()) { options.setUserName(username); options.setPassword(password.toCharArray()); }
        client.setCallback(new MqttCallbackExtended() {
            public void connectComplete(boolean reconnect,String serverURI) { subscriptionsReady=false; }
            public void connectionLost(Throwable cause) { subscriptionsReady=false; log.warn("MQTT connection lost; durable commands remain retryable"); }
            public void deliveryComplete(IMqttDeliveryToken token) {}
            public void messageArrived(String topic,MqttMessage message) {
                byte[] payload=Arrays.copyOf(message.getPayload(),message.getPayload().length);
                try { incoming.execute(()-> { try { handler.accept(topic,payload); } catch(RuntimeException invalid) { log.warn("Rejected MQTT message on {}: {} ({})",topic,invalid.getClass().getSimpleName(),invalid.getMessage()); } }); }
                catch(RejectedExecutionException busy) { log.warn("MQTT input queue full; command acknowledgements remain retryable"); }
            }
        });
    }
    public synchronized void start(String[] filters,BiConsumer<String,byte[]> handler) {
        this.filters=filters.clone(); this.handler=handler; subscriptionsReady=false;
        // Connection/subscription recovery must run even while the command outbox is empty.
        if(maintenanceTask==null) maintenanceTask=maintenance.scheduleWithFixedDelay(this::maintainConnection,1,1,TimeUnit.SECONDS);
        connect();
    }
    private void maintainConnection() {
        try { connect(); }
        catch(IllegalStateException unavailable) { log.debug("MQTT connection/subscription retry pending"); }
    }
    private synchronized void connect() {
        if(closed) throw new IllegalStateException("MQTT connection closed");
        try {
            if(!client.isConnected()) client.connect(options);
            if(!subscriptionsReady) subscribe();
        } catch(MqttException e) { throw new IllegalStateException("MQTT broker unavailable",e); }
    }
    private void subscribe() {
        if(closed) return;
        try { if(filters.length>0) { int[] qos=new int[filters.length]; Arrays.fill(qos,1); client.subscribe(filters,qos); } subscriptionsReady=true; }
        catch(MqttException e) { throw new IllegalStateException("MQTT subscription unavailable",e); }
    }
    public void publish(String topic,Object value) {
        connect();
        try { MqttMessage message=new MqttMessage(json.writeValueAsBytes(value)); message.setQos(1); message.setRetained(false); client.publish(topic,message); }
        catch(Exception e) { throw new IllegalStateException("MQTT delivery unavailable",e); }
    }
    public boolean connected() { return client.isConnected(); }
    @Override public synchronized void close() {
        if(closed) return; closed=true;
        maintenance.shutdownNow();
        try { if(client.isConnected()) client.disconnect(100); client.close(true); } catch(MqttException e) { log.debug("MQTT close",e); }
        incoming.shutdownNow();
    }
}
