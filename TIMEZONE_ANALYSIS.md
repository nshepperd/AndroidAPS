# Timezone Usage Analysis - AndroidAPS

## 1. Core Time Infrastructure

### DateUtilImpl (Central Utility)

**File:** `shared/impl/src/main/kotlin/app/aaps/shared/impl/utils/DateUtilImpl.kt`

All time handling flows through this class. Key design:

- Internal timestamps are **always UTC milliseconds** (Unix epoch via `System.currentTimeMillis()` / `Clock.millis()`)
- A `systemZone` property (`ZoneId.systemDefault()`) handles all local-time conversions
- Provides UTC methods (`toISOString`, `toISOAsUTC`) and local methods (`dateString`, `timeString`)

### T (Duration Helper)

**File:** `core/data/src/main/kotlin/app/aaps/core/data/time/T.kt`

Unit-conversion wrapper: `T.mins(5).msecs()` = 300000. Used extensively for converting between Nightscout's minute-based UTC offsets and internal millisecond offsets.

### Midnight Utilities

- **MidnightTime** (`core/interfaces/.../utils/MidnightTime.kt`) — epoch millis of local midnight; used for daily report boundaries
- **MidnightUtils** (`core/utils/.../MidnightUtils.kt`) — seconds-from-local-midnight; critical for profile lookups

---

## 2. Database Storage: UTC + Offset Pattern

Every database entity implements `DBEntryWithTime`:

```kotlin
interface DBEntryWithTime {
    var timestamp: Long      // UTC milliseconds since epoch
    var utcOffset: Long      // Device timezone offset in milliseconds
}
```

Default for `utcOffset` on all 19 entities:

```kotlin
override var utcOffset: Long = TimeZone.getDefault().getOffset(timestamp).toLong()
```

This captures the local timezone offset (including DST state) when the record is created.

**Affected entities:** `GlucoseValue`, `Bolus`, `Carbs`, `TemporaryBasal`, `TemporaryTarget`, `ExtendedBolus`, `ProfileSwitch`, `EffectiveProfileSwitch`, `TherapyEvent`, `BolusCalculatorResult`, `APSResult`, `DeviceStatus`, `UserEntry`, `HeartRate`, `StepsCount`, `TotalDailyDose`, `PreferenceChange`, `VersionChange`, `RunningMode`.

**Key point:** `timestamp` is always UTC. `utcOffset` is metadata recording the user's timezone but never shifts the timestamp itself.

---

## 3. Where Local Time Is Used for Logic (Not Just Display)

### a. Profile Access (CRITICAL — Safety-Relevant)

Basal rates, I:C ratios, and ISF are time-of-day blocks summing to 24 hours. Accessing the correct block requires local time:

```kotlin
// In OpenAPSSMBPlugin.kt and OpenAPSAutoISFPlugin.kt:
profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight())
```

`MidnightUtils.secondsFromMidnight()` uses `ZoneId.systemDefault()`. This is the most safety-critical timezone-dependent code path.

### b. DST Transition Handling

`DstHelperPlugin` (`plugins/constraints/.../DstHelperPlugin.kt`):
- **Warns** the user 12 hours before a DST change
- **Suspends the loop** for 3 hours after a DST change
- Detects via `Calendar.DST_OFFSET` comparison

### c. Daily Statistics Boundaries

`MidnightTime.calc()` uses local midnight for TDD calculations, daily reports, and graph rendering.

### d. Pump Basal Profile Matching

Medtronic's `BasalProfile.getEntryForTime()` converts UTC timestamps to local time-of-day for pump profile lookup.

---

## 4. External System Interactions

### a. Nightscout (NSClientV3)

| Field | Format | Direction |
|-------|--------|-----------|
| `date` | UTC milliseconds (Long) | Both |
| `utcOffset` | **Minutes** | Both |
| `created_at` | ISO-8601 string | Outbound (legacy) |
| `srvCreated`/`srvModified` | UTC milliseconds | Inbound |

**Conversion code** in `plugins/sync/.../nsclientV3/extensions/` (8+ files):
- Inbound: `utcOffset = T.mins(utcOffset ?: 0L).msecs()` (minutes → ms)
- Outbound: `utcOffset = T.msecs(utcOffset).mins()` (ms → minutes)

### b. Tidepool

`BaseElement.kt` sends three time representations per record:
- `time` = `dateUtil.toISOAsUTC(timestamp)` — UTC ISO string
- `deviceTime` = `dateUtil.toISONoZone(timestamp)` — local time, no zone
- `timezoneOffset` = `dateUtil.getTimeZoneOffsetMinutes(timestamp)` — minutes

### c. OpenHumans

