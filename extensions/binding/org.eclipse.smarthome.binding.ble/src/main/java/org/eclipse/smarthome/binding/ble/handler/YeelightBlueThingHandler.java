/**
 * Copyright (c) 1997, 2015 by Huawei Technologies Co., Ltd. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.smarthome.binding.ble.handler;

import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import org.eclipse.smarthome.binding.ble.BleBindingConstants;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.HSBType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.io.transport.bluetooth.BluetoothGattCharacteristic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link YeelightBlueThingHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Chris Jackson - Initial Contribution
 */
public class YeelightBlueThingHandler extends BleBaseThingHandler {

    private Logger logger = LoggerFactory.getLogger(YeelightBlueThingHandler.class);

    private final UUID UUID_YEELIGHT_CONTROL = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private final UUID UUID_YEELIGHT_STATUS_REQUEST = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb");
    private final UUID UUID_YEELIGHT_STATUS_RESPONSE = UUID.fromString("0000fff6-0000-1000-8000-00805f9b34fb");

    public YeelightBlueThingHandler(Thing thing) {
        super(thing);

        timer = new Timer();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        BluetoothGattCharacteristic characteristic = null;
        String value = null;

        if (command instanceof HSBType) {
            HSBType hsb = (HSBType) command;
            double r = hsb.getRed().doubleValue() * 2.55;
            double g = hsb.getGreen().doubleValue() * 2.55;
            double b = hsb.getBlue().doubleValue() * 2.55;
            double a = hsb.getSaturation().doubleValue();
            value = String.format("%.0f,%.0f,%.0f,%.0f", r, g, b, a);
        }

        else if (command instanceof PercentType) {
            value = ",,," + ((PercentType) command).intValue() + "";
        }

        else if (command instanceof OnOffType) {
            value = ",,," + ((OnOffType) command == OnOffType.ON ? 100 : 0) + "";
        }

        if (value == null) {
            logger.debug("Unable to find convert value!");
            return;
        }

        characteristic = gattClient.getCharacteristic(UUID_YEELIGHT_CONTROL);
        if (characteristic == null) {
            logger.debug("Unable to find control characteristic!");
            return;
        }

        // Terminate the value string with commas - up to 18 characters long
        for (int cnt = value.length(); cnt < 18; cnt++) {
            value += ",";
        }
        logger.debug("Yeelight conversion: {} to \"{}\"", command, value);

        characteristic.setValue(value);
        gattClient.writeCharacteristic(characteristic);

        setSleepTimer();
    }

    @Override
    public void initialize() {
        super.initialize();

        updateStatus(ThingStatus.ONLINE);
    }

    @Override
    public void handleInitialisation() {
        logger.debug("Initialising Yeelight Blue {}", device.getName());

        BluetoothGattCharacteristic characteristic;
        characteristic = gattClient.getCharacteristic(UUID_YEELIGHT_STATUS_RESPONSE);
        if (characteristic != null) {
            gattClient.setCharacteristicNotification(characteristic, true);
        }
    }

    @Override
    public boolean handleReceivedCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (characteristic.getUuid().equals(UUID_YEELIGHT_STATUS_RESPONSE)) {
            String value = characteristic.getStringValue(0);
            logger.debug("Yeelight status update is \"{}\"", value);

            String[] elements = value.split(",");

            float[] hsb = new float[3];
            int red, green, blue, light;
            try {
                red = Integer.parseInt(elements[0]);
            } catch (NumberFormatException e) {
                red = 0;
            }
            try {
                green = Integer.parseInt(elements[1]);
            } catch (NumberFormatException e) {
                green = 0;
            }
            try {
                blue = Integer.parseInt(elements[2]);
            } catch (NumberFormatException e) {
                blue = 0;
            }
            try {
                light = Integer.parseInt(elements[3]);
            } catch (NumberFormatException e) {
                light = 0;
            }
            RGBtoHSB(red, green, blue, hsb);

            HSBType hsbState = new HSBType(new DecimalType(hsb[0]), new PercentType((int) hsb[1]),
                    new PercentType((int) hsb[2]));

            updateState(new ChannelUID(getThing().getUID(), BleBindingConstants.CHANNEL_COLOR), hsbState);
            updateState(new ChannelUID(getThing().getUID(), BleBindingConstants.CHANNEL_SWITCH),
                    light == 0 ? OnOffType.OFF : OnOffType.ON);
            updateState(new ChannelUID(getThing().getUID(), BleBindingConstants.CHANNEL_BRIGHTNESS),
                    new PercentType(light));
        }

        return true;
    }

    @Override
    public void handleCharacteristicWrite(BluetoothGattCharacteristic characteristic, int status) {
        BluetoothGattCharacteristic readCharacteristic = null;

        // If this was a write to the control, then read back the state
        if (characteristic.getUuid().equals(UUID_YEELIGHT_CONTROL) == true) {
            readCharacteristic = gattClient.getCharacteristic(UUID_YEELIGHT_STATUS_REQUEST);
            readCharacteristic.setValue("S");
            gattClient.writeCharacteristic(readCharacteristic);
        }
    }

    // The following timer implements a poll when the device status is changed. It requests the status
    // update a number of times after the update. This ensures that any delays are accounted for.
    private Timer timer = null;
    private TimerTask timerTask = null;

    private class WakeupTimerTask extends TimerTask {
        private int count = 5;

        @Override
        public void run() {
            count--;
            BluetoothGattCharacteristic readCharacteristic = null;
            logger.debug("Requesting status ({});", count);
            readCharacteristic = gattClient.getCharacteristic(UUID_YEELIGHT_STATUS_REQUEST);
            readCharacteristic.setValue("S");
            gattClient.writeCharacteristic(readCharacteristic);

            if (count == 0) {
                timerTask.cancel();
                timerTask = null;
            }
        }
    }

    public synchronized void setSleepTimer() {
        // Stop any existing timer
        resetSleepTimer();

        // Create the timer task
        timerTask = new WakeupTimerTask();

        // Start the timer
        timer.scheduleAtFixedRate(timerTask, 100, 200);
    }

    public synchronized void resetSleepTimer() {
        // Stop any existing timer
        if (timerTask != null) {
            timerTask.cancel();
        }
        timerTask = null;
    }

    public float[] RGBtoHSB(int r, int g, int b, float[] hsbvals) {
        float hue, saturation, brightness;
        int max = (r > g) ? r : g;
        if (b > max) {
            max = b;
        }
        int min = (r < g) ? r : g;
        if (b < min) {
            min = b;
        }
        brightness = max / 2.55f;
        saturation = (max != 0 ? ((float) (max - min)) / ((float) max) : 0) * 100;
        if (saturation == 0) {
            hue = 0;
        } else {
            float red = ((float) (max - r)) / ((float) (max - min));
            float green = ((float) (max - g)) / ((float) (max - min));
            float blue = ((float) (max - b)) / ((float) (max - min));
            if (r == max) {
                hue = blue - green;
            } else if (g == max) {
                hue = 2.0f + red - blue;
            } else {
                hue = 4.0f + green - red;
            }
            hue = hue / 6.0f * 360;
            if (hue < 0) {
                hue = hue + 360.0f;
            }
        }
        hsbvals[0] = hue;
        hsbvals[1] = saturation;
        hsbvals[2] = brightness;
        return hsbvals;
    }
}