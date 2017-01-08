/**
 * Copyright (c) 2014 openHAB UG (haftungsbeschraenkt) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.smarthome.binding.mqtt.handler;

import static org.eclipse.smarthome.binding.mqtt.MqttBindingConstants.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.eclipse.smarthome.binding.mqtt.MqttBindingConstants;
import org.eclipse.smarthome.binding.mqtt.internal.MqttMessagePublisher;
import org.eclipse.smarthome.binding.mqtt.internal.MqttMessageSubscriber;
import org.eclipse.smarthome.binding.mqtt.internal.MqttMessageSubscriberListener;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.items.GenericItem;
import org.eclipse.smarthome.core.library.items.ColorItem;
import org.eclipse.smarthome.core.library.items.ContactItem;
import org.eclipse.smarthome.core.library.items.DateTimeItem;
import org.eclipse.smarthome.core.library.items.DimmerItem;
import org.eclipse.smarthome.core.library.items.NumberItem;
import org.eclipse.smarthome.core.library.items.RollershutterItem;
import org.eclipse.smarthome.core.library.items.StringItem;
import org.eclipse.smarthome.core.library.items.SwitchItem;
import org.eclipse.smarthome.core.library.types.DateTimeType;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.HSBType;
import org.eclipse.smarthome.core.library.types.IncreaseDecreaseType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.OpenClosedType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.StopMoveType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.library.types.UpDownType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.thing.binding.builder.ChannelBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ThingBuilder;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.Type;
import org.eclipse.smarthome.core.types.TypeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

/**
 * The {@link MqttHandler} is responsible for handling MQTT Topics as Things. MQTT messages are propagated then into
 * relevant channels and vice versa.
 *
 * @author Marcus of Wetware Labs - Initial contribution
 * @author Marcel Verpaalen - ESH version, multi-topic things, dynamic channels
 *
 */

// TODO: Allow changing of channel type!
// TODO: Apply logic build in state in the commands as well
// TODO: Major cleanup
// TODO: Move the properties down to the channel level (in-out, state/command, transform etc)
// TODO: Add button to add a channel
// Ensure transform is working

public class MqttHandler extends BaseThingHandler implements MqttBridgeListener, MqttMessageSubscriberListener {

    /* (non-Javadoc)
     * @see org.eclipse.smarthome.core.thing.binding.BaseThingHandler#handleConfigurationUpdate(java.util.Map)
     */
    @Override
    public void handleConfigurationUpdate(Map<String, Object> configurationParameters) {
        logger.debug("MQTT Config update called for {}", getThing().getUID().getAsString());

        for (Entry<String, Object> configurationParmeter : configurationParameters.entrySet()) {
            String key = configurationParmeter.getKey();
            Object newValue = configurationParmeter.getValue();
            logger.debug("MQTT Config parameter {} value {}", key, newValue);
        }

        super.handleConfigurationUpdate(configurationParameters);
    }

    private static final String CORE_LIBRARY_PACKAGE = "org.eclipse.smarthome.core.library.items.";

    @Override
    public void thingUpdated(Thing thing) {
        logger.debug("Updated called for  MQTT topic handler '{}'.", getThing().getUID().getAsString());
        //TODO: there is no channel updated, hence we nee to catch it here
        //TODO: somehow the framework keeps the old accepted type even thought th e channel is re-created with new channel type

        for (Channel channel : getThing().getChannels()) {

            String channelItemType = (String) channel.getConfiguration().get("itemtype");
            //   GenericItem item = parseType(channelItemType);

            logger.debug("Channel {} type: {} test itemType {}, acctepted type {}", channel.getUID().getAsString(),
                    channel.getChannelTypeUID(), channelItemType, channel.getAcceptedItemType());

            if (!channelItemType.contains(channel.getAcceptedItemType())) {
                logger.debug("NO MATCH, fixing");
                String chtopic = (String) channel.getConfiguration().get(TOPIC_ID);
                removeChannel(chtopic, channel.getUID().getId());
                String type = channelItemType.substring(0, channelItemType.length() - 4);
                newChannelfromTopic(chtopic, type, type + "-channel");
            }
        }
        super.thingUpdated(thing);
    }

