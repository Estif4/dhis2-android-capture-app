package org.dhis2.form.data

import androidx.paging.PagingData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import org.dhis2.commons.dialogs.bottomsheet.FieldWithIssue
import org.dhis2.commons.dialogs.bottomsheet.IssueType
import org.dhis2.commons.periods.model.Period
import org.dhis2.commons.prefs.Preference
import org.dhis2.commons.prefs.PreferenceProvider
import org.dhis2.form.data.EnrollmentRepository.Companion.ENROLLMENT_DATE_UID
import org.dhis2.form.model.ActionType
import org.dhis2.form.model.FieldUiModel
import org.dhis2.form.model.OptionSetConfiguration
import org.dhis2.form.model.RowAction
import org.dhis2.form.model.SectionUiModelImpl
import org.dhis2.form.model.StoreResult
import org.dhis2.form.ui.provider.DisplayNameProvider
import org.dhis2.form.ui.provider.LegendValueProvider
import org.dhis2.mobile.commons.providers.FieldErrorMessageProvider
import org.dhis2.mobileProgramRules.RuleEngineHelper
import org.hisp.dhis.android.core.common.ValidationStrategy
import org.hisp.dhis.android.core.common.ValueType
import org.hisp.dhis.android.core.common.ValueType.LONG_TEXT
import org.hisp.dhis.android.core.event.EventStatus
import org.hisp.dhis.rules.models.RuleEffect

private const val loopThreshold = 5

