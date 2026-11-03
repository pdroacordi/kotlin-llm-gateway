package io.github.pdroacordi.llmgateway.gateway.core

// The stated limit of inlineQualifiedNames: a name inside a string template is not seen.
val hiddenInTemplate = "${java.net.Socket("localhost", 80)}"
