package org.dhis2.form.ui.provider.inputfield

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.paging.compose.collectAsLazyPagingItems
import org.dhis2.commons.resources.ResourceManager
import org.dhis2.form.R
import org.dhis2.form.extensions.inputState
import org.dhis2.form.extensions.legend
import org.dhis2.form.extensions.orientation
import org.dhis2.form.extensions.supportingText
import org.dhis2.form.model.FieldUiModel
import org.dhis2.form.ui.intent.FormIntent
import org.hisp.dhis.mobile.ui.designsystem.component.InputRadioButton
import org.hisp.dhis.mobile.ui.designsystem.component.InputStyle
import org.hisp.dhis.mobile.ui.designsystem.component.RadioButtonData

@Composable
internal fun ProvideRadioButtonInput(
    modifier: Modifier,
    inputStyle: InputStyle,
    fieldUiModel: FieldUiModel,
    intentHandler: (FormIntent) -> Unit,
) {
    val dataMap = buildMap {
        fieldUiModel.optionSetConfiguration?.optionFlow?.collectAsLazyPagingItems()?.let { paging ->
            repeat(paging.itemCount) { index ->
                val optionData = paging[index]
                put(
                    optionData?.option?.code() ?: "",
                    RadioButtonData(
                        uid = optionData?.option?.uid() ?: "",
                        selected = fieldUiModel.displayName == optionData?.option?.displayName(),
                        enabled = true,
                        textInput = optionData?.option?.displayName() ?: "",
                    ),
                )
            }
        }
    }

    val (codeList, data) = dataMap.toList().unzip()

    InputRadioButton(
        modifier = modifier,
        inputStyle = inputStyle,
        title = fieldUiModel.label,
        radioButtonData = data,
        orientation = fieldUiModel.orientation(),
        state = fieldUiModel.inputState(),
        supportingText = fieldUiModel.supportingText(),
        legendData = fieldUiModel.legend(),
        isRequired = fieldUiModel.mandatory,
        itemSelected = data.find { it.selected },
        onItemChange = { item ->
            if (item != null) {
                val selectedIndex = data.indexOf(item)
                intentHandler(
                    FormIntent.OnSave(
                        fieldUiModel.uid,
                        codeList[selectedIndex],
                        fieldUiModel.valueType,
                    ),
                )
            } else {
                intentHandler(FormIntent.ClearValue(fieldUiModel.uid))
            }
        },
    )
}

@Composable
internal fun ProvideYesNoRadioButtonInput(
    modifier: Modifier,
    inputStyle: InputStyle,
    fieldUiModel: FieldUiModel,
    intentHandler: (FormIntent) -> Unit,
    resources: ResourceManager,
) {
    val data = listOf(
        RadioButtonData(
            uid = "true",
            selected = fieldUiModel.isAffirmativeChecked,
            enabled = true,
            textInput = resources.getString(R.string.yes),
        ),
        RadioButtonData(
            uid = "false",
            selected = fieldUiModel.isNegativeChecked,
            enabled = true,
            textInput = resources.getString(R.string.no),
        ),
    )

    InputRadioButton(
        modifier = modifier,
        inputStyle = inputStyle,
        title = fieldUiModel.label,
        radioButtonData = data,
        orientation = fieldUiModel.orientation(),
        state = fieldUiModel.inputState(),
        supportingText = fieldUiModel.supportingText(),
        legendData = fieldUiModel.legend(),
        isRequired = fieldUiModel.mandatory,
        itemSelected = data.find { it.selected },
        onItemChange = { item ->
            when (item?.uid) {
                "true" -> {
                    intentHandler(
                        FormIntent.OnSave(
                            fieldUiModel.uid,
                            true.toString(),
                            fieldUiModel.valueType,
                        ),
                    )
                }

                "false" -> {
                    intentHandler(
                        FormIntent.OnSave(
                            fieldUiModel.uid,
                            false.toString(),
                            fieldUiModel.valueType,
                        ),
                    )
                }

                else -> {
                    intentHandler(
                        FormIntent.ClearValue(fieldUiModel.uid),
                    )
                }
            }
        },
    )
}