class FormRepositoryImpl(
    private val formValueStore: FormValueStore,
    private val fieldErrorMessageProvider: FieldErrorMessageProvider,
    private val displayNameProvider: DisplayNameProvider,
    private val dataEntryRepository: DataEntryRepository,
    private val ruleEngineRepository: RuleEngineHelper?,
    private val rulesUtilsProvider: RulesUtilsProvider,
    private val legendValueProvider: LegendValueProvider,
    private val useCompose: Boolean,
    private val preferenceProvider: PreferenceProvider,
) : FormRepository {

    private var completionPercentage: Float = 0f
    private val itemsWithError: MutableList<RowAction> = mutableListOf()
    private val mandatoryItemsWithoutValue: MutableMap<String, String> = mutableMapOf()
    private var openedSectionUid: String? = null
    private var itemList: List<FieldUiModel> = emptyList()
    private var focusedItemId: String? = null
    private var ruleEffects: List<RuleEffect> = emptyList()
    private var ruleEffectsResult: RuleUtilsProviderResult? = null
    private var runDataIntegrity: Boolean = false
    private var calculationLoop: Int = 0
    private var backupList: List<FieldUiModel> = emptyList()
    private val fieldsWithOptionEffects = mutableListOf<FieldUiModel>()

    private val disableCollapsableSections: Boolean? =
        dataEntryRepository.disableCollapsableSections()

    override suspend fun fetchFormItems(shouldOpenErrorLocation: Boolean): List<FieldUiModel> {
        itemList = dataEntryRepository.list().blockingFirst() ?: emptyList()
        openedSectionUid = getInitialOpenedSection(shouldOpenErrorLocation)
        backupList = itemList
        return composeList()
    }

    private fun getInitialOpenedSection(shouldOpenErrorLocation: Boolean) = when {
        disableCollapsableSections == true ->
            null

        shouldOpenErrorLocation ->
            itemList.firstOrNull { it.error != null || it.warning != null }?.programStageSection
                ?: dataEntryRepository.firstSectionToOpen()

        else ->
            dataEntryRepository.firstSectionToOpen()
    }

    override suspend fun composeList(skipProgramRules: Boolean): List<FieldUiModel> {
        calculationLoop = 0
        return itemList
            .applyRuleEffects(skipProgramRules)
            .mergeListWithErrorFields(itemsWithError)
            .also {
                calculateCompletionPercentage(it)
            }
            .setOpenedSection()
            .setFocusedItem()
            .setLastItem()
    }

    override fun completeEvent() {
        formValueStore.completeEvent()
        preferenceProvider.setValue(Preference.PREF_COMPLETED_EVENT, formValueStore.recordUid())
    }

    override fun activateEvent() {
        formValueStore.activateEvent()
    }

    override fun fetchPeriods(): Flow<PagingData<Period>> {
        return dataEntryRepository.fetchPeriods()
    }

    private fun List<FieldUiModel>.setLastItem(): List<FieldUiModel> {
        if (isEmpty()) {
            return this
        }
        return if (this.all { it is SectionUiModelImpl }) {
            val lastItem = getLastSectionItem(this)
            return if (usesKeyboard(lastItem.valueType) && lastItem.valueType != LONG_TEXT) {
                updated(indexOf(lastItem), lastItem.setKeyBoardActionDone())
            } else {
                this
            }
        } else {
            this
        }
    }

    private fun usesKeyboard(valueType: ValueType?): Boolean {
        return valueType?.let {
            it.isText || it.isNumeric || it.isInteger
        } ?: false
    }

    private fun getLastSectionItem(list: List<FieldUiModel>): FieldUiModel {
        return if (list.all { it is SectionUiModelImpl }) {
            list.asReversed().first()
        } else {
            list.asReversed().first { it.valueType != null }
        }
    }

    private fun ruleEffects() = try {
        ruleEngineRepository?.evaluate() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun calculateCompletionPercentage(list: List<FieldUiModel>) {
        val unsupportedValueTypes = listOf(
            ValueType.TRACKER_ASSOCIATE,
            ValueType.USERNAME,
        )
        val fields = list.filter {
            it.valueType != null &&
                !unsupportedValueTypes.contains(it.valueType)
        }
        val totalFields = fields.size
        val fieldsWithValue = fields.filter { !it.value.isNullOrEmpty() }.size
        completionPercentage = if (totalFields == 0) {
            0f
        } else {
            fieldsWithValue.toFloat().div(totalFields.toFloat())
        }
    }

    override fun getConfigurationErrors(): List<RulesUtilsProviderConfigurationError>? {
        return ruleEffectsResult?.configurationErrors
    }

    override suspend fun runDataIntegrityCheck(backPressed: Boolean): DataIntegrityCheckResult {
        runDataIntegrity = true
        val itemsWithErrors = getFieldsWithError()
        val isEvent = dataEntryRepository.isEvent()
        val itemsWithWarning = ruleEffectsResult?.fieldsWithWarnings?.map { warningField ->
            FieldWithIssue(
                fieldUid = warningField.fieldUid,
                fieldName = itemList.find { it.uid == warningField.fieldUid }?.label ?: "",
                IssueType.WARNING,
                warningField.errorMessage,
            )
        } ?: emptyList()

        return if (isEvent) {
            getEventResult(itemsWithErrors, itemsWithWarning, backPressed)
        } else {
            getEnrollmentResult(itemsWithErrors, itemsWithWarning, backPressed)
        }
    }

    private fun getEnrollmentResult(
        itemsWithErrors: List<FieldWithIssue>,
        itemsWithWarning: List<FieldWithIssue>,
        allowDiscard: Boolean,
    ): DataIntegrityCheckResult {
        val result = when {
            itemsWithErrors.isNotEmpty() || ruleEffectsResult?.canComplete == false -> {
                FieldsWithErrorResult(
                    mandatoryFields = mandatoryItemsWithoutValue,
                    fieldUidErrorList = itemsWithErrors,
                    warningFields = itemsWithWarning,
                    canComplete = ruleEffectsResult?.canComplete ?: true,
                    onCompleteMessage = ruleEffectsResult?.messageOnComplete,
                    allowDiscard = allowDiscard,
                    eventResultDetails = EventResultDetails(null, null, null),
                )
            }

            mandatoryItemsWithoutValue.isNotEmpty() -> {
                MissingMandatoryResult(
                    mandatoryFields = mandatoryItemsWithoutValue,
                    errorFields = itemsWithErrors,
                    warningFields = itemsWithWarning,
                    canComplete = ruleEffectsResult?.canComplete ?: true,
                    onCompleteMessage = ruleEffectsResult?.messageOnComplete,
                    allowDiscard = allowDiscard,
                    eventResultDetails = EventResultDetails(null, null, null),

                )
            }

            itemsWithWarning.isNotEmpty() -> {
                FieldsWithWarningResult(
                    fieldUidWarningList = itemsWithWarning,
                    canComplete = ruleEffectsResult?.canComplete ?: true,
                    onCompleteMessage = ruleEffectsResult?.messageOnComplete,
                    eventResultDetails = EventResultDetails(null, null, null),
                )
            }

            backupOfChangedItems().isNotEmpty() && allowDiscard -> NotSavedResult
            else -> {
                SuccessfulResult(
                    canComplete = ruleEffectsResult?.canComplete ?: true,
                    onCompleteMessage = ruleEffectsResult?.messageOnComplete,
                    eventResultDetails = EventResultDetails(null, null, null),
                )
            }
        }
        return result
    }

    private fun getEventResult(
        itemsWithErrors: List<FieldWithIssue>,
        itemsWithWarning: List<FieldWithIssue>,
        backPressed: Boolean,
    ): DataIntegrityCheckResult {
        val eventStatus = formValueStore.eventState()
        val validationStrategy = dataEntryRepository.validationStrategy()

        return when {
            (itemsWithErrors.isEmpty() && itemsWithWarning.isEmpty() && mandatoryItemsWithoutValue.isEmpty()) -> {
                getSuccessfulResult()
            }

            (itemsWithErrors.isNotEmpty()) -> {
                getFieldWithErrorResult(
                    eventStatus,
                    itemsWithErrors,
                    itemsWithWarning,
                    validationStrategy,
                    backPressed,
                )
            }

            (mandatoryItemsWithoutValue.isNotEmpty()) -> {
                getMissingMandatoryResult(
                    eventStatus,
                    itemsWithErrors,
                    itemsWithWarning,
                    validationStrategy,
                    backPressed,
                )
            }

            else -> {
                getFieldWithWarningResult(eventStatus, itemsWithWarning, validationStrategy)
            }
        }
    }

    private fun getFieldWithWarningResult(
        eventStatus: EventStatus?,
        itemsWithWarning: List<FieldWithIssue>,
        validationStrategy: ValidationStrategy?,
    ): FieldsWithWarningResult {
        return when (eventStatus) {
            EventStatus.ACTIVE -> {
                FieldsWithWarningResult(
                    fieldUidWarningList = itemsWithWarning,
                    canComplete = ruleEffectsResult?.canComplete ?: true,
                    onCompleteMessage = ruleEffectsResult?.messageOnComplete,
                    eventResultDetails = EventResultDetails(
                        formValueStore.eventState(),
                        dataEntryRepository.eventMode(),
                        validationStrategy,
                    ),
                )
            }

            EventStatus.COMPLETED -> {
                FieldsWithWarningResult(
                    fieldUidWarningList = itemsWithWarning,
                    canComplete = ruleEffectsResult?.canComplete ?: false,
                    onCompleteMessage = ruleEffectsResult?.messageOnComplete,
                    eventResultDetails = EventResultDetails(
                        formValueStore.eventState(),
                        dataEntryRepository.eventMode(),
                        null,
                    ),
                )
            }

            else -> {
                FieldsWithWarningResult(
                    fieldUidWarningList = itemsWithWarning,
                    canComplete = ruleEffectsResult?.canComplete ?: false,
                    onCompleteMessage = ruleEffectsResult?.messageOnComplete,
                    eventResultDetails = EventResultDetails(
                        formValueStore.eventState(),
                        dataEntryRepository.eventMode(),
                        validationStrategy,
                    ),
                )
            }
        }
    }

    private fun getMissingMandatoryResult(
        eventStatus: EventStatus?,
        itemsWithErrors: List<FieldWithIssue>,
        itemsWithWarning: List<FieldWithIssue>,
        validationStrategy: ValidationStrategy?,
        backPressed: Boolean,
    ): DataIntegrityCheckResult {
        return when (eventStatus) {
            EventStatus.ACTIVE -> {
                MissingMandatoryResult(
                    mandatoryFields = mandatoryItemsWithoutValue,
                    errorFields = itemsWithErrors,
                    warningFields = itemsWithWarning,
                    canComplete = ruleEffectsResult?.canComplete ?: true,
                    onCompleteMessage = ruleEffectsResult?.messageOnComplete,
                    allowDiscard = backPressed,
                    eventResultDetails = EventResultDetails(
                        formValueStore.eventState(),
                        dataEntryRepository.eventMode(),
                        validationStrategy,
                    ),

                )
            }

            EventStatus.COMPLETED -> {
                MissingMandatoryResult(
                    mandatoryFields = mandatoryItemsWithoutValue,
                    errorFields = itemsWithErrors,
                    warningFields = itemsWithWarning,
                    canComplete = false,
                    onCompleteMessage = ruleEffectsResult?.messageOnComplete,
                    allowDiscard = backPressed,
                    eventResultDetails = EventResultDetails(
                        formValueStore.eventState(),
                        dataEntryRepository.eventMode(),
                        null,
                    ),
                )
            }

            else -> {
                MissingMandatoryResult(
                    mandatoryFields = mandatoryItemsWithoutValue,
                    errorFields = itemsWithErrors,
                    warningFields = itemsWithWarning,
                    canComplete = ruleEffectsResult?.canComplete ?: false,
                    onCompleteMessage = ruleEffectsResult?.messageOnComplete,
                    allowDiscard = backPressed,
                    eventResultDetails = EventResultDetails(
                        formValueStore.eventState(),
                        dataEntryRepository.eventMode(),
                        validationStrategy,
                    ),
                )
            }
        }
    }

    private fun getFieldWithErrorResult(
        eventStatus: EventStatus?,
        itemsWithErrors: List<FieldWithIssue>,
        itemsWithWarning: List<FieldWithIssue>,
        validationStrategy: ValidationStrategy?,
        backPressed: Boolean,
    ): FieldsWithErrorResult {
        return when (eventStatus) {
            EventStatus.ACTIVE -> {
                FieldsWithErrorResult(
                    mandatoryFields = mandatoryItemsWithoutValue,
                    fieldUidErrorList = itemsWithErrors,
                    warningFields = itemsWithWarning,
                    canComplete = ruleEffectsResult?.canComplete ?: true,
                    onCompleteMessage = ruleEffectsResult?.messageOnComplete,
                    allowDiscard = backPressed,
                    eventResultDetails = EventResultDetails(
                        formValueStore.eventState(),
                        dataEntryRepository.eventMode(),
                        validationStrategy,
                    ),
                )
            }

            EventStatus.COMPLETED -> {
                FieldsWithErrorResult(
                    mandatoryFields = mandatoryItemsWithoutValue,
                    fieldUidErrorList = itemsWithErrors,
                    warningFields = itemsWithWarning,
                    canComplete = false,
                    onCompleteMessage = ruleEffectsResult?.messageOnComplete,
                    allowDiscard = backPressed,
                    eventResultDetails = EventResultDetails(
                        formValueStore.eventState(),
                        dataEntryRepository.eventMode(),
                        dataEntryRepository.validationStrategy(),
                    ),
                )
            }

            else -> {
                FieldsWithErrorResult(
                    mandatoryFields = mandatoryItemsWithoutValue,
                    fieldUidErrorList = itemsWithErrors,
                    warningFields = itemsWithWarning,
                    canComplete = ruleEffectsResult?.canComplete ?: false,
                    onCompleteMessage = ruleEffectsResult?.messageOnComplete,
                    allowDiscard = backPressed,
                    eventResultDetails = EventResultDetails(
                        formValueStore.eventState(),
                        dataEntryRepository.eventMode(),
                        validationStrategy,
                    ),
                )
            }
        }
    }

    private fun getSuccessfulResult(): SuccessfulResult {
        return SuccessfulResult(
            canComplete = ruleEffectsResult?.canComplete ?: true,
            onCompleteMessage = ruleEffectsResult?.messageOnComplete,
            eventResultDetails = EventResultDetails(
                formValueStore.eventState(),
                dataEntryRepository.eventMode(),
                dataEntryRepository.validationStrategy(),
            ),
        )
    }

    override fun completedFieldsPercentage(value: List<FieldUiModel>): Float {
        return completionPercentage
    }

    override fun calculationLoopOverLimit(): Boolean {
        return calculationLoop == loopThreshold
    }

    override fun backupOfChangedItems() = backupList.minus(itemList.applyRuleEffects())

    private suspend fun getFieldsWithError() = itemsWithError.mapNotNull { errorItem ->
        itemList.find { item ->
            item.uid == errorItem.id
        }?.let { item ->
            FieldWithIssue(
                fieldUid = item.uid,
                fieldName = item.label,
                issueType = IssueType.ERROR,
                message = errorItem.error?.let {
                    fieldErrorMessageProvider.getFriendlyErrorMessage(it)
                } ?: "",
            )
        }
    }.plus(
        ruleEffectsResult?.fieldsWithErrors?.map { errorField ->
            FieldWithIssue(
                fieldUid = errorField.fieldUid,
                fieldName = itemList.find { it.uid == errorField.fieldUid }?.label ?: "",
                issueType = IssueType.ERROR,
                message = errorField.errorMessage,
            )
        } ?: emptyList(),
    )

    @Synchronized
    private fun List<FieldUiModel>.applyRuleEffects(
        skipProgramRules: Boolean = false,
    ): List<FieldUiModel> {
        ruleEffects = if (skipProgramRules) {
            ruleEffects
        } else {
            ruleEffects()
        }
        val fieldMap = this.associateBy { it.uid }.toMutableMap()
        ruleEffectsResult = rulesUtilsProvider.applyRuleEffects(
            applyForEvent = dataEntryRepository.isEvent(),
            fieldViewModels = fieldMap,
            ruleEffects,
            valueStore = formValueStore,
        )
        ruleEffectsResult?.fieldsToUpdate?.takeIf { it.isNotEmpty() }
            ?.forEach { fieldWithNewValue ->
                itemList.find { it.uid == fieldWithNewValue.fieldUid }?.let { field ->
                    updateValueOnList(field.uid, fieldWithNewValue.newValue, field.valueType)
                }
            }

        fieldsWithOptionEffects.forEach { field ->
            field.optionSet?.let { optionSetUid ->
                fetchOptions(field.uid, optionSetUid)
            }
        }

        fieldsWithOptionEffects.clear()

        ruleEffectsResult?.fieldsWithOptionEffects()?.forEach { fieldWithOptionEffect ->
            val item = itemList.find { it.uid == fieldWithOptionEffect }

            item?.let { field ->
                field.optionSet?.let { optionSetUid ->
                    fetchOptions(field.uid, optionSetUid)
                }
                fieldsWithOptionEffects.add(field)
            }
        }

        return if (ruleEffectsResult?.fieldsToUpdate?.isNotEmpty() == true &&
            calculationLoop < loopThreshold
        ) {
            calculationLoop += 1
            ArrayList(fieldMap.values).applyRuleEffects(skipProgramRules)
        } else {
            ArrayList(fieldMap.values)
        }
    }

    private fun List<FieldUiModel>.setFocusedItem(): List<FieldUiModel> {
        return focusedItemId?.let { uid ->
            find { item ->
                item.uid == uid
            }?.let { item ->
                updated(indexOf(item), item.setFocus())
            } ?: this
        } ?: this
    }

    private suspend fun List<FieldUiModel>.setOpenedSection(): List<FieldUiModel> {
        return map { field ->
            if (field.isSection()) {
                updateSection(field, this)
            } else {
                updateField(field)
            }
        }
            .filter { field ->
                when (field) {
                    is SectionUiModelImpl ->
                        field.isSectionWithFields()

                    else ->
                        useCompose || disableCollapsableSections == true ||
                            field.programStageSection == openedSectionUid
                }
            }
    }

    private fun updateSection(
        sectionFieldUiModel: FieldUiModel,
        fields: List<FieldUiModel>,
    ): FieldUiModel {
        var total = 0
        var values = 0
        val isOpen = (sectionFieldUiModel.uid == openedSectionUid)
            .takeIf { disableCollapsableSections != true }
        fields.filter {
            it.programStageSection.equals(sectionFieldUiModel.uid) && it.valueType != null
        }.forEach {
            total++
            if (!it.value.isNullOrEmpty()) {
                values++
            }
        }

        val warningCount = ruleEffectsResult?.warningMap()?.filter { warning ->
            fields.any { field ->
                field.uid == warning.key && field.programStageSection == sectionFieldUiModel.uid
            }
        }?.size ?: 0

        val mandatoryCount = mandatoryItemsWithoutValue.takeIf {
            runDataIntegrity
        }?.filter { mandatory ->
            mandatory.value == sectionFieldUiModel.uid
        }?.size ?: 0

        val errorCount = ruleEffectsResult?.errorMap()?.filter { error ->
            fields.any { field ->
                field.uid == error.key && field.programStageSection == sectionFieldUiModel.uid
            }
        }?.size ?: 0

        val errorFields = fields.count {
            it.programStageSection == sectionFieldUiModel.uid && it.error != null
        }

        return dataEntryRepository.updateSection(
            sectionFieldUiModel,
            isOpen,
            total,
            values,
            errorCount + mandatoryCount + errorFields,
            warningCount,
        )
    }

    private suspend fun updateField(fieldUiModel: FieldUiModel): FieldUiModel {
        val needsMandatoryWarning = hasMandatoryWarnings(fieldUiModel)
        if (needsMandatoryWarning) {
            mandatoryItemsWithoutValue[fieldUiModel.label] = fieldUiModel.programStageSection ?: ""
        }

        return dataEntryRepository.updateField(
            fieldUiModel,
            fieldErrorMessageProvider.mandatoryWarning().takeIf {
                needsMandatoryWarning && runDataIntegrity
            },
            ruleEffectsResult?.optionsToHide(fieldUiModel.uid) ?: emptyList(),
            ruleEffectsResult?.optionGroupsToHide(fieldUiModel.uid) ?: emptyList(),
            ruleEffectsResult?.optionGroupsToShow(fieldUiModel.uid) ?: emptyList(),
        )
    }

    private fun hasMandatoryWarnings(fieldUiModel: FieldUiModel): Boolean {
        return if (fieldUiModel.uid.contains(EventRepository.EVENT_CATEGORY_COMBO_UID)) {
            fieldUiModel.mandatory &&
                (
                    fieldUiModel.value.isNullOrEmpty() ||
                        fieldUiModel.value?.split(",")?.size !=
                        fieldUiModel.eventCategories?.size
                    )
        } else {
            fieldUiModel.mandatory &&
                fieldUiModel.value.isNullOrEmpty()
        }
    }

    private fun getNextItem(currentItemUid: String): String? {
        itemList.let { fields ->
            val oldItem = fields.find { it.uid == currentItemUid }
            val pos = fields.indexOf(oldItem)
            if (pos < fields.size - 1) {
                return fields[pos + 1].uid
            }
        }
        return null
    }

    override fun updateValueOnList(uid: String, value: String?, valueType: ValueType?) {
        val updatedEnrollmentDataList = dataEntryRepository.getSpecificDataEntryItems(uid)
        if (updatedEnrollmentDataList.isNotEmpty()) updateEnrollmentDate(updatedEnrollmentDataList)
        itemList.let { list ->

            list.find { item ->
                item.uid == uid
            }?.let { item ->
                itemList = list.updated(
                    list.indexOf(item),
                    item.setValue(value)
                        .setDisplayName(
                            displayNameProvider.provideDisplayName(
                                valueType,
                                value,
                                item.optionSet,
                                item.periodSelector?.type,
                            ),
                        )
                        .setLegend(
                            legendValueProvider.provideLegendValue(
                                item.uid,
                                value,
                            ),
                        ),
                )
            }
        }
    }

    private fun updateEnrollmentDate(fieldUiModelList: List<FieldUiModel>) {
        for (element in fieldUiModelList) {
            itemList.let { list ->
                list.find { item ->
                    item.uid == ENROLLMENT_DATE_UID
                }?.let { item ->
                    itemList = list.updated(
                        list.indexOf(item),
                        item.setSelectableDates(element.selectableDates),

                    )
                }
            }
        }
    }

    override fun removeAllValues() {
        itemList = itemList.map { fieldUiModel ->
            fieldUiModel.setValue(null).setDisplayName(null)
        }
    }

    override fun setFieldRequestingCoordinates(uid: String, requestInProcess: Boolean) {
        itemList.let { list ->
            list.find { item ->
                item.uid == uid
            }?.let { item ->
                itemList = list.updated(
                    list.indexOf(item),
                    item.setIsLoadingData(requestInProcess),
                )
            }
        }
    }

    override fun setFieldAddingImage(uid: String, requestInProcess: Boolean) {
        itemList.let { list ->
            list.find { item ->
                item.uid == uid
            }?.let { item ->
                itemList = list.updated(
                    list.indexOf(item),
                    item.setIsLoadingData(requestInProcess),
                )
            }
        }
    }

    private suspend fun List<FieldUiModel>.mergeListWithErrorFields(
        fieldsWithError: List<RowAction>,
    ): List<FieldUiModel> {
        mandatoryItemsWithoutValue.clear()
        val mergedList = this.map { item ->
            if (hasMandatoryWarnings(item)) {
                mandatoryItemsWithoutValue[item.label] = item.programStageSection ?: ""
            }
            fieldsWithError.find { it.id == item.uid }?.let { action ->
                val error = action.error?.let {
                    fieldErrorMessageProvider.getFriendlyErrorMessage(it)
                }
                item.setValue(action.value).setError(error)
                    .setDisplayName(
                        displayNameProvider.provideDisplayName(
                            action.valueType,
                            action.value,
                            item.optionSet,
                            item.periodSelector?.type,
                        ),
                    )
            } ?: item
        }
        return mergedList
    }

    override fun updateErrorList(action: RowAction) {
        if (action.error != null) {
            if (itemsWithError.none { it.id == action.id }) {
                itemsWithError.add(action)
            }
        } else {
            itemsWithError.find { it.id == action.id }?.let {
                itemsWithError.remove(it)
            }
        }
    }

    override fun save(id: String, value: String?, extraData: String?): StoreResult {
        val result = formValueStore.save(id, value, extraData)
        if (result.contextDataChanged()) ruleEngineRepository?.refreshContext()
        return result
    }

    override fun storeFile(id: String, filePath: String?): StoreResult {
        return formValueStore.storeFile(id, filePath)
    }

    override fun areSectionCollapsable(): Boolean {
        return disableCollapsableSections ?: false
    }

    override fun hasLegendSet(dataElementUid: String): Boolean =
        legendValueProvider.hasLegendSet(dataElementUid)

    override fun setFocusedItem(action: RowAction) {
        focusedItemId = when (action.type) {
            ActionType.ON_NEXT -> getNextItem(action.id)
            ActionType.ON_FINISH -> null
            else -> action.id
        }
    }

    override fun fetchOptions(uid: String, optionSetUid: String) {
        val (searchEmitter, flow) = dataEntryRepository.options(
            optionSetUid = optionSetUid,
            optionsToHide = ruleEffectsResult?.optionsToHide(uid) ?: emptyList(),
            optionGroupsToHide = ruleEffectsResult?.optionGroupsToHide(uid) ?: emptyList(),
            optionGroupsToShow = ruleEffectsResult?.optionGroupsToShow(uid) ?: emptyList(),
        )

        val newConf = OptionSetConfiguration(
            searchEmitter = searchEmitter,
            onSearch = { query ->
                searchEmitter.value = query
            },
            optionFlow = flow,
        )

        itemList.let { list ->
            list.find { item ->
                item.uid == uid
            }?.let { item ->
                item.optionSetConfiguration = newConf
                itemList = list.updated(
                    list.indexOf(item),
                    item,
                )
            }
        }
    }

    override fun clearFocusItem() {
        focusedItemId = null
    }

    override fun currentFocusedItem(): FieldUiModel? {
        return itemList.find { focusedItemId == it.uid }
    }

    override fun updateSectionOpened(action: RowAction) {
        if (disableCollapsableSections != true) {
            openedSectionUid = action.id
        }
    }

    override fun getDateFormatConfiguration(): String {
        return dataEntryRepository.dateFormatConfiguration() ?: "ddMMyyyy"
    }

    fun <E> Iterable<E>.updated(index: Int, elem: E): List<E> =
        mapIndexed { i, existing -> if (i == index) elem else existing }

    override fun getListFromPreferences(uid: String): MutableList<String> {
        val gson = Gson()
        val json = preferenceProvider.sharedPreferences().getString(uid, "[]")
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type)
    }

    override fun saveListToPreferences(uid: String, list: List<String>) {
        val gson = Gson()
        val json = gson.toJson(list)
        preferenceProvider.sharedPreferences().edit().putString(uid, json).apply()
    }
}
