// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.uicd.backend.core.devicesdriver;

import com.google.uicd.backend.core.config.UicdConfig;
import com.google.uicd.backend.core.exceptions.UicdDeviceException;
import com.google.uicd.backend.core.exceptions.UicdExternalCommandException;
import com.google.uicd.backend.core.utils.ADBCommandLineUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * DevicesDriverManager, for manage devices and start xmldumper service on devices.
 *
 */
public class DevicesDriverManager {
  private static final Object lock = new Object();
  protected Logger logger = LogManager.getLogManager().getLogger("uicd");
  private static DevicesDriverManager instance;
  private final HashMap<String, AndroidDeviceDriver> androidDriverLinkedMap = new LinkedHashMap<>();
  public HashSet<String> initXmlDumperDevices = new HashSet<>();
  private int selectedDeviceIndex = 0;

  public static DevicesDriverManager getInstance() {
    if (instance == null) {
      instance = new DevicesDriverManager();
    }
    return instance;
  }

  public Device getDevice(String deviceId) {
    return androidDriverLinkedMap.get(deviceId).getDevice();
  }

  public void stopXmldumperServer(String deviceId) throws UicdExternalCommandException {
    if (!androidDriverLinkedMap.containsKey(deviceId)) {
      return;
    }
    androidDriverLinkedMap.get(deviceId).stopXmlDumperServer();
    initXmlDumperDevices.remove(deviceId);
  }

  // used by uicd driver
  public void stopMultiXmldumperServer(List<String> deviceIds) {
    try {
      for (String deviceId : deviceIds) {
        stopXmldumperServer(deviceId);
      }
    } catch (UicdExternalCommandException e) {
      logger.info("Error in stop xmldumper server: " + e.getMessage());
    }
  }

  public Optional<String> getXmlDumperVersion(String deviceId) {
    return ADBCommandLineUtil.getXmlDumperApkVersion(deviceId);
  }

  public void startXmlDumperServer(String deviceId, boolean isUpdateApk)
      throws UicdExternalCommandException {
    AndroidDeviceDriver androidDeviceDriver = androidDriverLinkedMap.get(deviceId);
    if (isUpdateApk) {
      ADBCommandLineUtil.updateXmlDumperApk(
          androidDeviceDriver.getDeviceId(), androidDeviceDriver.getDevice().getApiLevel());
    }
    androidDeviceDriver.startXmlDumperServer();
  }

  public void turnOffAutoRotation(String deviceId) throws UicdExternalCommandException {
    ADBCommandLineUtil.turnOffAutoRotation(deviceId);
  }

  // Used by UicdCLI
  public void startMultiXmldumperServer(List<String> deviceIds, boolean isUpdateApk)
      throws UicdExternalCommandException {
    logger.info("Start startMultiXmldumperServer...");
    for (String deviceId : deviceIds) {
      startXmlDumperServer(deviceId, isUpdateApk);
    }
    logger.info("Finish startMultiXmldumperServer...");
  }

  public void startMultiXmldumperServer(List<String> deviceIds) throws UicdExternalCommandException {
    startMultiXmldumperServer(deviceIds, false);
  }

  public AndroidDeviceDriver getSelectedAndroidDeviceDriver() {
    return getXmldumperDriverList().get(selectedDeviceIndex);
  }

  public Device getMasterDevice() {
    return getXmldumperDriverList().get(selectedDeviceIndex).getDevice();
  }

  public void initDevicesList(List<String> deviceIds) throws UicdExternalCommandException {
    // We are trying to access the same resource(adb forward port), added lock to prevent race
    // condition.
    synchronized (lock) {
      initDevicesList(deviceIds, true);
    }
  }

