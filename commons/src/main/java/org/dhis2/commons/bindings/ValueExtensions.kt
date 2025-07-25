package org.dhis2.bindings

import org.dhis2.commons.date.DateUtils
import org.dhis2.commons.extensions.toPercentage
import org.hisp.dhis.android.core.D2
import org.hisp.dhis.android.core.common.ValueType
import org.hisp.dhis.android.core.trackedentity.TrackedEntityAttributeValue
import org.hisp.dhis.android.core.trackedentity.TrackedEntityAttributeValueObjectRepository
import org.hisp.dhis.android.core.trackedentity.TrackedEntityDataValue
import org.hisp.dhis.android.core.trackedentity.TrackedEntityDataValueObjectRepository
import java.text.ParseException

fun TrackedEntityAttributeValue.userFriendlyValue(
    d2: D2,
    addPercentageSymbol: Boolean = true,
): String? {
    return when {
        value().isNullOrEmpty() -> value()
        else -> {
            val attribute = d2.trackedEntityModule().trackedEntityAttributes()
                .uid(trackedEntityAttribute())
                .blockingGet()
            value()!!.userFriendlyValue(
                d2,
                attribute?.valueType(),
                attribute?.optionSet()?.uid(),
                addPercentageSymbol,
            )
        }
    }
}

fun TrackedEntityDataValue?.userFriendlyValue(
    d2: D2,
    addPercentageSymbol: Boolean = true,
): String? {
    return when {
        this == null -> null
        value().isNullOrEmpty() -> value()
        else -> {
            val dataElement = d2.dataElementModule().dataElements()
                .uid(dataElement())
                .blockingGet()

            value()!!.userFriendlyValue(
                d2,
                dataElement?.valueType(),
                dataElement?.optionSetUid(),
                addPercentageSymbol,
            )
        }
    }
}

fun String.userFriendlyValue(
    d2: D2,
    valueType: ValueType?,
    optionSetUid: String?,
    addPercentageSymbol: Boolean = true,
): String? {
    if (valueType == null) {
        return null
    } else if (check(d2, valueType, optionSetUid, this)) {
        optionSetUid?.takeIf { valueType != ValueType.MULTI_TEXT }?.let {
            return checkOptionSetValue(d2, optionSetUid, this)
        } ?: return checkValueTypeValue(d2, valueType, this, addPercentageSymbol)
    } else {
        return null
    }
}

fun checkOptionSetValue(d2: D2, optionSetUid: String, code: String): String? {
    return d2.optionModule().options()
        .byOptionSetUid().eq(optionSetUid)
        .byCode().eq(code).one().blockingGet()?.displayName()
}

fun checkValueTypeValue(
    d2: D2,
    valueType: ValueType?,
    value: String,
    addPercentageSymbol: Boolean = true,
): String {
    return when (valueType) {
        ValueType.ORGANISATION_UNIT ->
            d2.organisationUnitModule().organisationUnits()
                .uid(value)
                .blockingGet()
                ?.displayName() ?: value

        ValueType.IMAGE, ValueType.FILE_RESOURCE ->
            d2.fileResourceModule().fileResources().uid(value).blockingGet()?.path() ?: ""

        ValueType.DATE, ValueType.AGE ->
            try {
                DateUtils.uiDateFormat().format(
                    DateUtils.oldUiDateFormat().parse(value) ?: "",
                )
            } catch (exception: ParseException) {
                value
            }

        ValueType.DATETIME ->
            try {
                DateUtils.uiDateTimeFormat().format(
                    DateUtils.databaseDateFormatNoSeconds().parse(value) ?: "",
                )
            } catch (exception: ParseException) {
                value
            }

        ValueType.TIME ->
            try {
                DateUtils.timeFormat().format(
                    DateUtils.timeFormat().parse(value) ?: "",
                )
            } catch (exception: ParseException) {
                value
            }

        ValueType.PERCENTAGE -> {
            if (addPercentageSymbol) {
                value.toPercentage()
            } else {
                value
            }
        }

        else -> value
    }
}

