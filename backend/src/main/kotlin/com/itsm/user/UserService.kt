package com.itsm.user

import com.itsm.common.error.AuthenticationFailed
import com.itsm.common.security.CurrentUserProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
    private val currentUser: CurrentUserProvider
) {
    /** The caller as stored right now (never a client- or token-supplied role). 401 if the row vanished. */
    @Transactional(readOnly = true)
    fun getCurrentUser(): UserDto {
        val caller = currentUser.current()
        val user = userRepository.findById(caller.id).orElseThrow { AuthenticationFailed() }
        return user.toDto()
    }
}
