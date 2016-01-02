/**
 * Copyright (c) 2014 openHAB UG (haftungsbeschraenkt) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.smarthome.binding.mqtt.discovery;

import static org.eclipse.smarthome.binding.mqtt.MqttBindingConstants.*;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.core.events.EventPublisher;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.io.transport.mqtt.MqttMessageConsumer;
import org.eclipse.smarthome.io.transport.mqtt.MqttService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MqttDiscoveryService2} is responsible for discovering MQTT Topics as Things.
 *
 * @author Marcel Verpaalen - Initial contribution
 *
 */
public class MqttDiscoveryService2 extends AbstractDiscoveryService implements MqttMessageConsumer {

    private final static String discoveryTopicString = "#";

    /** MqttService for sending/receiving messages **/
    private MqttService mqttService;

    private HashSet<String> discoveredTopics = new HashSet<String>();

    private String brokerName;

    private final static Logger logger = LoggerFactory.getLogger(MqttDiscoveryService2.class);

    public MqttDiscoveryService2(MqttService mqttService, String brokerName) {
        super(10);
        logger.debug("Initiating MQTT topic discovery for broker {}.", brokerName);
        this.mqttService = mqttService;
        this.brokerName = brokerName;
    }

    @Override
    protected void startScan() {
        logger.debug("MQTT startscan initiated");
        discoveredTopics.clear();
    }

    public void activate() {
        logger.debug("Activated MQTT topic discovery for broker {}.", brokerName);
        mqttService.registerMessageConsumer(brokerName, this);
        removeOlderResults(getTimestampOfLastScan());
    }

    @Override
    protected void startBackgroundDiscovery() {
    }

    @Override
    public void deactivate() {
        mqttService.unregisterMessageConsumer(brokerName, this);
        discoveredTopics.clear();
    }

    /***
     * Received topic and add it to the discovery
     *
     * @param topic MQTT topic of the received message
     * @param state Payload of the message
     */
    public void topicReceived(String topic, String state) {
        logger.debug("MQTT topic discovery: Received state (topic '{}' payload '{}')", topic, state.toString());

        if (!discoveredTopics.contains(topic)) {
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
                        .withLabel("topic " + id).build();
                thingDiscovered(result);
            }
            discoveredTopics.add(topic);
        }
    }

    private String makeTopicString(String topicString) {

        if (StringUtils.isEmpty(topicString)) {
            return new String("empty");
        }

        String[] result = topicString.split("/");
        for (int i = 0; i < result.length; i++) {
            result[i] = capitalize(result[i]);
        }

        String resulttopic = StringUtils.join(result, "");
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
    public void processMessage(String topic, byte[] payload) {
        try {
            topicReceived(topic, new String(payload, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            topicReceived(topic, "");
        }
    }

    @Override
    public String getTopic() {
        return discoveryTopicString;
    }

    @Override
    public void setTopic(String topic) {
        logger.trace("discovery setTopic tiggered ");

    }

    @Override
    public void setEventPublisher(EventPublisher eventPublisher) {
        logger.trace("discovery setEventPublisher tiggered ");

    }

}
