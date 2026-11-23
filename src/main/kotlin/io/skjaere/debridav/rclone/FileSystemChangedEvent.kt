package io.skjaere.debridav.rclone

/**
 * Emitted when files change in the virtual filesystem so external caches
 * (currently rclone's VFS via the RC API) can be refreshed.
 *
 * `paths` holds the directories to refresh — typically the parent of the
 * affected file. A move includes both source and destination parents so
 * a single event represents the whole operation.
 */
data class FileSystemChangedEvent(val paths: Set<String>)
