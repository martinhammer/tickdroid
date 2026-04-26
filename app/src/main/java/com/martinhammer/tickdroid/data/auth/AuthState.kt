package com.martinhammer.tickdroid.data.auth

sealed interface AuthState {
    data object Unknown : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val credentials: Credentials) : AuthState
}
