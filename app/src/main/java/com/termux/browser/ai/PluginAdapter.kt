package com.termux.browser.ai

/**
 * Plugin-backed adapter: identical safety machinery to built-ins, with all
 * site-specific data (selectors, marker, strategies) coming from a signed,
 * validated bundle. Detection, health, waiting, and cancellation are owned
 * here in fixed code; the bundle contributes data only.
 */
class PluginAdapter(
    manifestId: String,
    hostSet: Set<String>,
    private val selectorSet: SelectorSet,
    private val marker: String?,
    private val version: Int
) : SiteAdapter {
    override val id = manifestId
    override val hosts: Set<String> = hostSet

    override fun detect(url: String): Boolean {
        val host = url.substringAfter("://", url).substringBefore('/').lowercase()
        return host in hosts || hosts.any { host.endsWith(".$it") }
    }

    override fun selectors(): SelectorSet = selectorSet

    override fun markerSelector(): String? = marker

    override fun snapshotScript(): String {
        return buildSnapshotScript(
            selectors.prompt, selectors.messages, selectors.stop, selectors.submit,
            marker
        )
    }

    override fun health(snapshot: Snapshot, url: String): AdapterHealth {
        val recognized = detect(url)
        // Same fail-closed bar as built-ins: provider identity plus
        // confirmed controls. A null marker skips only the marker check;
        // prompt/submit confirmation is never skipped.
        val identity = recognized && (marker == null || snapshot.markerFound)
        val enterCapable = snapshot.composerKind == "textarea" ||
            snapshot.composerKind == "contenteditable"
        return AdapterHealth(
            site = id,
            recognized = recognized,
            promptInput = identity && snapshot.promptFound,
            submitControl = identity && (snapshot.submitFound || enterCapable),
            responseContainer = identity && snapshot.messageCount > 0,
            loginState = if (snapshot.loginRequired) "logged_out" else "unknown",
            adapterVersion = "plugin-v$version",
            host = url.substringAfter("://", url).substringBefore('/'),
            uiVariant = "plugin-probe"
        )
    }

    companion object {
        fun fromBundle(bundle: ValidBundle): PluginAdapter {
            return PluginAdapter(
                manifestId = bundle.manifest.id,
                hosts = bundle.manifest.hosts.toSet(),
                selectors = SelectorSet(
                    prompt = bundle.selectors.prompt,
                    submit = bundle.selectors.submit,
                    messages = bundle.selectors.messages,
                    stop = bundle.selectors.stop
                ),
                marker = bundle.health.marker,
                version = bundle.manifest.version
            )
        }
    }
}
