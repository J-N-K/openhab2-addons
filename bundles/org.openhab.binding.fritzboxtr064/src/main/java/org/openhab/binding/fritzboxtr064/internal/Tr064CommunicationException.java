/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
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
package org.openhab.binding.fritzboxtr064.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link Tr064CommunicationException} is thrown for communication errors
 *
 * @author Jan N. Klug - Initial contribution
 */
@NonNullByDefault
public class Tr064CommunicationException extends Exception {
    private static final long serialVersionUID = 1L;

    public Tr064CommunicationException(Exception e) {
        super(e);
    }

    public Tr064CommunicationException(String s) {
        super(s);
    }
}
