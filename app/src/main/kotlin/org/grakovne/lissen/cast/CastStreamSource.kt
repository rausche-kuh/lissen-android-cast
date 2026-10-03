package org.grakovne.lissen.cast

import android.net.Uri
import androidx.core.net.toUri
import org.grakovne.lissen.cast.upnp.didlLite
import org.grakovne.lissen.channel.audiobookshelf.AudiobookshelfHostProvider
import org.grakovne.lissen.content.LissenMediaProvider
import org.grakovne.lissen.persistence.preferences.SessionPreferences
import org.grakovne.lissen.playback.service.SyncStateStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server URLs a renderer can open by itself. It can't send headers, so the token rides in the
 * query, as `authInterceptor` would send it. Always the channel: a local copy is out of its reach.
 */
@Singleton
class CastStreamSource
  @Inject
  constructor(
    private val mediaProvider: LissenMediaProvider,
    private val hostProvider: AudiobookshelfHostProvider,
    private val session: SessionPreferences,
    private val syncState: SyncStateStore,
  ) : StreamSource {
    override fun open(
      chapter: QueueChapter,
      fileId: String,
    ): CastStream {
      val url =
        mediaProvider
          .providePreferredChannel()
          .provideFileUri(chapter.bookId, fileId)
          .withToken()

      val coverUrl =
        hostProvider
          .provideHost()
          ?.url
          ?.toUri()
          ?.buildUpon()
          ?.appendPath("api")
          ?.appendPath("items")
          ?.appendPath(chapter.bookId)
          ?.appendPath("cover")
          ?.build()
          ?.withToken()

      val mimeType =
        syncState.value.item
          ?.takeIf { it.id == chapter.bookId }
          ?.files
          ?.find { it.id == fileId }
          ?.mimeType

      return CastStream(url, didlLite(url, chapter.title, chapter.album, coverUrl, mimeType))
    }

    private fun Uri.withToken(): String =
      when (val token = session.getAccessToken() ?: session.getToken()) {
        null -> this
        else -> buildUpon().appendQueryParameter("token", token).build()
      }.toString()
  }
