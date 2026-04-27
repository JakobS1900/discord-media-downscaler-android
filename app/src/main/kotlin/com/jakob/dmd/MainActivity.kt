package com.jakob.dmd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.jakob.dmd.ui.screen.HomeScreen
import com.jakob.dmd.ui.theme.DmdTheme
import com.jakob.dmd.viewmodel.HomeViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DmdTheme {
                HomeScreen(vm = vm)
            }
        }
    }
}
