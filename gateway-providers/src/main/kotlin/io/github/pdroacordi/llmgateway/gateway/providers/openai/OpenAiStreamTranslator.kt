package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.providers.upstream.StreamLifecycle

/** Upstream bytes to provider events for an OpenAI stream, one instance per stream (GW-02 section 5). */
internal class OpenAiStreamTranslator(
    reader: OpenAiChunkReader,
    classifier: OpenAiFailureClassifier,
    maxEventBytes: Int,
) : StreamLifecycle(OpenAiStreamDecoder(reader, classifier), maxEventBytes)
