package com.engfred.yvd.di

import android.content.Context
import androidx.work.WorkManager
import com.engfred.yvd.data.local.ResumeStateStore
import com.engfred.yvd.data.network.DownloaderImpl
import com.engfred.yvd.data.repository.ThemeRepositoryImpl
import com.engfred.yvd.data.repository.YoutubeRepositoryImpl
import com.engfred.yvd.domain.repository.ThemeRepository
import com.engfred.yvd.domain.repository.YoutubeRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency graph for the application singleton scope.
 *
 * Design notes:
 * - [DownloaderImpl] and [ResumeStateStore] are annotated with `@Singleton @Inject constructor()`
 *   directly on their classes, so Hilt discovers them automatically. They do NOT need explicit
 *   `@Provides` entries here.
 *
 * - [YoutubeRepository] maps to [YoutubeRepositoryImpl]. Because we're binding an interface to
 *   a concrete type, we need an explicit `@Provides` so Hilt knows which implementation to use.
 *
 * - [ThemeRepository] maps to [ThemeRepositoryImpl] for the same reason.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides the [YoutubeRepository] implementation.
     *
     * [DownloaderImpl] and [ResumeStateStore] are auto-provided by Hilt as singletons
     * (via their `@Inject constructor` annotations), so Hilt injects them here automatically.
     */
    @Provides
    @Singleton
    fun provideYoutubeRepository(
        @ApplicationContext context: Context,
        downloaderImpl: DownloaderImpl,
        resumeStateStore: ResumeStateStore
    ): YoutubeRepository = YoutubeRepositoryImpl(context, downloaderImpl, resumeStateStore)

    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context
    ): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideThemeRepository(
        @ApplicationContext context: Context
    ): ThemeRepository = ThemeRepositoryImpl(context)
}