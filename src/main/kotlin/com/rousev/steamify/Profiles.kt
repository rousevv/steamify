package com.rousev.steamify

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Whole setups to switch between: binds, action sets and Controller Settings. The live config files stay where
 * they are; each profile keeps its own copy in config/steamify-profiles/<id>/, and switching swaps them over.
 * Each controller remembers the profile last used with it and brings it back when it connects.
 */
object Profiles {
    class Profile(val id: String, var name: String)

    private val config = FabricLoader.getInstance().configDir
    private val dir = config.resolve("steamify-profiles")
    private val index = dir.resolve("profiles.properties")
    private val files = listOf("steamify.properties", "steamify-settings.properties", "steamify-sets.properties")

    val all = ArrayList<Profile>()
    lateinit var active: Profile
        private set

    private val links = HashMap<String, String>() // controller id -> profile id
    private val locked = HashSet<String>() // controller ids whose link only changes by unlocking first
    private var controller: String? = null // the connected one

    /** The profile the connected controller comes back to, or null with no controller. */
    fun linked(): Profile? = controller?.let { c -> all.firstOrNull { it.id == links[c] } }

    fun isLocked() = controller in locked

    /** Locks the connected controller to its linked profile, or unlocks it. */
    fun toggleLock() {
        val c = controller ?: return
        if (!locked.remove(c)) locked += c
        save()
    }

    /** A controller connected. Returns the profile switched to, or null when it stays the same. */
    fun connected(id: String): Profile? {
        controller = id
        val linked = all.firstOrNull { it.id == links[id] }
        if (linked == null || linked === active) {
            link()
            return null
        }
        switchTo(linked)
        return linked
    }

    fun disconnected() {
        controller = null
    }

    // Switching profiles while a controller is in use makes that its profile, unless it's locked to another
    private fun link() {
        controller?.takeIf { it !in locked }?.let { links[it] = active.id }
        save()
    }

    // Before any profile was made, the setup there is is the Default profile
    fun load() {
        if (index.exists()) {
            val p = Properties().apply { index.inputStream().use(::load) }
            for (id in p.getProperty("order").orEmpty().split(',').filter(String::isNotBlank)) {
                all += Profile(id, p.getProperty("$id.name") ?: id)
            }
            all.firstOrNull { it.id == p.getProperty("active") }?.let { active = it }
            for (k in p.stringPropertyNames()) if (k.startsWith("controller.")) links[k.removePrefix("controller.")] = p.getProperty(k)
            p.getProperty("locked")?.split('\n')?.filter(String::isNotBlank)?.let(locked::addAll)
        }
        if (all.isEmpty()) all += Profile("p1", "Default")
        if (!::active.isInitialized) active = all.first()
    }

    private fun save() {
        dir.createDirectories()
        val p = Properties()
        p.setProperty("order", all.joinToString(",") { it.id })
        p.setProperty("active", active.id)
        all.forEach { p.setProperty("${it.id}.name", it.name) }
        links.forEach { (c, id) -> p.setProperty("controller.$c", id) }
        p.setProperty("locked", locked.joinToString("\n"))
        index.outputStream().use { p.store(it, "Steamify profiles") }
    }

    // A missing file means defaults, so it's mirrored as missing
    private fun copy(from: java.nio.file.Path, to: java.nio.file.Path) {
        to.createDirectories()
        for (f in files) {
            val src = from.resolve(f)
            if (src.exists()) Files.copy(src, to.resolve(f), StandardCopyOption.REPLACE_EXISTING) else to.resolve(f).deleteIfExists()
        }
    }

    fun switchTo(p: Profile) {
        if (p === active) return
        copy(config, dir.resolve(active.id))
        copy(dir.resolve(p.id), config)
        active = p
        link()
        Settings.reloadProfile()
        Bindings.load()
        ActionSets.load()
    }

    /** A copy of the current setup, added to the list. The current profile stays active until you pick the new one. */
    fun create(): Profile {
        val id = nextId()
        val p = Profile(id, "Profile ${all.size + 1}")
        copy(config, dir.resolve(id))
        all += p
        save()
        return p
    }

