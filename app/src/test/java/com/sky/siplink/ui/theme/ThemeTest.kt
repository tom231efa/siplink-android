package com.sky.siplink.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemeTest {
    /** The roles that dynamic colour takes from the system. */
    private val accent = setOf("Primary", "OnPrimary", "PrimaryContainer", "OnPrimaryContainer")

    /** Every colour role of [scheme] by its name ("Surface", "OnPrimary", …), new roles included. */
    private fun roles(scheme: ColorScheme): Map<String, Long> = ColorScheme::class.java.methods
        .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }
        .associate { it.name.removePrefix("get").substringBefore('-') to it.invoke(scheme) as Long }

    /** Stands in for the system's scheme: Material's baseline, purple-blue on every surface. */
    private fun system(dark: Boolean) = if (dark) darkColorScheme() else lightColorScheme()

    @Test
    fun onlyTheAccentComesFromTheSystem() {
        for (dark in listOf(false, true)) {
            val own = roles(appColorScheme(dark))
            val system = roles(system(dark))
            val merged = roles(withSystemAccent(appColorScheme(dark), system(dark)))
            assertEquals(own.keys, merged.keys)
            for ((role, value) in merged) {
                val expected = if (role in accent) system.getValue(role) else own.getValue(role)
                assertEquals("$role (dark: $dark)", expected, value)
            }
        }
    }

    @Test
    fun theSystemSchemeOfTheTestDiffersWhereItMatters() {
        // Otherwise the test above could not tell the two apart. (White on the accent is the same in both.)
        for (dark in listOf(false, true)) {
            val own = roles(appColorScheme(dark))
            val system = roles(system(dark))
            listOf("Primary", "PrimaryContainer", "OnPrimaryContainer", "Background", "Surface", "SurfaceContainer", "SurfaceContainerHigh", "SecondaryContainer", "Outline").forEach {
                assertNotEquals("$it (dark: $dark)", own.getValue(it), system.getValue(it))
            }
        }
    }
}
