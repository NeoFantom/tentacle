package dev.tentacle

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLanguage {
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = Settings(context).language
        if (tag.isEmpty()) return context
        val locale = Locale.forLanguageTag(tag)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        return context.createConfigurationContext(configuration)
    }

    fun currentTag(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.let {
            if (it.isEmpty) "" else it[0].toLanguageTag()
        }
    } else Settings(context).language

    fun set(context: Context, tag: String) {
        Settings(context).language = tag
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        }
        ServerService.refreshNotificationChannel(context.applicationContext)
        dev.tentacle.update.AppUpdater.refreshNotificationChannel(context.applicationContext)
    }
}
