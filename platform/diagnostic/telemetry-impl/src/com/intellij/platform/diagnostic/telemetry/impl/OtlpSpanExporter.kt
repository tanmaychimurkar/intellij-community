// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("PrivatePropertyName")

package com.intellij.platform.diagnostic.telemetry.impl

import com.intellij.openapi.diagnostic.getOrLogException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.platform.diagnostic.telemetry.AsyncSpanExporter
import com.intellij.platform.diagnostic.telemetry.OpenTelemetryUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.OutputStreamContent
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler
import io.opentelemetry.sdk.trace.data.SpanData
import kotlinx.coroutines.CancellationException
import org.jetbrains.annotations.ApiStatus.Internal

private val httpClient by lazy {
  // HttpTimeout is not used - CIO engine handles that
  HttpClient(CIO) {
    expectSuccess = true

    install(HttpRequestRetry) {
      retryOnExceptionOrServerErrors(maxRetries = 3)
      exponentialDelay()
    }
  }
}

// ktor type for protobuf uses "protobuf", but go otlp requires "x-" prefix
private val Protobuf = ContentType("application", "x-protobuf")

@Internal
class OtlpSpanExporter(endpoint: String) : AsyncSpanExporter {
  private val traceUrl = "${(if (endpoint == "true") "http://127.0.0.1:4318/" else endpoint).removeSuffix("/")}/v1/traces"

  override suspend fun export(spans: Collection<SpanData>) {
    // checking whether the spans are exported from rem dev backend
    if (System.getProperty(OpenTelemetryUtils.RDCT_TRACING_DIAGNOSTIC_FLAG) != null) {
      return
    }

    val item = TraceRequestMarshaler.create(spans)
    try {
      httpClient.post(traceUrl) {
        setBody(OutputStreamContent(contentType = Protobuf, contentLength = item.binarySerializedSize.toLong(), body = {
          item.writeBinaryTo(this)
        }))
      }
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Throwable) {
      thisLogger().error("Failed to export opentelemetry spans (url=$traceUrl)", e)
    }
  }

  suspend fun exportBackendData(receivedBytes: Collection<Byte>) {
    runCatching {
      httpClient.post(traceUrl) {
        setBody(OutputStreamContent(contentType = Protobuf, contentLength = receivedBytes.size.toLong(), body = {
          this.write(receivedBytes.toByteArray())
        }))
      }
    }.getOrLogException(thisLogger())
  }
}