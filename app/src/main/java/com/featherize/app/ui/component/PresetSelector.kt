package com.featherize.app.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.featherize.app.domain.CompressionPreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetSelector(
    selected: CompressionPreset,
    onSelect: (CompressionPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = CompressionPreset.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        presets.forEachIndexed { index, preset ->
            SegmentedButton(
                selected = preset == selected,
                onClick = { onSelect(preset) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = presets.size),
            ) {
                Text(preset.label)
            }
        }
    }
}
