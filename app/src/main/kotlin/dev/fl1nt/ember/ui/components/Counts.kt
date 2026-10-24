package dev.fl1nt.ember.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import java.text.NumberFormat

/** A plural with its count grouped for the current locale, such as "1,480 uploaded". */
@Composable
fun countString(id: Int, count: Int): String = pluralStringResource(id, count, NumberFormat.getIntegerInstance().format(count))
