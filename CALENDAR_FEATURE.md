# BeeKeep Calendar — v0.8.0

## Included
- Native Android calendar month view with previous/next month navigation.
- Today shortcut and selected-day agenda.
- Dots indicate open tasks on calendar dates.
- Create one-time scheduled tasks with date/time, optional hive number, and reminder toggle.
- Quick templates: Inspect hive, Mite wash, Check stores, Feed hive.
- Overdue task highlighting and one-tap completion.
- Native notification permission prompt on Android 13+.
- AlarmManager-backed reminders with WorkManager fallback.
- Reminder alarms are recreated after device reboot.

## Mobile behavior
All state remains in BeeKeep's local Room database first. Calendar creation never waits for network connectivity. Cloud sync can carry the task afterward when available.
