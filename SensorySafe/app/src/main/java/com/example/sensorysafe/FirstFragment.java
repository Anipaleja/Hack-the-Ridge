package com.example.sensorysafe;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.appcompat.app.AlertDialog;

import com.example.sensorysafe.databinding.FragmentFirstBinding;

public class FirstFragment extends Fragment {

    private static final int SAMPLE_RATE = 44100;
    private static final int PERMISSION_REQUEST_RECORD_AUDIO = 1001;
    private static final int PERMISSION_REQUEST_SEND_SMS = 1002;
    private static final int PERMISSION_REQUEST_BLE = 1003;
    // Default threshold in the same relative 0-100 scale used below.
    // The actual value used at runtime is loaded from AlertSettings.
    private static final double DEFAULT_ALERT_DB_THRESHOLD = AlertSettings.DEFAULT_ALERT_THRESHOLD_RELATIVE_DB;

    private static final String ALERT_PHONE_NUMBER = "6478233878"; // 647-823-3878

    // BLE constants for FidgetToy
    private static final String FIDGET_DEVICE_NAME = "FidgetToy";
    private static final java.util.UUID FIDGET_CHARACTERISTIC_UUID = java.util.UUID.fromString("0000A001-0000-1000-8000-00805F9B34FB");

    private FragmentFirstBinding binding;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private boolean isMeasuring = false;
    private boolean hasAlertedHighVolume = false;

    // Generic pending SMS text for permission flow
    private String pendingSmsText = null;

    // BLE state
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private boolean isScanningBle = false;

