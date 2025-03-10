/* Copyright_License {

  XCSoar Glide Computer - http://www.xcsoar.org/
  Copyright (C) 2000-2016 The XCSoar Project
  A detailed list of copyright holders can be found in the file "AUTHORS".

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License
  as published by the Free Software Foundation; either version 2
  of the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
}
*/

package org.LK8000;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.UUID;

/**
 * AndroidPort implementation for Bluetooth Low Energy devices using the
 * GATT protocol.
 */
public class BluetoothGattClientPort
    extends BluetoothGattCallback
    implements AndroidPort  {
  private static final String TAG = "BluetoothGattClientPort";

  private static final UUID GENERIC_ACCESS_SERVICE =
          UUID.fromString("00001800-0000-1000-8000-00805F9B34FB");

  private static final UUID DEVICE_NAME_CHARACTERISTIC_UUID =
          UUID.fromString("00002A00-0000-1000-8000-00805F9B34FB");

  /**
   * The HM-10 and compatible bluetooth modules use a GATT characteristic
   * with this UUID for sending and receiving data.
   */
  private static final UUID HM10_SERVICE =
          UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");

  private static final UUID RX_TX_CHARACTERISTIC_UUID =
      UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");

  private static final UUID RX_TX_DESCRIPTOR_UUID =
      UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");


  /* Maximum number of milliseconds to wait for disconnected state after
     calling BluetoothGatt.disconnect() in close() */
  private static final int DISCONNECT_TIMEOUT = 500;

  private PortListener portListener;
  private volatile InputListener listener;

  private BluetoothGatt gatt = null;
  private BluetoothGattCharacteristic dataCharacteristic;
  private BluetoothGattCharacteristic deviceNameCharacteristic;
  private volatile boolean shutdown = false;

  private int maxChunkSize = 20;

  private final HM10WriteBuffer writeBuffer = new HM10WriteBuffer();

  private volatile int portState = STATE_LIMBO;

  private final Object gattStateSync = new Object();
  private int gattState = BluetoothGatt.STATE_DISCONNECTED;

  private BluetoothAdapter.LeScanCallback callback = null;

  static int deviceType(BluetoothDevice device) {
    try {
      return device.getType();
    }
    catch (SecurityException ignore) {
      return BluetoothDevice.DEVICE_TYPE_UNKNOWN;
    }
  }

  static boolean isUnknownType(BluetoothDevice device) {
    return (deviceType(device) != BluetoothDevice.DEVICE_TYPE_LE);
  }

  void startLeScan(Context context, String address) {
    if (callback == null) {
      callback = (device, rssi, scanRecord) -> {
        if (device.getAddress().equals(address)) {
          stopLeScan();
          connectDevice(context, device);
        }
      };
      BluetoothHelper.startLeScan(callback);
    }
  }

  void stopLeScan() {
    BluetoothAdapter.LeScanCallback tmp = callback;
    callback = null;
    if (tmp != null) {
      BluetoothHelper.stopLeScan(tmp);
    }
  }

  public BluetoothGattClientPort(Context context, BluetoothDevice device) {
    if (isUnknownType(device)) {
      // unknown device : scan for device...
      startLeScan(context, device.getAddress());
    }
    else {
      connectDevice(context, device);
    }
  }

  private void connectDevice(Context context, BluetoothDevice device) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        gatt = device.connectGatt(context, true, this, BluetoothDevice.TRANSPORT_LE);
      } else {
        gatt = device.connectGatt(context, true, this);
      }
    } catch (SecurityException e) {
      e.printStackTrace();
    }
  }

  private boolean findCharacteristics() {
    try {
      dataCharacteristic = null;
      deviceNameCharacteristic = null;

      BluetoothGattService service = gatt.getService(HM10_SERVICE);
      if (service != null) {
        dataCharacteristic = service.getCharacteristic(RX_TX_CHARACTERISTIC_UUID);
      }

      service = gatt.getService(GENERIC_ACCESS_SERVICE);
      if (service != null) {
        deviceNameCharacteristic = service.getCharacteristic(DEVICE_NAME_CHARACTERISTIC_UUID);
      }

      if (dataCharacteristic == null) {
        Log.e(TAG, "GATT data characteristic not found");
        return false;
      }

      if (deviceNameCharacteristic == null) {
        Log.e(TAG, "GATT device name characteristic not found");
        return false;
      }

      return true;
    } catch (Exception e) {
      Log.e(TAG, "GATT characteristics lookup failed", e);
      return false;
    }
  }

  @SuppressLint("MissingPermission")
  @Override
  public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
    int newPortState = STATE_LIMBO;
    if (BluetoothProfile.STATE_CONNECTED == newState) {
      if (!gatt.discoverServices()) {
        Log.e(TAG, "Discovering GATT services request failed");
        newPortState = STATE_FAILED;
      }
    } else {
      dataCharacteristic = null;
      deviceNameCharacteristic = null;
      if ((BluetoothProfile.STATE_DISCONNECTED == newState) && !shutdown) {
        if (!gatt.connect()) {
          Log.w(TAG,
              "Received GATT disconnected event, and reconnect attempt failed");
          newPortState = STATE_FAILED;
        }
      }
    }
    writeBuffer.clear();
    portState = newPortState;
    stateChanged();
    synchronized (gattStateSync) {
      gattState = newState;
      gattStateSync.notifyAll();
    }
  }

  @SuppressLint("MissingPermission")
  @Override
  public void onServicesDiscovered(BluetoothGatt gatt, int status) {
    if (BluetoothGatt.GATT_SUCCESS == status) {
      maxChunkSize = 20; // default mtu - 3
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        gatt.requestMtu(85); // Max NMEA length is 82 -> mtu = 82 + 3
      }
      else {
        configureCharacteristics(gatt);
      }
    } else {
      Log.e(TAG, "Discovering GATT services failed");
      portState = STATE_FAILED;
      stateChanged();
    }
  }

  @Override
  public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
    super.onMtuChanged(gatt, mtu, status);
    if (BluetoothGatt.GATT_SUCCESS == status) {
      maxChunkSize = mtu - 3;
      configureCharacteristics(gatt);
    }
  }

  @SuppressLint("MissingPermission")
  private void configureCharacteristics(BluetoothGatt gatt) {
    if (findCharacteristics()) {
      if (gatt.setCharacteristicNotification(dataCharacteristic, true)) {
        BluetoothGattDescriptor descriptor =
          dataCharacteristic.getDescriptor(RX_TX_DESCRIPTOR_UUID);
        if(descriptor != null) {
          descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
          gatt.writeDescriptor(descriptor);
        } else {
          Log.e(TAG, "Could not get RX_TX_DESCRIPTOR_UUID Descriptor");
        }
        portState = STATE_READY;
      } else {
        Log.e(TAG, "Could not enable GATT characteristic notification");
        portState = STATE_FAILED;
      }
    } else {
      portState = STATE_FAILED;
    }
    stateChanged();
  }

  @Override
  public void onCharacteristicRead(BluetoothGatt gatt,
      BluetoothGattCharacteristic characteristic, int status) {
    writeBuffer.beginWriteNextChunk(gatt, dataCharacteristic);
  }

  @Override
  public void onCharacteristicWrite(BluetoothGatt gatt,
      BluetoothGattCharacteristic characteristic, int status) {
    synchronized (writeBuffer) {
      if (BluetoothGatt.GATT_SUCCESS == status) {
        writeBuffer.beginWriteNextChunk(gatt, dataCharacteristic);
        writeBuffer.notifyAll();
      } else {
        Log.e(TAG, "GATT characteristic write failed");
        writeBuffer.setError();
      }
    }
  }

  @Override
  public void onCharacteristicChanged(BluetoothGatt gatt,
      BluetoothGattCharacteristic characteristic) {
    if ((dataCharacteristic != null) &&
        (dataCharacteristic.getUuid().equals(characteristic.getUuid()))) {
      if (listener != null) {
        byte[] data = characteristic.getValue();
        if (data.length > 0) {
          listener.dataReceived(data, data.length);
        }
      }
    }
  }

  @Override public void setListener(PortListener _listener) {
    portListener = _listener;
  }

  @Override
  public void setInputListener(InputListener _listener) {
    listener = _listener;
  }

  @SuppressLint("MissingPermission")
  @Override
  public void close() {
    stopLeScan();
    shutdown = true;
    writeBuffer.clear();
    if (gatt != null) {
      gatt.disconnect();
      waitForClose();
      gatt.close();
      gatt = null;
    }
  }

  private void waitForClose() {
    synchronized (gattStateSync) {
      long waitUntil = System.currentTimeMillis() + DISCONNECT_TIMEOUT;
      while (gattState != BluetoothGatt.STATE_DISCONNECTED) {
        long timeToWait = waitUntil - System.currentTimeMillis();
        if (timeToWait <= 0) {
          Log.e(TAG, "GATT disconnect timeout");
          break;
        }
        try {
          gattStateSync.wait(timeToWait);
        } catch (InterruptedException e) {
          Log.e(TAG, "GATT disconnect timeout", e);
          break;
        }
      }
    }
  }

  @Override
  public int getState() {
    return portState;
  }

  @Override
  public boolean drain() {
    return writeBuffer.drain();
  }

  @Override
  public int getBaudRate() {
    return 0;
  }

  @Override
  public boolean setBaudRate(int baud) {
    return true;
  }

  @Override
  public int write(byte[] data, int length) {
    return writeBuffer.write(gatt, dataCharacteristic,
            deviceNameCharacteristic,
            data, length, maxChunkSize);
  }

  protected final void stateChanged() {
    PortListener portListener = this.portListener;
    if (portListener != null)
      portListener.portStateChanged();
  }
}
