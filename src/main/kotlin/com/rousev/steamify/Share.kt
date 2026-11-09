package com.rousev.steamify

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.io.StringWriter
import java.util.Base64
import java.util.Properties
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Profiles and action sets as one line of text, to paste in chat or a Discord message:
 * "steamify-<kind>:" then the properties, deflated and base64 encoded.
 */
object Share {
    private val toastId = SystemToast.SystemToastId(3_000L)

    fun encode(kind: String, p: Properties): String {
        val text = StringWriter().also { p.store(it, null) }.toString()
        val bytes = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()
        return "steamify-$kind:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Null for anything that isn't a "kind" Steamify copied: other text, a set where a profile goes, a damaged paste. */
    fun decode(kind: String, text: String): Properties? = runCatching {
        val body = text.trim().removePrefix("steamify-$kind:").takeIf { it.length < text.trim().length } ?: return null
        val bytes = InflaterInputStream(Base64.getUrlDecoder().decode(body).inputStream()).use { it.readBytes() }
        Properties().apply { load(StringReader(bytes.decodeToString())) }
    }.getOrNull()

    fun copy(text: String) {
        val mc = Minecraft.getInstance()
        mc.keyboardHandler.clipboard = text
        toast("steamify.share.copied")
    }

    fun clipboard(): String = Minecraft.getInstance().keyboardHandler.clipboard

    fun toast(key: String) =
        SystemToast.addOrUpdate(Minecraft.getInstance().gui.toastManager(), toastId, Component.translatable(key), null)
}
