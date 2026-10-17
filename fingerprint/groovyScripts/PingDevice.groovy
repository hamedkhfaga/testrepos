import org.apache.ofbiz.base.util.Debug

String deviceId = parameters.biometricDeviceId
device = from("BiometricDevice").where("biometricDeviceId", deviceId).queryOne()

if (device) {
    // Check if the statusId exists in your system. 
    // If you haven't created 'DEVICE_DISABLED', use 'CTNT_DEACTIVATED' 
    // or just comment out the .store() line to test the button first.
    
    device.set("statusId", "CTNT_DEACTIVATED") 
    device.store()
    Debug.logInfo("Successfully pinged device: " + deviceId, "PingDevice.groovy")
}
return success()