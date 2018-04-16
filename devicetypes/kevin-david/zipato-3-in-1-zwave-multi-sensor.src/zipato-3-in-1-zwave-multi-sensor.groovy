/**
*   2018.04.16 Set humidity swing report interval to 3% (mostly to see if this thing works!)
*   2018.02.25 Initial import from https://community.smartthings.com/t/zipato-ph-pat02-3-in-1-z-wave-multi-sensor-device-handler-help-or-request-dth-in-post-10/78488/10
*   Copyright 2016 SmartThings
*   Copyright 2015 AstraLink
*
*   Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
*   in compliance with the License. You may obtain a copy of the License at:
*
*       http://www.apache.org/licenses/LICENSE-2.0
*
*   Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
*   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
*   for the specific language governing permissions and limitations under the License.
*
*   Zipato PH-PAT02 3-in-1 Z-Wave Multi-Sensor
*   Based on Z-Wave Plus Motion Sensor with Temperature Measurement, ZP3102*-5
*   To change parameters, see `configure()` function.
*/

metadata {
    definition (name: "Zipato 3-in-1 Z-Wave Multi-Sensor", namespace: "kevin-david", author: "Kevin David") {
        capability "Water Sensor"
        capability "Temperature Measurement"
        capability "Relative Humidity Measurement"
        capability "Configuration"
        capability "Battery"
        capability "Sensor"

        // for Astralink
        attribute "ManufacturerCode", "string"
        attribute "ProduceTypeCode", "string"
        attribute "ProductCode", "string"
        attribute "WakeUp", "string"
        attribute "WirelessConfig", "string"
                
        fingerprint deviceId: "0x0701", inClusters: "0x5E, 0x98, 0x86, 0x72, 0x5A, 0x85, 0x59, 0x73, 0x80, 0x71, 0x31, 0x70, 0x84, 0x7A"
        fingerprint type:"8C07", inClusters: "5E,98,86,72,5A,31,71"
        fingerprint mfr:"013C", prod:"0002", model:"001F"  // not using deviceJoinName because it's sold under different brand names
    }

    tiles {
        standardTile("water", "device.water", width: 3, height: 2) {
            state "dry", label:'dry', icon:"st.alarm.water.dry", backgroundColor:"#ffffff"
            state "wet", label:'wet', icon:"st.alarm.water.wet", backgroundColor:"#53a7c0"
        }

        valueTile("temperature", "device.temperature", inactiveLabel: false) {
            state "temperature", label:'${currentValue}°',
                backgroundColors:[
                    [value: 31, color: "#153591"],
                    [value: 44, color: "#1e9cbb"],
                    [value: 59, color: "#90d2a7"],
                    [value: 74, color: "#44b621"],
                    [value: 84, color: "#f1d801"],
                    [value: 95, color: "#d04e00"],
                    [value: 96, color: "#bc2323"]
                ]
        }
        valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat") {
            state "battery", label:'${currentValue}% Battery', unit:"%"
            
        }
        valueTile("humidity", "device.humidity", inactiveLabel: false, decoration: "flat") {
            state "humidity", label:'${currentValue}% Humidity', unit:"%"

        }
        main(["water", "temperature"])
        details(["water", "humidity", "temperature", "battery"])
    }
}

def updated() {
    if (!device.currentState("ManufacturerCode")) {
        response(secure(zwave.manufacturerSpecificV2.manufacturerSpecificGet()))
    }
}

def configure() {
    log.debug "configure()"
    def cmds = []

    if (state.sec != 1) {
        // secure inclusion may not be complete yet
        cmds << "delay 1000"
    }

    cmds += secureSequence([
        zwave.manufacturerSpecificV2.manufacturerSpecificGet(),
        zwave.batteryV1.batteryGet(),
        zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType:1, scale:1),
        
        // Sensor-specific settings. Sensor uses default tick interval of 30 minutes
        // See: https://www.zipato.com/wp-content/uploads/2015/10/ph-pat02-Zipato-Flood-Multisensor-3-in-1-User-Manual-v1.0.pdf
                
        // Set humidity diff report to 3 pct
        zwave.configurationV1.configurationSet(parameterNumber: 22, size: 1, scaledConfigurationValue: 3)
        
    ], 500)

    cmds << "delay 8000"
    cmds << secure(zwave.wakeUpV1.wakeUpNoMoreInformation())
    return cmds
}

