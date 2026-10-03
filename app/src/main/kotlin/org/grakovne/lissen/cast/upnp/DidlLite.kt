package org.grakovne.lissen.cast.upnp

/**
 * The server answers range requests. Several renderers refuse any seek in a stream that doesn't
 * say so: OP=01 is byte seeking, the flags mark a streamed, not interactive, transfer.
 */
private const val DLNA_SEEKABLE = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

/** The metadata renderers show for a stream. Without a known MIME type the renderer sniffs the stream. */
internal fun didlLite(
  url: String,
  title: String,
  album: String?,
  coverUrl: String?,
  mimeType: String?,
): String =
  buildString {
    append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
    append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
    append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
    append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
    append("<dc:title>${escapeXml(title)}</dc:title>")
    album?.let { append("<upnp:album>${escapeXml(it)}</upnp:album>") }
    coverUrl?.let { append("<upnp:albumArtURI>${escapeXml(it)}</upnp:albumArtURI>") }
    append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
    append("<res protocolInfo=\"http-get:*:${escapeXml(mimeType ?: "*")}:$DLNA_SEEKABLE\">${escapeXml(url)}</res>")
    append("</item></DIDL-Lite>")
  }
