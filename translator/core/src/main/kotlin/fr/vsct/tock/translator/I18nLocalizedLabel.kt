/*
 * Copyright (C) 2017 VSCT
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.vsct.tock.translator

import fr.vsct.tock.shared.Dice
import java.util.Locale

/**
 *
 */
data class I18nLocalizedLabel(val locale: Locale,
                              val interfaceType: UserInterfaceType,
                              val label: String,
                              val validated: Boolean,
                              val connectorId: String? = null,
                              val alternatives: List<String> = emptyList()) {

    constructor(locale: Locale, interfaceType: UserInterfaceType, label: String, alternatives: List<String>) : this(locale, interfaceType, label, false, null, alternatives)

    constructor(locale: Locale, interfaceType: UserInterfaceType, label: String) : this(locale, interfaceType, label, emptyList())

    fun randomText(): String {
        return if (alternatives.isEmpty()) {
            label
        } else {
            Dice.choose(listOf(label) + alternatives)
        }
    }
}