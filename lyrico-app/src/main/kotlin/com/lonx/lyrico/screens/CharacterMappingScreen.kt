package com.lonx.lyrico.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_back
import com.lonx.lyrico.resources.character_mapping_description
import com.lonx.lyrico.resources.character_replacement_selector_subtitle
import com.lonx.lyrico.resources.character_to_replace
import com.lonx.lyrico.resources.configure_character_mapping
import com.lonx.lyrico.resources.no_character_mappings
import com.lonx.lyrico.resources.replacement_not_selected
import com.lonx.lyrico.data.model.CharacterMappingRule
import com.lonx.lyrico.data.model.ReplacementCharOption
import com.lonx.lyrico.data.model.toReplacementOption
import com.lonx.lyrico.ui.components.scaffoldTopHorizontalPadding
import com.lonx.lyrico.viewmodel.CharacterMappingViewModel
import org.koin.compose.viewmodel.koinViewModel
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.utils.formattedStringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun CharacterMappingScreen(
    navigator: Navigator
) {
    val viewModel: CharacterMappingViewModel = koinViewModel()
    val characterMappingConfig by viewModel.characterMappingConfig.collectAsState()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(Res.string.configure_character_mapping),
                navigationIcon = {
                    IconButton(
                        onClick = { navigator.popBackStack() }) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldTopHorizontalPadding(paddingValues))
        ) {
            Text(
                text = stringResource(Res.string.character_mapping_description),
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.padding(12.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                    .fillMaxHeight(),
                overscrollEffect = null,
            ) {
                val config = characterMappingConfig
                if (config != null && config.rules.isNotEmpty()) {
                    items(items = config.rules) { rule ->
                        CharacterMappingRuleSection(
                            rule = rule,
                            onCharacterMappingChanged = { character, replacementChar ->
                                viewModel.updateCharacterMappingInRule(
                                    rule.id,
                                    character,
                                    replacementChar
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterMappingRuleSection(
    rule: CharacterMappingRule,
    onCharacterMappingChanged: (character: String, replacementChar: String?) -> Unit
) {
    if (rule.charMappings.isEmpty()) {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text(
                text = stringResource(Res.string.no_character_mappings),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        return
    }

    val optionLabels = listOf(
        stringResource(Res.string.replacement_not_selected)
    ) + ReplacementCharOption.entries.map { option ->
        stringResource(option.labelRes)
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp)
    ) {
        rule.charMappings.entries.forEach { (character, currentReplacement) ->
            val currentOption = currentReplacement.toReplacementOption()
            val selectedIndex = currentOption?.let { ReplacementCharOption.entries.indexOf(it) + 1 } ?: 0

            WindowDropdownPreference(
                title = formattedStringResource(Res.string.character_to_replace, character),
                summary = stringResource(Res.string.character_replacement_selector_subtitle),
                items = optionLabels,
                selectedIndex = selectedIndex,
                onSelectedIndexChange = { index ->
                    val replacementValue = if (index == 0) {
                        null
                    } else {
                        ReplacementCharOption.entries[index - 1].value
                    }
                    onCharacterMappingChanged(character, replacementValue)
                }
            )
        }
    }
}
