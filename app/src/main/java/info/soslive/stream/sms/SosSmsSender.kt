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
import info.soslive.stream.drive.ContactRules
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends the event link to the contacts in config.json from the phone itself (there is no backend
 * to do it). SMS goes out directly when SEND_SMS is granted, otherwise (and for e-mail) the UI
 * opens a pre-filled composer and the user taps Send. Replies arrive as normal SMS on the phone;
 * the app never reads SMS.
 */
@Singleton
class SosSmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun canSendDirectly(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** @return how many recipients the message was handed to. */
    fun sendDirect(phones: List<String>, text: String): Int {
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return 0
        var sent = 0
        for (phone in phones) {
            try {
                smsManager.sendMultipartTextMessage(ContactRules.dialable(phone), null, smsManager.divideMessage(text), null, null)
                sent++
            } catch (e: Exception) {
                Log.w(TAG, "SMS failed", e)
            }
        }
        return sent
    }

    /** SMS / e-mail text with the event link (no personal data besides the link). */
    fun sosText(link: String): String = context.getString(R.string.notify_text, link)

    fun sosSubject(): String = context.getString(R.string.notify_subject)

    companion object {
        private const val TAG = "SosSmsSender"

        /** Pre-filled SMS composer. */
        fun smsIntent(phones: List<String>, text: String): Intent =
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phones.joinToString(";") { ContactRules.dialable(it) }))
                .putExtra("sms_body", text)

        /** Pre-filled e-mail composer. */
        fun emailIntent(emails: List<String>, subject: String, text: String): Intent =
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_EMAIL, emails.toTypedArray())
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, text)
    }
}
