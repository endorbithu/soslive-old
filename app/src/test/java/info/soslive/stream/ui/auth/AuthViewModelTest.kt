package info.soslive.stream.ui.auth

import info.soslive.stream.R
import info.soslive.stream.auth.sso.SsoProvider
import info.soslive.stream.auth.sso.SsoResult
import info.soslive.stream.core.ui.UiText
import info.soslive.stream.data.remote.ApiException
import info.soslive.stream.domain.model.User
import info.soslive.stream.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val USER = User(1, "a@b.hu", "Anna", null, listOf("password"), emptyList(), "")

class FakeAuthRepository : AuthRepository {
    val user = MutableStateFlow<User?>(null)
    var nextResult: Result<User> = Result.success(USER)
    val calls = mutableListOf<String>()

    override val currentUser: Flow<User?> = user
    override suspend fun login(email: String, password: String) = record("login:$email")
    override suspend fun register(email: String, password: String, displayName: String) = record("register:$email:$displayName")
    override suspend fun loginWithGoogle(idToken: String) = record("google:$idToken")
    override suspend fun loginWithFacebook(accessToken: String) = record("facebook:$accessToken")
    override suspend fun logout() { user.value = null }

    private fun record(call: String): Result<User> {
        calls += call
        nextResult.onSuccess { user.value = it }
        return nextResult
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val repository = FakeAuthRepository()
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = AuthViewModel(repository)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `empty fields are validated locally`() {
        viewModel.login()
        val state = viewModel.state.value
        assertTrue(state.emailError is UiText.Res)
        assertTrue(state.passwordError is UiText.Res)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun `successful login stores the session and clears the password`() {
        viewModel.onEmailChange("a@b.hu")
        viewModel.onPasswordChange("secret123")
        viewModel.login()

        assertEquals(listOf("login:a@b.hu"), repository.calls)
        assertEquals(USER, repository.user.value)
        assertEquals("", viewModel.state.value.password)
        assertFalse(viewModel.state.value.loading)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `wrong credentials show a localized error`() {
        repository.nextResult = Result.failure(ApiException(401, "invalid_credentials", "Wrong e-mail or password"))
        viewModel.onEmailChange("a@b.hu")
        viewModel.onPasswordChange("bad")
        viewModel.login()

        val error = viewModel.state.value.error as UiText.Res
        assertEquals(R.string.error_invalid_credentials, error.id)
        assertEquals("bad", viewModel.state.value.password)
    }

    @Test
    fun `register validates password length`() {
        viewModel.onDisplayNameChange("Anna")
        viewModel.onEmailChange("a@b.hu")
        viewModel.onPasswordChange("short")
        viewModel.register()
        assertTrue(viewModel.state.value.passwordError is UiText.Res)
        assertTrue(repository.calls.isEmpty())

        viewModel.onPasswordChange("long enough")
        viewModel.register()
        assertEquals(listOf("register:a@b.hu:Anna"), repository.calls)
    }

    @Test
    fun `sso tokens are passed to the right provider endpoint`() {
        viewModel.onSsoResult(SsoProvider.GOOGLE, SsoResult.Success("mock:g@x.hu"))
        viewModel.onSsoResult(SsoProvider.FACEBOOK, SsoResult.Success("fb-token"))
        viewModel.onSsoResult(SsoProvider.FACEBOOK, SsoResult.Cancelled)
        assertEquals(listOf("google:mock:g@x.hu", "facebook:fb-token"), repository.calls)
    }
}
