package dev.fl1nt.ember.data.settings

import com.google.common.truth.Truth.assertThat
import dev.fl1nt.ember.api.Expiration
import dev.fl1nt.ember.api.ExpiryAction
import dev.fl1nt.ember.api.Selection
import dev.fl1nt.ember.api.Visibility
import org.junit.Test

class UploadPresetTest {

    @Test
    fun `inherited fields fall back, explicit fields win`() {
        val defaults = UploadPreset(
            visibility = Visibility.PRIVATE,
            expiration = Expiration.WEEK,
            expiryAction = ExpiryAction.DELETE,
            folder = UploadPreset.Choice.Id("folder-a", "Screenshots"),
        )
        val source = UploadPreset(visibility = Visibility.PUBLIC, folder = UploadPreset.Choice.None)
        val resolved = source.over(defaults)
        assertThat(resolved.visibility).isEqualTo(Visibility.PUBLIC)
        assertThat(resolved.expiration).isEqualTo(Expiration.WEEK)
        assertThat(resolved.folder).isEqualTo(UploadPreset.Choice.None)
        assertThat(UploadPreset().over(defaults).folder).isEqualTo(UploadPreset.Choice.Id("folder-a", "Screenshots"))
    }

    @Test
    fun `maps to Flare upload options`() {
        val options = UploadPreset(
            visibility = Visibility.PRIVATE,
            expiration = Expiration.DAY,
            expiryAction = ExpiryAction.SET_PRIVATE,
            profile = UploadPreset.Choice.Id("profile-1"),
            folder = UploadPreset.Choice.None,
        ).toOptions(password = "secret")
        assertThat(options.visibility).isEqualTo(Visibility.PRIVATE)
        assertThat(options.password).isEqualTo("secret")
        assertThat(options.expiration).isEqualTo(Expiration.DAY)
        assertThat(options.expiryAction).isEqualTo(ExpiryAction.SET_PRIVATE)
        assertThat(options.profile).isEqualTo(Selection.Id("profile-1"))
        assertThat(options.folder).isEqualTo(Selection.None)
    }

    @Test
    fun `an empty password and an action without expiration are not sent`() {
        val options = UploadPreset(expiration = Expiration.DISABLED, expiryAction = ExpiryAction.DELETE).toOptions(password = "")
        assertThat(options.password).isNull()
        assertThat(options.expiryAction).isNull()
        assertThat(options.expiration).isEqualTo(Expiration.DISABLED)
    }

    @Test
    fun `round trips through storage and tolerates bad data`() {
        val preset = UploadPreset(visibility = Visibility.PUBLIC, folder = UploadPreset.Choice.Id("f", "Work / Shots"))
        assertThat(UploadPreset.decode(preset.encode())).isEqualTo(preset)
        assertThat(UploadPreset.decode(null)).isEqualTo(UploadPreset())
        assertThat(UploadPreset.decode("{not json")).isEqualTo(UploadPreset())
        assertThat(UploadPreset.decode("""{"visibility":"PUBLIC","future":"field"}""").visibility).isEqualTo(Visibility.PUBLIC)
    }
}
