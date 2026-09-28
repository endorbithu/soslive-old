package info.soslive.stream.data.remote

import info.soslive.stream.data.local.SessionStorage
import info.soslive.stream.data.local.StoredSession
import info.soslive.stream.data.remote.dto.UserDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class FakeSessionStorage(initial: StoredSession?) : SessionStorage {
    val state = MutableStateFlow(initial)
    override val session: Flow<StoredSession?> = state
    override suspend fun save(session: StoredSession) { state.value = session }
    override suspend fun updateUser(user: UserDto) { state.value = state.value?.copy(user = user) }
    override suspend fun clear() { state.value = null }
}

class TokenAuthenticatorTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val user = UserDto(id = 1, email = "a@b.hu", displayName = "A")
    private lateinit var storage: FakeSessionStorage
    private lateinit var api: SosLiveApi

    @Before
    fun setUp() {
        server.start()
        storage = FakeSessionStorage(StoredSession("old-access", "old-refresh", user))
        val factory = json.asConverterFactory("application/json".toMediaType())
        val authApi = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(factory).build().create(AuthApi::class.java)
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(storage))
            .authenticator(TokenAuthenticator(storage, authApi))
            .build()
        api = Retrofit.Builder().baseUrl(server.url("/")).client(client).addConverterFactory(factory).build().create(SosLiveApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun userJson() = """{"id":1,"email":"a@b.hu","displayName":"A"}"""

    @Test
    fun `expired access token is refreshed once and the request retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"token_expired","message":"jwt expired"}}"""))
        server.enqueue(MockResponse().setBody("""{"accessToken":"new-access","refreshToken":"new-refresh","user":${userJson()}}"""))
        server.enqueue(MockResponse().setBody(userJson()))

        val me = api.me()

        assertEquals("A", me.displayName)
        assertEquals("Bearer old-access", server.takeRequest().getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/auth/refresh", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("old-refresh"))
        assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
        assertEquals("new-refresh", storage.state.value?.refreshToken)
    }

    @Test
    fun `rejected refresh token clears the session and surfaces an ApiException`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"token_expired","message":"jwt expired"}}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"invalid_refresh_token","message":"nope"}}"""))

        val result = apiCall(json) { api.me() }

        val error = result.exceptionOrNull() as ApiException
        assertEquals(401, error.httpStatus)
        assertEquals("token_expired", error.code)
        assertNull(storage.state.value)
    }
}
