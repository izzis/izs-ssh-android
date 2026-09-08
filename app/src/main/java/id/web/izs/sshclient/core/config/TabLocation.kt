package id.web.izs.sshclient.core.config

/**
 * Phone mapping of desktop `appearance.tabsLocation`
 * (tabby-core configDefaults.yaml: `top`, enum top|bottom|left|right).
 *
 * OFF is the phone-only state for an ABSENT key: no tab chrome at all
 * (the current list-based UX). Any other value — including garbage — also
 * resolves to OFF (fail-closed: never surprise with new chrome).
 * top/bottom → horizontal strip; left/right → side drawer.
 */
enum class TabLocation(val yamlValue: String) {
    OFF(""),
    TOP("top"),
    BOTTOM("bottom"),
    LEFT("left"),
    RIGHT("right"),
    ;

    val isStrip: Boolean get() = this == TOP || this == BOTTOM
    val isDrawer: Boolean get() = this == LEFT || this == RIGHT
}

/** Pure resolver (unit-tested): raw YAML string → location. */
fun resolveTabLocation(raw: String?): TabLocation {
    if (raw.isNullOrBlank()) return TabLocation.OFF
    return TabLocation.entries.firstOrNull { it.yamlValue == raw.trim().lowercase() }
        ?: TabLocation.OFF
}

/**
 * Effective location for the phone (unit-tested).
 * An unreadable store is ALWAYS OFF: on a locked encrypted shell the live
 * store is just the outer shell (no appearance key anyway). Once unlocked
 * the decrypted store is readable, so the synced value applies — the
 * passphrase in RAM means the phone CAN honor it. The key itself stays
 * writable regardless.
 *
 * @param blind pass [ignoreEncryptedValue]: true only while the store is
 * an unreadable encrypted shell.
 */
fun effectiveTabLocation(blind: Boolean, store: Map<String, Any?>): TabLocation =
    if (blind) TabLocation.OFF
    else resolveTabLocation(RawConfigStore.tabsLocationRaw(store))

/**
 * Whether an encrypted config's appearance value must be ignored (pure,
 * unit-tested). Locked shell = unreadable = ignore; unlocked (passphrase
 * in RAM, decrypted merged store) = readable = honor. Plaintext never
 * ignores.
 */
fun ignoreEncryptedValue(encrypted: Boolean, unlockRequired: Boolean): Boolean =
    encrypted && unlockRequired

/**
 * Source priority (Settings > Window, unit-tested).
 * LOCAL = this device's own setting wins, YAML ignored for display (this
 * is the painless path for encrypted configs). FOLLOW_YAML (default) =
 * the synced key wins, via [effectiveTabLocation].
 */
enum class TabSource { FOLLOW_YAML, LOCAL }

/** Lenient parse: anything but "local" is FOLLOW_YAML. */
fun parseTabSource(raw: String?): TabSource =
    if (raw?.trim()?.lowercase() == "local") TabSource.LOCAL else TabSource.FOLLOW_YAML

fun effectiveTabLocation(
    source: TabSource,
    local: TabLocation,
    blind: Boolean,
    store: Map<String, Any?>,
): TabLocation =
    if (source == TabSource.LOCAL) local
    else effectiveTabLocation(blind, store)