private getCommandClassVersions() {
    [
        0x71: 3,    // Notification
        0x5E: 2,    // ZwaveplusInfo
        0x59: 1,    // AssociationGrpInfo
        0x85: 2,    // Association
        0x20: 1,    // Basic
        0x80: 1,    // Battery
        0x70: 1,    // Configuration
        0x5A: 1,    // DeviceResetLocally
        0x7A: 2,    // FirmwareUpdateMd
        0x72: 2,    // ManufacturerSpecific
        0x73: 1,    // Powerlevel
        0x98: 1,    // Security
        0x31: 5,    // SensorMultilevel
        0x84: 2     // WakeUp
    ]
}

// Parse incoming device messages to generate events
def parse(String description) {
    def result = []
    def cmd
    if (description.startsWith("Err 106")) {
        state.sec = 0
        result = createEvent( name: "secureInclusion", value: "failed", eventType: "ALERT",
                descriptionText: "This sensor failed to complete the network security key exchange. If you are unable to control it via SmartThings, you must remove it from your network and add it again.")
    } else if (description.startsWith("Err")) {
        result = createEvent(descriptionText: "$device.displayName $description", isStateChange: true)
    } else {
        cmd = zwave.parse(description, commandClassVersions)
        if (cmd) {
            result = zwaveEvent(cmd)
        }
    }

    if (result instanceof List) {
        result = result.flatten()
    }

    log.debug "Parsed '$description' to $result"
    return result
}

def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
    def encapsulatedCommand = cmd.encapsulatedCommand(commandClassVersions)
    log.debug "encapsulated: $encapsulatedCommand"
    if (encapsulatedCommand) {
        state.sec = 1
        return zwaveEvent(encapsulatedCommand)
    } else {
        log.warn "Unable to extract encapsulated cmd from $cmd"
        return [createEvent(descriptionText: cmd.toString())]
    }
}

def sensorValueEvent(value) {
    def result = []
    if (value) {
        log.debug "sensorValueEvent($value) : active"
        result << createEvent(name: "water", value: "wet", descriptionText: "$device.displayName is wet")
    } else {
        log.debug "sensorValueEvent($value) : inactive"
        result << createEvent(name: "water", value: "dry", descriptionText: "$device.displayName is dry")
    }
    return result
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicReport cmd) {
    return sensorValueEvent(cmd.value)
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicSet cmd) {
    return sensorValueEvent(cmd.value)
}

def zwaveEvent(physicalgraph.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd) {
    return sensorValueEvent(cmd.value)
}

def zwaveEvent(physicalgraph.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd) {
    return sensorValueEvent(cmd.sensorValue)
}

def zwaveEvent(physicalgraph.zwave.commands.sensoralarmv1.SensorAlarmReport cmd) {
    return sensorValueEvent(cmd.sensorState)
}

