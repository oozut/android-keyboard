package org.futo.inputmethod.latin.uix.settings.pages

import android.view.inputmethod.InputMethodSubtype
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.MultilingualBucketSetting
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.Subtypes
import org.futo.inputmethod.latin.SubtypesSetting
import org.futo.inputmethod.latin.uix.FileKind
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.icon
import org.futo.inputmethod.latin.uix.kindTitle
import org.futo.inputmethod.latin.uix.namePreferenceKeyFor
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.Route
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.pages.modelmanager.openModelImporter
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.inputmethod.latin.uix.theme.UixThemeWrapper
import org.futo.inputmethod.latin.uix.theme.presets.DynamicDarkTheme
import org.futo.inputmethod.latin.uix.urlEncode
import org.futo.inputmethod.latin.utils.Dictionaries
import org.futo.inputmethod.latin.utils.SubtypeLocaleUtils
import org.futo.inputmethod.latin.xlm.ModelPaths
import org.futo.inputmethod.updates.openURI
import org.futo.voiceinput.shared.ggml.Backend
import org.futo.voiceinput.shared.ggml.BackendSupport
import org.futo.voiceinput.shared.ggml.BackendUnavailableReason
import java.util.Locale

private val InputMethodSubtype.layoutSetName
    get() = SubtypeLocaleUtils.getKeyboardLayoutSetName(this)

data class LanguageItem(
    val languageName: String,
    val options: LanguageOptions,
    val layouts: List<Pair<InputMethodSubtype, String>>,
    val inMultilingualBucket: Boolean
)

@Composable
fun LanguageConfigurable(
    kind: FileKind,
    selection: String,
    onSelected: () -> Unit
) {
    Row(
        modifier = Modifier
        .clickable(enabled = true) { onSelected() }
        .padding(start = 16.dp, top = 8.dp, end = 6.dp, bottom = 8.dp)
        .defaultMinSize(0.dp, 50.dp)) {
        Column(
            modifier = Modifier
                .weight(1.0f)
                .align(Alignment.CenterVertically)
        ) {
            Row {
                Icon(
                    painterResource(kind.icon()),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    kind.kindTitle(LocalResources.current),
                    modifier = Modifier.align(Alignment.CenterVertically),
                    style = Typography.Small,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                selection,
                style = Typography.Body.RegularMl
            )

        }
        Icon(
            Icons.Default.ArrowForward,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .size(44.dp)
                .padding(10.dp)
        )
    }
}

@Composable
fun LayoutConfigurable(
    name: String,
    active: Boolean,
    onDelete: () -> Unit,
    canDelete: Boolean
) {
    Row(
        modifier = Modifier
            .padding(start = 16.dp, end = 6.dp)
            .height(44.dp)
    ) {
        Text(
            name,
            style = Typography.Body.RegularMl,
            modifier = Modifier.align(Alignment.CenterVertically)
        )

        Spacer(modifier = Modifier.weight(1.0f))

        //Switch(checked = active, onCheckedChange = {}, modifier = Modifier.align(Alignment.CenterVertically))
        Spacer(modifier = Modifier.width(8.dp))

        if (canDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.CenterVertically)) {
                Icon(painterResource(id = R.drawable.trash), contentDescription = null)
            }
        }
    }
}

@Composable
fun ActionableItem(
    icon: Painter,
    text: String,
    color: Color,
    onTrigger: () -> Unit
) {
    TextButton(
        onClick = onTrigger, colors = ButtonColors(
            containerColor = color,
            contentColor = contentColorFor(color),
            disabledContainerColor = color,
            disabledContentColor = contentColorFor(color).copy(alpha = 0.75f)
        ), modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp, 0.dp)
            .height(44.dp)
    ) {
        Row {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text,
                modifier = Modifier.align(Alignment.CenterVertically),
                style = Typography.Body.Medium
            )
        }
    }
}

