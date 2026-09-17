package id.web.izs.sshclient

import id.web.izs.sshclient.core.config.ConfigMigrator
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.SshDefaults
import id.web.izs.sshclient.core.config.asStringMap
import org.junit.Assert.*
import org.junit.Test

/**
 * Lossless guarantee tests: download -> upload must not lose any config.
 * Uses a desktop-style YAML fixture with profiles, groups, vault blob,
 * and future/unknown keys (appearance, hotkeys, terminal).
 */
class RawRoundTripTest {

    private val desktopYaml = """
        version: 1
        profiles:
          - id: ssh:11111111-1111-1111-1111-111111111111
            type: ssh
            name: prod-web
            group: servers-group-id
            icon: fas fa-desktop
            options:
              host: 10.0.0.5
              user: deploy
              auth: password
              keepaliveInterval: 5000
          - id: ssh:22222222-2222-2222-2222-222222222222
            type: ssh
            name: db
            options:
              host: db.internal
        groups:
          - id: servers-group-id
            name: Servers
        ssh:
          knownHosts: []
          verifyHostKeys: true
        appearance:
          theme: Follow the color scheme
        hotkeys:
          settings: ['Ctrl-,']
        configSync:
          host: http://192.168.1.10:8080
          token: SECRET
          configID: 3
          auto: true
          parts: {hotkeys: true, appearance: true, vault: true}
    """.trimIndent()

    @Test
    fun `upload doc strips configSync but keeps everything else`() {
        val local = RawConfigStore.loadRaw(desktopYaml)
        val parts = mapOf("hotkeys" to true, "appearance" to true, "vault" to true)
        val upload = RawConfigStore.buildUploadDoc(local, remoteRaw = null, parts)
        assertFalse(upload.containsKey("configSync"))
        // profiles, groups, unknown keys preserved
        val domain = RawConfigStore.toDomain(upload)
        assertEquals(2, domain.profiles.size)
        assertEquals("prod-web", domain.profiles[0].name)
        assertEquals(1, domain.groups.size)
        assertTrue(upload.containsKey("appearance"))
        assertTrue(upload.containsKey("hotkeys"))
        // dump + reload is stable
        val reloaded = RawConfigStore.loadRaw(RawConfigStore.dumpRaw(upload))
        assertEquals(2, (reloaded["profiles"] as List<*>).size)
    }

    @Test
    fun `disabled parts are taken from remote on upload`() {
        val local = RawConfigStore.loadRaw(desktopYaml)
        val remote = RawConfigStore.loadRaw("appearance:\n  theme: Remote Theme\nvault: null\n")
        val parts = mapOf("hotkeys" to true, "appearance" to false, "vault" to true)
        val upload = RawConfigStore.buildUploadDoc(local, remote, parts)
        val appearance = upload["appearance"].asStringMap()!!
        assertEquals("Remote Theme", appearance["theme"])
        // enabled parts stay local
        assertTrue(upload.containsKey("hotkeys"))
    }

    @Test
    fun `download merge keeps local configSync and local disabled parts`() {
        val local = RawConfigStore.loadRaw(desktopYaml)
        val remote = RawConfigStore.loadRaw(
            "version: 1\nprofiles: []\nappearance:\n  theme: Remote\nencrypted: false\n",
        )
        val parts = mapOf("hotkeys" to true, "appearance" to false, "vault" to true)
        val merged = RawConfigStore.mergeDownload(remote, local, parts)
        val cs = merged["configSync"].asStringMap()!!
        assertEquals("http://192.168.1.10:8080", cs["host"])
        assertEquals(3, (cs["configID"] as Number).toInt())
        // appearance disabled -> local wins even though remote differs
        val appearance = merged["appearance"].asStringMap()!!
        assertEquals("Follow the color scheme", appearance["theme"])
    }

    @Test
    fun `encrypted vault blob passes through byte-identical`() {
        // Note: desktop js-yaml single-quotes an all-digit salt on dump (otherwise it
        // would reload as an integer). The fixture mirrors real desktop output.
        val yaml = "version: 1\nencrypted: true\n" +
            "vault: {version: 1, contents: ABCDEF123456, keySalt: '0011223344556677', " +
            "iv: '00112233445566778899aabbccddeeff'}\n" +
            "configSync: {host: h, token: t, configID: 1}\n"
        val local = RawConfigStore.loadRaw(yaml)
        val upload = RawConfigStore.buildUploadDoc(local, null, emptyMap())
        val vault = RawConfigStore.storedVault(upload)!!
        assertEquals("ABCDEF123456", vault.contents)
        assertEquals("0011223344556677", vault.keySalt)
        assertEquals("00112233445566778899aabbccddeeff", vault.iv)
    }

    @Test
    fun `group name migrates to group id without loss`() {
        val yaml = "profiles:\n  - {id: ssh:1, type: ssh, name: web, group: Servers, " +
            "options: {host: h}}\ngroups: []\n"
        val doc = RawConfigStore.loadRaw(yaml)
        val domain = RawConfigStore.toDomain(doc)
        val (profiles, groups) = ConfigMigrator.migrateGroupNamesToIds(domain.profiles, domain.groups)
        assertEquals(1, groups.size)
        assertEquals("Servers", groups[0].name)
        assertEquals(groups[0].id, profiles[0].group)
    }

    @Test
    fun `jumpHost name resolves to profile id`() {
        val yaml = "profiles:\n" +
            "  - {id: ssh:jump, type: ssh, name: bastion, options: {host: j}}\n" +
            "  - {id: ssh:app, type: ssh, name: app, options: {host: a, jumpHost: bastion}}\n"
        val domain = RawConfigStore.toDomain(RawConfigStore.loadRaw(yaml))
        val fixed = ConfigMigrator.normalizeJumpHosts(domain.profiles)
        assertEquals("ssh:jump", fixed.find { it.id == "ssh:app" }!!.options.jumpHost)
    }

    @Test
    fun `cloud-omitted defaults are applied transiently`() {
        val yaml = "profiles:\n  - {id: ssh:1, type: ssh, name: minimal, options: {host: h}}\n"
        val domain = RawConfigStore.toDomain(RawConfigStore.loadRaw(yaml))
        val view = SshDefaults.applyToProfile(domain.profiles[0])
        assertEquals(22, view.options.port)
        assertEquals("root", view.options.user)
        assertEquals(5000L, view.options.keepaliveInterval)
        // raw doc untouched (no defaults written back)
        val rawOpts = (((RawConfigStore.loadRaw(yaml)["profiles"] as? List<*>)!![0].asStringMap()!!)["options"].asStringMap()!!)
        assertFalse(rawOpts.containsKey("port"))
        assertFalse(rawOpts.containsKey("user"))
    }
}
