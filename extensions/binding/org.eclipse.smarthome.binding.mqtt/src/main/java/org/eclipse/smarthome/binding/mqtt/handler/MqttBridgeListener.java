package org.eclipse.smarthome.binding.mqtt.handler;

/**
 * The {@link MqttBridgeListener} is responsible for transmitting events from MQTT bridge to all it's associated Topics.
 *
 * @author Marcus of Wetware Labs - Initial contribution
 */
public interface MqttBridgeListener {

    /***
     * Received an discovery parameter update from the bridge
     *
     * @param discoveryTopic
     * @param discoveryMode
     */
    public abstract void discoveryConfigUpdate(String discoveryTopic, String discoveryMode);

    /***
     * Received when the bridge goes online or offline
     *
     * @param connected
     */
    public abstract void setBridgeConnected(boolean connected);

}