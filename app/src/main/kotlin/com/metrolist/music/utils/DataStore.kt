/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.metrolist.music.extensions.toEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.properties.ReadOnlyProperty

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * In-memory mirror of the Preferences DataStore.
 *
 * Why: the legacy `get(key)` operator was implemented as `runBlocking(IO) { data.first() }`
 * and is called ~500 times across the app, many of them from Composable init paths and
 * Service lifecycle methods on the main thread. Each call subscribes to the flow, awaits
 * first emission, unsubscribes — a few ms per call, but cumulative (and the very first
 * call blocks on disk I/O for 200ms-2s).
 *
 * After [installSnapshotCollector] runs, every subsequent read is a Map lookup. Reads
 * that happen before the collector has emitted still fall back to the original blocking
 * path, so behavior is unchanged for early-boot reads.
 */
@Volatile
private var prefsSnapshot: Preferences? = null

fun installPreferencesSnapshotCollector(
    scope: CoroutineScope,
    dataStore: DataStore<Preferences>,
) {
    scope.launch(Dispatchers.IO) {
        dataStore.data.collect { prefsSnapshot = it }
    }
}

operator fun <T> DataStore<Preferences>.get(key: Preferences.Key<T>): T? {
    prefsSnapshot?.let { return it[key] }
    return runBlocking(Dispatchers.IO) {
        data.first()[key]
    }
}

fun <T> DataStore<Preferences>.get(
    key: Preferences.Key<T>,
    defaultValue: T,
): T {
    prefsSnapshot?.let { return it[key] ?: defaultValue }
    return runBlocking(Dispatchers.IO) {
        data.first()[key] ?: defaultValue
    }
}

fun <T> preference(
    context: Context,
    key: Preferences.Key<T>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key] ?: defaultValue }

inline fun <reified T : Enum<T>> enumPreference(
    context: Context,
    key: Preferences.Key<String>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key].toEnum(defaultValue) }

@Composable
fun <T> rememberPreference(
    key: Preferences.Key<T>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val state =
        remember {
            context.dataStore.data
                .map { it[key] ?: defaultValue }
                .distinctUntilChanged()
        }.collectAsState(context.dataStore[key] ?: defaultValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    coroutineScope.launch {
                        context.dataStore.edit {
                            it[key] = value
                        }
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}

@Composable
inline fun <reified T : Enum<T>> rememberEnumPreference(
    key: Preferences.Key<String>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val initialValue = context.dataStore[key].toEnum(defaultValue = defaultValue)
    val state =
        remember {
            context.dataStore.data
                .map { it[key].toEnum(defaultValue = defaultValue) }
                .distinctUntilChanged()
        }.collectAsState(initialValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    coroutineScope.launch {
                        context.dataStore.edit {
                            it[key] = value.name
                        }
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}
