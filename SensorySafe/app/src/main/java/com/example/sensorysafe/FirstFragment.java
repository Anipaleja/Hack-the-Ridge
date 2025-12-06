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
    // Default threshold in the same relative 0-100 scale used below.
    // The actual value used at runtime is loaded from AlertSettings.
    private static final double DEFAULT_ALERT_DB_THRESHOLD = AlertSettings.DEFAULT_ALERT_THRESHOLD_RELATIVE_DB;

    private static final String ALERT_PHONE_NUMBER = "6478233878"; // 647-823-3878

    private FragmentFirstBinding binding;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private boolean isMeasuring = false;
    private boolean hasAlertedHighVolume = false;
    private Double pendingSmsAlertDb = null;

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

        binding.buttonFirst.setOnClickListener(v -> {
            if (isMeasuring) {
                stopMeasuring();
            } else {
                startMeasuring();
            }
        });

        // Bottom navigation: maps, meter (current), and threshold settings.
        binding.navMaps.setOnClickListener(v -> openMapsForLibraries());

        binding.navMeter.setOnClickListener(v -> {
            // Already on the meter screen; no navigation needed for now.
            // Could scroll to top or provide feedback if desired.
        });

        binding.navThreshold.setOnClickListener(v ->
                NavHostFragment.findNavController(FirstFragment.this)
                        .navigate(R.id.action_FirstFragment_to_SecondFragment));
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
        if (getContext() == null) {
            return;
        }

        // Check runtime SEND_SMS permission.
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            // Remember this value so we can send after the user grants permission.
            pendingSmsAlertDb = approxDb;
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, PERMISSION_REQUEST_SEND_SMS);
            return;
        }

        SmsManager smsManager = SmsManager.getDefault();
        String message = getString(R.string.high_volume_sms_text, approxDb);
        smsManager.sendTextMessage(ALERT_PHONE_NUMBER, null, message, null, null);
    }

    private void openMapsForLibraries() {
        Uri gmmIntentUri = Uri.parse("geo:0,0?q=library");
        android.content.Intent mapIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW, gmmIntentUri);
        mapIntent.setPackage("com.google.android.apps.maps");
        if (mapIntent.resolveActivity(requireContext().getPackageManager()) != null) {
            startActivity(mapIntent);
        }
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
                    && pendingSmsAlertDb != null) {
                double value = pendingSmsAlertDb;
                pendingSmsAlertDb = null;
                // Permission just granted; actually send the SMS.
                sendHighVolumeSms(value);
            } else {
                // Permission denied or no pending value; clear state.
                pendingSmsAlertDb = null;
            }
        }
    }

}
