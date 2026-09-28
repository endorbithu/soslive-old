package info.soslive.stream.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import info.soslive.stream.domain.repository.AuthRepository
import info.soslive.stream.ui.auth.LoginScreen
import info.soslive.stream.ui.auth.RegisterScreen
import info.soslive.stream.ui.events.EventDetailScreen
import info.soslive.stream.ui.events.EventsScreen
import info.soslive.stream.ui.profile.ProfileScreen
import info.soslive.stream.ui.stream.StreamScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable data object LoginRoute
@Serializable data object RegisterRoute
@Serializable data object HomeRoute
@Serializable data object EventsRoute
@Serializable data class EventDetailRoute(val id: Long)
@Serializable data object ProfileRoute

sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val userId: Long) : SessionState
}

@HiltViewModel
class AppViewModel @Inject constructor(authRepository: AuthRepository) : ViewModel() {
    val session: StateFlow<SessionState> = authRepository.currentUser
        .map { user -> if (user == null) SessionState.LoggedOut else SessionState.LoggedIn(user.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Loading)
}

/**
 * Root: the session decides which graph is shown. Logging in/out (or a rejected refresh token)
 * swaps the whole NavHost, so no screen has to navigate "to login" itself.
 */
@Composable
fun SosLiveApp(viewModel: AppViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    when (val s = session) {
        SessionState.Loading -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        SessionState.LoggedOut -> AuthNavHost()
        // Keyed by user: another account gets a fresh back stack and fresh ViewModels.
        is SessionState.LoggedIn -> key(s.userId) { MainNavHost() }
    }
}

@Composable
private fun AuthNavHost() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = LoginRoute) {
        composable<LoginRoute> { LoginScreen(onRegister = { navController.navigate(RegisterRoute) }) }
        composable<RegisterRoute> { RegisterScreen(onBack = { navController.popBackStack() }) }
    }
}

@Composable
private fun MainNavHost() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            StreamScreen(
                onOpenEvents = { navController.navigate(EventsRoute) },
                onOpenProfile = { navController.navigate(ProfileRoute) },
            )
        }
        composable<EventsRoute> {
            EventsScreen(
                onBack = { navController.popBackStack() },
                onOpenEvent = { id -> navController.navigate(EventDetailRoute(id)) },
            )
        }
        composable<EventDetailRoute> { EventDetailScreen(onBack = { navController.popBackStack() }) }
        composable<ProfileRoute> { ProfileScreen(onBack = { navController.popBackStack() }) }
    }
}
