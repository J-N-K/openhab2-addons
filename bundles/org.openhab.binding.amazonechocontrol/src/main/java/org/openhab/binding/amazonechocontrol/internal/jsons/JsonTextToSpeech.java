/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.amazonechocontrol.internal.jsons;

import java.util.List;

import org.eclipse.jdt.annotation.Nullable;

/**
 *
 * @author Tom Blum (Trinitus01) - Initial contribution
 */
public class JsonTextToSpeech {

    public @Nullable List<JsonDevices.Device> devices;
    public String text = "";
    public @Nullable List<Integer> ttsVolumes;
    public @Nullable List<Integer> standardVolumes;
}
