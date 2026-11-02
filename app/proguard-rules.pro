# AndroidX WorkManager initializes Room from an AndroidX Startup provider,
# before MainActivity.onCreate. R8 full mode can strip the generated public
# zero-argument constructor required by Room's reflective database factory.
# Exactly the same keep rules are used by N2K02/LocalAdb.
-keepattributes Signature,*Annotation*
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}
# WorkManager may also reflectively construct InputMergers during recovery.
-keep class * extends androidx.work.InputMerger {
    public <init>();
}
