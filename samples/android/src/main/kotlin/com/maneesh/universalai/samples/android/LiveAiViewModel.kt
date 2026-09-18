package com.maneesh.universalai.samples.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

class LiveAiViewModel(application: Application) : AndroidViewModel(application) {
    val controller = LiveAiController(viewModelScope, KeystoreLiveCredentialStore(application))
    override fun onCleared() { controller.close() }
}
