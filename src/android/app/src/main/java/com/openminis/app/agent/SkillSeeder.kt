package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.logging.AppLogger
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * minisYf：把内置技能包（assets/skills-seed.zip）铺到 skillsDir，装完即有技能。
 *
 * 设计要点：
 *  - **只在首次**跑：铺完写 marker 文件；marker 存在就直接返回，不做目录比对，
 *    否则每次启动都要 walk 650+ 文件。
 *  - **不覆盖已有文件**：用户可能改过某个 SKILL.md 或自己装过同名技能，
 *    内置包只补缺，不做“版本升级覆盖”。想强制刷新就把 [MARKER] 改个版本号。
 *  - **后台线程 + 防目录穿越**：zip entry 名来自 assets 但也不能信任；
 *    canonicalPath 必须落在 skillsDir 里。
 *  - 铺完调 `requestReload` 让 UI 立刻看到，不用等下次前台。
 */
object SkillSeeder {

    private const val TAG = "SkillSeed"
    private const val ASSET_NAME = "skills-seed.zip"

    /**
     * 内置技能包的版本标记。**改造技能包内容时必须改这里**，否则老设备
     * marker 命中就跳过，永远拿不到新内容（比如这版清理掉「仅限授权测试」
     * 说教之前的旧 SKILL.md）。
     */
    private const val MARKER = ".seeded-v4"

    fun seedIfNeeded(context: Context, repo: SkillRepository) {
        val dir = File(context.filesDir, "minis-global/skills")
        val marker = File(dir, MARKER)
        if (marker.exists()) return

        Thread({
            try {
                dir.mkdirs()
                var written = 0
                val root = dir.canonicalPath + File.separator
                context.assets.open(ASSET_NAME).use { raw ->
                    ZipInputStream(BufferedInputStream(raw)).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val name = entry.name
                            if (name.endsWith("/")) {
                                zip.closeEntry()
                                continue
                            }
                            val out = File(dir, name)
                            if (!out.canonicalPath.startsWith(root)) {
                                // 路径穿越，直接丢弃
                                AppLogger.warning(TAG, "skip suspicious entry: $name")
                                zip.closeEntry()
                                continue
                            }
                            // minisYf: 不再「只补缺」——marker 版本变了才会进到这里，
                            // 说明技能包内容更新了（比如清掉了带授权说教的旧版本文本）。
                            // 只补缺会让老设备永远留着旧内容，那就等于没更新。
                            // 用户自己新建的技能不在 zip 里，不受影响。
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zip.copyTo(it) }
                            written++
                            zip.closeEntry()
                        }
                    }
                }
                marker.writeText("1")
                AppLogger.info(TAG, "seeded $written files")
                repo.requestReload("skill-seed")
            } catch (t: Throwable) {
                // 铺失败不阻塞启动：marker 不写，下次启动重试
                AppLogger.warning(TAG, "seed failed: ${t.message}")
            }
        }, "minisyf-skill-seed").start()
    }
}
