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

package fr.vsct.tock.bot.connector.ga

import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.base.Throwables
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.cache.RemovalNotification
import fr.vsct.tock.bot.connector.ConnectorBase
import fr.vsct.tock.bot.connector.ga.model.GAIntent
import fr.vsct.tock.bot.connector.ga.model.request.GARequest
import fr.vsct.tock.bot.connector.ga.model.response.GABasicCard
import fr.vsct.tock.bot.connector.ga.model.response.GAExpectedInput
import fr.vsct.tock.bot.connector.ga.model.response.GAFinalResponse
import fr.vsct.tock.bot.connector.ga.model.response.GAInputPrompt
import fr.vsct.tock.bot.connector.ga.model.response.GAItem
import fr.vsct.tock.bot.connector.ga.model.response.GAResponse
import fr.vsct.tock.bot.connector.ga.model.response.GAResponseMetadata
import fr.vsct.tock.bot.connector.ga.model.response.GARichResponse
import fr.vsct.tock.bot.connector.ga.model.response.GAStatus
import fr.vsct.tock.bot.connector.ga.model.response.GAStatusCode.INTERNAL
import fr.vsct.tock.bot.connector.ga.model.response.GAStatusDetail
import fr.vsct.tock.bot.engine.ConnectorController
import fr.vsct.tock.bot.engine.action.Action
import fr.vsct.tock.bot.engine.action.SendSentence
import fr.vsct.tock.bot.engine.event.Event
import fr.vsct.tock.bot.engine.user.PlayerId
import fr.vsct.tock.bot.engine.user.PlayerType
import fr.vsct.tock.bot.engine.user.UserPreferences
import fr.vsct.tock.shared.error
import fr.vsct.tock.shared.jackson.mapper
import io.vertx.ext.web.RoutingContext
import mu.KotlinLogging
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 *
 */
