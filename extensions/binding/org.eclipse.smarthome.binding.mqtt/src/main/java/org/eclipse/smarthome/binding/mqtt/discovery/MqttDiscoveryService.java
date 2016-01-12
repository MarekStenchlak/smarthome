/**
 * Copyright (c) 2014 openHAB UG (haftungsbeschraenkt) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.smarthome.binding.mqtt.discovery;

import static org.eclipse.smarthome.binding.mqtt.MqttBindingConstants.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.eclipse.smarthome.binding.mqtt.handler.MqttBridgeHandler;
import org.eclipse.smarthome.binding.mqtt.handler.MqttBridgeListener;
import org.eclipse.smarthome.binding.mqtt.internal.MqttMessageSubscriber;
import org.eclipse.smarthome.binding.mqtt.internal.MqttMessageSubscriberListener;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MqttDiscoveryService} is responsible for discovering MQTT Topics as Things.
 *
 * @author Marcel Verpaalen - Initial contribution
 *
 */
public class MqttDiscoveryService extends AbstractDiscoveryService
        implements MqttMessageSubscriberListener, MqttBridgeListener {

    private Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            subscribe();
        }
    };

    private MqttBridgeHandler bridgeHandler;

    private HashSet<String> discoveredTopics = new HashSet<String>();

    private MqttMessageSubscriber subscriber;

    private Thing bridge;

    private String discoveryTopic = "#";

    private String discoveryMode = "MULTIPLE";

    private final static Logger logger = LoggerFactory.getLogger(MqttDiscoveryService.class);

    public MqttDiscoveryService(MqttBridgeHandler mqttBridgeHandler, Thing thing) {
        super(10);
        // super(MqttHandler.SUPPORTED_THING_TYPES, 10, true);
        this.bridgeHandler = mqttBridgeHandler;
        this.bridge = thing;
    }

    public MqttDiscoveryService(MqttBridgeHandler mqttBridgeHandler, Thing thing, String discoveryTopic,
            String discoveryMode) {
        super(10);
        // super(MqttHandler.SUPPORTED_THING_TYPES, 10, true);
        this.bridgeHandler = mqttBridgeHandler;
        this.bridge = thing;
        this.discoveryTopic = discoveryTopic;
        this.discoveryMode = discoveryMode;
    }

    @Override
    protected void startScan() {
        // TODO Auto-generated method stub
        subscribe();

    }

    public void activate() {
        subscribe();
        removeOlderResults(getTimestampOfLastScan());
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
        if (bridgeHandler != null) {
            bridgeHandler.unRegisterMessageConsumer(subscriber);
            bridgeHandler.unRegisterMqttBridgeListener(this);
        }
        discoveredTopics.clear();
    }

    private void subscribe() {

        // TODO: fix ugly hack with proper check for broker to be up
        if (bridgeHandler.getBroker() == null) {
            logger.debug("delay discovery until broker is avail");
            scheduler.schedule(pollingRunnable, 10, TimeUnit.SECONDS);
        } else {
            try {
                logger.error("Registering discovery subscriber for broker: {}", bridgeHandler.getBroker());

                subscriber = new MqttMessageSubscriber(
                        // getBridgeHandler().getUID().getId() + ":" + topic + ":" + type + ":" + transform, this);
                        bridgeHandler.getBroker() + ":" + discoveryTopic + ":" + "state" + ":" + "default", this);

                bridgeHandler.registerMessageConsumer(subscriber);
                bridgeHandler.registerMqttBridgeListener(this);

                logger.error("Registered discovery subscriber for broker: {}", bridgeHandler.getBroker());

            } catch (Exception e) {
                logger.error("Could not create subscriber: {}", e.getMessage());
                logger.error("Fail to register discovery subscriber for broker: {}", bridgeHandler.getBroker());

            }
        }
    }

    @Override
    public void mqttCommandReceived(String topic, String command) {
        // Ignore for now

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
        logger.trace("MQTT topic discovery: Received state (topic '{}' payload '{}')", topic, state.toString());

        String discoveredTopic = topic;
        if (discoveryMode.equals("MULTIPLE")) {
            String[] result = topic.split("/");
            if (result.length > 2) {
                result[result.length - 1] = "#";
            }
            discoveredTopic = StringUtils.join(result, "/");
        }
        if (!discoveredTopics.contains(discoveredTopic)) {
            addDiscoveryResult(discoveredTopic);
        }
    }

    /**
     * @param topic
     */
    private void addDiscoveryResult(String topic) {
        String id = makeTopicString(topic);

        logger.trace("Adding new topic thing on {} with id '{}' to Smarthome inbox", topic, id);
        Map<String, Object> properties = new HashMap<>(2);
        properties.put(TOPIC_ID, topic);
        properties.put(TYPE, "state");
        properties.put(DIRECTION, "in");
        properties.put(TRANSFORM, "default");
        ThingUID uid = new ThingUID(THING_TYPE_TOPIC, id);
        if (uid != null) {
            DiscoveryResult result = DiscoveryResultBuilder.create(uid).withProperties(properties)
                    .withBridge(bridge.getUID()).withLabel("topic: " + topic).build();
            thingDiscovered(result);
        }
        discoveredTopics.add(topic);
    }

    private String makeTopicString(String topicString) {

        if (StringUtils.isEmpty(topicString)) {
            return new String("empty");
        }

        String[] result = topicString.split("/");
        for (int i = 0; i < result.length; i++) {
            result[i] = capitalize(result[i]);
        }

        String resulttopic = StringUtils.join(result, "").replace("#", "");
        return resulttopic;
    }

    private static String capitalize(String string) {
        if (string == null || string.length() == 0) {
            return string;
        }
        char c[] = string.toCharArray();
        c[0] = Character.toUpperCase(c[0]);
        return new String(c);
    }

    @Override
    public void discoveryConfigUpdate(String discoveryTopic, String discoveryMode) {
        this.discoveryTopic = discoveryTopic;
        this.discoveryMode = discoveryMode;
        if (bridgeHandler != null) {
            bridgeHandler.unRegisterMessageConsumer(subscriber);
        }
        subscribe();
    }

    @Override
    public void setBridgeConnected(boolean connected) {
        // TODO Auto-generated method stub

    }

}
