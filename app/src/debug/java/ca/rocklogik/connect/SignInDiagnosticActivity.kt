package ca.rocklogik.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

/**
 * Debug builds only. Runs Google's legacy sign-in, which reports a numeric status where Credential
 * Manager reports only "cancelled": 10 means no Android OAuth client matches this package and
 * certificate in the web client's project; 12500 usually means the consent screen. Launch with
 * `adb shell am start -n ca.rocklogik.connect/.SignInDiagnosticActivity` and read logcat tag SignInDiag.
 */
@Suppress("DEPRECATION")
class SignInDiagnosticActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .requestEmail()
            .build()
        val client = GoogleSignIn.getClient(this, gso)
        client.signOut().addOnCompleteListener { startActivityForResult(client.signInIntent, 1) }
    }

    @Deprecated("legacy API, diagnostic only")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            val acct = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            Log.w(TAG, "OK: ${acct.email}, id token ${if (acct.idToken != null) "present" else "missing"}")
        } catch (e: ApiException) {
            Log.w(TAG, "FAILED: status ${e.statusCode} ${e.message}")
        }
        finish()
    }

    private companion object { const val TAG = "SignInDiag" }
}
