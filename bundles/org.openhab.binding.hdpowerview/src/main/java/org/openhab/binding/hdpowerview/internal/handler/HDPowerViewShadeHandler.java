/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
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
package org.openhab.binding.hdpowerview.internal.handler;

import static org.openhab.binding.hdpowerview.internal.HDPowerViewBindingConstants.*;
import static org.openhab.binding.hdpowerview.internal.api.ActuatorClass.*;
import static org.openhab.binding.hdpowerview.internal.api.CoordinateSystem.*;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.hdpowerview.internal.HDPowerViewWebTargets;
import org.openhab.binding.hdpowerview.internal.HubMaintenanceException;
import org.openhab.binding.hdpowerview.internal.HubProcessingException;
import org.openhab.binding.hdpowerview.internal.api.ActuatorClass;
import org.openhab.binding.hdpowerview.internal.api.CoordinateSystem;
import org.openhab.binding.hdpowerview.internal.api.ShadePosition;
import org.openhab.binding.hdpowerview.internal.api.responses.Shade;
import org.openhab.binding.hdpowerview.internal.api.responses.Shades.ShadeData;
import org.openhab.binding.hdpowerview.internal.config.HDPowerViewShadeConfiguration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StopMoveType;
import org.openhab.core.library.types.UpDownType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles commands for an HD PowerView Shade
 *
 * @author Andy Lintner - Initial contribution
 * @author Andrew Fiddian-Green - Added support for secondary rail positions
 */
@NonNullByDefault
public class HDPowerViewShadeHandler extends AbstractHubbedThingHandler {

    private final Logger logger = LoggerFactory.getLogger(HDPowerViewShadeHandler.class);

    private static final int REFRESH_DELAY_SEC = 10;
    private @Nullable ScheduledFuture<?> refreshFuture = null;

    public HDPowerViewShadeHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        try {
            getShadeId();
        } catch (NumberFormatException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Configuration 'id' not a valid integer");
            return;
        }
        if (getBridgeHandler() == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Hub not configured");
            return;
        }
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (RefreshType.REFRESH.equals(command)) {
            requestRefreshShade();
            return;
        }

