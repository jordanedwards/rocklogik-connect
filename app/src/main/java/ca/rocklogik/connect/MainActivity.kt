package ca.rocklogik.connect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ca.rocklogik.connect.ConnectViewModel.Conn
import ca.rocklogik.connect.ui.screens.FindScreen
import ca.rocklogik.connect.ui.screens.PendingStep
import ca.rocklogik.connect.ui.screens.SignInScreen
import ca.rocklogik.connect.ui.screens.StatusScreen
import ca.rocklogik.connect.ui.screens.TerminalScreen
import ca.rocklogik.connect.ui.screens.UnitScreen
import ca.rocklogik.connect.ui.screens.UnlockScreen
import ca.rocklogik.connect.ui.theme.RockLogikTheme

/**
 * One activity; each approved wireframe (ROCKLOGIK-81) is a destination here, in the canvas's
 * order. Steps not built yet show their ticket.
 */
class MainActivity : ComponentActivity() {
    private val vm: ConnectViewModel by viewModels()
    private val auth: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RockLogikTheme {
                val nav = rememberNavController()
                fun go(route: String) = nav.navigate(route) { launchSingleTop = true }
                fun back() { nav.popBackStack() }
                val chip = { (vm.conn.value as? Conn.Ready)?.name }

                val start = if (auth.state.value is AuthViewModel.State.SignedIn) "find" else "signin"
                NavHost(nav, startDestination = start) {
                    composable("signin") {
                        SignInScreen(auth, onSignedIn = { nav.navigate("find") { popUpTo("signin") { inclusive = true } } })
                    }
                    composable("find") {
                        FindScreen(vm, onSignOut = {
                            vm.disconnect(); auth.signOut()
                            nav.navigate("signin") { popUpTo(0) }
                        }, onPrepare = { go("identify") }, onPicked = { go("unlock") })
                    }
                    composable("unlock") {
                        UnlockScreen(vm, onBack = ::back, onUnlocked = {
                            nav.navigate("unit") { popUpTo("find") }
                        })
                    }
                    composable("unit") {
                        UnitScreen(vm, onBack = { nav.popBackStack("find", inclusive = false) },
                            onStart = { go("identify") }, onStatus = { go("status") }, onTerminal = { go("terminal") })
                    }
                    composable("identify") {
                        PendingStep("1 of 6 · Truck", 1, chip(), "Which truck is this?", "ROCKLOGIK-95", ::back, "Next: photos") { go("photos") }
                    }
                    composable("photos") {
                        PendingStep("2 of 6 · Photos", 2, chip(), "Four photos", "ROCKLOGIK-100", ::back, "Next: site network") { go("network") }
                    }
                    composable("network") {
                        PendingStep("3 of 6 · Network", 3, chip(), "Site wifi, if the site gave you one", "ROCKLOGIK-95", ::back, "Next: status check") { go("status") }
                    }
                    composable("status") {
                        StatusScreen(vm, onBack = ::back, onNext = { go("recce") }, onTerminal = { go("terminal") })
                    }
                    composable("recce") {
                        PendingStep("5 of 6 · Recce", 5, chip(), "Bus reconnaissance", "ROCKLOGIK-100", ::back, "Next: record") { go("record") }
                    }
                    composable("record") {
                        PendingStep("6 of 6 · Record", 6, chip(), "Check, then send", "ROCKLOGIK-102", ::back, null, null)
                    }
                    composable("pucks") {
                        PendingStep("Pucks", null, chip(), "Pucks, as the VISR sees them", "ROCKLOGIK-105", ::back, null, null)
                    }
                    composable("terminal") { TerminalScreen(vm, onBack = ::back) }
                }
            }
        }
    }
}
