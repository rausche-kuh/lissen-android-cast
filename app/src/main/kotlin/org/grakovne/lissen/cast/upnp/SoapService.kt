package org.grakovne.lissen.cast.upnp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element

/** Invokes the actions of one UPnP service on instance 0. */
internal class SoapService(
  private val controlUrl: String,
  private val serviceType: String,
  private val httpClient: OkHttpClient,
) {
  /** Sends the action and returns its response element. */
  fun invoke(
    action: String,
    vararg arguments: Pair<String, String>,
  ): Element {
    val request =
      Request
        .Builder()
        .url(controlUrl)
        .header("SOAPACTION", "\"$serviceType#$action\"")
        .post(envelope(serviceType, action, listOf("InstanceID" to "0") + arguments).toRequestBody(SOAP_MEDIA_TYPE))
        .build()

    httpClient.newCall(request).execute().use { response ->
      val body = response.body.string()
      val root = runCatching { parseXml(body) }.getOrNull()

      if (!response.isSuccessful || root == null) {
        val fault = root?.descendants("UPnPError")?.firstOrNull()
        val code = fault?.childText("errorCode")
        val description = fault?.childText("errorDescription")
        throw UpnpException(
          "$action failed: HTTP ${response.code}${code?.let { ", UPnP error $it" }.orEmpty()}${description?.let { " ($it)" }.orEmpty()}",
        )
      }

      return root.descendants("${action}Response").firstOrNull()
        ?: throw UpnpException("$action failed: no ${action}Response in the answer")
    }
  }

  companion object {
    private val SOAP_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()

    fun envelope(
      serviceType: String,
      action: String,
      arguments: List<Pair<String, String>>,
    ): String =
      buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:$action xmlns:u=\"$serviceType\">")
        arguments.forEach { (name, value) -> append("<$name>${escapeXml(value)}</$name>") }
        append("</u:$action></s:Body></s:Envelope>")
      }
  }
}
