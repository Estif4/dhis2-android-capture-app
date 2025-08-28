package dhis2.org.analytics.charts.providers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.commons.text.WordUtils
import org.dhis2.commons.periods.data.EthiopianDateConverter
import org.dhis2.commons.viewmodel.DispatcherProvider
import org.hisp.dhis.android.core.D2
import org.hisp.dhis.android.core.period.Period
import org.hisp.dhis.android.core.period.PeriodType
import org.joda.time.DateTime
import org.joda.time.Days
import org.joda.time.Months
import org.joda.time.Weeks
import org.joda.time.Years
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlin.coroutines.CoroutineContext

class PeriodStepProviderImpl(
    val d2: D2,
    val dispatcherProvider: DispatcherProvider,
) :
    PeriodStepProvider, CoroutineScope {

    private var job = Job()
    override val coroutineContext: CoroutineContext
        get() = job + dispatcherProvider.io()

    override fun periodStep(periodType: PeriodType?): Long {
        val currentDate = Date()
        val result = async(dispatcherProvider.io()) {
            val initialPeriodDate =
                getPeriodForPeriodTypeAndDate(
                    periodType ?: PeriodType.Daily,
                    currentDate,
                    -1,
                ).startDate()?.time ?: 0L

            val currentPeriodDate =
                getPeriodForPeriodTypeAndDate(
                    periodType ?: PeriodType.Daily,
                    currentDate,
                    0,
                ).startDate()?.time ?: 0L

            initialPeriodDate - currentPeriodDate
        }

        return runBlocking { result.await() }
    }

    private suspend fun getPeriodForPeriodTypeAndDate(
        periodType: PeriodType,
        currentDate: Date,
        offset: Int,
    ): Period {
        return withContext(dispatcherProvider.io()) {
            d2.periodModule().periodHelper().blockingGetPeriodForPeriodTypeAndDate(
                periodType,
                currentDate,
                offset,
            )
        }
    }

    override fun periodUIString(locale: Locale, period: Period): String {
        val formattedDate: String
        var periodString = DEFAULT_PERIOD
        when (period.periodType()) {
            PeriodType.Weekly,
            PeriodType.WeeklyWednesday,
            PeriodType.WeeklyThursday,
            PeriodType.WeeklySaturday,
            PeriodType.WeeklySunday,
            -> {
                periodString = DEFAULT_PERIOD_WEEK
                formattedDate = periodString.format(
                    weekOfTheYear(period.periodType()!!, period.periodId()!!),
                    formatDateWithCalendar(period.startDate()!!, DATE_FORMAT_EXPRESSION, locale),
                    formatDateWithCalendar(period.endDate()!!, DATE_FORMAT_EXPRESSION, locale),
                )
            }

            PeriodType.BiWeekly -> {
                formattedDate = ""
            }

            PeriodType.Monthly ->
                formattedDate = formatDateWithCalendar(period.startDate()!!, MONTHLY_FORMAT_EXPRESSION, locale)

            PeriodType.BiMonthly,
            PeriodType.Quarterly,
            PeriodType.QuarterlyNov,
            PeriodType.SixMonthly,
            PeriodType.SixMonthlyApril,
            PeriodType.FinancialApril,
            PeriodType.FinancialJuly,
            PeriodType.FinancialOct,
            -> formattedDate = periodString.format(
                formatDateWithCalendar(period.startDate()!!, MONTHLY_FORMAT_EXPRESSION, locale),
                formatDateWithCalendar(period.endDate()!!, MONTHLY_FORMAT_EXPRESSION, locale),
            )

            PeriodType.Yearly ->
                formattedDate = formatDateWithCalendar(period.startDate()!!, YEARLY_FORMAT_EXPRESSION, locale)

            else ->
                formattedDate = formatDateWithCalendar(period.startDate()!!, SIMPLE_DATE_FORMAT, locale)
        }
        return WordUtils.capitalize(formattedDate)
    }

    private fun weekOfTheYear(periodType: PeriodType, periodId: String): Int {
        val pattern =
            Pattern.compile(periodType.pattern)
        val matcher = pattern.matcher(periodId)
        var weekNumber = 0
        if (matcher.find()) {
            weekNumber = matcher.group(2)?.toInt() ?: 0
        }
        return weekNumber
    }

    override fun getPeriodDiff(initialPeriod: Period, currentPeriod: Period): Int {
        return when (initialPeriod.periodType()) {
            PeriodType.Daily -> Days.daysBetween(
                DateTime(initialPeriod.startDate()),
                DateTime(currentPeriod.startDate()),
            ).days

            PeriodType.Weekly,
            PeriodType.WeeklyWednesday,
            PeriodType.WeeklyThursday,
            PeriodType.WeeklySaturday,
            PeriodType.WeeklySunday,
            -> Weeks.weeksBetween(
                DateTime(initialPeriod.startDate()),
                DateTime(currentPeriod.startDate()),
            ).weeks

            PeriodType.BiWeekly -> Weeks.weeksBetween(
                DateTime(initialPeriod.startDate()),
                DateTime(currentPeriod.startDate()),
            ).weeks / 2

            PeriodType.Monthly -> Months.monthsBetween(
                DateTime(initialPeriod.startDate()),
                DateTime(currentPeriod.startDate()),
            ).months

            PeriodType.BiMonthly -> Months.monthsBetween(
                DateTime(initialPeriod.startDate()),
                DateTime(currentPeriod.startDate()),
            ).months / 2

            PeriodType.Quarterly,
            PeriodType.QuarterlyNov,
            -> Months.monthsBetween(
                DateTime(initialPeriod.startDate()),
                DateTime(currentPeriod.startDate()),
            ).months / 3

            PeriodType.SixMonthly,
            PeriodType.SixMonthlyApril,
            PeriodType.SixMonthlyNov,
            ->
                Months.monthsBetween(
                    DateTime(initialPeriod.startDate()),
                    DateTime(currentPeriod.startDate()),
                ).months / 6

            PeriodType.Yearly,
            PeriodType.FinancialApril,
            PeriodType.FinancialJuly,
            PeriodType.FinancialOct,
            PeriodType.FinancialNov,
            -> Years.yearsBetween(
                DateTime(initialPeriod.startDate()),
                DateTime(currentPeriod.startDate()),
            ).years

            null -> 0
        }
    }

    /**
     * Determines if Ethiopian calendar should be used.
     * This checks if the locale is Ethiopia or if there's a specific calendar setting.
     */
    private fun shouldUseEthiopianCalendar(locale: Locale): Boolean {
        // Check if the locale is Ethiopian (Amharic or Ethiopia)
        return locale.country.equals("ET", ignoreCase = true) ||
                locale.language.equals("am", ignoreCase = true)
    }

    /**
     * Formats a date using Ethiopian calendar if appropriate, otherwise uses Gregorian.
     */
    private fun formatDateWithCalendar(date: Date, formatExpression: String, locale: Locale): String {
        return if (shouldUseEthiopianCalendar(locale)) {
            formatEthiopianDate(date, formatExpression)
        } else {
            SimpleDateFormat(formatExpression, locale).format(date)
        }
    }

    /**
     * Formats a date using Ethiopian calendar with the appropriate format.
     */
    private fun formatEthiopianDate(date: Date, formatExpression: String): String {
        val ethDate = EthiopianDateConverter.gregorianToEthiopian(date)
        return when (formatExpression) {
            DATE_FORMAT_EXPRESSION -> String.format("%04d-%02d-%02d", ethDate.year, ethDate.month, ethDate.day)
            YEARLY_FORMAT_EXPRESSION -> ethDate.year.toString()
            SIMPLE_DATE_FORMAT -> String.format("%d/%d/%04d", ethDate.day, ethDate.month, ethDate.year)
            MONTHLY_FORMAT_EXPRESSION -> {
                // Use Ethiopian month names if available, otherwise fallback to numeric format
                val monthName = getEthiopianMonthName(ethDate.month)
                "$monthName ${ethDate.year}"
            }
            else -> String.format("%02d/%02d/%04d", ethDate.day, ethDate.month, ethDate.year)
        }
    }

    /**
     * Returns Ethiopian month name for the given month number (1-13).
     */
    private fun getEthiopianMonthName(month: Int): String {
        return when (month) {
            1 -> "Meskerem"
            2 -> "Tikimt"
            3 -> "Hidar"
            4 -> "Tahsas"
            5 -> "Tir"
            6 -> "Yekatit"
            7 -> "Megabit"
            8 -> "Miazia"
            9 -> "Ginbot"
            10 -> "Sene"
            11 -> "Hamle"
            12 -> "Nehase"
            13 -> "Pagume"
            else -> "Month $month"
        }
    }

    // TODO:Some of these strings need to be localized
    companion object {
        const val DATE_FORMAT_EXPRESSION = "yyyy-MM-dd"
        const val MONTHLY_FORMAT_EXPRESSION = "MMM yyyy"
        const val YEARLY_FORMAT_EXPRESSION = "yyyy"
        const val SIMPLE_DATE_FORMAT = "d/M/yyyy"
        const val DEFAULT_PERIOD = "%s - %s"
        const val DEFAULT_PERIOD_WEEK = "Week %d %s to %s"
        const val DEFAULT_PERIOD_BI_WEEK = "%d %s - %d %s"
    }
}