    // Shared setups leave out what belongs to this controller and install rather than the profile
    // Calibration is "gyro.bias"/"gyro.noise" or "gyro.<controller>.bias"/".noise"; other "gyro." keys are settings and travel
    private fun personal(key: String) =
        key.startsWith("gyro.") && (key.endsWith(".bias") || key.endsWith(".noise")) ||
            key == "onboarded" || key == "translationNoticeSeen" || key == "keyboard.language"

    /** A profile as text for the clipboard: its three files, minus calibration and the like. */
    fun export(p: Profile): String {
        if (p === active) copy(config, dir.resolve(p.id))
        val out = Properties()
        out.setProperty("name", p.name)
        files.forEachIndexed { i, f ->
            val src = dir.resolve(p.id).resolve(f)
            if (!src.exists()) return@forEachIndexed
            val props = Properties().apply { src.inputStream().use(::load) }
            for (k in props.stringPropertyNames()) if (!personal(k)) out.setProperty("$i.$k", props.getProperty(k))
        }
        return Share.encode(KIND, out)
    }

    /** Adds a profile from clipboard text and switches to it. Null when it isn't one. */
    fun import(text: String): Profile? {
        val shared = Share.decode(KIND, text) ?: return null
        val id = nextId()
        val target = dir.resolve(id).createDirectories()
        files.forEachIndexed { i, f ->
            val props = Properties()
            for (k in shared.stringPropertyNames()) if (k.startsWith("$i.")) props.setProperty(k.removePrefix("$i."), shared.getProperty(k))
            if (!props.isEmpty) target.resolve(f).outputStream().use { props.store(it, "Steamify, from a shared profile") }
        }
        val p = Profile(id, shared.getProperty("name") ?: "Shared")
        all += p
        switchTo(p)
        return p
    }

    private const val KIND = "profile"

    private fun nextId() = "p" + ((all.maxOfOrNull { it.id.removePrefix("p").toIntOrNull() ?: 0 } ?: 0) + 1)

    fun delete(p: Profile) {
        if (p === active) return
        all -= p
        links.entries.removeAll { (c, id) -> (id == p.id).also { if (it) locked -= c } }
        files.forEach { dir.resolve(p.id).resolve(it).deleteIfExists() }
        dir.resolve(p.id).deleteIfExists()
        save()
    }

    /** Moves a profile one place up (-1) or down (1) in the list. */
    fun move(p: Profile, by: Int) {
        val i = all.indexOf(p)
        val j = i + by
        if (i < 0 || j !in all.indices) return
        all[i] = all[j]
        all[j] = p
        save()
    }

    fun rename(name: String) {
        active.name = name.ifBlank { "Profile" }
        save()
    }
}

/** Every profile as a button: one click switches to it. */
class ProfilesScreen(private val back: () -> Screen) : Screen(Component.translatable("steamify.profiles.title")) {
    private var layout = HeaderAndFooterLayout(this)
    private var list: ScrollableLayout? = null

