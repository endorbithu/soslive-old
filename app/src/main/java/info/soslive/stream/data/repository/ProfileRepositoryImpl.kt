package info.soslive.stream.data.repository

import info.soslive.stream.data.local.SessionStorage
import info.soslive.stream.data.remote.SosLiveApi
import info.soslive.stream.data.remote.apiCall
import info.soslive.stream.data.remote.dto.UpdateMeRequest
import info.soslive.stream.data.remote.dto.UserDto
import info.soslive.stream.data.remote.dto.toDomain
import info.soslive.stream.domain.model.User
import info.soslive.stream.domain.repository.ProfileRepository
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val api: SosLiveApi,
    private val sessionStorage: SessionStorage,
    private val json: Json,
) : ProfileRepository {

    override suspend fun refresh(): Result<User> = store { api.me() }

    override suspend fun update(displayName: String, sosContacts: List<String>, sosMessage: String): Result<User> =
        store { api.updateMe(UpdateMeRequest(displayName.trim(), sosContacts, sosMessage.trim())) }

    private suspend fun store(call: suspend () -> UserDto): Result<User> =
        apiCall(json) { call() }.map { dto ->
            sessionStorage.updateUser(dto)
            dto.toDomain()
        }
}
