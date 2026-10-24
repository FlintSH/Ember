package dev.fl1nt.ember.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fl1nt.ember.R
import dev.fl1nt.ember.api.Expiration
import dev.fl1nt.ember.api.ExpiryAction
import dev.fl1nt.ember.api.RemoteFolder
import dev.fl1nt.ember.api.Visibility
import dev.fl1nt.ember.data.settings.UploadPreset
import dev.fl1nt.ember.ui.icons.Lucide

/**
 * Label for an inherited choice. Rows that edit Ember's own defaults inherit from
 * Flare. Everywhere else they inherit Ember's default, so show what that is.
 */
@Composable
private fun inheritLabel(effective: String?) =
    effective?.let { stringResource(R.string.choice_default_value, it) } ?: stringResource(R.string.choice_flare_default)

@Composable
fun visibilityLabel(value: Visibility) = stringResource(if (value == Visibility.PUBLIC) R.string.visibility_public else R.string.visibility_private)

@Composable
fun expirationLabel(value: Expiration) = stringResource(
    when (value) {
        Expiration.DISABLED -> R.string.expiration_never
        Expiration.HOUR -> R.string.expiration_hour
        Expiration.DAY -> R.string.expiration_day
        Expiration.WEEK -> R.string.expiration_week
        Expiration.MONTH -> R.string.expiration_month
    },
)

@Composable
fun expiryActionLabel(value: ExpiryAction) = stringResource(if (value == ExpiryAction.DELETE) R.string.expiry_delete else R.string.expiry_private)

@Composable
private fun folderLabel(choice: UploadPreset.Choice): String? = when (choice) {
    UploadPreset.Choice.Inherit -> null
    UploadPreset.Choice.None -> stringResource(R.string.folder_unfiled)
    is UploadPreset.Choice.Id -> choice.label ?: stringResource(R.string.folder_by_id, choice.id.take(10))
}

@Composable
fun VisibilityRow(value: Visibility?, onChange: (Visibility?) -> Unit, emberDefaults: UploadPreset?, enabled: Boolean = true) {
    OptionRow(
        title = stringResource(R.string.field_visibility),
        icon = Lucide.Eye,
        enabled = enabled,
        options = listOf(
            Option(null, inheritLabel(emberDefaults?.visibility?.let { visibilityLabel(it) })),
            Option(Visibility.PUBLIC, stringResource(R.string.visibility_public), stringResource(R.string.visibility_public_description)),
            Option(Visibility.PRIVATE, stringResource(R.string.visibility_private), stringResource(R.string.visibility_private_description)),
        ),
        selected = value,
        onSelect = onChange,
    )
}

@Composable
fun ExpirationRow(value: Expiration?, onChange: (Expiration?) -> Unit, emberDefaults: UploadPreset?) {
    OptionRow(
        title = stringResource(R.string.field_expiration),
        icon = Lucide.Timer,
        options = listOf(
            Option(null, inheritLabel(emberDefaults?.expiration?.let { expirationLabel(it) })),
            Option(Expiration.DISABLED, stringResource(R.string.expiration_never)),
            Option(Expiration.HOUR, stringResource(R.string.expiration_hour)),
            Option(Expiration.DAY, stringResource(R.string.expiration_day)),
            Option(Expiration.WEEK, stringResource(R.string.expiration_week)),
            Option(Expiration.MONTH, stringResource(R.string.expiration_month), stringResource(R.string.expiration_month_description)),
        ),
        selected = value,
        onSelect = onChange,
    )
}

@Composable
fun ExpiryActionRow(value: ExpiryAction?, onChange: (ExpiryAction?) -> Unit, emberDefaults: UploadPreset?) {
    OptionRow(
        title = stringResource(R.string.field_expiry_action),
        icon = Lucide.CalendarClock,
        options = listOf(
            Option(null, inheritLabel(emberDefaults?.expiryAction?.let { expiryActionLabel(it) })),
            Option(ExpiryAction.DELETE, stringResource(R.string.expiry_delete)),
            Option(ExpiryAction.SET_PRIVATE, stringResource(R.string.expiry_private)),
        ),
        selected = value,
        onSelect = onChange,
    )
}

/**
 * Chooses a destination folder. Flare only lets the account upload token list
 * folders; with a named token people paste a folder's "Upload here" link instead.
 */
