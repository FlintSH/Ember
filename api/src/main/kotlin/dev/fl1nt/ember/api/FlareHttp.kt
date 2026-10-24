package dev.fl1nt.ember.api

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Settings an [OkHttpClient] needs for talking to Flare.
 *
 * Flare runs on Node, which closes idle keep-alive connections after 5 seconds.
 * OkHttp only rechecks a pooled connection before a POST once it has been idle
 * for 10, so an upload sent in between goes out on a connection Flare already
 * closed. Uploads can't be repeated automatically, because a repeat could store
 * the file twice, so the upload would fail. Dropping idle connections sooner
 * avoids that.
 */
fun OkHttpClient.Builder.forFlare(): OkHttpClient.Builder =
    connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, IDLE_SECONDS, TimeUnit.SECONDS))

private const val MAX_IDLE_CONNECTIONS = 5
private const val IDLE_SECONDS = 4L
