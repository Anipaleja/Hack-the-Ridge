package com.example.sensorysafe;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.example.sensorysafe.databinding.FragmentSecondBinding;

public class SecondFragment extends Fragment {

    private FragmentSecondBinding binding;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {

        binding = FragmentSecondBinding.inflate(inflater, container, false);
        return binding.getRoot();

    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize slider with the current stored threshold (relative 0-100 scale).
        float currentThreshold = (float) AlertSettings.getAlertThresholdRelativeDb(requireContext());
        binding.sliderAlertThreshold.setValue(currentThreshold);
        updateThresholdLabel(currentThreshold);

        binding.sliderAlertThreshold.addOnChangeListener((slider, value, fromUser) -> {
            updateThresholdLabel(value);
        });

        // "Save now" button: persist threshold and navigate back to the meter.
        binding.buttonSecond.setOnClickListener(v -> {
            float value = binding.sliderAlertThreshold.getValue();
            AlertSettings.setAlertThresholdRelativeDb(requireContext(), value);
            NavHostFragment.findNavController(SecondFragment.this)
                    .navigate(R.id.action_SecondFragment_to_FirstFragment);
        });
    }

    private void updateThresholdLabel(float value) {
        // Display as an approximate dB value (0-100 scale).
        String label = getString(R.string.threshold_display_value, value);
        binding.textviewThresholdValue.setText(label);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

}