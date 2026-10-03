package org.grakovne.lissen.cast

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.grakovne.lissen.cast.upnp.SsdpDiscovery
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RendererHttpClient

@Module
@InstallIn(SingletonComponent::class)
object CastModule {
  /** Without the server's auth interceptor: renderers must never receive the account token in a header. */
  @Provides
  @Singleton
  @RendererHttpClient
  fun provideRendererHttpClient(): OkHttpClient =
    OkHttpClient
      .Builder()
      .connectTimeout(3, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .build()

  @Provides
  @Singleton
  fun provideSsdpDiscovery(
    @RendererHttpClient httpClient: OkHttpClient,
  ): SsdpDiscovery = SsdpDiscovery(httpClient)
}