@Composable
fun LanguageSurface(
    item: LanguageItem,
    modifier: Modifier = Modifier,
    onConfigurableSelected: (FileKind) -> Unit,
    onLayoutRemoved: (InputMethodSubtype) -> Unit,
    onLayoutAdditionRequested: () -> Unit,
    onLanguageRemoved: () -> Unit,
    onToggleMultilingualBucket: (Boolean) -> Unit,
    onVoiceInputBackendSelected: (Backend) -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier
                .padding(start = 32.dp, end = 32.dp)
                .widthIn(296.dp, 400.dp)
                .background(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp)
                )
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(top = 14.dp, bottom = 12.dp)
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSecondaryContainer) {
                Text(
                    item.languageName,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                    style = Typography.Heading.MediumMl
                )

                if (item.options.dictionary == null) {
                    Tip(stringResource(R.string.language_settings_warning_language_has_no_dict))
                } else {
                    Spacer(modifier = Modifier.height(32.dp))
                }

                LanguageConfigurable(
                    kind = FileKind.VoiceInput,
                    selection = item.options.voiceInputModel
                        ?: stringResource(R.string.language_settings_resource_none)
                ) { onConfigurableSelected(FileKind.VoiceInput) }
                if (item.options.voiceInputModel != null) {
                    val showBackendPicker = item.options.voiceInputBackends.size > 1
                    val qnnUnavailable = item.options.voiceInputQnnUnavailable

                    VoiceInputBackendPicker(
                        backends = item.options.voiceInputBackends,
                        selected = item.options.voiceInputBackend,
                        hasGgmlModel = item.options.voiceInputHasGgmlModel,
                        hasQnnModel = item.options.voiceInputHasQnnModel,
                        onSelected = onVoiceInputBackendSelected,
                        titleColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )

                    // Only when QNN is missing from the picker entirely: when it is
                    // offered, selecting it shows the same explanation underneath.
                    if (qnnUnavailable != null && Backend.QNN !in item.options.voiceInputBackends) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Tip(
                            "⚠\uFE0F " + stringResource(
                                unavailableMessageRes(Backend.QNN, qnnUnavailable)
                            )
                        )
                    }

                    if (showBackendPicker) {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
                LanguageConfigurable(
                    kind = FileKind.Dictionary,
                    selection = item.options.dictionary
                        ?: stringResource(R.string.language_settings_resource_none)
                ) { onConfigurableSelected(FileKind.Dictionary) }
                LanguageConfigurable(
                    kind = FileKind.Transformer,
                    selection = item.options.transformerModel
                        ?: stringResource(R.string.language_settings_resource_none)
                ) { onConfigurableSelected(FileKind.Transformer) }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    stringResource(R.string.language_settings_layouts_of_this_language),
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp),
                    style = Typography.SmallMl,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(9.dp))

                item.layouts.forEach {
                    LayoutConfigurable(
                        name = it.second,
                        active = true,
                        onDelete = { onLayoutRemoved(it.first) },
                        canDelete = (item.layouts.size > 1)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Spacer(modifier = Modifier.height(21.dp))

                Row(Modifier.padding(start = 16.dp, end = 6.dp)) {
                    Text(
                        stringResource(R.string.language_settings_enable_multilingual_typing_for_this_language),
                        modifier = Modifier
                            .weight(1.0f)
                            .align(Alignment.CenterVertically),
                        style = Typography.SmallMl,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                    Checkbox(
                        checked = item.inMultilingualBucket,
                        onCheckedChange = { onToggleMultilingualBucket(it) },
                        modifier = Modifier.align(Alignment.CenterVertically),
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            uncheckedColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            checkmarkColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    )
                }


                Spacer(modifier = Modifier.height(21.dp))

                ActionableItem(
                    icon = painterResource(id = R.drawable.plus_circle),
                    text = stringResource(R.string.language_settings_add_layout_for_this_language),
                    color = MaterialTheme.colorScheme.primary,
                    onTrigger = onLayoutAdditionRequested
                )

                Spacer(modifier = Modifier.height(10.dp))

                ActionableItem(
                    icon = painterResource(id = R.drawable.trash),
                    text = stringResource(R.string.language_settings_remove_this_language),
                    color = MaterialTheme.colorScheme.error,
                    onTrigger = onLanguageRemoved
                )
            }
        }
    }
}