        switch (channelUID.getId()) {
            case CHANNEL_SHADE_POSITION:
                if (command instanceof PercentType) {
                    moveShade(PRIMARY_ACTUATOR, ZERO_IS_CLOSED, ((PercentType) command).intValue());
                } else if (command instanceof UpDownType) {
                    moveShade(PRIMARY_ACTUATOR, ZERO_IS_CLOSED, UpDownType.UP.equals(command) ? 0 : 100);
                } else if (command instanceof StopMoveType) {
                    if (StopMoveType.STOP.equals(command)) {
                        stopShade();
                    } else {
                        logger.warn("Unexpected StopMoveType command");
                    }
                }
                break;

            case CHANNEL_SHADE_VANE:
                if (command instanceof PercentType) {
                    moveShade(PRIMARY_ACTUATOR, VANE_COORDS, ((PercentType) command).intValue());
                } else if (command instanceof OnOffType) {
                    moveShade(PRIMARY_ACTUATOR, VANE_COORDS, OnOffType.ON.equals(command) ? 100 : 0);
                }
                break;

            case CHANNEL_SHADE_SECONDARY_POSITION:
                if (command instanceof PercentType) {
                    moveShade(SECONDARY_ACTUATOR, ZERO_IS_OPEN, ((PercentType) command).intValue());
                } else if (command instanceof UpDownType) {
                    moveShade(SECONDARY_ACTUATOR, ZERO_IS_OPEN, UpDownType.UP.equals(command) ? 0 : 100);
                } else if (command instanceof StopMoveType) {
                    if (StopMoveType.STOP.equals(command)) {
                        stopShade();
                    } else {
                        logger.warn("Unexpected StopMoveType command");
                    }
                }
                break;
        }
    }

    /**
     * Update the state of the channels based on the ShadeData provided
     *
     * @param shadeData the ShadeData to be used; may be null
     */
    protected void onReceiveUpdate(@Nullable ShadeData shadeData) {
        if (shadeData != null) {
            updateStatus(ThingStatus.ONLINE);
            updateBindingStates(shadeData.positions);
            updateBatteryLevel(shadeData.batteryStatus);
            updateState(CHANNEL_SHADE_BATTERY_VOLTAGE, new QuantityType<>(shadeData.batteryStrength / 10, Units.VOLT));
            updateState(CHANNEL_SHADE_SIGNAL_STRENGTH, new DecimalType(shadeData.signalStrength));
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
        }
    }

    private void updateBindingStates(@Nullable ShadePosition shadePos) {
        if (shadePos != null) {
            updateState(CHANNEL_SHADE_POSITION, shadePos.getState(PRIMARY_ACTUATOR, ZERO_IS_CLOSED));
            updateState(CHANNEL_SHADE_VANE, shadePos.getState(PRIMARY_ACTUATOR, VANE_COORDS));
            updateState(CHANNEL_SHADE_SECONDARY_POSITION, shadePos.getState(SECONDARY_ACTUATOR, ZERO_IS_OPEN));
        } else {
            updateState(CHANNEL_SHADE_POSITION, UnDefType.UNDEF);
            updateState(CHANNEL_SHADE_VANE, UnDefType.UNDEF);
            updateState(CHANNEL_SHADE_SECONDARY_POSITION, UnDefType.UNDEF);
        }
    }

    private void updateBatteryLevel(int batteryStatus) {
        int mappedValue;
        switch (batteryStatus) {
            case 1: // Low
                mappedValue = 10;
                break;
            case 2: // Medium
                mappedValue = 50;
                break;
            case 3: // High
            case 4: // Plugged in
                mappedValue = 100;
                break;
            default: // No status available (0) or invalid
                return;
        }
        updateState(CHANNEL_SHADE_LOW_BATTERY, batteryStatus == 1 ? OnOffType.ON : OnOffType.OFF);
        updateState(CHANNEL_SHADE_BATTERY_LEVEL, new DecimalType(mappedValue));
    }

    private void moveShade(ActuatorClass actuatorClass, CoordinateSystem coordSys, int newPercent) {
        try {
            HDPowerViewHubHandler bridge;
            if ((bridge = getBridgeHandler()) == null) {
                throw new HubProcessingException("Missing bridge handler");
            }
            HDPowerViewWebTargets webTargets = bridge.getWebTargets();
            if (webTargets == null) {
                throw new HubProcessingException("Web targets not initialized");
            }
            int shadeId = getShadeId();

            switch (actuatorClass) {
                case PRIMARY_ACTUATOR:
                    // write the new primary position
                    webTargets.moveShade(shadeId, ShadePosition.create(coordSys, newPercent));
                    break;
                case SECONDARY_ACTUATOR:
                    // read the current primary position; default value 100%
                    int primaryPercent = 100;
                    Shade shade = webTargets.getShade(shadeId);
                    if (shade != null) {
                        ShadeData shadeData = shade.shade;
                        if (shadeData != null) {
                            ShadePosition shadePos = shadeData.positions;
                            if (shadePos != null) {
                                State primaryState = shadePos.getState(PRIMARY_ACTUATOR, ZERO_IS_CLOSED);
                                if (primaryState instanceof PercentType) {
                                    primaryPercent = ((PercentType) primaryState).intValue();
                                }
                            }
                        }
                    }
                    // write the current primary position, plus the new secondary position
                    webTargets.moveShade(shadeId,
                            ShadePosition.create(ZERO_IS_CLOSED, primaryPercent, ZERO_IS_OPEN, newPercent));
            }
        } catch (HubProcessingException | NumberFormatException e) {
            logger.warn("Unexpected error: {}", e.getMessage());
            return;
        } catch (HubMaintenanceException e) {
            // exceptions are logged in HDPowerViewWebTargets
            return;
        }
    }

    private int getShadeId() throws NumberFormatException {
        String str = getConfigAs(HDPowerViewShadeConfiguration.class).id;
        if (str == null) {
            throw new NumberFormatException("null input string");
        }
        return Integer.parseInt(str);
    }

    private void stopShade() {
        try {
            HDPowerViewHubHandler bridge;
            if ((bridge = getBridgeHandler()) == null) {
                throw new HubProcessingException("Missing bridge handler");
            }
            HDPowerViewWebTargets webTargets = bridge.getWebTargets();
            if (webTargets == null) {
                throw new HubProcessingException("Web targets not initialized");
            }
            int shadeId = getShadeId();
            webTargets.stopShade(shadeId);
            requestRefreshShade();
        } catch (HubProcessingException | NumberFormatException e) {
            logger.warn("Unexpected error: {}", e.getMessage());
            return;
        } catch (HubMaintenanceException e) {
            // exceptions are logged in HDPowerViewWebTargets
            return;
        }
    }

    /**
     * Request that the shade shall undergo a 'hard' refresh
     */
    protected synchronized void requestRefreshShade() {
        if (refreshFuture == null) {
            refreshFuture = scheduler.schedule(this::doRefreshShade, REFRESH_DELAY_SEC, TimeUnit.SECONDS);
        }
    }

    private void doRefreshShade() {
        try {
            HDPowerViewHubHandler bridge;
            if ((bridge = getBridgeHandler()) == null) {
                throw new HubProcessingException("Missing bridge handler");
            }
            HDPowerViewWebTargets webTargets = bridge.getWebTargets();
            if (webTargets == null) {
                throw new HubProcessingException("Web targets not initialized");
            }
            int shadeId = getShadeId();
            Shade shade = webTargets.refreshShade(shadeId);
            if (shade != null) {
                ShadeData shadeData = shade.shade;
                if (shadeData != null) {
                    if (Boolean.TRUE.equals(shadeData.timedOut)) {
                        logger.warn("Shade {} wireless refresh time out", shadeId);
                    }
                }
            }
        } catch (HubProcessingException | NumberFormatException e) {
            logger.warn("Unexpected error: {}", e.getMessage());
        } catch (HubMaintenanceException e) {
            // exceptions are logged in HDPowerViewWebTargets
        }
        refreshFuture = null;
    }
}
