# Ethiopian Calendar Support in Aggregates

## Problem Solved

Previously, dates in the aggregates/analytics module were always displayed in Gregorian calendar format, even when the app was configured for Ethiopian calendar usage. This has been fixed.

## Solution

The fix automatically detects when Ethiopian calendar should be used and formats aggregate dates accordingly.

## How It Works

### Automatic Detection
The system now checks the device locale:
- **Ethiopian locale** (`am_ET` or `am`): Uses Ethiopian calendar
- **Other locales**: Uses Gregorian calendar (unchanged behavior)

### Date Conversion
When Ethiopian calendar is detected:
- Gregorian dates are converted to Ethiopian dates using the existing `EthiopianDateConverter`
- Ethiopian month names are used (Meskerem, Tir, Hidar, etc.)
- Ethiopian years are displayed (e.g., 2015 instead of 2023)

### Supported Period Types
All period types in aggregates now support Ethiopian calendar:
- Daily periods
- Weekly periods  
- Monthly periods
- Quarterly periods
- Yearly periods
- Custom date ranges

## Examples

### Before Fix
- Monthly period: "Jan 2023" (always Gregorian)
- Date range: "2023-01-15 to 2023-02-14" (always Gregorian)

### After Fix
**For Ethiopian locale (am_ET):**
- Monthly period: "Tir 2015" (Ethiopian)
- Date range: "2015-05-06 to 2015-06-06" (Ethiopian format)

**For other locales (e.g., en_US):**
- Monthly period: "Jan 2023" (Gregorian - unchanged)
- Date range: "2023-01-15 to 2023-02-14" (Gregorian - unchanged)

## Files Changed

1. **`dhis_android_analytics/src/main/java/dhis2/org/analytics/charts/providers/PeriodStepProviderImpl.kt`**
   - Added Ethiopian calendar detection logic
   - Added Ethiopian date formatting methods
   - Updated period string generation to use appropriate calendar

2. **Tests added** to verify both Ethiopian and Gregorian formatting work correctly

## Backward Compatibility

This change is fully backward compatible:
- Existing installations continue to work unchanged
- Only Ethiopian locale users see the new Ethiopian calendar formatting
- All other locales maintain existing Gregorian behavior

## Technical Details

The implementation reuses the existing `EthiopianDateConverter` from the `commons` module, ensuring consistency with other parts of the app that already support Ethiopian calendar.