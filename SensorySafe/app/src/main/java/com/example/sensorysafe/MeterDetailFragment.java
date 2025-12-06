package com.example.sensorysafe;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.sensorysafe.databinding.FragmentMeterDetailBinding;

public class MeterDetailFragment extends Fragment {

    private FragmentMeterDetailBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentMeterDetailBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        double lastLevel = AlertSettings.getLastMeasuredRelativeDb(requireContext());
        binding.textLastLevel.setText(
                getString(R.string.meter_detail_last_level, lastLevel)
        );

        String category;
        if (lastLevel < 40) {
            category = getString(R.string.meter_category_quiet);
        } else if (lastLevel < 60) {
            category = getString(R.string.meter_category_noisy);
        } else if (lastLevel < 80) {
            category = getString(R.string.meter_category_loud);
        } else {
            category = getString(R.string.meter_category_danger);
        }
        binding.textCategory.setText(
                getString(R.string.meter_detail_category_label, category)
        );
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}