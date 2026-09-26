package org.tidefell.android.folders

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import java.io.File

/**
 * Folders in the phone's shared storage (ADR 0025). The engine reads and writes them by path,
 * which needs All files access; app storage needs no permission.
 */
object PhoneStorage {
    /** A place to start browsing from. */
    data class Place(val label: String, val dir: File)

    /** True once the user has granted All files access. */
    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    /** Opens the system screen where the user grants this app All files access. */
    fun accessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    /** The camera's folder, if the phone has one with files in it. */
    fun cameraFolder(): File? {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        return File(dcim, "Camera").takeIf { dir -> dir.listFiles()?.any { it.isFile } == true }
    }

    /**
     * Where the picker starts: the camera folder first, when there is one, since it is the
     * folder most people add and where the phone's own photos are; then each storage volume.
     */
    fun places(context: Context): List<Place> {
        val volumes = context.getSystemService(StorageManager::class.java).storageVolumes
            .mapNotNull { volume -> volume.directory?.let { Place(volume.getDescription(context), it) } }
        return ordered(cameraFolder()?.let { Place("Camera", it) }, volumes)
    }

    /** [camera] first if present, then [volumes]. */
    fun ordered(camera: Place?, volumes: List<Place>): List<Place> = listOfNotNull(camera) + volumes

    /** The folders directly inside [dir], by name, without hidden ones or the engine's own. */
    fun subfolders(dir: File): List<File> =
        dir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    /** True if [dir] is inside [root] or is [root], by path alone, without touching the disk. */
    fun isWithin(dir: File, root: File): Boolean {
        val d = dir.absoluteFile.normalize()
        val r = root.absoluteFile.normalize()
        return d == r || d.path.startsWith(r.path + File.separator)
    }
}
