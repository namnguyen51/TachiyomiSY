package eu.kanade.tachiyomi.data.backup.legacy

import android.content.Context
import android.net.Uri
import com.github.salomonbrys.kotson.fromJson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.AbstractBackupRestore
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.MANGAS
import eu.kanade.tachiyomi.data.backup.legacy.models.DHistory
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.database.models.TrackImpl
import eu.kanade.tachiyomi.source.Source
import exh.EXHMigrations
import java.util.Date

class LegacyBackupRestore(context: Context, notifier: BackupNotifier) : AbstractBackupRestore<LegacyBackupManager>(context, notifier) {

    override suspend fun performRestore(uri: Uri): Boolean {
        // SY -->
        throttleManager.resetThrottle()
        // SY <--
        val reader = JsonReader(context.contentResolver.openInputStream(uri)!!.bufferedReader())
        val json = JsonParser.parseReader(reader).asJsonObject

        val version = json.get(Backup.VERSION)?.asInt ?: 1
        backupManager = LegacyBackupManager(context, version)

        val mangasJson = json.get(MANGAS).asJsonArray
        restoreAmount = mangasJson.size() + 3 // +1 for categories, +1 for saved searches, +1 for merged manga references

        // Restore categories
        json.get(Backup.CATEGORIES)?.let { restoreCategories(it) }

        // SY -->
        json.get(Backup.SAVEDSEARCHES)?.let { restoreSavedSearches(it) }

        json.get(Backup.MERGEDMANGAREFERENCES)?.let { restoreMergedMangaReferences(it) }
        // SY <--

        // Store source mapping for error messages
        sourceMapping = LegacyBackupRestoreValidator.getSourceMapping(json)

        // Restore individual manga
        mangasJson.forEach {
            if (job?.isActive != true) {
                return false
            }

            restoreManga(it.asJsonObject)
        }

        return true
    }

    private fun restoreCategories(categoriesJson: JsonElement) {
        db.inTransaction {
            backupManager.restoreCategories(categoriesJson.asJsonArray)
        }

        restoreProgress += 1
        showRestoreProgress(restoreProgress, restoreAmount, context.getString(R.string.categories))
    }

    // SY -->
    private fun restoreSavedSearches(savedSearchesJson: JsonElement) {
        backupManager.restoreSavedSearches(savedSearchesJson)

        restoreProgress += 1
        showRestoreProgress(restoreProgress, restoreAmount, context.getString(R.string.saved_searches))
    }

    private fun restoreMergedMangaReferences(mergedMangaReferencesJson: JsonElement) {
        db.inTransaction {
            backupManager.restoreMergedMangaReferences(mergedMangaReferencesJson.asJsonArray)
        }

        restoreProgress += 1
        showRestoreProgress(restoreProgress, restoreAmount, context.getString(R.string.merged_references))
    }
    // SY <--

    private suspend fun restoreManga(mangaJson: JsonObject) {
        val manga = backupManager.parser.fromJson<MangaImpl>(
            mangaJson.get(
                Backup.MANGA
            )
        )
        val chapters = backupManager.parser.fromJson<List<ChapterImpl>>(
            mangaJson.get(Backup.CHAPTERS)
                ?: JsonArray()
        )
        val categories = backupManager.parser.fromJson<List<String>>(
            mangaJson.get(Backup.CATEGORIES)
                ?: JsonArray()
        )
        val history = backupManager.parser.fromJson<List<DHistory>>(
            mangaJson.get(Backup.HISTORY)
                ?: JsonArray()
        )
        val tracks = backupManager.parser.fromJson<List<TrackImpl>>(
            mangaJson.get(Backup.TRACK)
                ?: JsonArray()
        )

        // EXH -->
        EXHMigrations.migrateBackupEntry(manga)
        // <-- EXH

        val source = backupManager.sourceManager.get(manga.source)
        val sourceName = sourceMapping[manga.source] ?: manga.source.toString()

        try {
            if (source != null) {
                restoreMangaData(manga, source, chapters, categories, history, tracks)
            } else {
                errors.add(Date() to "${manga.title} [$sourceName]: ${context.getString(R.string.source_not_found_name, sourceName)}")
            }
        } catch (e: Exception) {
            errors.add(Date() to "${manga.title} [$sourceName]: ${e.message}")
        }

        restoreProgress += 1
        showRestoreProgress(restoreProgress, restoreAmount, manga.title)
    }

    /**
     * Returns a manga restore observable
     *
     * @param manga manga data from json
     * @param source source to get manga data from
     * @param chapters chapters data from json
     * @param categories categories data from json
     * @param history history data from json
     * @param tracks tracking data from json
     */
    private suspend fun restoreMangaData(
        manga: Manga,
        source: Source,
        chapters: List<Chapter>,
        categories: List<String>,
        history: List<DHistory>,
        tracks: List<Track>
    ) {
        val dbManga = backupManager.getMangaFromDatabase(manga)

        db.inTransaction {
            if (dbManga == null) {
                // Manga not in database
                restoreMangaFetch(source, manga, chapters, categories, history, tracks)
            } else { // Manga in database
                // Copy information from manga already in database
                backupManager.restoreMangaNoFetch(manga, dbManga)
                // Fetch rest of manga information
                restoreMangaNoFetch(source, manga, chapters, categories, history, tracks)
            }
        }
    }

    /**
     * Fetches manga information.
     *
     * @param manga manga that needs updating
     * @param chapters chapters of manga that needs updating
     * @param categories categories that need updating
     */
    private suspend fun restoreMangaFetch(
        source: Source,
        manga: Manga,
        chapters: List<Chapter>,
        categories: List<String>,
        history: List<DHistory>,
        tracks: List<Track>
    ) {
        try {
            val fetchedManga = backupManager.fetchManga(source, manga)
            fetchedManga.id ?: return

            updateChapters(source, fetchedManga, chapters)

            restoreExtraForManga(fetchedManga, categories, history, tracks)

            updateTracking(fetchedManga, tracks)
        } catch (e: Exception) {
            errors.add(Date() to "${manga.title} - ${e.message}")
        }
    }

    private suspend fun restoreMangaNoFetch(
        source: Source,
        backupManga: Manga,
        chapters: List<Chapter>,
        categories: List<String>,
        history: List<DHistory>,
        tracks: List<Track>
    ) {
        if (!backupManager.restoreChaptersForManga(backupManga, chapters)) {
            updateChapters(source, backupManga, chapters)
        }

        restoreExtraForManga(backupManga, categories, history, tracks)

        updateTracking(backupManga, tracks)
    }

    private fun restoreExtraForManga(manga: Manga, categories: List<String>, history: List<DHistory>, tracks: List<Track>) {
        // Restore categories
        backupManager.restoreCategoriesForManga(manga, categories)

        // Restore history
        backupManager.restoreHistoryForManga(history)

        // Restore tracking
        backupManager.restoreTrackForManga(manga, tracks)
    }
}
