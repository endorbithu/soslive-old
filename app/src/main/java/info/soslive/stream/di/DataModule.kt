package info.soslive.stream.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import info.soslive.stream.data.local.ActiveEventStorage
import info.soslive.stream.data.local.DataStoreActiveEventStorage
import info.soslive.stream.data.local.DataStoreSessionStorage
import info.soslive.stream.data.local.SessionStorage
import info.soslive.stream.data.repository.AuthRepositoryImpl
import info.soslive.stream.data.repository.EventRepositoryImpl
import info.soslive.stream.data.repository.ProfileRepositoryImpl
import info.soslive.stream.domain.repository.AuthRepository
import info.soslive.stream.domain.repository.EventRepository
import info.soslive.stream.domain.repository.ProfileRepository
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindSessionStorage(impl: DataStoreSessionStorage): SessionStorage

    @Binds
    @Singleton
    abstract fun bindActiveEventStorage(impl: DataStoreActiveEventStorage): ActiveEventStorage

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds
    @Singleton
    abstract fun bindEventRepository(impl: EventRepositoryImpl): EventRepository

    companion object {
        @Provides
        @Singleton
        fun provideClock(): Clock = Clock.systemUTC()
    }
}
