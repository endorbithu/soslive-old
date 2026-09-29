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
import info.soslive.stream.auth.AccountStore
import info.soslive.stream.auth.GoogleAuth
import info.soslive.stream.ui.auth.SignInScreen
import info.soslive.stream.ui.events.EventDetailScreen
import info.soslive.stream.ui.events.EventsScreen
import info.soslive.stream.ui.settings.SettingsScreen
import info.soslive.stream.ui.stream.StreamScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable data object HomeRoute
@Serializable data object EventsRoute
@Serializable data class EventDetailRoute(val fileId: String, val title: String)
@Serializable data object SettingsRoute

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val email: String) : SessionState
}

@HiltViewModel
class AppViewModel @Inject constructor(accountStore: AccountStore, googleAuth: GoogleAuth) : ViewModel() {
    val session: StateFlow<SessionState> = accountStore.account
        .onEach { googleAuth.accountEmail = it?.email } // the Drive token is requested for this account
        .map { account -> if (account == null) SessionState.SignedOut else SessionState.SignedIn(account.email) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Loading)
}

/** The stored account decides what is shown: Google sign-in or the main screens. */
@Composable
fun SosLiveApp(viewModel: AppViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    when (val s = session) {
        SessionState.Loading -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        SessionState.SignedOut -> SignInScreen()
        is SessionState.SignedIn -> key(s.email) { MainNavHost() }
    }
}

@Composable
private fun MainNavHost() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            StreamScreen(
                onOpenEvents = { navController.navigate(EventsRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }
        composable<EventsRoute> {
            EventsScreen(
                onBack = { navController.popBackStack() },
                onOpenEvent = { fileId, title -> navController.navigate(EventDetailRoute(fileId, title)) },
            )
        }
        composable<EventDetailRoute> { EventDetailScreen(onBack = { navController.popBackStack() }) }
        composable<SettingsRoute> { SettingsScreen(onBack = { navController.popBackStack() }) }
    }
}
