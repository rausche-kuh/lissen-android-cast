package org.grakovne.lissen.cast.googlecast

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import su.litvak.chromecast.api.v2.Media
import su.litvak.chromecast.api.v2.MediaStatus
import su.litvak.chromecast.api.v2.Request
import su.litvak.chromecast.api.v2.Response

/** The media channel's queue requests, which the library has none of. Jackson writes them from their getters. */
abstract class QueueRequest(
  val type: String,
  val mediaSessionId: Long,
) : Request {
  private var id: Long? = null

  override fun getRequestId(): Long? = id

  override fun setRequestId(requestId: Long?) {
    id = requestId
  }
}

class QueueInsert(
  mediaSessionId: Long,
  val items: List<QueueItem>,
) : QueueRequest("QUEUE_INSERT", mediaSessionId)

class QueueRemove(
  mediaSessionId: Long,
  val itemIds: List<Long>,
) : QueueRequest("QUEUE_REMOVE", mediaSessionId)

/** The receiver starts to buffer the item [preloadTime] seconds before the one ahead of it ends. */
class QueueItem(
  val media: Media,
  val preloadTime: Double,
) {
  val autoplay = true
}

/** The receiver's answer to a queue request: the media status, or an error. The library names the message type responseType. */
@JsonIgnoreProperties(ignoreUnknown = true)
class QueueResponse : Response {
  var responseType: String? = null
  var reason: String? = null
  var status: List<MediaStatus>? = null

  private var id: Long? = null

  override fun getRequestId(): Long? = id

  override fun setRequestId(requestId: Long?) {
    id = requestId
  }
}