@Preview
@Composable
fun LanguageSurfacePreview() {
    UixThemeWrapper(colorScheme = DynamicDarkTheme.obtainColors(LocalContext.current)) {
        LanguageSurface(
            item = LanguageItem(
                languageName = "Language Name",
                options = LanguageOptions(
                    "Model Name",
                    "Model Name",
                    "Model Name"
                ),
                layouts = listOf(
                    InputMethodSubtype.InputMethodSubtypeBuilder().build() to "QWERTY",
                    InputMethodSubtype.InputMethodSubtypeBuilder().build() to "Dvorak"
                ),
                inMultilingualBucket = true
            ),
            onLanguageRemoved = { }, onLayoutRemoved = { }, onConfigurableSelected = { },
            onLayoutAdditionRequested = { }, onToggleMultilingualBucket = { })
    }
}

data class LanguageOptions(
    val voiceInputModel: String?,
    val dictionary: String?,
    val transformerModel: String?,
    /**
     * Backends the user may pick for this language's voice input model. Only
     * ones the device can actually run are listed (see
     * [BackendSupport.availableBackends]), so the picker never offers QNN on a
     * device without a Snapdragon SoC, and never offers a backend whose native
     * library was not packaged for this ABI.
     */
    val voiceInputBackends: List<Backend> = emptyList(),
    val voiceInputBackend: Backend = Backend.CPU,
    /** Whether a ggml model — what [Backend.CPU] and [Backend.VULKAN] run — is assigned. */
    val voiceInputHasGgmlModel: Boolean = false,
    /** Whether a Qualcomm model — what [Backend.QNN] runs — is assigned. */
    val voiceInputHasQnnModel: Boolean = false,
    /**
     * A Qualcomm model is assigned here, but this device cannot run QNN; the
     * value says what is missing, so the warning can explain it instead of the
     * option just disappearing. `null` when QNN is fine.
     */
    val voiceInputQnnUnavailable: BackendUnavailableReason? = null
)

/**
 * The one-line explanation shown under the picker for a backend that will not
 * run. In a shipping build the unsupported backends are not offered in the first
 * place, so most of these are only reachable from a development build; the QNN
 * ones also appear when a Qualcomm model is assigned on a device that cannot run
 * it, where the tab is hidden and the explanation is the only thing the user sees.
 */
@StringRes
private fun unavailableMessageRes(backend: Backend, reason: BackendUnavailableReason): Int =
    when (reason) {
        BackendUnavailableReason.NOT_QUALCOMM ->
            R.string.language_settings_voice_input_backend_qnn_unavailable_not_qualcomm
        BackendUnavailableReason.UNSUPPORTED_CHIPSET ->
            R.string.language_settings_voice_input_backend_qnn_unavailable_chipset
        BackendUnavailableReason.VULKAN_UNAVAILABLE ->
            R.string.voice_input_backend_vulkan_unavailable
        BackendUnavailableReason.VULKAN_GPU_UNSUPPORTED ->
            R.string.voice_input_backend_vulkan_experimental
        BackendUnavailableReason.NO_MODEL -> when (backend) {
            Backend.QNN -> R.string.voice_input_backend_qnn_no_model
            else -> R.string.voice_input_backend_no_ggml_model
        }
        // The engine is simply not on this device.
        BackendUnavailableReason.UNSUPPORTED_ABI,
        BackendUnavailableReason.LIBRARY_MISSING -> when (backend) {
            Backend.QNN -> R.string.language_settings_voice_input_backend_qnn_unavailable
            else -> R.string.voice_input_backend_vulkan_unavailable
        }
    }

