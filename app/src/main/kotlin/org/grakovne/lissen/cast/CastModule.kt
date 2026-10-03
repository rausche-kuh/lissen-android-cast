package org.grakovne.lissen.cast

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import org.grakovne.lissen.cast.googlecast.GoogleCastProtocol
import org.grakovne.lissen.cast.upnp.SsdpDiscovery
import org.grakovne.lissen.cast.upnp.UpnpProtocol
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

  /** The device list shows what every protocol in the set finds. */
  @Provides
  @Singleton
  @IntoSet
  fun provideUpnpProtocol(
    @RendererHttpClient httpClient: OkHttpClient,
  ): CastProtocol = UpnpProtocol(SsdpDiscovery(httpClient), httpClient)

  @Provides
  @Singleton
  @IntoSet
  fun provideGoogleCastProtocol(): CastProtocol = GoogleCastProtocol()
}
