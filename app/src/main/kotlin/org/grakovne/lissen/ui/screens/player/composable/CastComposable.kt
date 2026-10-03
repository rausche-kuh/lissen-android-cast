package org.grakovne.lissen.ui.screens.player.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import org.grakovne.lissen.R
import org.grakovne.lissen.cast.castDeviceOrder
import org.grakovne.lissen.common.withHaptic
import org.grakovne.lissen.ui.components.LissenModalBottomSheet
import org.grakovne.lissen.ui.screens.common.RequestLocalNetworkPermission
import org.grakovne.lissen.ui.screens.common.hasLocalNetworkPermission
import org.grakovne.lissen.viewmodel.CastViewModel

/** Cast devices play at their own speed, so what they can't do is greyed out while casting. */
@Composable
fun isCasting(viewModel: CastViewModel = hiltViewModel()): Boolean = viewModel.active.collectAsState().value != null

/** The cast device was asked to play and hasn't started: play, pause and seeks wait for it. */
@Composable
fun isCastConnecting(viewModel: CastViewModel = hiltViewModel()): Boolean = viewModel.connecting.collectAsState().value

@Composable
fun CastButton(
  modifier: Modifier = Modifier,
  viewModel: CastViewModel = hiltViewModel(),
) {
  val view = LocalView.current
  val active by viewModel.active.collectAsState()
  var sheetOpen by rememberSaveable { mutableStateOf(false) }

  IconButton(
    onClick = { withHaptic(view) { sheetOpen = true } },
    colors =
      IconButtonDefaults.iconButtonColors(
        containerColor = Color.Black.copy(alpha = 0.35f),
        contentColor = Color.White,
      ),
    modifier =
      modifier
        .size(40.dp)
        .testTag("playerCastButton"),
  ) {
    Icon(
      imageVector = if (active != null) Icons.Outlined.CastConnected else Icons.Outlined.Cast,
      contentDescription = stringResource(R.string.a11y_cast),
      tint = if (active != null) colorScheme.primary else Color.White,
      modifier = Modifier.size(22.dp),
    )
  }

  if (sheetOpen) {
    CastDeviceSheet(
      viewModel = viewModel,
      onDismissRequest = { sheetOpen = false },
    )
  }
}

@Composable
fun CastDeviceSheet(
  viewModel: CastViewModel,
  onDismissRequest: () -> Unit,
) {
  val context = LocalContext.current
  val active by viewModel.active.collectAsState()
  val available by viewModel.available.collectAsState()
  val devices by viewModel.devices.collectAsState()
  val scanning by viewModel.scanning.collectAsState()

  var permitted by remember { mutableStateOf(hasLocalNetworkPermission(context)) }
  if (permitted.not()) {
    RequestLocalNetworkPermission(onGranted = { permitted = true })
  }

  LifecycleStartEffect(permitted, available) {
    if (permitted && available) viewModel.startScan()
    onStopOrDispose { viewModel.stopScan() }
  }

  LissenModalBottomSheet(onDismissRequest = onDismissRequest) {
    Text(
      text = stringResource(R.string.cast_sheet_title),
      style = typography.bodyLarge,
      textAlign = TextAlign.Center,
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(bottom = 12.dp),
    )

    when (scanning) {
      true -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
      false -> Spacer(modifier = Modifier.height(4.dp))
    }

    CastDeviceRow(
      title = stringResource(R.string.cast_this_device),
      subtitle = null,
      icon = Icons.Outlined.Smartphone,
      selected = active == null,
      onClick = {
        viewModel.disconnect()
        onDismissRequest()
      },
    )

    if (available) {
      (listOfNotNull(active) + devices.orEmpty())
        .distinctBy { it.id }
        .sortedWith(castDeviceOrder)
        .forEach { device ->
          CastDeviceRow(
            title = device.name,
            subtitle = device.protocol,
            icon = Icons.Outlined.Speaker,
            selected = active?.id == device.id,
            onClick = {
              viewModel.connect(device)
              onDismissRequest()
            },
          )
        }
    }

    val hint =
      when {
        available.not() -> stringResource(R.string.cast_unavailable_offline)
        devices?.isEmpty() == true && active == null -> stringResource(R.string.cast_no_devices)
        else -> null
      }

    hint?.let {
      Text(
        text = it,
        style = typography.bodyMedium,
        color = colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
      )
    }

    Spacer(modifier = Modifier.height(16.dp))
  }
}

@Composable
private fun CastDeviceRow(
  title: String,
  subtitle: String?,
  icon: ImageVector,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val view = LocalView.current

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .selectable(selected = selected, role = Role.RadioButton) { withHaptic(view) { onClick() } }
        .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      modifier = Modifier.size(20.dp),
      tint = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = typography.bodyLarge,
        color = colorScheme.onSurface,
      )
      subtitle?.let {
        Text(
          text = it,
          style = typography.bodySmall,
          color = colorScheme.onSurfaceVariant,
        )
      }
    }
    if (selected) {
      Icon(
        imageVector = Icons.Outlined.Check,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = colorScheme.primary,
      )
    }
  }
}
