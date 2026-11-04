package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonValue
import tools.jackson.databind.JsonNode

fun extras(node: JsonNode) = node.toJsonValue()
