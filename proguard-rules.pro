-keepnames class eu.kanade.tachiyomi.extension.all.komgagorse.KomgaGorse

-keepclassmembers class eu.kanade.tachiyomi.extension.all.komgagorse.KomgaGorse {
    public okhttp3.Request gorsePreferenceStatusRequest(eu.kanade.tachiyomi.source.model.SManga);
    public okhttp3.Request gorsePreferenceUpdateRequest(eu.kanade.tachiyomi.source.model.SManga, java.lang.String);
}
