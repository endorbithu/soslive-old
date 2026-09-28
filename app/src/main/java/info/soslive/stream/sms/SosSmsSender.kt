package info.soslive.stream.sms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import info.soslive.stream.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends the SOS text to the user's emergency contacts. Uses SmsManager directly when SEND_SMS is
 * granted, otherwise the UI opens the SMS app pre-filled via [composeIntent].
 */
@Singleton
class SosSmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun canSendDirectly(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** @return how many recipients the message was handed to. */
    fun sendDirect(numbers: List<String>, text: String): Int {
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return 0
        var sent = 0
        for (number in numbers) {
            try {
                smsManager.sendMultipartTextMessage(number, null, smsManager.divideMessage(text), null, null)
                sent++
            } catch (e: Exception) {
                Log.w(TAG, "SMS to $number failed", e)
            }
        }
        return sent
    }

    fun buildMessage(template: String, shareUrl: String): String =
        buildMessage(template, context.getString(R.string.sos_default_message), shareUrl)

    companion object {
        private const val TAG = "SosSmsSender"

        /** Opens the default SMS app pre-filled (fallback when SEND_SMS is not granted). */
        fun composeIntent(numbers: List<String>, text: String): Intent =
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(";")))
                .putExtra("sms_body", text)

        fun buildMessage(template: String, fallback: String, shareUrl: String): String =
            "${template.ifBlank { fallback }.trim()} - $shareUrl"
    }
}