@StringRes
private fun Backend.displayNameRes(): Int = when (this) {
    Backend.CPU -> R.string.voice_input_backend_cpu
    Backend.VULKAN -> R.string.voice_input_backend_vulkan
    Backend.QNN -> R.string.voice_input_backend_qnn
}

@StringRes
private fun Backend.descriptionRes(): Int = when (this) {
    Backend.CPU -> R.string.voice_input_backend_cpu_description
    Backend.VULKAN -> R.string.voice_input_backend_vulkan_description
    Backend.QNN -> R.string.voice_input_backend_qnn_description
}

/**
 * Segmented backend picker (CPU / Vulkan / QNN). Renders nothing when there is
 * only one possible backend, so devices with no accelerator simply see no
 * control at all rather than a dead switch.
 *
 * Whichever backend is selected is checked with [BackendSupport.unavailableReason]
 * and, when it will not run, the reason is shown underneath in the error colour.
 * In a shipping build [BackendSupport.availableBackends] does not offer a backend
 * the device cannot run, so nothing is selected that would produce a warning —
 * with one exception: a Qualcomm model assigned on a device that cannot run QNN,
 * where the tab is hidden but the reason is still worth showing. Everything else
 * only ever warns from a development build, which offers all three on purpose.
 */
@Composable
fun VoiceInputBackendPicker(
    backends: List<Backend>,
    selected: Backend,
    hasGgmlModel: Boolean,
    hasQnnModel: Boolean,
    onSelected: (Backend) -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
    modifier: Modifier = Modifier
) {
    if (backends.size < 2) return

    // Deliberately not remembered: PackageManager feature lookups are cached by
    // the framework and the rest are cheap field reads, and recomputing keeps
    // the warning correct when a model is imported or removed.
    val context = LocalContext.current
    val unavailableReason = BackendSupport.unavailableReason(
        context, selected, hasGgmlModel, hasQnnModel
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.language_settings_voice_input_backend_title),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            style = Typography.SmallMl,
            color = titleColor
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            backends.forEach { backend ->
                val isSelected = backend == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .background(
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelected(backend) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(backend.displayNameRes()),
                        style = Typography.SmallMl,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }

        Text(
            stringResource(selected.descriptionRes()),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            style = Typography.Small,
            color = titleColor.copy(alpha = 0.75f)
        )

        if (unavailableReason != null) {
            Text(
                stringResource(unavailableMessageRes(selected, unavailableReason)),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                style = Typography.Small,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun ConfirmResourceActionDialog(
    onDismissRequest: () -> Unit,

    onExplore: () -> Unit,
    onDelete: () -> Unit,
    onImport: () -> Unit,

    resourceKind: FileKind,
    isCurrentlySet: Boolean,
    locale: Locale,
    voiceInputBackends: List<Backend> = emptyList(),
    voiceInputBackend: Backend = Backend.CPU,
    voiceInputHasGgmlModel: Boolean = false,
    voiceInputHasQnnModel: Boolean = false,
    voiceInputQnnUnavailable: BackendUnavailableReason? = null,
    onVoiceInputBackendSelected: (Backend) -> Unit = {}
) {
    val context = LocalContext.current
    val hasBuiltInFallback = if (resourceKind == FileKind.VoiceInput) {
        ResourceHelper.BuiltInVoiceInputFallbacks[locale.language] != null
    } else if (resourceKind == FileKind.VoiceInputQnn) {
        false
    } else if (resourceKind == FileKind.VoiceInputQnnZip) {
        false
    } else if (resourceKind == FileKind.Dictionary) {
        Dictionaries.getDictionaryIfExists(context, locale, Dictionaries.DictionaryKind.Any) != null
    } else {
        true
    }

    AlertDialog(
        icon = {
            Icon(painterResource(id = resourceKind.icon()), contentDescription = null)
        },
        title = {
            Text(text = "${locale.displayLanguage} - ${resourceKind.kindTitle(LocalResources.current)}")
        },
        text = {
            Column {
                if (isCurrentlySet) {
                    Text(
                        text = when (resourceKind) {
                            FileKind.VoiceInput -> stringResource(R.string.language_settings_resource_voice_input_selected)
                            FileKind.VoiceInputQnn -> stringResource(R.string.language_settings_resource_voice_input_qnn_selected)
                            FileKind.VoiceInputQnnZip -> stringResource(R.string.language_settings_resource_voice_input_qnn_selected)
                            FileKind.Transformer -> stringResource(R.string.language_settings_resource_transformer_selected)
                            FileKind.Dictionary -> stringResource(R.string.language_settings_resource_dictionary_selected)
                            FileKind.Invalid -> ""
                        } + if (!hasBuiltInFallback) {
                            "\n\n" +
                                    when (resourceKind) {
                                        FileKind.VoiceInput -> stringResource(R.string.language_settings_resource_voice_input_selected_no_default_warning)
                                        FileKind.VoiceInputQnn -> stringResource(R.string.language_settings_resource_voice_input_selected_no_default_warning)
                                        FileKind.VoiceInputQnnZip -> stringResource(R.string.language_settings_resource_voice_input_selected_no_default_warning)
                                        FileKind.Transformer -> stringResource(R.string.language_settings_resource_transformer_selected_no_default_warning)
                                        FileKind.Dictionary -> stringResource(R.string.language_settings_resource_dictionary_selected_no_default_warning)
                                        FileKind.Invalid -> ""
                                    }
                        } else {
                            ""
                        }
                    )
                } else {
                    Text(
                        text = when (resourceKind) {
                            FileKind.VoiceInput -> stringResource(R.string.language_settings_resource_voice_input_selected_unset)
                            FileKind.VoiceInputQnn -> stringResource(R.string.language_settings_resource_voice_input_selected_unset)
                            FileKind.VoiceInputQnnZip -> stringResource(R.string.language_settings_resource_voice_input_selected_unset)
                            FileKind.Transformer -> stringResource(R.string.language_settings_resource_transformer_selected_unset)
                            FileKind.Dictionary -> stringResource(R.string.language_settings_resource_dictionary_selected_unset)
                            FileKind.Invalid -> ""
                        }
                    )
                }

                // Voice-input kinds only: this dialog is shared with the
                // dictionary/transformer resources, where a transcription backend
                // is meaningless.
                val isVoiceInputKind = resourceKind == FileKind.VoiceInput ||
                        resourceKind == FileKind.VoiceInputQnn ||
                        resourceKind == FileKind.VoiceInputQnnZip
                if (isVoiceInputKind &&
                    (voiceInputBackends.size > 1 || voiceInputQnnUnavailable != null)
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    VoiceInputBackendPicker(
                        backends = voiceInputBackends,
                        selected = voiceInputBackend,
                        hasGgmlModel = voiceInputHasGgmlModel,
                        hasQnnModel = voiceInputHasQnnModel,
                        onSelected = onVoiceInputBackendSelected
                    )
                    // Only when QNN is missing from the picker entirely: when it
                    // is offered, selecting it shows the same explanation.
                    if (voiceInputQnnUnavailable != null &&
                        Backend.QNN !in voiceInputBackends
                    ) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Tip(
                            "⚠\uFE0F " + stringResource(
                                unavailableMessageRes(Backend.QNN, voiceInputQnnUnavailable)
                            )
                        )
                    }
                }
            }
        },
        onDismissRequest = {
            onDismissRequest()
        },
        confirmButton = {
            TextButton(onClick = {
                onImport()
            }) {
                Text(
                    if (isCurrentlySet) {
                        stringResource(R.string.language_settings_resource_replace_button)
                    } else {
                        stringResource(R.string.language_settings_resource_import_file_button)
                    }
                )
            }
        },
        dismissButton = {
            if (isCurrentlySet) {
                TextButton(onClick = { onDelete() }) {
                    if (hasBuiltInFallback) {
                        Text(stringResource(R.string.language_settings_resource_revert_to_default_button))
                    } else {
                        Text(stringResource(R.string.language_settings_resource_remove_button))
                    }

                }
            } else {
                TextButton(onClick = { onExplore() }) {
                    Text(stringResource(R.string.language_settings_resource_explore_online_button))
                }
            }
        }
    )
}


@Composable
fun ConfirmDeleteLanguageDialog(
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
    locale: Locale
) {
    AlertDialog(
        icon = {
            Icon(painterResource(id = R.drawable.trash), contentDescription = null)
        },
        title = {
            Text(
                text = stringResource(
                    R.string.language_settings_remove_language_title,
                    locale.displayLanguage
                )
            )
        },
        text = {
            Text(
                text = stringResource(
                    R.string.language_settings_remove_language_body,
                    locale.displayLanguage
                )
            )
        },
        onDismissRequest = {
            onDismissRequest()
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.language_settings_remove_language_remove_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.language_settings_remove_language_cancel_button))
            }
        }
    )
}