    override fun init() {
        val scrolled = list?.scrollAmount ?: 0.0
        layout = HeaderAndFooterLayout(this)
        layout.addTitleHeader(title, font)
        val col = LinearLayout.vertical().spacing(4)
        val name = EditBox(font, W, 20, Component.translatable("steamify.sets.name"))
        name.value = Profiles.active.name
        name.setResponder(Profiles::rename)
        name.setTooltip(Tooltip.create(Component.translatable("steamify.profiles.name.tooltip")))
        col.addChild(name)
        for ((i, p) in Profiles.all.withIndex()) {
            val row = LinearLayout.horizontal().spacing(4)
            val active = p === Profiles.active
            row.addChild(Button.builder(Component.literal("↑")) { Profiles.move(p, -1); rebuildWidgets() }.width(20).build()).active = i > 0
            row.addChild(Button.builder(Component.literal("↓")) { Profiles.move(p, 1); rebuildWidgets() }
                .width(20).build()).active = i < Profiles.all.size - 1
            val label = if (active) Component.literal("> ${p.name} <").withStyle(ChatFormatting.YELLOW) else Component.literal(p.name)
            row.addChild(Button.builder(label) {
                Profiles.switchTo(p)
                rebuildWidgets()
            }.width(124).build())
            row.addChild(Button.builder(Component.translatable("steamify.share.copy")) { Share.copy(Profiles.export(p)) }
                .width(50).tooltip(Tooltip.create(Component.translatable("steamify.profiles.copy.tooltip"))).build())
            row.addChild(Button.builder(Component.translatable("steamify.profiles.delete")) {
                Profiles.delete(p)
                rebuildWidgets()
            }.width(50).build()).active = !active
            col.addChild(row)
        }
        val adds = LinearLayout.horizontal().spacing(4)
        adds.addChild(Button.builder(Component.translatable("steamify.profiles.add")) {
            Profiles.create()
            rebuildWidgets()
        }.width(W / 2 - 2).tooltip(Tooltip.create(Component.translatable("steamify.profiles.add.tooltip"))).build())
        adds.addChild(Button.builder(Component.translatable("steamify.profiles.paste")) {
            if (Profiles.import(Share.clipboard()) == null) Share.toast("steamify.share.invalid") else rebuildWidgets()
        }.width(W / 2 - 2).tooltip(Tooltip.create(Component.translatable("steamify.profiles.paste.tooltip"))).build())
        col.addChild(adds)
        list = layout.addToContents(ScrollableLayout(minecraft, col, layout.contentHeight))
        // Locking needs a controller in use and a profile it's linked to
        val footer = LinearLayout.horizontal().spacing(4)
        val linked = Profiles.linked()
        footer.addChild(Button.builder(lockLabel(linked)) {
            Profiles.toggleLock()
            it.message = lockLabel(Profiles.linked())
        }.width(W - 84).tooltip(Tooltip.create(Component.translatable("steamify.profiles.lock.tooltip"))).build()).active = linked != null
        footer.addChild(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(80).build())
        layout.addToFooter(footer)
        layout.visitWidgets(::addRenderableWidget)
        repositionElements()
        list?.scrollTo(scrolled) // rebuilt after every click, so a click doesn't jump back to the top
    }

    override fun repositionElements() = arrangeScrolling(layout, list)

    // A fresh Controller Settings, so its options show the profile that's now active
    override fun onClose() = minecraft.gui.setScreen(back())

    private fun lockLabel(linked: Profiles.Profile?) = Component.translatable("steamify.profiles.lock",
        linked?.name ?: "-", if (Profiles.isLocked()) CommonComponents.OPTION_ON else CommonComponents.OPTION_OFF)

    private companion object {
        const val W = 280 // a row: up, down, name, Copy, Delete
    }
}

/**
 * Profiles button left of "Options..." on the title and pause screens, opening the profile list. The tooltip
 * names the active profile; those screens are kept rather than rebuilt when you come back, so it updates itself.
 */
class ProfileButton(private val screen: Screen) :
    Button.Plain(0, 0, 20, 20, Component.translatable("steamify.profiles.title"), { Minecraft.getInstance().gui.setScreen(ProfilesScreen { screen }) }, { it.get() }) {
    private var shown: String? = null

    override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        extractDefaultSprite(g)
        g.blitSprite(RenderPipelines.GUI_TEXTURED, ICON, x + 2, y + 2, 16, 16)
        val name = Profiles.active.name
        if (name != shown) {
            shown = name
            setTooltip(Tooltip.create(Component.translatable("steamify.profiles.button", name)))
        }
    }

    companion object {
        private val ICON = Identifier.fromNamespaceAndPath("steamify", "icon/profiles")

        /** Placed left of the screen's "Options..." button, or not at all if a mod moved it away. */
        @JvmStatic
        fun beside(screen: Screen, widgets: List<*>): ProfileButton? {
            val options = widgets.filterIsInstance<AbstractWidget>().firstOrNull { it.message == OPTIONS } ?: return null
            return ProfileButton(screen).also { it.setPosition(options.x - 24, options.y) }
        }

        private val OPTIONS = Component.translatable("menu.options")
    }
}