@Composable
fun FolderRow(
    value: UploadPreset.Choice,
    onChange: (UploadPreset.Choice) -> Unit,
    folders: List<RemoteFolder>?,
    emberDefaults: UploadPreset?,
    explainUnavailableList: Boolean = true,
) {
    var askForId by remember { mutableStateOf(false) }
    val manual = remember { Any() }
    val paths = remember(folders) { folders?.let(::folderPaths).orEmpty() }
    val options = buildList<Option<Any>> {
        add(Option(UploadPreset.Choice.Inherit, inheritLabel(emberDefaults?.folder?.let { folderLabel(it) })))
        add(Option(UploadPreset.Choice.None, stringResource(R.string.folder_unfiled)))
        paths.forEach { (folder, path) -> add(Option(UploadPreset.Choice.Id(folder.id, path), path)) }
        if (value is UploadPreset.Choice.Id && paths.none { it.first.id == value.id }) {
            add(Option(value, value.label ?: stringResource(R.string.folder_by_id, value.id.take(10))))
        }
        add(Option(manual, stringResource(R.string.folder_enter_link), stringResource(R.string.folder_enter_link_description)))
    }
    OptionRow(
        title = stringResource(R.string.field_folder),
        icon = Lucide.Folder,
        description = if (folders == null && explainUnavailableList) stringResource(R.string.folder_list_unavailable) else null,
        options = options,
        selected = value,
        onSelect = { selected ->
            if (selected === manual) askForId = true else onChange(selected as UploadPreset.Choice)
        },
    )
    if (askForId) {
        IdDialog(
            title = stringResource(R.string.folder_dialog_title),
            body = stringResource(R.string.folder_dialog_body),
            label = stringResource(R.string.folder_dialog_label),
            parse = ::parseFolderId,
            onDismiss = { askForId = false },
            onConfirm = { id ->
                onChange(UploadPreset.Choice.Id(id, null))
                askForId = false
            },
        )
    }
}

/** Upload profiles can't be listed with a token, so they're chosen by ID. */
@Composable
fun ProfileRow(value: UploadPreset.Choice, onChange: (UploadPreset.Choice) -> Unit) {
    var askForId by remember { mutableStateOf(false) }
    val manual = remember { Any() }
    val options = buildList<Option<Any>> {
        add(Option(UploadPreset.Choice.Inherit, inheritLabel(null), stringResource(R.string.profile_default_description)))
        add(Option(UploadPreset.Choice.None, stringResource(R.string.profile_none), stringResource(R.string.profile_none_description)))
        if (value is UploadPreset.Choice.Id) add(Option(value, stringResource(R.string.profile_by_id, value.id.take(12))))
        add(Option(manual, stringResource(R.string.profile_enter_id)))
    }
    OptionRow(
        title = stringResource(R.string.field_profile),
        icon = Lucide.SlidersHorizontal,
        options = options,
        selected = value,
        onSelect = { selected -> if (selected === manual) askForId = true else onChange(selected as UploadPreset.Choice) },
    )
    if (askForId) {
        IdDialog(
            title = stringResource(R.string.profile_dialog_title),
            body = stringResource(R.string.profile_dialog_body),
            label = stringResource(R.string.profile_dialog_label),
            parse = { it.trim().takeIf { id -> id.matches(ID_PATTERN) } },
            onDismiss = { askForId = false },
            onConfirm = { id ->
                onChange(UploadPreset.Choice.Id(id))
                askForId = false
            },
        )
    }
}

@Composable
private fun IdDialog(title: String, body: String, label: String, parse: (String) -> String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = parse(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                EmberTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = label,
                    errorText = if (text.isNotBlank() && parsed == null) stringResource(R.string.id_dialog_invalid) else null,
                )
            }
        },
        confirmButton = { GhostButton(stringResource(R.string.action_use), onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) },
        dismissButton = { GhostButton(stringResource(R.string.action_cancel), onClick = onDismiss) },
        modifier = Modifier,
    )
}

private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")

/** Accepts a folder ID or a link such as `https://files.example.com/dashboard/upload?folder=abc`. */
fun parseFolderId(text: String): String? {
    val trimmed = text.trim()
    val fromLink = Regex("""[?&]folder=([A-Za-z0-9_-]{1,128})""").find(trimmed)?.groupValues?.get(1)
    return fromLink ?: trimmed.takeIf { it.matches(ID_PATTERN) }
}

/** Pairs each folder with its full path, such as "Projects / Website". */
fun folderPaths(folders: List<RemoteFolder>): List<Pair<RemoteFolder, String>> {
    val byId = folders.associateBy { it.id }
    fun path(folder: RemoteFolder, depth: Int = 0): String {
        val parent = folder.parentId?.let(byId::get)
        return if (parent == null || depth > 16) folder.name else path(parent, depth + 1) + " / " + folder.name
    }
    return folders.map { it to path(it) }.sortedBy { it.second.lowercase() }
}
