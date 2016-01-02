/**
 * Copyright (c) 2014 openHAB UG (haftungsbeschraenkt) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.smarthome.binding.mqtt.internal;

import static org.eclipse.smarthome.binding.mqtt.MqttBindingConstants.TOPIC_ID;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;

import org.eclipse.smarthome.binding.mqtt.discovery.MqttDiscoveryService;
import org.eclipse.smarthome.binding.mqtt.handler.MqttBridgeHandler;
import org.eclipse.smarthome.binding.mqtt.handler.MqttHandler;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandlerFactory;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.osgi.framework.ServiceRegistration;

import com.google.common.collect.Sets;

/**
 * The {@link MqttHandlerFactory} is responsible for creating things and thing handlers.
 *
 * @author Marcus of Wetware Labs - Initial contribution
 */
public class MqttHandlerFactory extends BaseThingHandlerFactory {

    private Map<ThingUID, ServiceRegistration<?>> discoveryServiceRegs = new HashMap<>();

    public final static Set<ThingTypeUID> SUPPORTED_THING_TYPES = Sets.union(MqttBridgeHandler.SUPPORTED_THING_TYPES,
            MqttHandler.SUPPORTED_THING_TYPES);

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES.contains(thingTypeUID);
    }

    @Override
    protected ThingHandler createHandler(Thing thing) {

        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (MqttBridgeHandler.SUPPORTED_THING_TYPES.contains(thingTypeUID)) {
            MqttBridgeHandler handler = new MqttBridgeHandler((Bridge) thing);
            // TODO: choose discovery method 1 or 2
            registerMqttDiscoveryService(handler);
            return handler;
        } else if (MqttHandler.SUPPORTED_THING_TYPES.contains(thingTypeUID)) {
            return new MqttHandler(thing);
        } else {
            return null;
        }

    }

    @Override
    public Thing createThing(ThingTypeUID thingTypeUID, Configuration configuration, ThingUID thingUID,
            ThingUID bridgeUID) {
        if (MqttBridgeHandler.SUPPORTED_THING_TYPES.contains(thingTypeUID)) {
            // ThingUID mqttBridgeUID = getBridgeThingUID(thingTypeUID, thingUID, configuration);
            // return super.createThing(thingTypeUID, configuration, mqttBridgeUID, null);
            return super.createThing(thingTypeUID, configuration, thingUID, null);
        }
        if (MqttHandler.SUPPORTED_THING_TYPES.contains(thingTypeUID)) {
            ThingUID topicUID = getTopicThingUID(thingTypeUID, thingUID, configuration, bridgeUID);
            return super.createThing(thingTypeUID, configuration, topicUID, bridgeUID);
        }
        throw new IllegalArgumentException("The thing type " + thingTypeUID + " is not supported by the MQTT binding.");
    }

    private ThingUID getBridgeThingUID(ThingTypeUID thingTypeUID, ThingUID thingUID, Configuration configuration) {
        // string bridgName = (String) configuration.get(TOPIC_ID);

        if (thingUID == null) {
            thingUID = new ThingUID(thingTypeUID, "1");
        }
        return thingUID;
    }

    private ThingUID getTopicThingUID(ThingTypeUID thingTypeUID, ThingUID thingUID, Configuration configuration,
            ThingUID bridgeUID) {
        String topicId = (String) configuration.get(TOPIC_ID);

        if (thingUID == null) {
            thingUID = new ThingUID(thingTypeUID, topicId, bridgeUID.getId());
        }
        return thingUID;
    }

    @Override
    protected synchronized void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof MqttBridgeHandler) {
            unregisterMqttDiscoveryService((MqttBridgeHandler) thingHandler);
        }
        super.removeHandler(thingHandler);
    }

    /**
     * @param MqttBridgeHandler
     */
    private void unregisterMqttDiscoveryService(MqttBridgeHandler MqttBridgeHandler) {
        ServiceRegistration<?> serviceReg = this.discoveryServiceRegs.get(MqttBridgeHandler.getThing().getUID());
        if (serviceReg != null) {
            // remove discovery service, if bridge handler is removed
            MqttDiscoveryService service = (MqttDiscoveryService) bundleContext.getService(serviceReg.getReference());
            service.deactivate();
            serviceReg.unregister();
            discoveryServiceRegs.remove(MqttBridgeHandler.getThing().getUID());
        }
    }

    private void registerMqttDiscoveryService(MqttBridgeHandler MqttBridgeHandler) {
        MqttDiscoveryService discoveryService = new MqttDiscoveryService(MqttBridgeHandler);
        discoveryService.activate();

        this.discoveryServiceRegs.put(((ThingHandler) MqttBridgeHandler).getThing().getUID(), bundleContext
                .registerService(DiscoveryService.class.getName(), discoveryService, new Hashtable<String, Object>()));
    }
}