data class DeleteInfo(
    val locale: Locale,
    val kind: FileKind
)

val LanguageSettingsTop = listOf(
    userSettingNavigationItem(
        title = R.string.language_settings_add_language_button,
        style = NavigationItemStyle.Misc,
        navigateTo = "addLanguage",
    )
)
val LanguageSettingsBottom = listOf(
    userSettingNavigationItem(
        title = R.string.language_settings_import_resource_from_file,
        style = NavigationItemStyle.Misc,
        navigate = { nav ->
            openModelImporter(nav.context)
        },
    ),
    userSettingNavigationItem(
        title = R.string.language_settings_explore_voice_input_models_online,
        style = NavigationItemStyle.Misc,
        navigate = { nav ->
            nav.context.openURI(
                FileKind.VoiceInput.getAddonUrlForLocale(null),
                true
            )
        },
    ),
    userSettingNavigationItem(
        title = R.string.language_settings_explore_dictionaries_online,
        style = NavigationItemStyle.Misc,
        navigate = { nav ->
            nav.context.openURI(
                FileKind.Dictionary.getAddonUrlForLocale(null),
                true
            )
        },
    ),
    userSettingNavigationItem(
        title = R.string.language_settings_explore_transformers_online,
        style = NavigationItemStyle.Misc,
        navigate = { nav ->
            nav.context.openURI(
                FileKind.Transformer.getAddonUrlForLocale(null),
                true
            )
        },
    )
)

