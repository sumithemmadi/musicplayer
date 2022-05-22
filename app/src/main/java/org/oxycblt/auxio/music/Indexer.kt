/*
 * Copyright (c) 2022 Auxio Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.database.getIntOrNull
import androidx.core.database.getStringOrNull
import org.oxycblt.auxio.music.excluded.ExcludedDatabase
import org.oxycblt.auxio.ui.Sort
import org.oxycblt.auxio.util.contentResolverSafe
import org.oxycblt.auxio.util.logD

/**
 * This class acts as the base for most the black magic required to get a remotely sensible music
 * indexing system while still optimizing for time. I would recommend you leave this module now
 * before you lose your sanity trying to understand the hoops I had to jump through for this system,
 * but if you really want to stay, here's a debrief on why this code is so awful.
 *
 * MediaStore is not a good API. It is not even a bad API. Calling it a bad API is an insult to
 * other bad android APIs, like CoordinatorLayout or InputMethodManager. No. MediaStore is a crime
 * against humanity and probably a way to summon Zalgo if you look at it the wrong way.
 *
 * You think that if you wanted to query a song's genre from a media database, you could just put
 * "genre" in the query and it would return it, right? But not with MediaStore! No, that's too
 * straightforward for this contract that was dropped on it's head as a baby. So instead, you have
 * to query for each genre, query all the songs in each genre, and then iterate through those songs
 * to link every song with their genre. This is not documented anywhere, and the O(mom im scared)
 * algorithm you have to run to get it working single-handedly DOUBLES Auxio's loading times. At no
 * point have the devs considered that this system is absolutely insane, and instead focused on
 * adding infuriat- I mean nice proprietary extensions to MediaStore for their own Google Play
 * Music, and of course every Google Play Music user knew how great that turned out!
 *
 * It's not even ergonomics that makes this API bad. It's base implementation is completely borked
 * as well. Did you know that MediaStore doesn't accept dates that aren't from ID3v2.3 MP3 files? I
 * sure didn't, until I decided to upgrade my music collection to ID3v2.4 and FLAC only to see that
 * the metadata parser has a brain aneurysm the moment it stumbles upon a dreaded TRDC or DATE tag.
 * Once again, this is because internally android uses an ancient in-house metadata parser to get
 * everything indexed, and so far they have not bothered to modernize this parser or even switch it
 * to something more powerful like Taglib, not even in Android 12. ID3v2.4 has been around for *21
 * years.* *It can drink now.* All of my what.
 *
 * Not to mention all the other infuriating quirks. Album artists can't be accessed from the albums
 * table, so we have to go for the less efficient "make a big query on all the songs lol" method so
 * that songs don't end up fragmented across artists. Pretty much every OEM has added some extension
 * or quirk to MediaStore that I cannot reproduce, with some OEMs (COUGHSAMSUNGCOUGH) crippling the
 * normal tables so that you're railroaded into their music app. The way I do blacklisting relies on
 * a semi-deprecated method, and the supposedly "modern" method is SLOWER and causes even more
 * problems since I have to manage databases across version boundaries. Sometimes music will have a
 * deformed clone that I can't filter out, sometimes Genres will just break for no reason, and
 * sometimes tags encoded in UTF-8 will be interpreted as anything from UTF-16 to Latin-1 to *Shift
 * JIS* WHY WHY WHY WHY WHY WHY WHY WHY WHY WHY WHY WHY WHY WHY WHY WHY WHY WHY
 *
 * Is there anything we can do about it? No. Google has routinely shut down issues that begged
 * google to fix glaring issues with MediaStore or to just take the API behind the woodshed and
 * shoot it. Largely because they have zero incentive to improve it given how "obscure" local music
 * listening is. As a result, some players like Vanilla and VLC just hack their own
 * pseudo-MediaStore implementation from their own (better) parsers, but this is both infeasible for
 * Auxio due to how incredibly slow it is to get a file handle from the android sandbox AND how much
 * harder it is to manage a database of your own media that mirrors the filesystem perfectly. And
 * even if I set aside those crippling issues and changed my indexer to that, it would face the even
 * larger problem of how google keeps trying to kill the filesystem and force you into their
 * ContentResolver API. In the future MediaStore could be the only system we have, which is also the
 * day that greenland melts and birthdays stop happening forever.
 *
 * I'm pretty sure nothing is going to happen and MediaStore will continue to be neglected and
 * probably deprecated eventually for a "new" API that just coincidentally excludes music indexing.
 * Because go screw yourself for wanting to listen to music you own. Be a good consoomer and listen
 * to your AlgoPop StreamMix™.
 *
 * I wish I was born in the neolithic.
 *
 * @author OxygenCobalt
 */
object Indexer {
    /**
     * The album_artist MediaStore field has existed since at least API 21, but until API 30 it was
     * a proprietary extension for Google Play Music and was not documented. Since this field
     * probably works on all versions Auxio supports, we suppress the warning about using a
     * possibly-unsupported constant.
     */
    @Suppress("InlinedApi")
    private const val AUDIO_COLUMN_ALBUM_ARTIST = MediaStore.Audio.AudioColumns.ALBUM_ARTIST

