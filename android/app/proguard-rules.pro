# StateStore and HistoryStore read JSON from older APKs using Gson reflection.
# Preserve only the persisted schemas and field names; methods can still be optimized.
-keepattributes Signature,InnerClasses,EnclosingMethod

-keep,allowoptimization class ua.svitlo.app.domain.AppState { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.SavedAddress { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.AddressItem { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.VoeIncidentAddress { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.Schedule { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.ScheduleDay { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.Slot { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.CurrentOutage { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.CachedSchedule { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.FetchAttempt { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.OutageObservation { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.SyncStats { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.TrackingSession { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.HistoryInterval { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.HistoryTail { <fields>; }
-keep,allowoptimization class ua.svitlo.app.domain.WidgetConfig { <fields>; }
-keep enum ua.svitlo.app.domain.PowerState { *; }
-keep enum ua.svitlo.app.domain.WidgetStyle { *; }
-keep enum ua.svitlo.app.domain.FetchFailure { *; }

# Existing WorkManager rows contain these class names across app upgrades.
-keepnames class ua.svitlo.app.background.*Worker
-keepclassmembers class ua.svitlo.app.background.*Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room instantiates WorkManager's generated database through a no-arg constructor.
-keep class androidx.work.impl.WorkDatabase_Impl { public <init>(); }
