package ca.rocklogik.connect

import android.app.Activity
import android.app.Application
import android.os.Build
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.rocklogik.connect.cloud.CloudApi
import ca.rocklogik.connect.cloud.TokenStore
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sign-in (ROCKLOGIK-90): Google through Credential Manager, exchanged with rocklogik-cloud for
 * its own token, which is kept encrypted on the phone. The users table on the cloud is the
 * allow-list; this class only reports what the cloud said.
 */
class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private companion object { const val TAG = "AuthViewModel" }

    sealed interface State {
        data object SignedOut : State
        data object Working : State
        data class SignedIn(val name: String, val email: String) : State
        data class Failed(val message: String) : State
    }

    private val store = TokenStore(app)
    private val cloud = CloudApi()
    private val _state = MutableStateFlow<State>(store.load()?.let { State.SignedIn(it.name, it.email) } ?: State.SignedOut)
    val state: StateFlow<State> = _state.asStateFlow()

    val configured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotEmpty()

    fun signIn(activity: Activity) {
        if (_state.value is State.Working) return
        _state.value = State.Working
        viewModelScope.launch {
            _state.value = try {
                // The button flow: Google's full account chooser. One-tap (GetGoogleIdOption)
                // offers only accounts it judges eligible and fails silently with 28433 otherwise.
                val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
                val result = CredentialManager.create(activity)
                    .getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
                val cred = result.credential
                if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    State.Failed("Google returned an unexpected credential.")
                } else {
                    val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    val s = cloud.signIn(idToken, "${Build.MANUFACTURER} ${Build.MODEL}".take(100))
                    store.save(TokenStore.Session(s.token, s.name, s.email))
                    State.SignedIn(s.name, s.email)
                }
            } catch (e: GetCredentialCancellationException) {
                android.util.Log.w(TAG, "cancelled: ${e.message}")
                State.SignedOut
            } catch (e: NoCredentialException) {
                android.util.Log.w(TAG, "no credential: ${e.message}")
                State.Failed("Google offered no account for RockLogik Connect." + detail(e))
            } catch (e: GetCredentialException) {
                android.util.Log.w(TAG, "get credential: ${e.type}: ${e.message}")
                State.Failed("Google sign-in failed." + detail(e))
            } catch (e: CloudApi.Refused) {
                State.Failed(explain(e.reason))
            }
        }
    }

    fun signOut() {
        val token = store.load()?.token
        store.clear()
        _state.value = State.SignedOut
        if (token != null) viewModelScope.launch { cloud.signOut(token) }
    }

    fun token(): String? = store.load()?.token

    /** The exception's own text, on screen in debug builds only: it names the misconfiguration. */
    private fun detail(e: Exception): String = if (BuildConfig.DEBUG) " (${e.message})" else ""

    private fun explain(reason: String): String = when (reason) {
        "not_invited" -> "This Google account is not on the RockLogik list. Ask to be invited."
        "inactive" -> "This account has been switched off. Ask a RockLogik administrator."
        "account_mismatch" -> "That email is tied to a different Google account."
        "invalid_token" -> "RockLogik could not verify the Google sign-in. Check the date and time on this device."
        "unreachable" -> "RockLogik cannot be reached. Check the connection and try again."
        else -> "Sign-in refused ($reason)."
    }
}