    private final ScanCallback fidgetScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device != null && FIDGET_DEVICE_NAME.equals(device.getName())) {
                // Stop scanning once we find the FidgetToy device.
                stopFidgetToyBleScan();
                connectToFidgetToy(device);
            }
        }
    };

    private final BluetoothGattCallback fidgetGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> showFidgetConnectedPopup());
                }
                gatt.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                bluetoothGatt = null;
                // Optionally restart scanning to reconnect.
                startFidgetToyBleScanIfPermitted();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            for (BluetoothGattService service : gatt.getServices()) {
                BluetoothGattCharacteristic characteristic = service.getCharacteristic(FIDGET_CHARACTERISTIC_UUID);
                if (characteristic != null) {
                    // Enable notifications on the characteristic.
                    gatt.setCharacteristicNotification(characteristic, true);
                    BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                    if (descriptor != null) {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(descriptor);
                    }
                    break;
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (FIDGET_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                byte[] value = characteristic.getValue();
                if (value == null) return;
                String text = new String(value).trim().toLowerCase(java.util.Locale.US);
                handleFidgetToyMessage(text);
            }
        }
    };

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {

        binding = FragmentFirstBinding.inflate(inflater, container, false);
        return binding.getRoot();

    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.textviewFirst.setText(R.string.measurement_initial);

        // Toggle app theme between light and dark when the top-right menu icon is pressed.
        binding.buttonMenu.setOnClickListener(v -> {
            int current = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
            if (current == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
            } else {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
            }
        });

        binding.buttonFirst.setOnClickListener(v -> {
            if (isMeasuring) {
                stopMeasuring();
            } else {
                startMeasuring();
            }
        });

        // Top navigation: library helper, meter details, and threshold settings.
        binding.navLibraryHelper.setOnClickListener(v -> {
            double threshold = AlertSettings.getAlertThresholdRelativeDb(requireContext());
            double lastLevel = AlertSettings.getLastMeasuredRelativeDb(requireContext());
            if (lastLevel >= threshold) {
                openMapsForLibraries();
            } else {
                new AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.library_helper_title))
                        .setMessage(getString(R.string.library_helper_below_threshold))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });

        binding.navMeter.setOnClickListener(v ->
                NavHostFragment.findNavController(FirstFragment.this)
                        .navigate(R.id.action_FirstFragment_to_MeterDetailFragment));

        binding.navThreshold.setOnClickListener(v ->
                NavHostFragment.findNavController(FirstFragment.this)
                        .navigate(R.id.action_FirstFragment_to_SecondFragment));

        // Start scanning for the FidgetToy BLE device.
        startFidgetToyBleScanIfPermitted();
    }

    private void startMeasuring() {
        if (getContext() == null) {
            return;
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_RECORD_AUDIO);
            return;
        }

        if (isMeasuring) {
            return;
        }

        int bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (bufferSize <= 0) {
            return;
        }

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );

        try {
            audioRecord.startRecording();
        } catch (IllegalStateException e) {
            audioRecord.release();
            audioRecord = null;
            return;
        }

        isMeasuring = true;
        hasAlertedHighVolume = false;
        binding.textviewFirst.setText(R.string.measurement_running);

        // Load the current user-configured alert threshold once for this measurement session.
        final double alertThreshold = AlertSettings.getAlertThresholdRelativeDb(requireContext());

        recordingThread = new Thread(() -> {
            short[] buffer = new short[bufferSize];
            while (isMeasuring && !Thread.interrupted()) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    double sum = 0;
                    for (int i = 0; i < read; i++) {
                        sum += buffer[i] * buffer[i];
                    }
                    if (sum > 0) {
                        double rms = Math.sqrt(sum / read);
                        // Relative decibels (dBFS) using max 16-bit value as reference
                        final double db = 20.0 * Math.log10(rms / 32767.0);
                        // Shift to an approximate 0-100 scale for threshold comparison
                        final double relativeDb = db + 100.0;

                        if (!hasAlertedHighVolume && relativeDb >= alertThreshold) {
                            hasAlertedHighVolume = true;
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> sendHighVolumeAlert(relativeDb));
                            }
                        }

                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (binding != null) {
                                    // Clamp the displayed value to a friendly 0-100 range so it never shows negatives.
                                    double displayDb = Math.max(0.0, Math.min(100.0, relativeDb));
                                    binding.textviewFirst.setText(
                                            getString(R.string.current_level_db, displayDb)
                                    );
                                    // Persist last measured value for the detailed meter page.
                                    AlertSettings.setLastMeasuredRelativeDb(requireContext(), displayDb);
                                }
                            });
                        }
                    }
                }
            }
        });
        recordingThread.start();
    }

    private void stopMeasuring() {
        isMeasuring = false;
        hasAlertedHighVolume = false;

        if (recordingThread != null) {
            recordingThread.interrupt();
            recordingThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
            audioRecord.release();
            audioRecord = null;
        }

        if (binding != null) {
            binding.textviewFirst.setText(R.string.measurement_stopped);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopMeasuring();
        binding = null;
    }

    private void sendHighVolumeAlert(double relativeDb) {
        if (getContext() == null) {
            return;
        }

        // Clamp to 0-100 range for display so users never see negative values.
        double approxDb = Math.max(0.0, Math.min(100.0, relativeDb));

        // 1) In-app popup dialog that must be acknowledged.
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.high_volume_notification_title))
                .setMessage(getString(R.string.high_volume_notification_text, approxDb))
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    d.dismiss();
                    // After the user dismisses the popup, send an SMS alert.
                    sendHighVolumeSms(approxDb);
                })
                .setCancelable(false)
                .create();
        dialog.show();

        // 2) Optional system notification so the user is also alerted if the app is backgrounded.
        NotificationCompat.Builder builder = new NotificationCompat.Builder(requireContext(), MainActivity.HIGH_VOLUME_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.high_volume_notification_title))
                .setContentText(getString(R.string.high_volume_notification_text, approxDb))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        // Use the Google Maps intent API to show nearby libraries when the user taps the alert.
        Uri gmmIntentUri = Uri.parse("geo:0,0?q=library");
        android.content.Intent mapIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW, gmmIntentUri);
        mapIntent.setPackage("com.google.android.apps.maps");
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                requireContext(),
                0,
                mapIntent,
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                        ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
                        : android.app.PendingIntent.FLAG_UPDATE_CURRENT
        );
        builder.setContentIntent(pendingIntent);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(requireContext());

        // On Android 13+ notifications require the POST_NOTIFICATIONS runtime permission.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                // Permission not granted; do not attempt to post the notification.
                return;
            }
        }

        notificationManager.notify(1, builder.build());
    }

    private void sendHighVolumeSms(double approxDb) {
        String message = getString(R.string.high_volume_sms_text, approxDb);
        sendAlertSms(message);
    }

    private void sendAlertSms(String message) {
        if (getContext() == null) {
            return;
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            // Remember this message so we can send after the user grants permission.
            pendingSmsText = message;
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, PERMISSION_REQUEST_SEND_SMS);
            return;
        }

        SmsManager smsManager = SmsManager.getDefault();
        smsManager.sendTextMessage(ALERT_PHONE_NUMBER, null, message, null, null);
    }

    private void openMapsForLibraries() {
        // Prefer the Google Maps app in navigation mode to the nearest library.
        Uri gmmIntentUri = Uri.parse("google.navigation:q=library&mode=w");
        android.content.Intent mapIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW, gmmIntentUri);
        mapIntent.setPackage("com.google.android.apps.maps");
        if (mapIntent.resolveActivity(requireContext().getPackageManager()) != null) {
            startActivity(mapIntent);
        } else {
            // Fallback: open Google Maps in the browser searching for nearby libraries.
            Uri webUri = Uri.parse("https://www.google.com/maps/search/library/");
            android.content.Intent webIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW, webUri);
            startActivity(webIntent);
        }
    }

    // ==== BLE: FidgetToy integration ====

    private void startFidgetToyBleScanIfPermitted() {
        if (getContext() == null) {
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // Android 12+: need BLUETOOTH_SCAN/CONNECT runtime permissions.
            boolean hasScan = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
            boolean hasConnect = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
            if (!hasScan || !hasConnect) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                }, PERMISSION_REQUEST_BLE);
                return;
            }
        } else {
            // Pre-Android 12: location is required for BLE scans on many devices.
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_BLE);
                return;
            }
        }

        if (isScanningBle) return;

        BluetoothManager bluetoothManager = (BluetoothManager) requireContext().getSystemService(android.content.Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            // Bluetooth is off or unavailable.
            return;
        }

        bleScanner = adapter.getBluetoothLeScanner();
        if (bleScanner == null) return;

        ScanFilter filter = new ScanFilter.Builder()
                .setDeviceName(FIDGET_DEVICE_NAME)
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build();

        bleScanner.startScan(java.util.Collections.singletonList(filter), settings, fidgetScanCallback);
        isScanningBle = true;
    }

    private void stopFidgetToyBleScan() {
        if (bleScanner != null && isScanningBle) {
            bleScanner.stopScan(fidgetScanCallback);
        }
        isScanningBle = false;
    }

    private void connectToFidgetToy(BluetoothDevice device) {
        if (getContext() == null) return;
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        bluetoothGatt = device.connectGatt(requireContext(), false, fidgetGattCallback);
    }

    private void handleFidgetToyMessage(String message) {
        if (message.contains("safe")) {
            // Normal state; no action.
            return;
        } else if (message.contains("warn")) {
            sendAlertSms(getString(R.string.ble_warn_sms));
        } else if (message.contains("emergency")) {
            sendAlertSms(getString(R.string.ble_emergency_sms));
        }
    }

    private void showFidgetConnectedPopup() {
        if (getContext() == null) {
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.fidget_connected_title)
                .setMessage(R.string.fidget_connected_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMeasuring();
            } else if (binding != null) {
                binding.textviewFirst.setText(R.string.permission_denied);
            }
        } else if (requestCode == PERMISSION_REQUEST_SEND_SMS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && pendingSmsText != null) {
                String text = pendingSmsText;
                pendingSmsText = null;
                // Permission just granted; actually send the SMS.
                sendAlertSms(text);
            } else {
                // Permission denied or no pending value; clear state.
                pendingSmsText = null;
            }
        } else if (requestCode == PERMISSION_REQUEST_BLE) {
            // Retry starting BLE scan after permissions.
            startFidgetToyBleScanIfPermitted();
        }
    }

}