fun TrackedEntityAttributeValueObjectRepository.blockingSetCheck(
    d2: D2,
    attrUid: String,
    value: String,
    onCrash: (attrUid: String, value: String) -> Unit = { _, _ -> },
): Boolean {
    return d2.trackedEntityModule().trackedEntityAttributes().uid(attrUid).blockingGet()?.let {
        if (check(d2, it.valueType(), it.optionSet()?.uid(), value)) {
            val finalValue = assureCodeForOptionSet(d2, it.optionSet()?.uid(), value)
            try {
                blockingSet(finalValue)
            } catch (e: Exception) {
                onCrash(attrUid, value)
                return false
            }
            true
        } else {
            blockingDeleteIfExist()
            false
        }
    } ?: false
}

fun TrackedEntityAttributeValueObjectRepository.blockingGetCheck(
    d2: D2,
    attrUid: String,
): TrackedEntityAttributeValue? {
    return d2.trackedEntityModule().trackedEntityAttributes().uid(attrUid).blockingGet()?.let {
        if (blockingExists() && check(
                d2,
                it.valueType(),
                it.optionSet()?.uid(),
                blockingGet()?.value()!!,
            )
        ) {
            blockingGet()
        } else {
            blockingDeleteIfExist()
            null
        }
    }
}

fun TrackedEntityDataValueObjectRepository.blockingSetCheck(
    d2: D2,
    deUid: String,
    value: String,
): Boolean {
    return d2.dataElementModule().dataElements().uid(deUid).blockingGet()?.let {
        if (check(d2, it.valueType(), it.optionSet()?.uid(), value)) {
            val finalValue = assureCodeForOptionSet(d2, it.optionSet()?.uid(), value)
            blockingSet(finalValue)
            true
        } else {
            blockingDeleteIfExist()
            false
        }
    } ?: false
}

fun String?.withValueTypeCheck(valueType: ValueType?): String? {
    return this?.let {
        if (isEmpty()) return this
        when (valueType) {
            ValueType.PERCENTAGE,
            ValueType.INTEGER,
            ValueType.INTEGER_POSITIVE,
            ValueType.INTEGER_NEGATIVE,
            ValueType.INTEGER_ZERO_OR_POSITIVE,
            -> (
                it.toIntOrNull() ?: it.toFloat().toInt()
                ).toString()

            ValueType.UNIT_INTERVAL -> (it.toIntOrNull() ?: it.toFloat()).toString()
            else -> this
        }
    } ?: this
}

fun TrackedEntityDataValueObjectRepository.blockingGetValueCheck(
    d2: D2,
    deUid: String,
): TrackedEntityDataValue? {
    return d2.dataElementModule().dataElements().uid(deUid).blockingGet()?.let {
        if (blockingExists() && check(
                d2,
                it.valueType(),
                it.optionSet()?.uid(),
                blockingGet()?.value()!!,
            )
        ) {
            blockingGet()
        } else {
            blockingDeleteIfExist()
            null
        }
    }
}

private fun check(d2: D2, valueType: ValueType?, optionSetUid: String?, value: String): Boolean {
    return when {
        valueType != ValueType.MULTI_TEXT && optionSetUid != null -> {
            val optionByCodeExist = d2.optionModule().options().byOptionSetUid().eq(optionSetUid)
                .byCode().eq(value).one().blockingExists()
            val optionByNameExist = d2.optionModule().options().byOptionSetUid().eq(optionSetUid)
                .byDisplayName().eq(value).one().blockingExists()
            optionByCodeExist || optionByNameExist
        }

        valueType != null -> {
            if (valueType.isNumeric) {
                try {
                    value.toFloat().toString()
                    true
                } catch (e: Exception) {
                    false
                }
            } else {
                when (valueType) {
                    ValueType.FILE_RESOURCE, ValueType.IMAGE ->
                        d2.fileResourceModule().fileResources()
                            .byUid().eq(value).one().blockingExists()

                    ValueType.ORGANISATION_UNIT ->
                        d2.organisationUnitModule().organisationUnits().uid(value).blockingExists()

                    else -> true
                }
            }
        }

        else -> false
    }
}

private fun assureCodeForOptionSet(d2: D2, optionSetUid: String?, value: String): String {
    return optionSetUid?.let {
        if (d2.optionModule().options()
                .byOptionSetUid().eq(it)
                .byName().eq(value)
                .one().blockingExists()
        ) {
            d2.optionModule().options().byOptionSetUid().eq(it).byName().eq(value).one()
                .blockingGet()?.code()
        } else {
            value
        }
    } ?: value
}
