/**
 * Copyright (c) 1997, 2015 by Huawei Technologies Co., Ltd. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.smarthome.io.transport.bluetooth.bluez.internal.dbus;

import java.util.List;

import org.freedesktop.dbus.DBusInterface;
import org.freedesktop.dbus.DBusInterfaceName;

@DBusInterfaceName("org.bluez.GattDescriptor1")
public interface GattDescriptor1 extends DBusInterface {

    public List<Byte> ReadValue();

    public void WriteValue(List<Byte> value);

}