class GAConnector internal constructor(
        val applicationId: String,
        val path: String) : ConnectorBase(GAConnectorProvider.connectorType) {

    //internal for tests
    internal data class ActionWithDelay(val action: Action, val delayInMs: Long = 0)

    //internal for tests
    internal data class RoutingContextHolder(
            val context: RoutingContext,
            val request: GARequest,
            val actions: MutableList<ActionWithDelay> = CopyOnWriteArrayList()) {

        private fun GARichResponse.merge(simpleResponse: GAItem?): GARichResponse =
                if (simpleResponse == null) {
                    this
                } else {
                    copy(
                            items = listOf(simpleResponse) + items,
                            suggestions = suggestions,
                            linkOutSuggestion = linkOutSuggestion
                    )
                }


        private fun GARichResponse.merge(other: GARichResponse): GARichResponse =
                copy(
                        items = mergeItems(items + other.items),
                        suggestions = suggestions + other.suggestions,
                        linkOutSuggestion = linkOutSuggestion ?: other.linkOutSuggestion
                )


        private fun GABasicCard.merge(other: GABasicCard): GABasicCard =
                copy(
                        title = title ?: other.title,
                        subtitle = subtitle ?: other.subtitle,
                        formattedText = formattedText ?: other.formattedText,
                        image = image ?: other.image,
                        buttons = if (buttons.isNotEmpty()) buttons else other.buttons
                )

        //the first has to be a simple response
        //cf https://developers.google.com/actions/reference/rest/Shared.Types/AppResponse#RichResponse
        private fun mergeItems(items: List<GAItem>): List<GAItem> =
                if (items.size < 2) {
                    items
                } else {
                    listOf(items.first()) +
                            items.takeLast(items.size - 1).reduce { a, b ->
                                if (a.basicCard != null && b.basicCard != null) {
                                    GAItem(basicCard = a.basicCard.merge(b.basicCard))
                                } else {
                                    a
                                }
                            }
                }

        fun buildResponse(): GAResponse {
            val texts =
                    actions
                            .filter { it.action is SendSentence && it.action.text != null }
                            .mapIndexed { i, a ->
                                val s = a.action as SendSentence
                                val text = s.text!!
                                if (i == 0) {
                                    simpleResponseWithoutTranslate(text)
                                } else {
                                    simpleResponseWithoutTranslate(text)
                                            .run {
                                                if (a.delayInMs != 0L && ssml != null) {
                                                    copy(
                                                            ssml = "<break time=\"${a.delayInMs}ms\"/>" + ssml
                                                    )
                                                } else {
                                                    this
                                                }
                                            }
                                }
                            }

            val simpleResponse = if (texts.isNotEmpty()) {
                GAItem(
                        texts.reduce { s, t ->
                            s.copy(
                                    textToSpeech = concat(s.textToSpeech, t.textToSpeech),
                                    ssml = concat(s.ssml, t.ssml),
                                    displayText = concat(s.displayText ?: s.textToSpeech, t.displayText ?: t.textToSpeech)
                            )
                        }.run {
                            val newSSML = ssml
                                    ?.replace("<speak>", "", true)
                                    ?.replace("</speak>", "", true)
                                    ?.run { if (isBlank()) null else "<speak>${this}</speak>" }
                            copy(
                                    textToSpeech = if (newSSML.isNullOrBlank()) textToSpeech else null,
                                    ssml = newSSML,
                                    displayText = displayText?.run { if (isBlank()) null else this }
                            )
                        }
                )
            } else {
                null
            }

            val connectorMessages = actions.map { it.action }
                    .filterIsInstance<SendSentence>()
                    .mapNotNull {
                        (it.message(gaConnectorType) as GAResponseConnectorMessage?)
                    }

            var finalResponse: GAFinalResponse? = null
            var expectedInput: GAExpectedInput? = null

            if (connectorMessages.any { it.finalResponse != null }) {
                finalResponse =
                        connectorMessages
                                .first { it.finalResponse != null }
                                .finalResponse!!
                                .run {
                                    copy(
                                            richResponse = richResponse.merge(simpleResponse)
                                    )
                                }
            } else {
                val message: GAExpectedInput? =
                        connectorMessages
                                .mapNotNull { it.expectedInput }
                                .run {
                                    if (isEmpty())
                                        null
                                    else reduce { a, b ->
                                        a.copy(
                                                inputPrompt = a.inputPrompt.copy(
                                                        richInitialPrompt = a.inputPrompt.richInitialPrompt.merge(b.inputPrompt.richInitialPrompt)
                                                ),
                                                possibleIntents = a.possibleIntents + b.possibleIntents.filter { ib -> a.possibleIntents.none { ia -> ia.intent == ib.intent } })
                                    }
                                }

                expectedInput = if (message == null) {
                    if (simpleResponse == null) {
                        logger.warn { "no simple response for $this" }
                        null
                    } else {
                        GAExpectedInput(
                                GAInputPrompt(
                                        GARichResponse(
                                                listOf(simpleResponse))))
                    }
                } else {
                    if (simpleResponse == null) {
                        message
                    } else {
                        message.copy(
                                inputPrompt = message.inputPrompt.copy(
                                        richInitialPrompt = message.inputPrompt.richInitialPrompt.merge(simpleResponse)
                                )
                        )
                    }
                }?.run {
                    if (possibleIntents.none { it.intent == GAIntent.text }) {
                        copy(possibleIntents = listOf(expectedTextIntent()) + possibleIntents)
                    } else {
                        this
                    }
                }
            }

            context.response().putHeader("Google-Actions-API-Version", "2")

            return GAResponse(
                    request.conversation.conversationToken ?: "",
                    finalResponse == null,
                    if (expectedInput == null) null else listOf(expectedInput),
                    finalResponse,
                    null,
                    null, //GAResponseMetadata(GAStatus(0, "OK")),
                    request.isInSandbox
            )
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val currentMessages: Cache<String, RoutingContextHolder> = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .removalListener { e: RemovalNotification<String, RoutingContextHolder> ->
                if (e.wasEvicted()) {
                    logger.error { "request not handled for user ${e.key} : ${e.value.actions}" }
                }
            }
            .build()

    override fun register(controller: ConnectorController) {
        controller.registerServices(path, { router ->
            logger.info("deploy rest google assistant services for root path $path ")

            router.post(path).blockingHandler({ context ->
                try {
                    handleRequest(controller, context, context.bodyAsString)
                } catch (e: Throwable) {
                    context.sendTechnicalError(e)
                }
            }, false)
        })
    }

    private fun RoutingContext.sendTechnicalError(
            throwable: Throwable,
            requestBody: String? = null,
            request: GARequest? = null
    ) {
        try {
            logger.error(throwable)
            response().end(
                    mapper.writeValueAsString(
                            GAResponse(
                                    request?.conversation?.conversationToken ?: "",
                                    false,
                                    emptyList(),
                                    null,
                                    null,
                                    GAResponseMetadata(
                                            GAStatus(
                                                    INTERNAL,
                                                    throwable.message ?: "error",
                                                    listOf(
                                                            GAStatusDetail(
                                                                    Throwables.getStackTraceAsString(throwable),
                                                                    requestBody,
                                                                    request
                                                            )
                                                    )
                                            )
                                    ),
                                    false
                            )
                    )
            )
        } catch (t: Throwable) {
            logger.error(t)
        }
    }

    //internal for tests
    internal fun handleRequest(controller: ConnectorController,
                               context: RoutingContext,
                               body: String) {
        try {
            logger.debug { "Google Assistant request input : $body" }
            val request: GARequest = mapper.readValue(body)
            try {
                val event = WebhookActionConverter.toEvent(request, applicationId)
                val userId = request.user.userId
                try {
                    currentMessages.put(userId, RoutingContextHolder(context, request))
                    controller.handle(event)
                } catch (t: Throwable) {
                    logger.error(t)
                    send(controller.errorMessage(
                            PlayerId(userId, PlayerType.user),
                            applicationId,
                            PlayerId(applicationId, PlayerType.bot)))
                } finally {
                    if (!sendAnswer(userId)) {
                        context.sendTechnicalError(IllegalStateException("no answer found for user $userId"), body, request)
                    }
                }
            } catch (t: Throwable) {
                context.sendTechnicalError(t, body, request)
            }
        } catch (t: Throwable) {
            context.sendTechnicalError(t, body)
        }
    }

    private fun sendAnswer(userId: String): Boolean {
        return currentMessages.getIfPresent(userId)
                ?.let {
                    currentMessages.invalidate(userId)
                    sendResponse(it)
                    true
                }
                ?: false
    }

    override fun send(event: Event, delayInMs: Long) {
        if (event is Action) {
            val response = currentMessages.getIfPresent(event.recipientId.id)
            if (response == null) {
                logger.error { "no message registered for $event" }
            } else {
                response.actions.add(ActionWithDelay(event, delayInMs))
            }
        } else {
            logger.trace { "unsupported event: $event" }
        }
    }

    private fun sendResponse(routingContext: RoutingContextHolder) {
        val gaResponse = routingContext.buildResponse()

        logger.debug { "ga response : $gaResponse" }

        val writeValueAsString = mapper.writeValueAsString(gaResponse)

        logger.debug { "ga json response: $writeValueAsString" }

        routingContext.context.response().end(writeValueAsString)
    }

    override fun loadProfile(applicationId: String, userId: PlayerId): UserPreferences? {
        return currentMessages.getIfPresent(userId.id)
                ?.request?.user?.profile?.run {
            if (givenName != null) {
                UserPreferences(givenName, familyName)
            } else {
                null
            }
        }
    }
}