- Timestamps as UTC milliseconds
- `utcOffset` in **milliseconds** (unlike Nightscout's minutes)
- File names formatted in UTC

### d. xDrip (Local Broadcast)

Android broadcast intents with UTC millisecond timestamps serialized via `.toJson(dateUtil)`.

### e. Dexcom CGM

`DexcomPlugin.kt:98` — timestamps in **seconds**, converted: `it.getLong("timestamp") * 1000`

### f. Garmin

Uses `java.time.Instant` and `java.time.Duration` — standard UTC.

---

## 5. Pump Driver Timezone Handling

| Pump | Time Format | DST Capable | Key Detail |
|------|------------|-------------|------------|
| **Dana RS** | UTC or local (configurable) | Only if `usingUTC` | Sends/receives UTC + zone offset; falls back to local for older firmware |
| **Medtrum** | Custom epoch (2014-01-01) | Yes | `SetTimeZonePacket` sends offset in minutes; special handling for zones > GMT+12 |
| **Omnipod Eros** | Joda-Time `DateTimeZone` | No | Stores timezone at pod activation; ignores DST in `TimeUtil.toDuration` |
| **Omnipod Dash** | java.time `Instant` | Yes | Modern API |
| **Medtronic** | Local time | No | Basal profile lookup uses `toLocalTime()` |
| **Combo V2** | — | Yes | Handles DST natively |
| **Diaconn G8** | UTC + hourly offset | No | Converts via Joda `DateTime.withZone(DateTimeZone.UTC)` |
| **Insight** | Component fields (y/m/d/h/m/s) | No | No timezone awareness |
| **Equil** | — | No | — |
| **EOPatch** | — | No | — |

`canHandleDST()` on the `Pump` interface signals DST capability. Pumps returning `false` trigger `DstHelperPlugin` loop suspension.

---

## 6. Conversion Map

| Conversion | Method | Location |
|-----------|--------|----------|
| UTC ms → ISO UTC string | `dateUtil.toISOString()` | `DateUtilImpl.kt:64-68` |
| UTC ms → ISO UTC (Tidepool) | `dateUtil.toISOAsUTC()` | `DateUtilImpl.kt:70-73` |
| UTC ms → ISO local (no zone) | `dateUtil.toISONoZone()` | `DateUtilImpl.kt:75-79` |
| ISO string → UTC ms | `dateUtil.fromISODateString()` | `DateUtilImpl.kt:48-62` |
| UTC ms → local display | `dateUtil.dateString()`, `timeString()` | `DateUtilImpl.kt` (uses `systemZone`) |
| UTC ms → seconds from local midnight | `MidnightUtils.secondsFromMidnight()` | `MidnightUtils.kt:19-25` |
| UTC ms → local midnight epoch | `MidnightTime.calc()` | `MidnightTime.kt` |
| UTC ms → UTC midnight epoch | `dateUtil.timeStampToUtcDateMillis()` | `DateUtilImpl.kt:451-452` |
| Merge UTC date + local time | `dateUtil.mergeUtcDateToTimestamp()` | `DateUtilImpl.kt:457-468` |
| TZ offset (DST-aware) | `dateUtil.getTimeZoneOffsetMinutes()` | `DateUtilImpl.kt:309-312` |
| Nightscout min ↔ internal ms | `T.mins().msecs()` / `T.msecs().mins()` | NSClientV3 extensions |
| Pump time → system time (Medtrum) | `MedtrumTimeUtil.convertPumpTimeToSystemTimeMillis()` | `MedtrumTimeUtil.kt:17-22` |
| Phone TZ → pump TZ (Dana) | `DateTime.withZone(DateTimeZone.UTC)` | `DanaRSService.kt:233-237` |

---

## 7. Risks and Observations

1. **UTC offset unit inconsistency**: Nightscout uses minutes, OpenHumans uses milliseconds, internal storage uses milliseconds. The `T` class bridges this.

2. **Profile time-of-day is the most safety-critical local-time path.** If `ZoneId.systemDefault()` returns an incorrect zone (phone misconfigured during travel), basal/ISF/I:C lookups return wrong values.

3. **Pumps that can't handle DST** (Medtronic, Insight, Equil, EOPatch, Diaconn) rely on `DstHelperPlugin` to suspend the loop — users who ignore warnings risk incorrect dosing.

4. **Legacy Joda-Time** still used in Dana and Omnipod Eros drivers alongside the modern `java.time` API. The two handle DST differently.

5. **The `utcOffset` default** (`TimeZone.getDefault().getOffset(timestamp)`) uses the phone's timezone setting — if a record is created with the wrong phone timezone, the offset is incorrect (though the UTC timestamp itself stays correct).
