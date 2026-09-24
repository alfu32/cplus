package cplus

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VersionTest {
    val version=Version()
    @BeforeEach
    fun setUp() {
        println("starting")
    }

    @AfterEach
    fun tearDown() {
        println("done")
    }

    @Test
    fun testToString() {
        println(version)
    }
    @Test
    fun testArrays() {
        val a=listOf(1,2,3,4,5,6,7,8,9,10)
        println(a);
        println(a.foldIndexed(""){ i,v,acc -> "$acc + $v"})
    }

}