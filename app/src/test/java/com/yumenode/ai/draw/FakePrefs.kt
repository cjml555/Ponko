package com.yumenode.ai.draw

import android.content.SharedPreferences
import java.io.File

/**
 * SharedPreferences en memoria para los tests JVM.
 *
 * El store solo usa getString / edit().putString / edit().remove, asi que
 * basta con implementar esa superficie sin depender de Android.
 */
class FakePrefs(
    private val values: MutableMap<String, String?> = HashMap()
) : SharedPreferences {

    fun snapshot(): Map<String, String?> = HashMap(values)

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    override fun getString(key: String?, defValue: String?): String? =
        if (values.containsKey(key)) values[key] else defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        defValues

    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun edit(): SharedPreferences.Editor = Editor(this, values)

    /** Editor mínimo: putString / remove / clear. */
    class Editor(
        private val prefs: FakePrefs,
        private val values: MutableMap<String, String?>,
    ) : SharedPreferences.Editor {
        private val pending = HashMap<String, String?>()
        private val removed = HashSet<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String?>?): SharedPreferences.Editor {
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String): SharedPreferences.Editor {
            removed.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            applyEdits()
            return true
        }

        override fun apply() = applyEdits()

        private fun applyEdits() {
            if (clearAll) values.clear()
            removed.forEach { values.remove(it) }
            pending.forEach { (k, v) -> values[k] = v }
        }
    }
}