val LanguageSettingsLite = UserSettingsMenu(
    title = R.string.language_settings_title,
    navPath = "languages", registerNavPath = false,
    settings = LanguageSettingsTop + listOf(
        userSettingNavigationItem(
            title = R.string.language_settings_manage_languages_title,
            subtitle = R.string.language_settings_manage_languages_subtitle,
            navigateTo = "languages",
            style = NavigationItemStyle.Misc
        )
    ) + LanguageSettingsBottom
)

@Preview(showBackground = true)
@Composable
fun LanguagesScreen(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val deleteDialogInfo: MutableState<DeleteInfo?> = remember { mutableStateOf(null) }
    val languageDeleteInfo: MutableState<Locale?> = remember { mutableStateOf(null) }
    // Bumped whenever an import/delete may have changed which model is assigned,
    // so the per-language model names and backend options recompute.
    val gpuRevision = remember { mutableStateOf(0) }

    val inputMethods = useDataStoreValue(SubtypesSetting)
    val inputMethodList = remember(inputMethods) {
        Subtypes.layoutsMappedByLanguage(inputMethods)
    }

    val multilingualBucket = useDataStore(MultilingualBucketSetting)

    val inputMethodKeys = remember(inputMethodList) { inputMethodList.keys.toList().sorted() }

    if (deleteDialogInfo.value != null) {
        val info = deleteDialogInfo.value!!
        val dialogVoiceInputSelection = remember(info, gpuRevision.value) {
            ResourceHelper.resolveVoiceInputSelection(context, info.locale)
        }
        ConfirmResourceActionDialog(
            onDismissRequest = {
                gpuRevision.value++
                deleteDialogInfo.value = null
            },
            onExplore = {
                context.openURI(info.kind.getAddonUrlForLocale(info.locale), true)
                deleteDialogInfo.value = null
            },
            onDelete = {
                ResourceHelper.deleteResourceForLanguage(context, info.kind, info.locale)
                gpuRevision.value++
                deleteDialogInfo.value = null
            },
            onImport = {
                openModelImporter(context)
                gpuRevision.value++
                deleteDialogInfo.value = null
            },

            resourceKind = info.kind,
            locale = info.locale,
            isCurrentlySet = runBlocking {
                ResourceHelper.findFileForKind(
                    context,
                    info.locale,
                    info.kind
                )?.exists() == true
            },
            voiceInputBackends = remember(dialogVoiceInputSelection) {
                BackendSupport.availableBackends(
                    context,
                    dialogVoiceInputSelection.ggmlModel != null,
                    dialogVoiceInputSelection.qnnModel != null
                )
            },
            voiceInputBackend = dialogVoiceInputSelection.backend,
            voiceInputHasGgmlModel = dialogVoiceInputSelection.ggmlModel != null,
            voiceInputHasQnnModel = dialogVoiceInputSelection.qnnModel != null,
            voiceInputQnnUnavailable = if (dialogVoiceInputSelection.qnnModel != null) {
                BackendSupport.unavailableReason(
                    context,
                    Backend.QNN,
                    hasGgmlModel = dialogVoiceInputSelection.ggmlModel != null,
                    hasQnnModel = true
                )
            } else null,
            onVoiceInputBackendSelected = { backend ->
                ResourceHelper.setVoiceInputBackend(context, info.locale, backend)
                gpuRevision.value++
            }
        )
    } else if (languageDeleteInfo.value != null) {
        val info = languageDeleteInfo.value!!
        ConfirmDeleteLanguageDialog(
            locale = info,
            onDelete = {
                languageDeleteInfo.value = null

                val key = inputMethodKeys.find { localeString ->
                    Subtypes.getLocale(localeString) == info
                }

                if (key != null) {
                    val subtypes = inputMethodList[key]!!
                    subtypes.forEach { Subtypes.removeLanguage(context, it) }

                    if(multilingualBucket.value.contains(key)) {
                        multilingualBucket.value.toMutableSet().apply {
                            remove(key)
                            multilingualBucket.setValue(this)
                        }
                    }
                }
            },
            onDismissRequest = {
                languageDeleteInfo.value = null
            }
        )
    }
    LazyColumn(horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            ScreenTitle(
                stringResource(R.string.language_settings_title),
                showBack = true,
                navController
            )
        }

        items(LanguageSettingsTop) {
            it.component()
        }

        items(inputMethodKeys) { localeString ->
            val subtypes = inputMethodList[localeString]!!

            val locale = Subtypes.getLocale(localeString)

            val name = Subtypes.getName(subtypes.first())

            // The same helper the voice input action uses, so the name shown
            // here is always the model that will actually run. That also makes
            // an assigned Qualcomm model visible (it used to show as "None").
            val voiceInputSelection = remember(gpuRevision.value, localeString) {
                ResourceHelper.resolveVoiceInputSelection(context, locale)
            }
            val voiceInputModelName = voiceInputSelection.model?.name?.let { stringResource(it) }
            val dictionaryName = runBlocking {
                ResourceHelper.findKeyForLocaleAndKind(
                    context,
                    locale,
                    FileKind.Dictionary
                )
            }?.let {
                runBlocking {
                    context.getSetting(
                        FileKind.Dictionary.namePreferenceKeyFor(it),
                        "Dictionary"
                    )
                } + " " + resources.getString(
                    R.string.language_settings_resource_imported_indicator
                )
             } ?: if (Dictionaries.getDictionaryIfExists(context, locale, Dictionaries.DictionaryKind.Any) != null) {
                resources.getString(R.string.language_settings_resource_builtin_dictionary_name)
            } else {
                null
            }

            val transformerName =
                runBlocking { ModelPaths.getModelOptions(context) }.get(locale.language)?.let {
                    it.loadDetails()?.let {
                        it.name + if (it.isUnsupported()) (" " + resources.getString(R.string.language_settings_resource_unsupported_indicator)) else ""
                    }
                }

            val voiceInputBackends = remember(voiceInputSelection) {
                BackendSupport.availableBackends(
                    context,
                    voiceInputSelection.ggmlModel != null,
                    voiceInputSelection.qnnModel != null
                )
            }
            val voiceInputQnnUnavailable = if (voiceInputSelection.qnnModel != null) {
                BackendSupport.unavailableReason(
                    context,
                    Backend.QNN,
                    hasGgmlModel = voiceInputSelection.ggmlModel != null,
                    hasQnnModel = true
                )
            } else null

            val options = LanguageOptions(
                voiceInputModel = voiceInputModelName,
                dictionary = dictionaryName,
                transformerModel = transformerName,
                voiceInputBackends = voiceInputBackends,
                voiceInputBackend = voiceInputSelection.backend,
                voiceInputHasGgmlModel = voiceInputSelection.ggmlModel != null,
                voiceInputHasQnnModel = voiceInputSelection.qnnModel != null,
                voiceInputQnnUnavailable = voiceInputQnnUnavailable
            )


            Spacer(modifier = Modifier.height(12.dp))

            LanguageSurface(
                LanguageItem(
                    languageName = name,
                    options = options,
                    layouts = subtypes.map {
                        val name = Subtypes.getLayoutName(context, it.layoutSetName)
                        it to name
                    },
                    inMultilingualBucket = multilingualBucket.value.contains(
                        localeString
                    )
                ),
                onLanguageRemoved = {
                    languageDeleteInfo.value = locale
                },
                onLayoutRemoved = { subtype ->
                    val layoutSetName = subtype.layoutSetName
                    if(layoutSetName.startsWith("custom")) {
                        val i = layoutSetName.substring("custom".length).toIntOrNull()
                        if(i != null) {
                            navController.navigate(Route.DevLayoutEdit(i))
                        }
                    } else {
                        Subtypes.removeLanguage(context, subtype)
                    }
                },
                onConfigurableSelected = { kind ->
                    if (kind == FileKind.Transformer && transformerName != null) {
                        navController.navigate("models")
                    } else {
                        deleteDialogInfo.value = DeleteInfo(locale, kind)
                    }
                },
                onLayoutAdditionRequested = {
                    navController.navigate(Route.AddLayout(locale.toLanguageTag()))
                },
                onToggleMultilingualBucket = { to ->
                    val newSet = multilingualBucket.value.toMutableSet()
                    if (to) {
                        newSet.add(localeString)
                    } else {
                        newSet.remove(localeString)
                    }

                    multilingualBucket.setValue(newSet)
                },
                onVoiceInputBackendSelected = { backend ->
                    ResourceHelper.setVoiceInputBackend(context, locale, backend)
                    gpuRevision.value++
                }
            )

            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
            ScreenTitle(stringResource(R.string.language_settings_other_options))
        }
        items(LanguageSettingsBottom) {
            it.component()
        }
    }
}