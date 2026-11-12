package com.itsm.auth.dto

/**
 * The ONLY body `POST /api/auth/register` ever returns on success (HTTP 202). It is identical whether the address was new or
 * already registered, and carries no token: the caller must sign in.
 */
data class RegistrationResponse(val status: String = ACCEPTED) {
    companion object {
        const val ACCEPTED = "ACCEPTED"
    }
}
