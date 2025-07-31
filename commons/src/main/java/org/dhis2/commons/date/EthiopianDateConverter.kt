package org.dhis2.commons.date

import java.util.Calendar
import java.util.Date

object EthiopianDateConverter {
    // Ethiopian calendar constants
    private const val ETH_TO_GREG_OFFSET = 8 // Ethiopian year is typically 7-8 years behind Gregorian
    
    // Ethiopian months have 30 days each, except the 13th month (Pagume) which has 5 or 6 days
    private val ETHIOPIAN_MONTH_NAMES = arrayOf(
        "መስከረም", "ጥቅምት", "ህዳር", "ታህሳስ", "ጥር", "የካቲት",
        "መጋቢት", "ሚያዝያ", "ግንቦት", "ሰኔ", "ሐምሌ", "ነሐሴ", "ጳጉሜ"
    )

    /**
     * Converts Ethiopian date to Gregorian Calendar
     */
    fun toGregorian(ethYear: Int, ethMonth: Int, ethDay: Int): Calendar {
        val gregDate = Calendar.getInstance()
        
        // Ethiopian New Year (1 Meskerem) typically falls on September 11 (12 in leap year)
        // Calculate the number of days from Ethiopian epoch
        val ethiopianDays = (ethYear - 1) * 365 + ((ethYear - 1) / 4) + // years and leap days
                          (ethMonth - 1) * 30 + ethDay - 1 // months and days
        
        // Set to Ethiopian New Year base date (September 11, for the given Gregorian year)
        val baseYear = ethYear + ETH_TO_GREG_OFFSET
        gregDate.set(baseYear, Calendar.SEPTEMBER, 11, 0, 0, 0)
        gregDate.set(Calendar.MILLISECOND, 0)
        
        // Add the calculated days
        val totalDaysFromNewYear = (ethMonth - 1) * 30 + ethDay - 1
        gregDate.add(Calendar.DAY_OF_YEAR, totalDaysFromNewYear)
        
        return gregDate
    }

    /**
     * Converts Gregorian Calendar to Ethiopian date (year, month, day)
     */
    fun toEthiopian(gregDate: Calendar): Triple<Int, Int, Int> {
        val year = gregDate.get(Calendar.YEAR)
        val month = gregDate.get(Calendar.MONTH) // 0-based
        val day = gregDate.get(Calendar.DAY_OF_MONTH)
        
        // Determine Ethiopian year based on Gregorian date
        val ethYear = if (month >= Calendar.SEPTEMBER) {
            // After September, we're in the new Ethiopian year
            year - ETH_TO_GREG_OFFSET + 1
        } else {
            // Before September, we're still in the previous Ethiopian year
            year - ETH_TO_GREG_OFFSET
        }
        
        // Calculate Ethiopian New Year for this Ethiopian year
        val ethiopianNewYear = Calendar.getInstance()
        ethiopianNewYear.set(year - (if (month >= Calendar.SEPTEMBER) 0 else 1), Calendar.SEPTEMBER, 11, 0, 0, 0)
        ethiopianNewYear.set(Calendar.MILLISECOND, 0)
        
        // Calculate days difference from Ethiopian New Year
        val diffInMillis = gregDate.timeInMillis - ethiopianNewYear.timeInMillis
        val daysSinceNewYear = (diffInMillis / (24 * 60 * 60 * 1000)).toInt()
        
        // Calculate Ethiopian month and day
        val ethMonth = (daysSinceNewYear / 30) + 1
        val ethDay = (daysSinceNewYear % 30) + 1
        
        // Handle edge cases for the 13th month (Pagume)
        if (ethMonth > 13) {
            return Triple(ethYear + 1, 1, ethDay - 30)
        }
        
        return Triple(ethYear, ethMonth, ethDay)
    }

    /**
     * Converts Java Date to Ethiopian date
     */
    fun gregorianToEthiopian(date: Date): Triple<Int, Int, Int> {
        val calendar = Calendar.getInstance()
        calendar.time = date
        return toEthiopian(calendar)
    }

    /**
     * Formats Ethiopian date as string
     */
    fun formatEthiopianDate(ethYear: Int, ethMonth: Int, ethDay: Int): String {
        return String.format("%02d/%02d/%04d", ethDay, ethMonth, ethYear)
    }

    /**
     * Gets Ethiopian month name
     */
    fun getEthiopianMonthName(month: Int): String {
        return if (month in 1..13) ETHIOPIAN_MONTH_NAMES[month - 1] else "Unknown"
    }
}