    public final static Set<ThingTypeUID> SUPPORTED_THING_TYPES = Sets.newHashSet(THING_TYPE_TOPIC);

    private Logger logger = LoggerFactory.getLogger(MqttHandler.class);

    // lists for propagating received states and commands into possible channels (eventually reaching an Ttem)
    HashMap<String, GenericItem> itemList = new HashMap<String, GenericItem>();
    List<Class<? extends State>> stateList = new ArrayList<Class<? extends State>>();
    List<Class<? extends Command>> commandList = new ArrayList<Class<? extends Command>>();

    private HashSet<String> channelTopics = new HashSet<String>();

    private MqttBridgeHandler bridgeHandler;

    /** Message producer for sending messages to MQTT **/
    private MqttMessagePublisher publisher;

    /** Message consumer for receiving state messages from MQTT **/
    private MqttMessageSubscriber subscriber;

    private boolean initialized = false;

    /***
     * Called by framework when this Topic handler is initialized
     */
    @Override
    public void initialize() {
        logger.debug("Initializing MQTT topic handler '{}'.", getThing().getUID().getAsString());
        final String topicId = (String) getConfig().get(TOPIC_ID);
        final String type = (String) getConfig().get(TYPE);

        String transform = null;
        try {
            transform = (String) getConfig().get(TRANSFORM);
        } catch (Exception e) {
            //
        }

        if (topicId != null) {
            if (type != null) {
                if (getBridgeHandler() != null) {
                    if (getConfig().get(DIRECTION) != null) {
                        if (getConfig().get(DIRECTION).equals("in")) {
                            setupSubscriber(topicId, type, transform);
                        } else if (getConfig().get(DIRECTION).equals("out")) {
                            setupPublisher(topicId, type, transform);
                        } else if (getConfig().get(DIRECTION).equals("both")) {
                            setupSubscriber(topicId, type, transform);
                            setupPublisher(topicId, type, transform);
                        } else {
                            throw new IllegalArgumentException("MQTT direction invalid!");
                        }
                    } else {
                        throw new IllegalArgumentException("MQTT direction must be defined!");
                    }
                }
            } else {
                throw new IllegalArgumentException("MQTT type must be defined!");
            }
        } else {
            throw new IllegalArgumentException("MQTT topic must be defined!");
        }

        String discoveredTopic = (String) getConfig().get(DISCOVERED_TOPIC);
        if (discoveredTopic != null && !discoveredTopic.isEmpty()) {
            newChannelfromTopic(discoveredTopic);
        }

        stateList.add(OnOffType.class);
        stateList.add(OpenClosedType.class);
        stateList.add(UpDownType.class);
        stateList.add(HSBType.class);
        stateList.add(PercentType.class);
        stateList.add(DecimalType.class);
        stateList.add(DateTimeType.class);
        stateList.add(StringType.class);

        commandList.add(OnOffType.class);
        commandList.add(OpenClosedType.class);
        commandList.add(UpDownType.class);
        commandList.add(IncreaseDecreaseType.class);
        commandList.add(StopMoveType.class);
        commandList.add(HSBType.class);
        commandList.add(PercentType.class);
        commandList.add(DecimalType.class);
        commandList.add(StringType.class);

        itemList.put(CHANNEL_CONTACT, new ContactItem(""));
        itemList.put(CHANNEL_DATETIME, new DateTimeItem(""));
        itemList.put(CHANNEL_DIMMER, new DimmerItem(""));
        itemList.put(CHANNEL_NUMBER, new NumberItem(""));
        itemList.put(CHANNEL_ROLLERSHUTTER, new RollershutterItem(""));
        itemList.put(CHANNEL_STRING, new StringItem(""));
        itemList.put(CHANNEL_SWITCH, new SwitchItem(""));
        itemList.put(CHANNEL_COLOR, new ColorItem(""));

        if (getBridgeHandler() != null) {
            getBridgeHandler().registerMqttBridgeListener(this);
            updateStatus(ThingStatus.ONLINE);
            initialized = true;

        }
        logger.debug("MQTT topic {} handler initialized.", topicId);
    }

