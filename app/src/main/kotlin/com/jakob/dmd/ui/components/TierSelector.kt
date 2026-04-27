package com.jakob.dmd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jakob.dmd.domain.model.Tier
import com.jakob.dmd.ui.theme.DmdAccent
import com.jakob.dmd.ui.theme.DmdBg3
import com.jakob.dmd.ui.theme.DmdFg

@Composable
fun TierSelector(
    selected: Tier,
    onChange: (Tier) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(count = Tier.entries.size) { idx ->
            val t = Tier.entries[idx]
            FilterChip(
                selected = t == selected,
                onClick = { onChange(t) },
                label = { Text(t.label, color = DmdFg) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = DmdBg3,
                    selectedContainerColor = DmdAccent,
                ),
            )
        }
    }
}
