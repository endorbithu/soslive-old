package info.soslive.stream.data.repository

import info.soslive.stream.data.local.SessionStorage
import info.soslive.stream.data.local.toStored
import info.soslive.stream.data.remote.AuthApi
import info.soslive.stream.data.remote.SosLiveApi
import info.soslive.stream.data.remote.apiCall
import info.soslive.stream.data.remote.dto.FacebookLoginRequest
import info.soslive.stream.data.remote.dto.GoogleLoginRequest
import info.soslive.stream.data.remote.dto.LoginRequest
import info.soslive.stream.data.remote.dto.LogoutRequest
import info.soslive.stream.data.remote.dto.RegisterRequest
import info.soslive.stream.data.remote.dto.SessionDto
import info.soslive.stream.data.remote.dto.toDomain
import info.soslive.stream.domain.model.User
import info.soslive.stream.domain.repository.AuthRepository
import info.soslive.stream.domain.repository.EventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val api: SosLiveApi,
    private val sessionStorage: SessionStorage,
    private val eventRepository: EventRepository,
    private val json: Json,
) : AuthRepository {

    override val currentUser: Flow<User?> = sessionStorage.session
        .map { it?.user?.toDomain() }
        .distinctUntilChanged()

    override suspend fun login(email: String, password: String) =
        startSession { authApi.login(LoginRequest(email.trim(), password)) }

    override suspend fun register(email: String, password: String, displayName: String) =
        startSession { authApi.register(RegisterRequest(email.trim(), password, displayName.trim())) }

    override suspend fun loginWithGoogle(idToken: String) =
        startSession { authApi.google(GoogleLoginRequest(idToken)) }

    override suspend fun loginWithFacebook(accessToken: String) =
        startSession { authApi.facebook(FacebookLoginRequest(accessToken)) }

    override suspend fun logout() {
        val refreshToken = sessionStorage.current()?.refreshToken
        // Best effort - the local session is cleared even if the backend is unreachable.
        apiCall(json) { api.logout(LogoutRequest(refreshToken)) }
        eventRepository.clearActiveEvent()
        sessionStorage.clear()
    }

    private suspend fun startSession(call: suspend () -> SessionDto): Result<User> =
        apiCall(json) { call() }.map { session ->
            // A different account may log in on this device: drop the previous user's open incident.
            eventRepository.clearActiveEvent()
            sessionStorage.save(session.toStored())
            session.user.toDomain()
        }
}
