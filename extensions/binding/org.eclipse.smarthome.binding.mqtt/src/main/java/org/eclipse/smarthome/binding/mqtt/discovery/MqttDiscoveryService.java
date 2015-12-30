package org.eclipse.smarthome.binding.mqtt.discovery;

import org.eclipse.smarthome.binding.mqtt.handler.MqttBridgeHandler;
import org.eclipse.smarthome.binding.mqtt.handler.MqttBridgeListener;
import org.eclipse.smarthome.binding.mqtt.internal.MqttMessageSubscriber;
import org.eclipse.smarthome.binding.mqtt.internal.MqttMessageSubscriberListener;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MqttDiscoveryService extends AbstractDiscoveryService
        implements MqttMessageSubscriberListener, MqttBridgeListener {

    private MqttBridgeHandler bridgeHandler;

    private final static Logger logger = LoggerFactory.getLogger(MqttDiscoveryService.class);

    public MqttDiscoveryService(MqttBridgeHandler mqttBridgeHandler) {
        super(10);
        // super(MqttHandler.SUPPORTED_THING_TYPES, 10, true);
        this.bridgeHandler = mqttBridgeHandler;
    }

    @Override
    protected void startScan() {
        // TODO Auto-generated method stub
        subscribe();

    }

    public void activate() {
        // maxCubeBridgeHandler.registerDeviceStatusListener(this);
        subscribe();
    }

    @Override
    protected void startBackgroundDiscovery() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                subscribe();
            }
        }).start();
    }

    @Override
    public void deactivate() {
        // maxCubeBridgeHandler.unregisterDeviceStatusListener(this);
    }

    private void subscribe() {

        try {

            MqttMessageSubscriber subscriber = new MqttMessageSubscriber(
                    // getBridgeHandler().getUID().getId() + ":" + topic + ":" + type + ":" + transform, this);
                    bridgeHandler.getBroker() + ":" + "#" + ":" + "state" + ":" + "default", this);

            bridgeHandler.registerMessageConsumer(subscriber);
            logger.error("Registered discovery subscriber for broker: {}", bridgeHandler.getBroker());

        } catch (Exception e) {
            logger.error("Could not create subscriber: {}", e.getMessage());
        }
    }

    @Override
    public void mqttCommandReceived(String topic, String command) {
        // TODO Auto-generated method stub

    }

    /***
     * Received state from MqttMessageSubscriber. Try to cast it to every possible State Type and send it to all
     * channels that support this type
     *
     * @param topic MQTT topic of the received message
     * @param state Payload of the message
     */
    @Override
    public void mqttStateReceived(String topic, String state) {
        logger.info("received topic: {}", topic);
        logger.debug("MQTT: Received state (topic '{}' payload '{}')", topic, state);

        /*
         * logger.trace("Adding new MAX! Cube Lan Gateway on {} with id '{}' to Smarthome inbox", IpAddress,
         * cubeSerialNumber);
         * Map<String, Object> properties = new HashMap<>(2);
         * properties.put(MaxBinding.PROPERTY_IP_ADDRESS, IpAddress);
         * properties.put(MaxBinding.PROPERTY_SERIAL_NUMBER, cubeSerialNumber);
         * properties.put(MaxBinding.PROPERTY_RFADDRESS, rfAddress);
         * ThingUID uid = new ThingUID(MaxBinding.CUBEBRIDGE_THING_TYPE, cubeSerialNumber);
         * if (uid != null) {
         * DiscoveryResult result = DiscoveryResultBuilder.create(uid).withProperties(properties)
         * .withLabel("MAX! Cube LAN Gateway").build();
         * thingDiscovered(result);
         * }
         */

    }

}
