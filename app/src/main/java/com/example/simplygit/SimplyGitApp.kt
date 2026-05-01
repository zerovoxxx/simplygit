package com.example.simplygit

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Annotated with [HiltAndroidApp] to generate Hilt components
 * (SPEC §4.1.2 / §4.7).
 */
@HiltAndroidApp
class SimplyGitApp : Application()
