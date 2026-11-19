package com.martinhammer.tickdroid.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class ValueCellTextTest {

    private val uk = Locale.forLanguageTag("en-GB")

    // A stand-in for ICU's CompactDecimalFormat: thousands as "1.2K".
    private val compact: (BigDecimal) -> String = { v ->
        val k = v.divide(BigDecimal(1000))
        if (v.abs() >= BigDecimal(1000)) k.setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "K" else v.toPlainString()
    }

    private fun fit(milli: Int, places: Int, cell: Float, scale: Float = 1f) =
        numberCellText(milli, places, cell, scale, uk, compact)

    // The mock-up C values at the Journal's cell sizes (High ~28dp, Medium ~36dp, Low ~44dp).

    @Test fun `a short value keeps its decimals, as large as fits`() {
        assertEquals(CellText("10.4", 16f), fit(10400, 1, 44f))
        assertEquals(CellText("72.4", 13f), fit(72400, 1, 36f))
        assertEquals(CellText("10.4", 9f), fit(10400, 1, 28f))
        assertEquals(CellText("-3.5", 16f), fit(-3500, 1, 36f))
    }

    @Test fun `a longer value steps down in size before it loses its decimals`() {
        assertEquals(CellText("1204.5", 16f), fit(1204500, 1, 64f))
        assertEquals(CellText("1204.5", 11f), fit(1204500, 1, 44f))
        assertEquals(CellText("1204.5", 9f), fit(1204500, 1, 36f))
        assertEquals(CellText("1205", 9f), fit(1204500, 1, 28f))
    }

    @Test fun `then the short form, at the largest size that fits`() {
        assertEquals(CellText("12840", 9f), fit(12840000, 0, 36f))
        assertEquals(CellText("12.8K", 9f), fit(12840000, 0, 28f))
    }

    @Test fun `a larger font scale steps down sooner`() {
        assertEquals(CellText("10.4", 11f), fit(10400, 1, 36f, scale = 1.3f))
        assertEquals(CellText("10", 13f), fit(10400, 1, 28f, scale = 1.3f))
        assertEquals(CellText("1205", 9f), fit(1204500, 1, 36f, scale = 1.3f))
    }

    @Test fun `a place is never shortened`() {
        assertEquals(CellText("2", 16f), placeCellText(2, 28f * PlaceCircleFraction, 1f))
        assertEquals("12", placeCellText(12, 28f * PlaceCircleFraction, 2f).text)
    }

    @Test fun `Today's control drops the unit before shrinking the value`() {
        assertEquals(ControlFit(16f, showUnit = true), todayNumberFit("8,204", "steps", 96f, 1f))
        assertEquals(ControlFit(16f, showUnit = false), todayNumberFit("1,204,567.5", "km", 96f, 1f))
        assertEquals(ControlFit(13f, showUnit = false), todayNumberFit("12,345,678.9", "", 96f, 1f))
    }
}
