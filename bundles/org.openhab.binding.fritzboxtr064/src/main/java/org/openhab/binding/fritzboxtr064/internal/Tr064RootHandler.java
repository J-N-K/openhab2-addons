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

import static org.openhab.binding.fritzboxtr064.internal.FritzboxTr064BindingConstants.THING_TYPE_ROOTDEVICE;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPMessage;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.util.DigestAuthentication;
import org.eclipse.smarthome.core.cache.ExpiringCacheMap;
import org.eclipse.smarthome.core.thing.*;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.thing.binding.builder.ThingBuilder;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.fritzboxtr064.internal.config.Tr064ChannelConfig;
import org.openhab.binding.fritzboxtr064.internal.config.Tr064RootConfiguration;
import org.openhab.binding.fritzboxtr064.internal.model.scpd.root.SCPDDeviceType;
import org.openhab.binding.fritzboxtr064.internal.model.scpd.root.SCPDServiceType;
import org.openhab.binding.fritzboxtr064.internal.model.scpd.service.SCPDActionType;
import org.openhab.binding.fritzboxtr064.internal.util.SCPDUtil;
import org.openhab.binding.fritzboxtr064.internal.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link Tr064RootHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Jan N. Klug - Initial contribution
 */
@NonNullByDefault
public class Tr064RootHandler extends BaseBridgeHandler {
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_ROOTDEVICE);
    private static final int RETRY_INTERVAL = 60;
    private static final Set<String> PROPERTY_ARGUMENTS = Set.of("NewSerialNumber", "NewSoftwareVersion",
            "NewModelName");

    private final Logger logger = LoggerFactory.getLogger(Tr064RootHandler.class);

    private final HttpClient httpClient;
    private final Tr064DynamicStateDescriptionProvider dynamicStateDescriptionProvider;

    private Tr064RootConfiguration config = new Tr064RootConfiguration();
    private String deviceType = "";

    private @Nullable SCPDUtil scpdUtil;
    private SOAPConnector soapConnector;
    private String endpointBaseURL = "http://fritz.box:49000";

    private final Map<ChannelUID, Tr064ChannelConfig> channels = new HashMap<>();
    // caching is used to prevent excessive calls to the same action
    private final ExpiringCacheMap<ChannelUID, State> stateCache = new ExpiringCacheMap<>(2000);
    private final Set<ChannelUID> linkedChannels = ConcurrentHashMap.newKeySet();

    private @Nullable ScheduledFuture<?> connectFuture;
    private @Nullable ScheduledFuture<?> pollFuture;

    Tr064RootHandler(Bridge bridge, HttpClient httpClient,
            Tr064DynamicStateDescriptionProvider dynamicStateDescriptionProvider) {
        super(bridge);
        this.httpClient = httpClient;
        this.dynamicStateDescriptionProvider = dynamicStateDescriptionProvider;
        soapConnector = new SOAPConnector(httpClient, endpointBaseURL);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        Tr064ChannelConfig channelConfig = channels.get(channelUID);
        if (channelConfig == null) {
            logger.trace("Channel {} not supported.", channelUID);
            return;
        }

        if (command instanceof RefreshType) {
            State state = stateCache.putIfAbsentAndGet(channelUID, () -> getChannelStateFromDevice(channelConfig));
            if (state != null) {
                updateState(channelUID, state);
            }
        }

        if (channelConfig.getChannelType().getSetAction() == null) {
            logger.debug("Discarding command {} to {}, read-only channel", command, channelUID);
            return;
        }
        scheduler.execute(() -> sendChannelCommandToDevice(channelConfig, command));
    }

    @Override
    public void initialize() {
        config = getConfigAs(Tr064RootConfiguration.class);
        if (!config.isValid()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "One or more mandatory configuration field is empty");
            return;
        }

        endpointBaseURL = "http://" + config.host + ":49000";
        updateStatus(ThingStatus.UNKNOWN);
        connectFuture = scheduler.scheduleWithFixedDelay(this::internalInitialize, 0, RETRY_INTERVAL, TimeUnit.SECONDS);
    }

    private void internalInitialize() {
        try {
            scpdUtil = new SCPDUtil(httpClient, endpointBaseURL);
        } catch (SCPDException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "could not get device definitions from " + config.host);
            return;
        }
        if (establishSecureConnectionAndUpdateProperties()) {
            final ScheduledFuture<?> connectFuture = this.connectFuture;
            if (connectFuture != null) {
                connectFuture.cancel(false);
                this.connectFuture = null;
            }
            configureChannels();
            installPolling();
            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE);
        }
    }

    @Override
    public void dispose() {
        final ScheduledFuture<?> connectFuture = this.connectFuture;
        if (connectFuture != null) {
            connectFuture.cancel(true);
            this.connectFuture = null;
        }
        uninstallPolling();

        stateCache.clear();
        dynamicStateDescriptionProvider.removeDescriptionsForThing(thing.getUID());

        super.dispose();
    }

    private void poll() {
        channels.forEach((channelUID, channelConfig) -> {
            State state = stateCache.putIfAbsentAndGet(channelUID, () -> getChannelStateFromDevice(channelConfig));
            if (state != null) {
                updateState(channelUID, state);
            }
        });
    }

    private void sendChannelCommandToDevice(Tr064ChannelConfig channelConfig, Command command) {
        soapConnector.sendChannelCommandToDevice(channelConfig, command);
    }

    private State getChannelStateFromDevice(Tr064ChannelConfig channelConfig) {
        return soapConnector.getChannelStateFromDevice(channelConfig, channels, stateCache);
    }

    private void configureChannels() {
        ThingBuilder thingBuilder = editThing();
        thingBuilder.withoutChannels(thing.getChannels());
        final SCPDUtil scpdUtil = this.scpdUtil;
        if (scpdUtil != null) {
            Util.checkAvailableChannels(thing, thingBuilder, scpdUtil, "", deviceType, channels,
                    dynamicStateDescriptionProvider);
            updateThing(thingBuilder.build());
        }
    }

    private boolean establishSecureConnectionAndUpdateProperties() {
        final SCPDUtil scpdUtil = this.scpdUtil;
        if (scpdUtil != null) {
            try {
                SCPDDeviceType device = scpdUtil.getDevice("")
                        .orElseThrow(() -> new SCPDException("Root device not found"));
                SCPDServiceType deviceService = device.getServiceList().stream()
                        .filter(service -> service.getServiceId().equals("urn:DeviceInfo-com:serviceId:DeviceInfo1"))
                        .findFirst().orElseThrow(() -> new SCPDException(
                                "service 'urn:DeviceInfo-com:serviceId:DeviceInfo1' not found"));

                this.deviceType = device.getDeviceType();

                // try to get security (https) port
                SOAPMessage soapResponse = soapConnector.sendSyncSOAPRequest(deviceService, "GetSecurityPort",
                        Collections.emptyMap(), 1000);
                if (!soapResponse.getSOAPBody().hasFault()) {
                    SOAPValueParser soapValueParser = new SOAPValueParser(httpClient);
                    soapValueParser.getStateFromSOAPValue(soapResponse, "NewSecurityPort").ifPresentOrElse(port -> {
                        endpointBaseURL = "https://" + config.host + ":" + port.toString();
                        soapConnector = new SOAPConnector(httpClient, endpointBaseURL);
                        logger.debug("endpointBaseURL is now '{}'", endpointBaseURL);
                    }, () -> logger.warn("Could not determine secure port, disabling https"));
                } else {
                    logger.warn("Could not determine secure port, disabling https");
                }

                // clear auth cache and force re-auth
                httpClient.getAuthenticationStore().clearAuthenticationResults();
                AuthenticationStore auth = httpClient.getAuthenticationStore();
                auth.addAuthentication(new DigestAuthentication(new URI(endpointBaseURL), Authentication.ANY_REALM,
                        config.user, config.password));

                // check & update properties
                SCPDActionType getInfoAction = scpdUtil.getService(deviceService.getServiceId())
                        .orElseThrow(() -> new SCPDException(
                                "Could not get service definition for 'urn:DeviceInfo-com:serviceId:DeviceInfo1'"))
                        .getActionList().stream().filter(action -> action.getName().equals("GetInfo")).findFirst()
                        .orElseThrow(() -> new SCPDException("Action 'GetInfo' not found"));
                SOAPMessage soapResponse1 = soapConnector.sendSyncSOAPRequest(deviceService, getInfoAction.getName(),
                        Collections.emptyMap(), 5000);
                SOAPValueParser soapValueParser = new SOAPValueParser(httpClient);
                Map<String, String> properties = editProperties();
                PROPERTY_ARGUMENTS.forEach(argumentName -> getInfoAction.getArgumentList().stream()
                        .filter(argument -> argument.getName().equals(argumentName)).findFirst()
                        .ifPresent(argument -> soapValueParser.getStateFromSOAPValue(soapResponse1, argumentName)
                                .ifPresent(value -> properties.put(argument.getRelatedStateVariable(),
                                        value.toString()))));
                properties.put("deviceType", device.getDeviceType());
                updateProperties(properties);

                return true;
            } catch (SCPDException | SOAPException | Tr064CommunicationException | URISyntaxException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
                return false;
            }
        }
        return false;
    }

    public List<SCPDDeviceType> getAllSubDevices() {
        final SCPDUtil scpdUtil = this.scpdUtil;
        return (scpdUtil == null) ? Collections.emptyList() : scpdUtil.getAllSubDevices();
    }

    public SOAPConnector getSOAPUtil() {
        return soapConnector;
    }

    public @Nullable SCPDUtil getSCPDUtil() {
        return scpdUtil;
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        linkedChannels.add(channelUID);
        super.channelLinked(channelUID);
        logger.debug("Channel {} linked", channelUID);
    }

    @Override
    public void channelUnlinked(ChannelUID channelUID) {
        super.channelUnlinked(channelUID);
        linkedChannels.remove(channelUID);
        logger.debug("Channel {} unlinked", channelUID);
    }

    private void uninstallPolling() {
        final ScheduledFuture<?> pollFuture = this.pollFuture;
        if (pollFuture != null) {
            pollFuture.cancel(true);
            this.pollFuture = null;
        }
    }

    private void installPolling() {
        uninstallPolling();
        pollFuture = scheduler.scheduleWithFixedDelay(this::poll, 0, config.refresh, TimeUnit.SECONDS);
    }
}
