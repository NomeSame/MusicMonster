package com.nomesame.musicmonster
import org.junit.Assert.*
import org.junit.Test
class SongRenameRulesTest {
 @Test fun keepsExtensionAndRemovesPastedDuplicate() {
  assertEquals("new.MP3", SongRenameRules.fileName(" new.mp3 ", "old.MP3"))
  assertEquals("new.part.flac", SongRenameRules.fileName("new.part", "old.flac"))
  assertEquals("new", SongRenameRules.fileName("new", "old"))
 }
 @Test fun rejectsPathsReservedNamesAndEmptyInput() {
  listOf("", " ", ".", "..", "../test", "a\\b", "a:b", "a?b", "a|b", "a*", "a\nb", "CON", "aux", "LPT9", "end.").forEach {
   assertNull(it, SongRenameRules.fileName(it, "old.mp3"))
  }
 }
 @Test fun boundsActualUtf8Bytes() {
  assertNotNull(SongRenameRules.fileName("a".repeat(236), "old.mp3"))
  assertNull(SongRenameRules.fileName("a".repeat(237), "old.mp3"))
  assertNotNull(SongRenameRules.fileName("\u00e4".repeat(118), "old.mp3"))
  assertNull(SongRenameRules.fileName("\u00e4".repeat(119), "old.mp3"))
 }
}
