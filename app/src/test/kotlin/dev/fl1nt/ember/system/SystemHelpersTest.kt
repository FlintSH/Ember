package dev.fl1nt.ember.system

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.fl1nt.ember.api.RemoteFolder
import dev.fl1nt.ember.ui.components.folderPaths
import dev.fl1nt.ember.ui.components.parseFolderId
import org.junit.Test

class SystemHelpersTest {

    @Test
    fun `recognizes local network hosts without DNS`() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.4.1", "172.31.255.254", "169.254.10.10", "flare.local", "Flare.Local.", "fd12:3456::1", "fe80::1", "[fd00::2]")
            .forEach { assertWithMessage(it).that(LocalAddresses.isLocalHost(it)).isTrue() }
        listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "127.0.0.1", "files.example.com", "2001:db8::1")
            .forEach { assertWithMessage(it).that(LocalAddresses.isLocalHost(it)).isFalse() }
    }

    @Test
    fun `recognizes loopback hosts, which need no HTTP warning`() {
        listOf("localhost", "LOCALHOST.", "app.localhost", "127.0.0.1", "127.10.0.1", "::1", "[::1]")
            .forEach { assertWithMessage(it).that(LocalAddresses.isLoopbackHost(it)).isTrue() }
        listOf("10.0.2.2", "files.example.com", "localhost.example.com", "128.0.0.1")
            .forEach { assertWithMessage(it).that(LocalAddresses.isLoopbackHost(it)).isFalse() }
    }

    @Test
    fun `detects a single shared url`() {
        assertThat(SharedText.singleUrl("  https://example.com/a?b=c  ")).isEqualTo("https://example.com/a?b=c")
        assertThat(SharedText.singleUrl("<https://example.com>")).isEqualTo("https://example.com")
        assertThat(SharedText.singleUrl("check https://example.com")).isNull()
        assertThat(SharedText.singleUrl("ftp://example.com")).isNull()
        assertThat(SharedText.singleUrl("just text")).isNull()
    }

    @Test
    fun `suggests a file extension for obvious content`() {
        assertThat(SharedText.suggestedName("""{"a": 1}""", "paste.txt")).isEqualTo("paste.json")
        assertThat(SharedText.suggestedName("[1, 2]", "paste.txt")).isEqualTo("paste.json")
        assertThat(SharedText.suggestedName("<!DOCTYPE html><p>", "snippet.txt")).isEqualTo("snippet.html")
        assertThat(SharedText.suggestedName("#!/bin/sh\necho hi", "paste.txt")).isEqualTo("paste.sh")
        assertThat(SharedText.suggestedName("hello", "paste.txt")).isEqualTo("paste.txt")
        assertThat(SharedText.suggestedName("{ not closed", "paste.txt")).isEqualTo("paste.txt")
    }

    @Test
    fun `accepts Flare's Upload here links and plain folder ids`() {
        assertThat(parseFolderId("https://files.example.com/dashboard/upload?folder=cm_abc123")).isEqualTo("cm_abc123")
        assertThat(parseFolderId("https://files.example.com/dashboard/upload?x=1&folder=f-9")).isEqualTo("f-9")
        assertThat(parseFolderId("  cm_folder_1 ")).isEqualTo("cm_folder_1")
        assertThat(parseFolderId("not a folder")).isNull()
        assertThat(parseFolderId("")).isNull()
    }

    @Test
    fun `builds full folder paths`() {
        val folders = listOf(
            RemoteFolder("child", "Website", parentId = "root"),
            RemoteFolder("root", "Projects"),
            RemoteFolder("other", "Archive"),
        )
        assertThat(folderPaths(folders).map { it.second }).containsExactly("Archive", "Projects", "Projects / Website").inOrder()
    }
}
