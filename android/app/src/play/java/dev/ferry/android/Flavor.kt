package dev.ferry.android

/** Play build: no All files access, so a Mac's file browsing is answered with an error. */
object Flavor {
    // shortcut: no browsing on Play; let the user pick a folder with SAF (OpenDocumentTree) when Play users need it.
    val ALL_FILES = false
}
