package org.grakovne.lissen.cast

/** A device playback can move to, found by one of the [CastProtocol]s. */
interface CastDevice {
  /** Unique across protocols. */
  val id: String
  val name: String

  /** The protocol, as the device list names it. */
  val protocol: String
}

/** The same device seen through two protocols lists twice, next to each other. */
val castDeviceOrder: Comparator<CastDevice> = compareBy({ it.name.lowercase() }, { it.name }, { it.protocol }, { it.id })

class DeviceLink(
  val transport: Transport,
  val volume: VolumeControl?,
)

/** One way to find and play on devices. Every protocol is bound into a set in [CastModule]. */
interface CastProtocol {
  /** One search of the network. Blocks until it is over. */
  fun search(): List<CastDevice>

  /** Null for a device of another protocol. Doesn't block: the transport reaches the device at its first call. */
  fun open(device: CastDevice): DeviceLink?
}