  // In local mode, we always reuse the ports. Since testing could stopped by the user
  // at any time, there is no good way to clean up the ports for now.
  // We don't have this issue on Mobile Harness, since we need clean up the port after each run.
  public void initDevicesList(List<String> deviceIds, boolean autoAllocate)
      throws UicdExternalCommandException {
    logger.info("Start initDeviceList...");
    if (deviceIds.isEmpty()) {
      logger.warning("Devices list is empty returning.");
      return;
    }
    // Reset xmldumper Mapping
    initXmlDumperDevices.clear();
    androidDriverLinkedMap.clear();

    int forwardPort = UicdConfig.getInstance().getAdbForwardStartPort();
    forwardPort =
        autoAllocate
            ? ADBCommandLineUtil.getFirstAvailablePortSlot(
                deviceIds.get(0), forwardPort, deviceIds.size())
            : forwardPort;

    int deviceIndex = 0;
    for (String deviceId : deviceIds) {
      logger.info("Init " + deviceId);
      String screenSizeStr = ADBCommandLineUtil.getDeviceScreenSize(deviceId);
      String productName = ADBCommandLineUtil.getDeviceProductName(deviceId);
      int apiLevel = ADBCommandLineUtil.getDeviceApiLevel(deviceId);
      ArrayList<String> adbOutput = new ArrayList<String>();
      ADBCommandLineUtil.executeAdb(
          "adb shell dumpsys input | grep 'SurfaceOrientation' | awk '{ print $2 }'",
          deviceId,
          adbOutput);
      String orientation = !adbOutput.isEmpty() ? adbOutput.get(0) : "0";
      Device device =
          new Device(
              deviceId,
              screenSizeStr,
              productName,
              forwardPort,
              forwardPort + 1,
              forwardPort + 2,
              forwardPort + 3,
              deviceIndex,
              apiLevel,
              orientation);
      // initProperties requires an external adb call. instead of calling it inside the constructor,
      //  call it separately.
      device.initProperties();

      // Allocate the port here to fix the multithread issue. We should forward all ports in
      // initDevicesList. However minicap is a little bit complicate. Currently in the CLI and
      // Mobileharness, we don't need minicap, and the multithread issue only happend in MH.
      // set the port forward here, so that the port is occupied immediately, other thread will
      // get the current port.
      String forwardCmd = "forward tcp:%d tcp:%d";
      ADBCommandLineUtil.executeAdb(
          String.format(forwardCmd, device.getXmlDumperHostPort(), device.getXmlDumperDevicePort()),
          deviceId);
      logger.info(device.toString());
      AndroidDeviceDriver androidDeviceDriver = new AndroidDeviceDriver(device);
      androidDriverLinkedMap.put(deviceId, androidDeviceDriver);
      forwardPort += 3;
      deviceIndex++;
      androidDeviceDriver.refreshScreenDimension();
    }
    logger.info("Finish initDeviceList...");
  }

  public AndroidDeviceDriver getAndroidDriverByDeviceId(String deviceId)
      throws UicdDeviceException {
    if (!androidDriverLinkedMap.containsKey(deviceId)) {
      throw new UicdDeviceException("Can not find dirver for device:" + deviceId);
    }
    return androidDriverLinkedMap.get(deviceId);
  }

  public int getSelectedDeviceIndex() {
    return selectedDeviceIndex;
  }

  public void setSelectedDeviceIndex(int selectedDeviceIndex) {
    this.selectedDeviceIndex = selectedDeviceIndex;
  }

  public void setSelectedDeviceByDeviceId(String deviceId) throws UicdDeviceException {
    this.selectedDeviceIndex =
        this.getAndroidDriverByDeviceId(deviceId).getDevice().getDeviceIndex();
  }

  public List<AndroidDeviceDriver> getXmldumperDriverList() {
    return new ArrayList<>(this.getAndroidDriverLinkedMap().values());
  }

  public List<String> getDeviceList() {
    return new ArrayList<>(this.getAndroidDriverLinkedMap().keySet());
  }

  public HashMap<String, AndroidDeviceDriver> getAndroidDriverLinkedMap() {
    return androidDriverLinkedMap;
  }

  public static void reset() {
    for (AndroidDeviceDriver androidDeviceDriver : instance.androidDriverLinkedMap.values()) {
      killXmldumperServer(androidDeviceDriver);
    }
    instance = new DevicesDriverManager();
  }

  public static void killXmldumperServer(AndroidDeviceDriver androidDeviceDriver) {
    try {
      Device device = androidDeviceDriver.getDevice();
      String deviceId = device.getDeviceId();
      ADBCommandLineUtil.executeAdb(
          "forward --remove tcp:" + device.getXmlDumperHostPort(), deviceId, true /* waitFor */);
      ADBCommandLineUtil.executeAdb(
          "shell killall com.google.uicd.xmldumper", deviceId, true /* waitFor */);
    } catch (UicdExternalCommandException e) {
      DevicesDriverManager.getInstance().logger.info(e.getMessage());
    }
  }
}