    fun index(context: Context): MusicStore.Library? {
        val songs = loadSongs(context)
        if (songs.isEmpty()) return null

        val albums = buildAlbums(songs)
        val artists = buildArtists(albums)
        val genres = readGenres(context, songs)

        // Sanity check: Ensure that all songs are linked up to albums/artists/genres.
        for (song in songs) {
            if (song._isMissingAlbum || song._isMissingArtist || song._isMissingGenre) {
                throw IllegalStateException(
                    "Found malformed song: ${song.rawName} [" +
                        "album: ${!song._isMissingAlbum} " +
                        "artist: ${!song._isMissingArtist} " +
                        "genre: ${!song._isMissingGenre}]")
            }
        }

        return MusicStore.Library(genres, artists, albums, songs)
    }

    /**
     * Does the initial query over the song database, including excluded directory checks. The songs
     * returned by this function are **not** well-formed. The companion [buildAlbums],
     * [buildArtists], and [readGenres] functions must be called with the returned list so that all
     * songs are properly linked up.
     */
    private fun loadSongs(context: Context): List<Song> {
        val excludedDatabase = ExcludedDatabase.getInstance(context)
        var selector = "${MediaStore.Audio.Media.IS_MUSIC}=1"
        val args = mutableListOf<String>()

        // Apply the excluded directories by filtering out specific DATA values.
        // DATA was deprecated in Android 10, but it was un-deprecated in Android 12L,
        // so it's probably okay to use it. The only reason we would want to use
        // another method is for external partitions support, but there is no demand for that.
        for (path in excludedDatabase.readPaths()) {
            selector += " AND ${MediaStore.Audio.Media.DATA} NOT LIKE ?"
            args += "$path%" // Append % so that the selector properly detects children
        }

        var songs = mutableListOf<Song>()

        context.contentResolverSafe.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Audio.AudioColumns._ID,
                    MediaStore.Audio.AudioColumns.TITLE,
                    MediaStore.Audio.AudioColumns.DISPLAY_NAME,
                    MediaStore.Audio.AudioColumns.TRACK,
                    MediaStore.Audio.AudioColumns.DURATION,
                    MediaStore.Audio.AudioColumns.YEAR,
                    MediaStore.Audio.AudioColumns.ALBUM,
                    MediaStore.Audio.AudioColumns.ALBUM_ID,
                    MediaStore.Audio.AudioColumns.ARTIST,
                    AUDIO_COLUMN_ALBUM_ARTIST,
                    MediaStore.Audio.AudioColumns.DATA),
                selector,
                args.toTypedArray(),
                null)
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.TITLE)
                val fileIndex =
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DISPLAY_NAME)
                val trackIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.TRACK)
                val durationIndex =
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DURATION)
                val yearIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.YEAR)
                val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ALBUM)
                val albumIdIndex =
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ALBUM_ID)
                val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ARTIST)
                val albumArtistIndex = cursor.getColumnIndexOrThrow(AUDIO_COLUMN_ALBUM_ARTIST)
                val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val title = cursor.getString(titleIndex)

                    // Try to use the DISPLAY_NAME field to obtain a (probably sane) file name
                    // from the android system. Once again though, OEM issues get in our way and
                    // this field isn't available on some platforms. In that case, see if we can
                    // grok a file name from the DATA field.
                    val fileName =
                        cursor.getStringOrNull(fileIndex)
                            ?: cursor
                                .getStringOrNull(dataIndex)
                                ?.substringAfterLast('/', MediaStore.UNKNOWN_STRING)
                                ?: MediaStore.UNKNOWN_STRING

                    // The TRACK field is for some reason formatted as DTTT, where D is the disk
                    // and T is the track. This is dumb and insane and forces me to mangle track
                    // numbers above 1000, but there is nothing we can do that won't break the app
                    // below API 30.
                    var track: Int? = null
                    var disc: Int? = null

                    val rawTrack = cursor.getIntOrNull(trackIndex)
                    if (rawTrack != null) {
                        track = rawTrack % 1000
                        disc = rawTrack / 1000
                        if (disc == 0) {
                            // A disc number of 0 means that there is no disc.
                            disc = null
                        }
                    }

                    val duration = cursor.getLong(durationIndex)
                    val year = cursor.getIntOrNull(yearIndex)

                    val album = cursor.getString(albumIndex)
                    val albumId = cursor.getLong(albumIdIndex)

                    // If the artist field is <unknown>, make it null. This makes handling the
                    // insanity of the artist field easier later on.
                    val artist =
                        cursor.getStringOrNull(artistIndex)?.run {
                            if (this == MediaStore.UNKNOWN_STRING) {
                                null
                            } else {
                                this
                            }
                        }

                    val albumArtist = cursor.getStringOrNull(albumArtistIndex)

                    songs.add(
                        Song(
                            title,
                            fileName,
                            duration,
                            track,
                            disc,
                            id,
                            year,
                            album,
                            albumId,
                            artist,
                            albumArtist,
                        ))
                }
            }

        // Deduplicate songs to prevent (most) deformed music clones
        songs =
            songs
                .distinctBy {
                    it.rawName to
                        it._mediaStoreAlbumName to
                        it._mediaStoreArtistName to
                        it._mediaStoreAlbumArtistName to
                        it.track to
                        it.durationMs
                }
                .toMutableList()

        logD("Successfully loaded ${songs.size} songs")

        return songs
    }

    /**
     * Group songs up into their respective albums. Instead of using the unreliable album or artist
     * databases, we instead group up songs by their *lowercase* artist and album name to create
     * albums. This serves two purposes:
     * 1. Sometimes artist names can be styled differently, e.g "Rammstein" vs. "RAMMSTEIN". This
     * makes sure both of those are resolved into a single artist called "Rammstein"
     * 2. Sometimes MediaStore will split album IDs up if the songs differ in format. This ensures
     * that all songs are unified under a single album.
     *
     * This does come with some costs, it's far slower than using the album ID itself, and it may
     * result in an unrelated album art being selected depending on the song chosen as the template,
     * but it seems to work pretty well.
     */
    private fun buildAlbums(songs: List<Song>): List<Album> {
        val albums = mutableListOf<Album>()
        val songsByAlbum = songs.groupBy { it._albumGroupingId }

        for (entry in songsByAlbum) {
            val albumSongs = entry.value

            // Use the song with the latest year as our metadata song.
            // This allows us to replicate the LAST_YEAR field, which is useful as it means that
            // weird years like "0" wont show up if there are alternatives.
            // Note: Normally we could want to use something like maxByWith, but apparently
            // that does not exist in the kotlin stdlib yet.
            val comparator = Sort.NullableComparator<Int>()
            var templateSong = albumSongs[0]
            for (i in 1..albumSongs.lastIndex) {
                val candidate = albumSongs[i]
                if (comparator.compare(templateSong.track, candidate.track) < 0) {
                    templateSong = candidate
                }
            }

            val albumName = templateSong._mediaStoreAlbumName
            val albumYear = templateSong._mediaStoreYear
            val albumCoverUri =
                ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    templateSong._mediaStoreAlbumId)
            val artistName = templateSong._artistGroupingName

            albums.add(
                Album(
                    albumName,
                    albumYear,
                    albumCoverUri,
                    albumSongs,
                    artistName,
                ))
        }

        logD("Successfully built ${albums.size} albums")

        return albums
    }

    /**
     * Group up albums into artists. This also requires a de-duplication step due to some edge cases
     * where [buildAlbums] could not detect duplicates.
     */
    private fun buildArtists(albums: List<Album>): List<Artist> {
        val artists = mutableListOf<Artist>()
        val albumsByArtist = albums.groupBy { it._artistGroupingId }

        for (entry in albumsByArtist) {
            val templateAlbum = entry.value[0]
            val artistName =
                when (templateAlbum._artistGroupingName) {
                    MediaStore.UNKNOWN_STRING -> null
                    else -> templateAlbum._artistGroupingName
                }
            val artistAlbums = entry.value

            artists.add(Artist(artistName, artistAlbums))
        }

        logD("Successfully built ${artists.size} artists")

        return artists
    }

    /**
     * Read all genres and link them up to the given songs. This is the code that requires me to
     * make dozens of useless queries just to link genres up.
     */
    private fun readGenres(context: Context, songs: List<Song>): List<Genre> {
        val genres = mutableListOf<Genre>()

        context.contentResolverSafe.query(
                MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
                null,
                null,
                null)
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)

                while (cursor.moveToNext()) {
                    // Genre names can be a normal name, an ID3v2 constant, or null. Normal names
                    // are resolved as usual, but null values don't make sense and are often junk
                    // anyway, so we skip genres that have them.
                    val id = cursor.getLong(idIndex)
                    val name = cursor.getStringOrNull(nameIndex) ?: continue
                    val genreSongs = queryGenreSongs(context, id, songs) ?: continue

                    genres.add(Genre(name, genreSongs))
                }
            }

        val songsWithoutGenres = songs.filter { it._isMissingGenre }
        if (songsWithoutGenres.isNotEmpty()) {
            // Songs that don't have a genre will be thrown into an unknown genre.
            val unknownGenre = Genre(null, songsWithoutGenres)

            genres.add(unknownGenre)
        }

        logD("Successfully loaded ${genres.size} genres")

        return genres
    }

    /**
     * Queries the genre songs for [genreId]. Some genres are insane and don't contain songs for
     * some reason, so if that's the case then this function will return null.
     */
    private fun queryGenreSongs(context: Context, genreId: Long, songs: List<Song>): List<Song>? {
        val genreSongs = mutableListOf<Song>()

        // Don't even bother blacklisting here as useless iterations are less expensive than IO
        context.contentResolverSafe.query(
                MediaStore.Audio.Genres.Members.getContentUri("external", genreId),
                arrayOf(MediaStore.Audio.Genres.Members._ID),
                null,
                null,
                null)
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres.Members._ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    songs.find { it._mediaStoreId == id }?.let { song -> genreSongs.add(song) }
                }
            }

        return genreSongs.ifEmpty { null }
    }
}
