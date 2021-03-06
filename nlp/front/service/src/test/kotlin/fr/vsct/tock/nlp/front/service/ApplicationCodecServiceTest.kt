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

package fr.vsct.tock.nlp.front.service

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.whenever
import fr.vsct.tock.nlp.front.shared.codec.ApplicationDump
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse

/**
 *
 */
class ApplicationCodecServiceTest : AbstractTest() {

    @Before
    fun before() {
        whenever(context.config.getApplicationByNamespaceAndName(any(), any())).thenReturn(app)
    }

    @Test
    fun import_existingApp_shouldNotCreateApp() {
        val dump = ApplicationDump(app)

        val report = ApplicationCodecService.import(namespace, dump)
        assertFalse(report.modified)
    }
}