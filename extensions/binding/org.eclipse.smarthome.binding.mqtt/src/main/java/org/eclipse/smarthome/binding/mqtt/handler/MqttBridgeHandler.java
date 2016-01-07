package org.eclipse.smarthome.binding.mqtt.handler;

import static org.eclipse.smarthome.binding.mqtt.MqttBindingConstants.*;

import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.smarthome.binding.mqtt.discovery.MqttDiscoveryService2;
import org.eclipse.smarthome.binding.mqtt.internal.MqttMessagePublisher;
import org.eclipse.smarthome.binding.mqtt.internal.MqttMessageSubscriber;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.io.transport.mqtt.MqttConnectionObserver;
import org.eclipse.smarthome.io.transport.mqtt.MqttService;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MqttBridgeHandler} is responsible for handling connection to MQTT service
 *
 * @author Marcus of Wetware Labs - Initial contribution
 */
public class MqttBridgeHandler extends BaseBridgeHandler implements MqttConnectionObserver {

    public final static Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_BRIDGE);

    private Map<ThingUID, ServiceRegistration<?>> discoveryServiceRegs = new HashMap<>();

    private Logger logger = LoggerFactory.getLogger(MqttBridgeHandler.class);

    private List<MqttBridgeListener> mqttBridgeListeners = new CopyOnWriteArrayList<>();

    private String broker;

    /** MqttService for sending/receiving messages **/
    private MqttService mqttService;

    private MqttDiscoveryService2 discoveryService;

    public MqttBridgeHandler(Bridge mqttBridge) {
        super(mqttBridge);
    }

    @Override
    public void thingUpdated(Thing thing) {
        unregisterConnectionObserver(broker, this);
        initialize();
    }

    /**
     * Initializes the topics for this bridge
     */
    private void initializeTopics() {
        for (Thing thing : getThing().getThings()) {
            ThingHandler handler = thing.getHandler();
            if (handler != null) {
                handler.initialize();
            }
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // TODO Auto-generated method stub. No implementation needed?
    }

    /***
     * Get MQTT Broker name (actually the bridge ID).
     *
     * @return broker name
     */
    public String getBroker() {
        return broker;
    }

    /***
     * Register a MQTT topic subscriber to the broker associated with this bridge
     *
     * @param subscriber message subscriber
     */
    public void registerMessageConsumer(MqttMessageSubscriber subscriber) {
        mqttService.registerMessageConsumer(broker, subscriber);
    }

    /***
     * Register a MQTT topic publisher to the broker associated with this bridge
     *
     * @param publisher message publisher to be registered
     */
    public void registerMessageProducer(MqttMessagePublisher publisher) {
        mqttService.registerMessageProducer(broker, publisher);
    }

    /***
     * Unregister a MQTT topic subscriber from the broker associated with this bridge
     *
     * @param subscriber message subscriber to be unregistered
     */
    public void unRegisterMessageConsumer(MqttMessageSubscriber subscriber) {
        mqttService.unregisterMessageConsumer(broker, subscriber);
    }

    /***
     * Unregister a MQTT topic publisher from the broker associated with this bridge
     *
     * @param publisher message publisher to be unregistered
     */
    public void unRegisterMessageProducer(MqttMessagePublisher publisher) {
        mqttService.unregisterMessageProducer(broker, publisher);
    }

    public void registerConnectionObserver(String brokerName, MqttConnectionObserver connectionObserver) {
        mqttService.registerConnectionObserver(brokerName, connectionObserver);
    }

    public void unregisterConnectionObserver(String brokerName, MqttConnectionObserver connectionObserver) {
        mqttService.unregisterConnectionObserver(brokerName, connectionObserver);
    }

    /***
     * Called by the framework when this MQTT bridge is initialized.
     */
    @Override
    public void initialize() {
        logger.debug("Initializing MQTT bridge handler.");

        // final String broker = this.getThing().getBridgeUID().segments[2];
        broker = this.getThing().getUID().getId();
        // broker = (String) getConfig().get(BROKER);

        try {
            // get a reference to org.eclipse.smarthome.io.transport.mqtt service
            ServiceReference<MqttService> mqttServiceReference = bundleContext.getServiceReference(MqttService.class);
            mqttService = bundleContext.getService(mqttServiceReference);
            Dictionary<String, Object> properties = null;
            try {
                // get reference to ConfigurationAdmin and update the configuration of io.transport.mqtt service (PID is
                // actually org.eclipse.smarthome.mqtt)
                ServiceReference<ConfigurationAdmin> configurationAdminReference = bundleContext
                        .getServiceReference(ConfigurationAdmin.class);
                if (configurationAdminReference != null) {
                    ConfigurationAdmin confAdmin = bundleContext.getService(configurationAdminReference);

                    Configuration mqttServiceConf = confAdmin.getConfiguration(MQTT_SERVICE_PID);
                    properties = mqttServiceConf.getProperties();
                }

            } catch (Exception e) {
                logger.error("Failed to get Service Admin");
            }
            try {
                if (properties == null) {
                    // confAdmin.createFactoryConfiguration(MQTT_SERVICE_PID);
                    // properties = mqttServiceConf.getProperties();
                    properties = new Hashtable<String, Object>();
                    properties.put("service.pid", MQTT_SERVICE_PID); // CHECK! initialize the PID. Is this
                                                                     // necessary?
                }

                if (getConfig().get(URL) != null) {
                    properties.put(broker + "." + URL, getConfig().get(URL));
                }
                if (getConfig().get(USER) != null) {
                    properties.put(broker + "." + USER, getConfig().get(USER));
                }
                if (getConfig().get(PWD) != null) {
                    properties.put(broker + "." + PWD, getConfig().get(PWD));
                }
                if (getConfig().get(CLIENTID) != null) {
                    properties.put(broker + "." + CLIENTID, getConfig().get(CLIENTID));
                } else {
                    properties.put(broker + "." + CLIENTID, getThing().getUID().getId());
                }
                if (getConfig().get(QOS) != null) {
                    properties.put(broker + "." + QOS, getConfig().get(QOS));
                }
                if (getConfig().get(RETAIN) != null) {
                    properties.put(broker + "." + RETAIN, getConfig().get(RETAIN).toString());
                }
                if (getConfig().get(ASYNC) != null) {
                    properties.put(broker + "." + ASYNC, getConfig().get(ASYNC).toString());
                }

                logger.debug("Initiate for broker {}", broker);
                // mqttServiceConf.update(properties); // FIXME! Updating properties like this via Configuration
                // class does not notify the mqttservice!
                mqttService.updated(properties); // CHECK! Is this safe to do? Properties set this way are not
                                                 // propagated to ConfigurationAdmin..
                // updateStatus(ThingStatus.ONLINE);

            } catch (Exception e) {
                logger.error("Failed to set MQTT broker properties");
            }
        } catch (Exception e) {
            logger.error("Failed to get MQTT service!");
        }
        initializeTopics();
        initializeDiscoveryTopics();

        registerConnectionObserver(broker, this);
        // registerMqttDiscoveryService(mqttService, broker);
        // registerMqttDiscoveryService(this, getThing());
    }

    private void initializeDiscoveryTopics() {
        String discoveryTopic = null;
        String discoveryMode = null;
        if (getConfig().get(DISCOVERYTOPIC) != null && !((String) getConfig().get(DISCOVERYTOPIC)).isEmpty()) {
            discoveryTopic = (String) getConfig().get(DISCOVERYTOPIC);
        }
        if (getConfig().get(DISCOVERYMODE) != null && !((String) getConfig().get(DISCOVERYMODE)).isEmpty()) {
            discoveryMode = (String) getConfig().get(DISCOVERYMODE);
        }

        for (MqttBridgeListener mqttBridgeListener : mqttBridgeListeners) {
            mqttBridgeListener.discoveryConfigUpdate(discoveryTopic, discoveryMode);
        }

    }

    /***
     * Called by the framework when this MQTT bridge is removed.
     */
    @Override
    public void dispose() {
        logger.debug("Mqtt Handler disposed.");
        // discoveryService.deactivate();
        // unregisterMqttDiscoveryService(this);
        super.dispose();
    }

    /***
     * Called by a Topic handler to register itself to the bridge in order to get events.
     * Currently no events are being sent/implemented.
     *
     * @param mqttBridgeListener Topic handler to be registered
     * @return true if success
     */
    public boolean registerMqttBridgeListener(MqttBridgeListener mqttBridgeListener) {
        if (mqttBridgeListener == null) {
            throw new NullPointerException("It's not allowed to pass a null mqttBridgeListener.");
        }
        boolean result = mqttBridgeListeners.add(mqttBridgeListener);
        if (result) {
            // no action needed yet
        }
        return result;
    }

    /***
     * Called by a Topic handler to register itself to the bridge in order to get events.
     * Currently no events are being sent/implemented.
     *
     * @param mqttBridgeListener Topic handler to be registered
     * @return true if success
     */
    public boolean unRegisterMqttBridgeListener(MqttBridgeListener mqttBridgeListener) {
        if (mqttBridgeListener == null) {
            throw new NullPointerException("It's not allowed to pass a null mqttBridgeListener.");
        }
        boolean result = mqttBridgeListeners.remove(mqttBridgeListener);
        if (result) {
            // no action needed yet
        }
        return result;
    }

    @Override
    public void setConnected(boolean connected) {
        if (connected) {
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatus.OFFLINE);
        }

    }

    // private void registerMqttDiscoveryService(MqttService mqttService, String brokerName) {
    // // TODO: choose discovery method 1 or 2
    // discoveryService = new MqttDiscoveryService2(mqttService, brokerName);
    // discoveryService.activate();
    // }

    // /**
    // * @param MqttBridgeHandler
    // */
    //
    // private void unregisterMqttDiscoveryService(MqttBridgeHandler MqttBridgeHandler) {
    // ServiceRegistration<?> serviceReg = this.discoveryServiceRegs.get(MqttBridgeHandler.getThing().getUID());
    // if (serviceReg != null) {
    // // remove discovery service, if bridge handler is removed
    // MqttDiscoveryService service = (MqttDiscoveryService) bundleContext.getService(serviceReg.getReference());
    // service.deactivate();
    // serviceReg.unregister();
    // discoveryServiceRegs.remove(MqttBridgeHandler.getThing().getUID());
    // }
    // }
    //
    // private void registerMqttDiscoveryService(MqttBridgeHandler MqttBridgeHandler, Thing thing) {
    // MqttDiscoveryService discoveryService = new MqttDiscoveryService(MqttBridgeHandler, thing);
    // discoveryService.activate();
    //
    // this.discoveryServiceRegs.put(((ThingHandler) MqttBridgeHandler).getThing().getUID(), bundleContext
    // .registerService(DiscoveryService.class.getName(), discoveryService, new Hashtable<String, Object>()));
    // }

}
