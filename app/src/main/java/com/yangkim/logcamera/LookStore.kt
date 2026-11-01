package com.yangkim.logcamera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 내가 만든 LUT, 가져온 .cube, 선택된 LUT를 저장/관리. */
class LookStore(private val ctx: Context) {
    private val lookDir = File(ctx.filesDir, "looks").apply { mkdirs() }
    private val cubeDir = File(ctx.filesDir, "cubes").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("logcam", Context.MODE_PRIVATE)
    private val cubeCache = HashMap<String, Cube>()

    fun all(): List<Look> = Look.builtins() + userLooks()

    fun userLooks(): List<Look> {
        val files = lookDir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull {
            try { Look.fromJson(JSONObject(it.readText())) } catch (e: Exception) { null }
        }.sortedBy { it.createdAt }
    }

    fun save(look: Look) {
        File(lookDir, look.id + ".json").writeText(look.toJson().toString())
    }

    fun delete(look: Look) {
        if (look.builtin) return
        File(lookDir, look.id + ".json").delete()
        val cube = look.baseCube ?: return
        if (userLooks().none { it.baseCube == cube }) {
            File(cubeDir, cube).delete()
            synchronized(cubeCache) { cubeCache.remove(cube) }
        }
    }

    fun selected(): Look {
        val id = prefs.getString("selected", null)
        return all().firstOrNull { it.id == id } ?: Look.builtins().first()
    }

    fun setSelected(id: String) {
        prefs.edit().putString("selected", id).apply()
    }

    fun loadCube(name: String): Cube? = synchronized(cubeCache) {
        cubeCache[name] ?: try {
            Cube.parse(File(cubeDir, name).readText()).also { cubeCache[name] = it }
        } catch (e: Exception) {
            null
        }
    }

    /** .cube 텍스트를 가져와 새 룩으로 등록. 형식이 틀리면 예외. */
    fun importCube(displayName: String, text: String): Look {
        val cube = Cube.parse(text)
        val id = UUID.randomUUID().toString()
        val fname = "$id.cube"
        File(cubeDir, fname).writeText(text)
        synchronized(cubeCache) { cubeCache[fname] = cube }
        val name = displayName.removeSuffix(".cube").removeSuffix(".CUBE").ifBlank { "가져온 LUT" }
        val look = Look(id, name, false, fname, LookParams.neutral(), System.currentTimeMillis())
        save(look)
        return look
    }

    fun newLook(name: String, from: Look): Look {
        val look = Look(
            UUID.randomUUID().toString(), name, false, from.baseCube,
            from.params.copy(), System.currentTimeMillis()
        )
        save(look)
        return look
    }

    /** 다운로드/LogCamera 폴더에 .cube 로 내보내기. */
    fun exportCube(look: Look): Uri? {
        val base = look.baseCube?.let { loadCube(it) }
        val data = LookEngine.build(look.params, base, LookEngine.LUT_SIZE)
        val text = Cube.toText(look.name, LookEngine.LUT_SIZE, data)
        val safe = look.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$safe.cube")
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LogCamera")
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: return null
        return uri
    }
}
