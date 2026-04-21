package com.metrolist.music.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ListExtTest {

    @Test
    fun `reversed true returns a reversed view`() {
        val list = listOf(1, 2, 3)
        assertEquals(listOf(3, 2, 1), list.reversed(true))
    }

    @Test
    fun `reversed false returns the original reference`() {
        val list = listOf(1, 2, 3)
        assertSame(list, list.reversed(false))
    }

    @Test
    fun `move forward shifts element and preserves size`() {
        val list = mutableListOf("a", "b", "c", "d")
        list.move(fromIndex = 0, toIndex = 2)
        assertEquals(listOf("b", "c", "a", "d"), list)
    }

    @Test
    fun `move backward shifts element and preserves size`() {
        val list = mutableListOf("a", "b", "c", "d")
        list.move(fromIndex = 3, toIndex = 1)
        assertEquals(listOf("a", "d", "b", "c"), list)
    }

    @Test
    fun `mergeNearbyElements collapses consecutive equal keys`() {
        val input = listOf("apple", "ant", "banana", "berry", "cherry")
        val merged = input.mergeNearbyElements(
            key = { it.first() },
            merge = { first, second -> "$first+$second" },
        )
        assertEquals(listOf("apple+ant", "banana+berry", "cherry"), merged)
    }

    @Test
    fun `mergeNearbyElements does not collapse non-adjacent duplicates`() {
        val input = listOf("apple", "banana", "ant")
        val merged = input.mergeNearbyElements(
            key = { it.first() },
            merge = { first, _ -> first },
        )
        // 'apple' and 'ant' share key 'a' but are not adjacent — no merge
        assertEquals(listOf("apple", "banana", "ant"), merged)
    }

    @Test
    fun `mergeNearbyElements empty list returns empty list`() {
        val result: List<Int> = emptyList<Int>().mergeNearbyElements()
        assertEquals(emptyList<Int>(), result)
    }

    @Test
    fun `mergeNearbyElements single element returns single element`() {
        assertEquals(listOf(42), listOf(42).mergeNearbyElements())
    }
}
