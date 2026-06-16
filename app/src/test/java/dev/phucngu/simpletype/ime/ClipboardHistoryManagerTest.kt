package dev.phucngu.simpletype.ime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardHistoryManagerTest {

    private lateinit var manager: ClipboardHistoryManager
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        val prefs = ctx.getSharedPreferences("clipboard_history", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        manager = ClipboardHistoryManager(ctx)
    }

    @Test
    fun testAddItem() {
        manager.addItem("Hello")
        val items = manager.getItems()
        assertEquals(1, items.size)
        assertEquals("Hello", items[0].text)
    }

    @Test
    fun testRemoveDuplicatesAndMoveToTop() {
        manager.addItem("A")
        manager.addItem("B")
        manager.addItem("A")
        val items = manager.getItems()
        assertEquals(2, items.size)
        assertEquals("A", items[0].text)
        assertEquals("B", items[1].text)
    }

    @Test
    fun testPinning() {
        manager.addItem("Pinned")
        val id = manager.getItems()[0].id
        manager.togglePin(id)
        assertTrue(manager.getItems()[0].isPinned)
        
        manager.togglePin(id)
        assertFalse(manager.getItems()[0].isPinned)
    }

    @Test
    fun testPinnedItemsSortToTop() {
        manager.addItem("A")
        manager.addItem("B")
        manager.addItem("C") // order: C, B, A
        // Pin A (currently at the bottom)
        val idA = manager.getItems().first { it.text == "A" }.id
        manager.togglePin(idA)

        val items = manager.getItems()
        assertEquals("A", items[0].text)
        assertTrue(items[0].isPinned)
        // Unpinned items keep newest-first order below the pinned one
        assertEquals("C", items[1].text)
        assertEquals("B", items[2].text)
    }

    @Test
    fun testNewItemStaysBelowPinned() {
        manager.addItem("Old")
        val id = manager.getItems()[0].id
        manager.togglePin(id)
        manager.addItem("New")

        val items = manager.getItems()
        assertEquals("Old", items[0].text) // pinned stays on top
        assertEquals("New", items[1].text)
    }

    @Test
    fun testDeleteItem() {
        manager.addItem("Delete me")
        val id = manager.getItems()[0].id
        manager.deleteItem(id)
        assertTrue(manager.getItems().isEmpty())
    }
}
