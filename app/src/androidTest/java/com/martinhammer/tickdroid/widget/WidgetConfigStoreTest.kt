package com.martinhammer.tickdroid.widget

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WidgetConfigStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: WidgetConfigStore

    @Before fun setUp() { store = WidgetConfigStore(context).also { it.clear() } }
    @After fun tearDown() { store.clear() }

    @Test fun both_kinds_round_trip() {
        val button = WidgetConfig.Button(5)
        val selection = WidgetConfig.TrackSelection(mapOf(1L to false), TrackLabels.Short)
        store.put(1, button)
        store.put(2, selection)
        assertEquals(button, store.get(1))
        assertEquals(selection, store.get(2))
        assertNull(store.get(3))
    }

    @Test fun remove_and_clear() {
        store.put(1, WidgetConfig.Button(5))
        store.put(2, WidgetConfig.Button(6))
        store.put(3, WidgetConfig.Button(7))
        store.remove(intArrayOf(1, 2))
        assertNull(store.get(1))
        assertNull(store.get(2))
        assertEquals(WidgetConfig.Button(7), store.get(3))
        store.clear()
        assertNull(store.get(3))
    }

    @Test fun writes_bump_the_revision() {
        val before = store.revision.value
        store.put(1, WidgetConfig.Button(5))
        store.remove(intArrayOf(1))
        store.clear()
        assertTrue(store.revision.value >= before + 3)
    }
}
