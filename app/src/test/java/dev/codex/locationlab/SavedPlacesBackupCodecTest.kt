package dev.codex.locationlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPlacesBackupCodecTest {
    @Test
    fun roundTripsAndExcludesRealLocationHistory() {
        val source = SavedPlacesBackup(
            home = BackupPlace("Home", 12.9716, 77.5946),
            favorites = listOf(BackupPlace("Studio", 13.0358, 77.5970), BackupPlace("Park", 12.9352, 77.6245))
        )
        val json = SavedPlacesBackupCodec.encode(source)
        assertEquals(source, SavedPlacesBackupCodec.decode(json))
        assertTrue("An export must only contain deliberately saved places.",
            !json.contains("last_real") && !json.contains("real_location"))
    }

    @Test
    fun rejectsForeignFilesOutOfRangeCoordinatesAndEmptyNames() {
        assertThrows(IllegalArgumentException::class.java) {
            SavedPlacesBackupCodec.decode("""{"product":"Other","schemaVersion":1,"favorites":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SavedPlacesBackupCodec.decode("""{"product":"GeoShift","schemaVersion":1,"home":{"name":"Bad","latitude":91,"longitude":0},"favorites":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SavedPlacesBackupCodec.decode("""{"product":"GeoShift","schemaVersion":1,"home":{"name":" ","latitude":1,"longitude":2},"favorites":[]}""")
        }
    }

    @Test
    fun decodesAnAbsentHomeAsNoHome() {
        val restored = SavedPlacesBackupCodec.decode("""{"product":"GeoShift","schemaVersion":1,"home":null,"favorites":[]}""")
        assertEquals(SavedPlacesBackup(null, emptyList()), restored)
    }
}
