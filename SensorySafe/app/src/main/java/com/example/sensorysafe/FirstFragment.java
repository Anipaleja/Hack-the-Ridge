package com.example.sensorysafe;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.sensorysafe.databinding.FragmentFirstBinding;

public class FirstFragment extends Fragment {

    private static final int SAMPLE_RATE = 44100;
    private static final int PERMISSION_REQUEST_RECORD_AUDIO = 1001;
    // Default threshold in the same relative 0-100 scale used below.
    // The actual value used at runtime is loaded from AlertSettings.
    private static final double DEFAULT_ALERT_DB_THRESHOLD = AlertSettings.DEFAULT_ALERT_THRESHOLD_RELATIVE_DB;

    private FragmentFirstBinding binding;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private boolean isMeasuring = false;
    private boolean hasAlertedHighVolume = false;

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
        binding.buttonFirst.setTextDirection(R.string.start_measuring);

        binding.buttonFirst.setOnClickListener(v -> {
            if (isMeasuring) {
                stopMeasuring();
            } else {
                startMeasuring();
            }
        });
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
        binding.buttonFirst.setTextDirection(R.string.stop_measuring);
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
                                    binding.textviewFirst.setText(
                                            getString(R.string.current_level_db, db)
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
            binding.buttonFirst.setTextDirection(R.string.start_measuring);
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

        double approxDb = relativeDb; // relative scale; labelled as 60 dB threshold in UI

        NotificationCompat.Builder builder = new NotificationCompat.Builder(requireContext(), MainActivity.HIGH_VOLUME_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.high_volume_notification_title))
                .setContentText(getString(R.string.high_volume_notification_text, approxDb))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(requireContext());
        notificationManager.notify(1, builder.build());
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
        }
    }

}