    /***
     * Callback from framework when this Topic handler is deleted
     */
    @Override
    public void dispose() {
        logger.debug("Disposing MQTT topic handler.");
        if (publisher != null) {
            getBridgeHandler().unRegisterMessageProducer(publisher);
        }
        if (subscriber != null) {
            getBridgeHandler().unRegisterMessageConsumer(subscriber);
        }
        if (getBridgeHandler() != null) {
            getBridgeHandler().unRegisterMqttBridgeListener(this);
        }
        super.dispose();

    }

    public MqttHandler(Thing thing) {
        super(thing);
    }

    /**
     * Handles a command for a given channel.
     *
     * @param channelUID unique identifier of the channel on which the update was performed
     * @param command new command
     */
    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (publisher != null) {
            String cmdstr = command.toString();
            logger.debug("MQTT: send command '{}' as topic '{}'", cmdstr, publisher.getTopic());
            publisher.publish(publisher.getTopic(), cmdstr.getBytes());
        } else {
            logger.warn("MQTT: handleCommand invoked on topic '{}' but declared 'input'! Ignoring..");
        }
    }

    /***
     * Received command from MqttMessageSubscriber. Try to cast it to every possible Command Type and send it to all
     * channels that support this type
     *
     * @param topic MQTT topic of the received message
     * @param command Payload of the message
     */
    @Override
    public void mqttCommandReceived(String topic, String command) {

        logger.debug("MQTT: Received command (topic '{}' payload '{}')", topic, command);

        for (String channel : itemList.keySet()) {
            // for (Channel channel : getThing().getChannels()) {
            // go through every active (linked) channel and check if the Item associated with it has DataTypes that we
            // can cast the command into

            if (isLinked(channel)) {
                // if (true) {
                for (Class<? extends Type> asc : itemList.get(channel).getAcceptedDataTypes()) {

                    try {
                        Method valueOf = asc.getMethod("valueOf", String.class);
                        Command c = (Command) valueOf.invoke(asc, command);
                        if (c != null) {
                            // command could be casted to type 'type'
                            logger.debug(
                                    "MQTT: Received state (topic '{}'). Propagating payload '{}' as type '{}' to channel '{}')",
                                    topic, command, c.getClass().getName(), channel);
                            // postCommand(channel, c);
                            break;
                        }
                    } catch (NoSuchMethodException e) {
                    } catch (IllegalArgumentException e) {
                    } catch (IllegalAccessException e) {
                    } catch (InvocationTargetException e) {
                    }
                }
            }
        }

    }

    /**
     * Parses a string into a type.
     *
     * @param typeName name of the type, for example StringType.
     * @return Parsed type or null, if the type couldn't be parsed.
     */
    public GenericItem parseType(String typeName) {
        try {
            Class<?> stateClass = Class.forName(CORE_LIBRARY_PACKAGE + typeName);
            Object type = stateClass.getConstructors()[0].newInstance("");
            return (GenericItem) type;
        } catch (Exception ex) {
            logger.error("Could not create type: {} error {}", typeName, ex.getMessage());
        }
        return null;

    }

    /***
     * Received state from MqttMessageSubscriber. Try to cast it to every possible State Type and send it to all
     * channels that support this type
     *
     * @param topic MQTT topic of the received message
     * @param state Payload of the message
     */
    // @SuppressWarnings("deprecation")
    @Override
    public void mqttStateReceived(String topic, String state) {
        logger.trace("MQTT: Received state (topic '{}' payload '{}')", topic, state);

        if (!channelTopics.contains(topic)) {
            newChannelfromTopic(topic);
        }

        /*
        for (Channel channel : getThing().getChannels()) {
            logger.debug("Channel {} debug running topic", channel.getUID().getAsString(), topic);
        }
        */
        // new alternative code for dynamic topic channels
        for (Channel channel : getThing().getChannels()) {

            Configuration channelConf = channel.getConfiguration();

            /*
             * //if the channel config does not have a topic, give it the topic of the Thing
             * if (channelConf.get(TOPIC_ID) == null){
             * channelConf.put(TOPIC_ID, getConfig().get(TOPIC_ID)) ;
             * }
             */
            Object channelConfTopic = null;
            if (channelConf != null) {
                channelConfTopic = channelConf.get(TOPIC_ID);
            }

            if (channelConfTopic != null) {

                if (channelConfTopic.equals(topic)) {
                    String channelz = channel.getUID().getId();
                    logger.debug("dynamic channel {} , {}", channelz, channel.getUID().getAsString());

                    // TODO: Replace depreciated method with new way
                    // TODO: change to the right channel-type when the item type is adjusted

                    String channelItemType = (String) channel.getConfiguration().get("itemtype");
                    logger.debug("dynamic channel configured type: {} ", channelItemType);
                    GenericItem item = parseType(channelItemType);
                    State s = TypeParser.parseState(item.getAcceptedDataTypes(), state);

                    if (s != null) {
                        // state could be casted to type 'type'
                        logger.debug(
                                "MQTT: Received state ( topic '{}'). Propagating payload '{}' to dynamic channel '{}' as type '{}')",
                                topic, state, channelz, s.getClass().getName());
                        updateState(channelz, s);
                        break;
                        //       }
                    } else {
                        logger.debug(
                                "MQTT: Received state ( topic '{}'). Could not parse payload '{}' to dynamic channel '{}' for item type '{}')",
                                topic, state, channel.getChannelTypeUID(), item.getClass().getName());
                    }

                } else {
                    logger.trace("Topic {} does not match channeltopic {}", topic, channelConfTopic.toString());
                }

            } else {
                logger.debug("Channel {} does have channeltopic or config", channel.toString());

            }

        }
        /*
         * if (false) {
         * // Original method Marcus for channels based on specific type
         * for (String channelName : itemList.keySet()) {
         * // go through every active (linked) channel and check if the Item associated with it has DataTypes that
         * // we
         * // can cast the state into
         *
         * logger.trace("Channel for (topic '{}' payload '{}') {}:{}", topic, state, channelName,
         * isLinked(channelName));
         *
         * if (isLinked(channelName) || true) {
         *
         * for (Class<? extends Type> asc : itemList.get(channelName).getAcceptedDataTypes()) {
         * // for (Class<? extends Type> asc : channelT.getAcceptedItemType()) {
         *
         * try {
         * Method valueOf = asc.getMethod("valueOf", String.class);
         * State s = (State) valueOf.invoke(asc, state);
         *
         * if (s != null) {
         * // state could be casted to type 'type'
         * logger.trace(
         * "MQTT: Received state (topic '{}'). Propagating payload '{}' to channel '{}' as type '{}')",
         * topic, state, channelName, s.getClass().getName());
         * updateState(channelName, s);
         * break;
         * }
         * } catch (NoSuchMethodException e) {
         * } catch (IllegalArgumentException e) {
         * } catch (IllegalAccessException e) {
         * } catch (InvocationTargetException e) {
         * }
         * }
         * }
         *
         * }
         * }
         */

    }

    /***
     * Generates a dynamic channel from a topic if not exists
     *
     * @param channelTopicId MQTT topic of the received message
     *            channelTopicId
     */
    private synchronized void newChannelfromTopic(String topic) {
        // newChannelfromTopic(topic, "StringType");
        //  newChannelfromTopic(topic, "StringItem", "string-channel");
        newChannelfromTopic(topic, "Number", "Number-channel");

    }

    private synchronized void removeChannel(String topic, String channelTopicId) {
        if (channelTopics.contains(topic)) {
            channelTopics.remove(topic);
        }
        /*
        if (getThing().getChannel(channelTopicId) != null) {
            logger.info("Channel for topic '{}' for thing {} already exist... removing", channelTopicId,
                    getThing().getUID());
            ThingBuilder thingBuilder = editThing();
            thingBuilder.withoutChannel(new ChannelUID(getThing().getUID(), channelTopicId));
            updateThing(thingBuilder.build());
            if (channelTopics.contains(topic)) {
                channelTopics.remove(topic);
            }
        
        }
        */
    }

    /***
     * Generates a dynamic channel from a topic if not exists
     *
     * @param channelTopicId MQTT topic of the received message
     *            channelTopicId
     *            itemtype
     */
    private synchronized void newChannelfromTopic(String topic, String itemtype, String channelType) {

        if (!channelTopics.contains(topic)) {
            String channelTopicId = makeTopicString(topic);

            //  if (getThing().getChannel(channelTopicId) == null) {

            logger.info("creating channel for topic '{}' for thing {} with types {} {}", channelTopicId,
                    getThing().getUID(), itemtype, channelType);

            ThingBuilder thingBuilder = editThing();

            if (getThing().getChannel(channelTopicId) != null) {
                logger.info("Channel for topic '{}' for thing {} already exist... removing", channelTopicId,
                        getThing().getUID());

                thingBuilder.withoutChannel(new ChannelUID(getThing().getUID(), channelTopicId));
            }
            //    updateThing(thingBuilder.build());
            //    thingBuilder = editThing();

            //       List<Channel> channels = new CopyOnWriteArrayList<>();
            ChannelTypeUID channelTypeUID = new ChannelTypeUID(MqttBindingConstants.BINDING_ID, channelType);

            Configuration channelConfig = new Configuration();
            channelConfig.put(TOPIC_ID, topic);
            channelConfig.put("itemtype", itemtype + "Item");

            Channel channel = ChannelBuilder.create(new ChannelUID(getThing().getUID(), channelTopicId), itemtype)
                    .withType(channelTypeUID).withLabel(channelTopicId).withDescription(topic)
                    .withConfiguration(channelConfig).build();
                    //          channels.add(channel);

            // thingBuilder.withChannel(channel).withConfiguration(getConfig());
            thingBuilder.withChannel(channel);

            updateThing(thingBuilder.build());

            channelTopics.add(topic);
            /*            } else {
                logger.info("Channel for topic '{}' for thing {} already exist... removing", channelTopicId,
                        getThing().getUID());
                channelTopics.add(topic);
            }
            */
        }

        // for (Channel channel : getThing().getChannels()) {
        // logger.debug("Channels'{}') linked? {}", channel.getUID().toString(), channel.isLinked());
        // }
    }

    /**
     * Initialize subscriber which broadcasts all received state/command events into the associated channels
     *
     * @param topic to subscribe to.
     */
    private void setupSubscriber(String topic, String type, String transform) {

        if (StringUtils.isBlank(topic)) {
            logger.trace("No topic defined for Subscriber");
            return;
        }

        try {
            if (transform == null || StringUtils.isBlank(transform)) {
                transform = "default";
            }

            subscriber = new MqttMessageSubscriber(
                    // getBridgeHandler().getUID().getId() + ":" + topic + ":" + type + ":" + transform, this);
                    getBridgeHandler().getBroker() + ":" + topic + ":" + type + ":" + transform, this);

            getBridgeHandler().registerMessageConsumer(subscriber);

        } catch (Exception e) {
            logger.error("Could not create subscriber: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage().toString());

        }

    }

    /**
     * Initialize publisher which broadcasts all received state/command events from channel into MQTT broker
     *
     * @param topic to subscribe to.
     */
    private void setupPublisher(String topic, String type, String transform) {

        if (StringUtils.isBlank(topic)) {
            logger.trace("No topic defined for Publisher");
            return;
        }

        try {
            logger.debug("Setting up Publisher for topic {}", topic);
            if (transform == null || StringUtils.isBlank(transform)) {
                transform = "default";
            }
            publisher = new MqttMessagePublisher(
                    getBridgeHandler().getBroker() + ":" + topic + ":" + type + ":*:" + transform);

            getBridgeHandler().registerMessageProducer(publisher);

        } catch (Exception e) {
            logger.error("Could not create Publisher: {}", e.getMessage());
        }

    }

    // TODO: remove when dynamic channels are better supported in UI
    /**
     *
     *
     * private void channelEnableMessage() {
     * String channelEnableString = "";
     * for (Channel channel : getThing().getChannels()) {
     * if (!channel.isLinked()) {
     * channelEnableString = channelEnableString + "smarthome setup enableChannel ";
     * channelEnableString = channelEnableString + channel.getUID().getAsString() + "\n";
     * }
     * }
     *
     * if (!channelEnableString.isEmpty()) {
     * logger.info("Dynamic channels are not visible in UI, use command line to enable \n{}", channelEnableString);
     * }
     * }
     */

    /**
     * Handles a update for a given channel.
     *
     * @param channelUID unique identifier of the channel on which the update was performed
     * @param newState new state
     */
    @Override
    public void handleUpdate(ChannelUID channelUID, State newState) {
        if (publisher != null) {
            String statestr = newState.toString();
            logger.debug("MQTT: send state '{}' as topic '{}'", statestr, publisher.getTopic());
            publisher.publish(publisher.getTopic(), statestr.getBytes());
        } else {
            logger.warn("MQTT: handleUpdate invoked on topic '{}' but declared 'input'! Ignoring..");
        }
    }

    /***
     * Callback from framework when a configuration of the Topic has been changed
     *
     * @param thing Updated thing
     */
    // @Override
    // public void thingUpdated(Thing topic) {
    // super.thingUpdated(topic);
    // }

    /***
     * Get the MQTT bridge handler. If this is first time, register this Topic handler instance to receive events from
     * bridge
     *
     * @return bridge handler
     */
    private synchronized MqttBridgeHandler getBridgeHandler() {
        if (this.bridgeHandler == null) {
            Bridge bridge = getBridge();
            if (bridge == null) {
                return null;
            }
            ThingHandler handler = bridge.getHandler();
            if (handler instanceof MqttBridgeHandler) {
                this.bridgeHandler = (MqttBridgeHandler) handler;
                this.bridgeHandler.registerMqttBridgeListener(this);
            } else {
                return null;
            }
        }
        return this.bridgeHandler;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.eclipse.smarthome.core.thing.binding.BaseThingHandler#
     * bridgeHandlerInitialized
     * (org.eclipse.smarthome.core.thing.binding.ThingHandler,
     * org.eclipse.smarthome.core.thing.Bridge)
     */
    /*
    @Override
    public void bridgeHandlerInitialized(ThingHandler thingHandler, Bridge bridge) {
        logger.debug("Bridge {} initialized for topic: {}", bridge.getUID().toString(), getThing().getUID().toString());
        if (bridgeHandler != null) {
            // bridgeHandler.unRegisterMqttBridgeListener(this);
            bridgeHandler = null;
        }
        this.bridgeHandler = (MqttBridgeHandler) thingHandler;
        // this.bridgeHandler.registerMqttBridgeListener(this);
        initialize();
        super.bridgeHandlerInitialized(thingHandler, bridge);
    }
    */
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
    public void discoveryConfigUpdate(String discoveryTopic, String discoveryMode) {
        // ignore

    }

    @Override
    public void setBridgeConnected(boolean connected) {
        logger.debug("setBridgeConnected for topic handler '{}' connected={}.", getThing().getUID().getAsString(),
                connected);

        if (connected && initialized) {
            updateStatus(ThingStatus.ONLINE);
        }
    }

}