def zwaveEvent(physicalgraph.zwave.commands.notificationv3.NotificationReport cmd) {
    def result = []
    if (cmd.notificationType == 0x07) {
        if (cmd.event == 0x01 || cmd.event == 0x02) {
            result << sensorValueEvent(1)
        } else if (cmd.event == 0x03) {
            result << createEvent(descriptionText: "$device.displayName covering was removed", isStateChange: true)
            result << response(secure(zwave.manufacturerSpecificV2.manufacturerSpecificGet()))
        } else if (cmd.event == 0x07) {
            result << sensorValueEvent(1)
        } else if (cmd.event == 0x08) {
            result << sensorValueEvent(1)
        } else if (cmd.event == 0x00) {
            if (cmd.eventParametersLength && cmd.eventParameter[0] == 3) {
                result << createEvent(descriptionText: "$device.displayName covering replaced", isStateChange: true, displayed: false)
            } else {
                result << sensorValueEvent(0)
            }
        } else if (cmd.event == 0xFF) {
            result << sensorValueEvent(1)
        } else {
            result << createEvent(descriptionText: "$device.displayName sent event $cmd.event")
        }
    } else if (cmd.notificationType) {
        def text = "Notification $cmd.notificationType: event ${([cmd.event] + cmd.eventParameter).join(", ")}"
        result << createEvent(name: "notification$cmd.notificationType", value: "$cmd.event", descriptionText: text, displayed: false)
    } else {
        def value = cmd.v1AlarmLevel == 255 ? "wet" : cmd.v1AlarmLevel ?: "dry"
        result << createEvent(name: "alarm $cmd.v1AlarmType", value: value, displayed: false)
    }
    return result
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd) {
    def event = createEvent(name: "WakeUp", value: "wakeup", descriptionText: "${device.displayName} woke up", isStateChange: true, displayed: false)  // for Astralink
    def cmds = []

    if (!device.currentState("ManufacturerCode")) {
        cmds << secure(zwave.manufacturerSpecificV2.manufacturerSpecificGet())
        cmds << "delay 2000"
    }
    if (!state.lastbat || now() - state.lastbat > 10*60*60*1000) {
        event.descriptionText += ", requesting battery"
        cmds << secure(zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType:1, scale:1))
        cmds << "delay 800"
        cmds << secure(zwave.batteryV1.batteryGet())
        cmds << "delay 2000"
    } else {
        log.debug "not checking battery, was updated ${(now() - state.lastbat)/60000 as int} min ago"
    }
    cmds << secure(zwave.wakeUpV1.wakeUpNoMoreInformation())

    return [event, response(cmds)]
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
    def result = []
    def map = [ name: "battery", unit: "%" ]
    if (cmd.batteryLevel == 0xFF) {
        map.value = 1
        map.descriptionText = "${device.displayName} has a low battery"
        map.isStateChange = true
    } else {
        map.value = cmd.batteryLevel
    }
    def event = createEvent(map)

    // Save at least one battery report in events list every few days
    if (!event.isStateChange && (now() - 3*24*60*60*1000) > device.latestState("battery")?.date?.time) {
        map.isStateChange = true
    }
    state.lastbat = now()
    return [event]
}

def zwaveEvent(physicalgraph.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd) {
    def result = []
    def map = [:]
    switch (cmd.sensorType) {
        case 1:
            def cmdScale = cmd.scale == 1 ? "F" : "C"
            map.name = "temperature"
            map.value = convertTemperatureIfNeeded(cmd.scaledSensorValue, cmdScale, cmd.precision)
            map.unit = getTemperatureScale()
            break;
        case 5:
            map.name = "humidity"
            map.value = cmd.scaledSensorValue.toInteger().toString()
            map.unit = cmd.scale == 0 ? "%" : ""
            break;
        default:
            map.descriptionText = cmd.toString()
    }
    result << createEvent(map)
    return result
}

def zwaveEvent(physicalgraph.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
    def result = []
    def manufacturerCode = String.format("%04X", cmd.manufacturerId)
    def productTypeCode = String.format("%04X", cmd.productTypeId)
    def productCode = String.format("%04X", cmd.productId)
    def wirelessConfig = "ZWP"
    log.debug "MSR ${manufacturerCode} ${productTypeCode} ${productCode}"
    
    result << createEvent(name: "ManufacturerCode", value: manufacturerCode)
    result << createEvent(name: "ProduceTypeCode", value: productTypeCode)
    result << createEvent(name: "ProductCode", value: productCode)
    result << createEvent(name: "WirelessConfig", value: wirelessConfig)

    if (manufacturerCode == "0109" && productTypeCode == "2002") {
        result << response(secureSequence([
            // Change re-trigger duration to 1 minute
            zwave.configurationV1.configurationSet(parameterNumber: 1, configurationValue: [1], size: 1),
            zwave.batteryV1.batteryGet(),
            zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType:1, scale:1)
        ], 400))
    }

    return result
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
    return [createEvent(descriptionText: "$device.displayName: $cmd", displayed: false)]
}

private secure(physicalgraph.zwave.Command cmd) {
    if (state.sec == 0) {  // default to secure
        cmd.format()
    } else {
        zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
    }
}

private secureSequence(commands, delay=200) {
    delayBetween(commands.collect{ secure(it) }, delay)
}
