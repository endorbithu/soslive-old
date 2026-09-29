package info.soslive.stream.core.ui

import info.soslive.stream.R
import info.soslive.stream.drive.DriveConsentRequiredException
import info.soslive.stream.drive.DriveException
import info.soslive.stream.drive.DriveNotFoundException
import java.io.IOException

/** User facing text for a failure. */
fun Throwable.toUiText(): UiText = when (this) {
    is DriveConsentRequiredException -> UiText.Res(R.string.error_drive_permission)
    is DriveNotFoundException -> UiText.Res(R.string.error_drive_missing)
    is DriveException -> UiText.Res(R.string.error_drive, message ?: "HTTP $status")
    is IOException -> UiText.Res(R.string.error_network)
    else -> UiText.Res(R.string.error_generic)
}
