package com.martinhammer.tickdroid.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val store: CredentialStore,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        _state.value = store.load()?.let(AuthState::SignedIn) ?: AuthState.SignedOut
    }

    fun currentCredentials(): Credentials? =
        (_state.value as? AuthState.SignedIn)?.credentials

    fun signIn(credentials: Credentials) {
        store.save(credentials)
        _state.value = AuthState.SignedIn(credentials)
    }

    fun signOut() {
        store.clear()
        _state.value = AuthState.SignedOut
    }

    /**
     * Login Flow v2 placeholder. v1 uses the pasted-app-password path via [signIn].
     * See mobile_instructions.md §1.
     */
    @Suppress("unused")
    fun beginLoginFlowV2(): Nothing =
        TODO("Login Flow v2 not implemented in v1")
}
