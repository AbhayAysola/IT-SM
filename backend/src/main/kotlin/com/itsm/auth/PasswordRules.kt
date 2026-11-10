package com.itsm.auth

import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput

/** Password policy shared by registration, admin user creation and admin password reset. */
object PasswordRules {
    const val MIN_LENGTH = 8

    /** BCrypt only uses the first 72 BYTES; longer input would be silently truncated, so it is rejected instead. */
    const val BCRYPT_MAX_BYTES = 72

    fun tooLongForBcrypt(password: String) = password.toByteArray(Charsets.UTF_8).size > BCRYPT_MAX_BYTES

    fun require(password: String, field: String = "password") {
        val errors = buildList {
            if (password.isBlank()) add(FieldError(field, "must not be blank"))
            if (password.length < MIN_LENGTH) add(FieldError(field, "must be at least $MIN_LENGTH characters"))
            if (tooLongForBcrypt(password)) add(FieldError(field, "must be at most $BCRYPT_MAX_BYTES bytes"))
        }
        if (errors.isNotEmpty()) throw InvalidInput("Invalid password", errors.distinct())
    }
}
