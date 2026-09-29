package info.soslive.stream.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import info.soslive.stream.BuildConfig
import info.soslive.stream.auth.AccountStore
import info.soslive.stream.auth.GoogleAuth
import info.soslive.stream.core.config.AppConfig
import info.soslive.stream.drive.DriveApi
import info.soslive.stream.drive.GoogleDriveApi
import info.soslive.stream.drive.LocalDriveApi
import info.soslive.stream.drive.SosliveDrive
import info.soslive.stream.stream.StreamProvider
import info.soslive.stream.stream.StreamSettingsStore
import info.soslive.stream.stream.UserStreamProvider
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
                redactHeader("Authorization")
            },
        )
        .build()

    /** Google Drive when a Google client id is configured, otherwise the local simulation. */
    @Provides
    @Singleton
    fun provideDriveApi(@ApplicationContext context: Context, client: OkHttpClient, auth: GoogleAuth, clock: Clock): DriveApi =
        if (AppConfig.googleConfigured) GoogleDriveApi(client, auth)
        else LocalDriveApi(File(context.filesDir, "simulated-drive"), clock)

    @Provides
    @Singleton
    fun provideSosliveDrive(drive: DriveApi, store: AccountStore, clock: Clock): SosliveDrive =
        SosliveDrive(drive, store, AppConfig.webappUrl, clock)

    @Provides
    @Singleton
    fun provideStreamProvider(store: StreamSettingsStore): StreamProvider = UserStreamProvider { store.load() }
}
