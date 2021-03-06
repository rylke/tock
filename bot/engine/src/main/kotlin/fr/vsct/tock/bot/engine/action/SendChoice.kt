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

package fr.vsct.tock.bot.engine.action

import fr.vsct.tock.bot.definition.Intent
import fr.vsct.tock.bot.definition.IntentAware
import fr.vsct.tock.bot.definition.StoryStep
import fr.vsct.tock.bot.engine.BotBus
import fr.vsct.tock.bot.engine.dialog.EventState
import fr.vsct.tock.bot.engine.message.Choice
import fr.vsct.tock.bot.engine.message.Message
import fr.vsct.tock.bot.engine.user.PlayerId
import fr.vsct.tock.shared.Dice
import java.net.URLDecoder.decode
import java.net.URLEncoder.encode
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/**
 * A user choice (click on a button or direct action).
 */
class SendChoice(playerId: PlayerId,
                 applicationId: String,
                 recipientId: PlayerId,
                 val intentName: String,
                 val parameters: Map<String, String> = emptyMap(),
                 id: String = Dice.newId(),
                 date: Instant = Instant.now(),
                 state: EventState = EventState(),
                 metadata: ActionMetadata = ActionMetadata())
    : Action(playerId, recipientId, applicationId, id, date, state, metadata) {

    constructor(playerId: PlayerId,
                applicationId: String,
                recipientId: PlayerId,
                intentName: String,
                step: StoryStep,
                parameters: Map<String, String> = emptyMap(),
                id: String = Dice.newId(),
                date: Instant = Instant.now(),
                state: EventState = EventState(),
                metadata: ActionMetadata = ActionMetadata()) :
            this(playerId,
                    applicationId,
                    recipientId,
                    intentName,
                    parameters + (STEP_PARAMETER to step.name),
                    id,
                    date,
                    state,
                    metadata)

    companion object {

        const val TITLE_PARAMETER = "_title"
        const val URL_PARAMETER = "_url"
        const val IMAGE_PARAMETER = "_image"
        const val EXIT_INTENT = "_exit"
        const val STEP_PARAMETER = "_step"
        const val PREVIOUS_INTENT_PARAMETER = "_previous_intent"

        fun encodeChoiceId(
                bus: BotBus,
                intent: IntentAware,
                step: StoryStep? = null,
                parameters: Map<String, String> = emptyMap()): String {
            return encodeChoiceId(intent, step, parameters, bus.step, bus.dialog.state.currentIntent)
        }

        fun encodeChoiceId(
                intent: IntentAware,
                step: StoryStep? = null,
                parameters: Map<String, String> = emptyMap(),
                busStep: StoryStep? = null,
                currentIntent: Intent? = null): String {
            val currentStep = if (step == null) busStep else step
            return StringBuilder().apply {
                append(intent.wrappedIntent().name)
                val params = parameters +
                        listOfNotNull(
                                if (currentStep != null) STEP_PARAMETER to currentStep.name else null,
                                if (currentIntent != null && currentIntent != intent)
                                    PREVIOUS_INTENT_PARAMETER to currentIntent.name else null
                        )

                if (params.isNotEmpty()) {
                    params.map { e ->
                        "${encode(e.key, UTF_8.name())}=${encode(e.value, UTF_8.name())}"
                    }.joinTo(this, "&", "?")
                }
            }.toString()
        }

        fun decodeChoiceId(id: String): Pair<String, Map<String, String>> {
            val questionMarkIndex = id.indexOf("?")
            return if (questionMarkIndex == -1) {
                id to emptyMap()
            } else {
                id.substring(0, questionMarkIndex) to id.substring(questionMarkIndex + 1)
                        .split("&")
                        .map {
                            it.split("=")
                                    .let { decode(it[0], UTF_8.name()) to decode(it[1], UTF_8.name()) }
                        }.toMap()
            }
        }

    }

    override fun toMessage(): Message {
        return Choice(intentName, parameters)
    }

    fun step(): String? = parameters[STEP_PARAMETER]

    internal fun previousIntent(): String? = parameters[PREVIOUS_INTENT_PARAMETER]

    override fun toString(): String {
        return "SendChoice(intentName='$intentName', parameters=$parameters)"
    }


}