package com.rousev.steamify

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.language.I18n
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
 * Whole setups: binds, action sets and Controller Settings. The live config files stay put; each profile keeps a
 * copy in config/steamify-profiles/<id>/ and switching swaps them over. Each controller links to one profile, and
 * keyboard and mouse count as one more controller, so typing switches to its profile.
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
    private val locked = HashSet<String>() // switching profiles doesn't relink these
    private val names = HashMap<String, String>() // controller id -> name
    private var pad: String? = null // connected
    private var controller: String? = null // in use: the pad or KEYBOARD

    private const val STEAM_CONTROLLER_USB = "28de:1302"
    private const val STEAM_CONTROLLER_BLUETOOTH = "28de:1303"
    private const val STEAM_CONTROLLER_PUCK = "28de:1304"
    private const val STEAM_DECK = "28de:1205"
    private fun vendorProduct(c: String) = c.take(9)

    fun controllers(): List<String> = links.keys.sortedBy { nameOf(it) }

    // A Steam Controller has a different id per connection, so that tells its rows apart. Controllers linked before
    // names were kept show the id until they connect again
    fun nameOf(c: String) = if (c == KEYBOARD) I18n.get("steamify.controllers.keyboard") else when (vendorProduct(c)) {
        STEAM_CONTROLLER_USB -> "Steam Controller (USB)"
        STEAM_CONTROLLER_BLUETOOTH -> "Steam Controller (Bluetooth)"
        STEAM_CONTROLLER_PUCK -> "Steam Controller (Puck)"
        STEAM_DECK -> names[c] ?: "Steam Deck"
        else -> names[c] ?: c
    }

    private fun steamController(c: String) =
        vendorProduct(c) in setOf(STEAM_CONTROLLER_USB, STEAM_CONTROLLER_BLUETOOTH, STEAM_CONTROLLER_PUCK)

    fun seen(c: String, name: String) {
        if (c in links && names.put(c, name) != name) save()
    }

    fun profileOf(c: String) = all.firstOrNull { it.id == links[c] }

    fun inUse(c: String) = c == controller

    /** For screens to notice changes. Not changed by a rename. */
    fun state() = listOf(all.map { it.id }, active.id, links.toMap(), names.toMap(), controller)

    /** Locked. The controller in use switches right away. */
    fun assign(c: String, p: Profile) {
        links[c] = p.id
        locked += c
        if (c == controller) switchTo(p)
        save()
    }

    /** It gets a fresh profile the next time it connects. */
    fun forget(c: String) {
        links -= c
        locked -= c
        names -= c
        save()
    }

    fun linked(): Profile? = controller?.let { c -> all.firstOrNull { it.id == links[c] } }

    fun isLocked() = controller in locked

    fun toggleLock() {
        val c = controller ?: return
        if (!locked.remove(c)) locked += c
        save()
    }

    /**
     * Switches only while it's the input in use; otherwise that waits for its first press ([inputChanged]).
     * Returns the profile switched to, or null when it stays the same.
     */
    fun connected(id: String, name: String, inUse: Boolean): Profile? {
        pad = id
        if (inUse) controller = id
        names[id] = name
        // A Steam Controller on a new connection (puck, USB, Bluetooth) joins its other connections' profile
        if (id !in links && steamController(id)) links.keys.firstOrNull(::steamController)?.let {
            links[id] = links.getValue(it)
            locked += id
            save()
        }
        if (all.none { it.id == links[id] }) {
            locked += id
            links[id] = profileForNewController(id).id
            save()
        }
        return if (inUse) to(links[id]) else null
    }

    // The first controller takes the profile in use; a later one a copy of it, or of another controller's when the
    // one in use is the keyboard's, whose hotbar and sneak are set up for the keyboard
    private fun profileForNewController(id: String): Profile {
        if (active.id !in links.values) return active
        val keyboardsInUse = active.id == links[KEYBOARD]
        val base = if (keyboardsInUse) links.filterKeys { it != KEYBOARD }.values.firstNotNullOfOrNull(::byId) else null
        return create(if (steamController(id)) "Steam Controller" else nameOf(id), base ?: active)
    }

    fun disconnected() {
        if (controller == pad) controller = null
        pad = null
    }

    /**
     * The keyboard gets a profile of its own the first time, but only once there's a controller to tell it apart
     * from. Returns the profile switched to, or null when it stays the same.
     */
    fun inputChanged(keyboard: Boolean): Profile? {
        if (!keyboard) {
            controller = pad ?: return null
            return to(links[pad])
        }
        if (KEYBOARD !in links) {
            if (links.isEmpty()) return null
            links[KEYBOARD] = keyboardProfile().id
            locked += KEYBOARD
            save()
        }
        controller = KEYBOARD
        return to(links[KEYBOARD])
    }

    private fun byId(id: String) = all.firstOrNull { it.id == id }

    private fun to(id: String?): Profile? {
        val p = id?.let(::byId) ?: return null
        if (p === active) return null
        switchTo(p)
        return p
    }

    // The normal hotbar back, and sneak held rather than toggled
    private fun keyboardProfile(): Profile {
        val p = create(I18n.get("steamify.controllers.keyboard"))
        val file = dir.resolve(p.id).resolve("steamify-settings.properties")
        val props = Properties()
        if (file.exists()) file.inputStream().use(props::load)
        props.setProperty(Flag.HOTBAR_STRIP.key, "true")
        props.setProperty("vanilla.toggleCrouch", "false")
        file.outputStream().use { props.store(it, "Steamify settings") }
        return p
    }

    private fun link() {
        controller?.takeIf { it !in locked }?.let { links[it] = active.id }
        save()
    }

    // Before any profile was made, the existing setup is the Default profile
    fun load() {
        if (index.exists()) {
            val p = Properties().apply { index.inputStream().use(::load) }
            for (id in p.getProperty("order").orEmpty().split(',').filter(String::isNotBlank)) {
                all += Profile(id, p.getProperty("$id.name") ?: id)
            }
            all.firstOrNull { it.id == p.getProperty("active") }?.let { active = it }
            for (k in p.stringPropertyNames()) {
                if (k.startsWith("controller.")) links[k.removePrefix("controller.")] = p.getProperty(k)
                if (k.startsWith("controllerName.")) names[k.removePrefix("controllerName.")] = p.getProperty(k)
            }
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
        names.forEach { (c, n) -> p.setProperty("controllerName.$c", n) }
        p.setProperty("locked", locked.joinToString("\n"))
        index.outputStream().use { p.store(it, "Steamify profiles") }
    }

    // A missing file means defaults, so it's copied as missing
    private fun copy(from: java.nio.file.Path, to: java.nio.file.Path) {
        if (from == config) Settings.captureVanilla()
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

    /** Doesn't switch to it. */
    fun create(name: String = "Profile ${all.size + 1}", from: Profile = active): Profile {
        val id = nextId()
        val taken = all.map { it.name }.toSet()
        val numbered = generateSequence(2) { it + 1 }.map { "$name $it" }
        val p = Profile(id, (sequenceOf(name) + numbered).first { it !in taken })
        copy(if (from === active) config else dir.resolve(from.id), dir.resolve(id))
        all += p
        save()
        return p
    }

    // Left out of shared profiles: what belongs to this controller and install, and macros, which people keep
    // things like a server login in
    private fun personal(key: String): Boolean {
        val calibration = key.startsWith("gyro.") && (key.endsWith(".bias") || key.endsWith(".noise"))
        val macro = key.startsWith("macro") || "steamify.macro." in key
        val install = key == "onboarded" || key == "translationNoticeSeen" || key == "keyboard.language"
        return calibration || macro || install
    }

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

    /** Switches to it. Null when the text isn't a profile. */
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
    const val KEYBOARD = "keyboard"

    private fun nextId() = "p" + ((all.maxOfOrNull { it.id.removePrefix("p").toIntOrNull() ?: 0 } ?: 0) + 1)

    fun delete(p: Profile) {
        if (p === active) return
        all -= p
        // Deleting the keyboard's profile means it doesn't want one, rather than getting a new one
        if (links[KEYBOARD] == p.id) links[KEYBOARD] = active.id
        val orphaned = links.filterValues { it == p.id }.keys
        for (c in orphaned) {
            links -= c
            locked -= c
            names -= c
        }
        files.forEach { dir.resolve(p.id).resolve(it).deleteIfExists() }
        dir.resolve(p.id).deleteIfExists()
        save()
    }

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

class ProfilesScreen(private val back: () -> Screen) : Screen(Component.translatable("steamify.profiles.title")) {
    private var layout = HeaderAndFooterLayout(this)
    private var list: ScrollableLayout? = null
    private var shown: Any? = null

    // A controller connecting while this is open can add or switch a profile
    override fun tick() {
        if (Profiles.state() != shown) rebuildWidgets()
    }

    override fun init() {
        shown = Profiles.state()
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
        col.addChild(Button.builder(Component.translatable("steamify.profiles.controllers")) { minecraft.gui.setScreen(ControllersScreen(this)) }
            .width(W).tooltip(Tooltip.create(Component.translatable("steamify.profiles.controllers.tooltip"))).build())
        list = layout.addToContents(ScrollableLayout(minecraft, col, layout.contentHeight))
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
        list?.scrollTo(scrolled) // rebuilt after every click
    }

    override fun repositionElements() = arrangeScrolling(layout, list)

    // A fresh Controller Settings, showing the profile now active
    override fun onClose() = minecraft.gui.setScreen(back())

    private fun lockLabel(linked: Profiles.Profile?) = Component.translatable("steamify.profiles.lock",
        linked?.name ?: "-", if (Profiles.isLocked()) CommonComponents.OPTION_ON else CommonComponents.OPTION_OFF)

    private companion object {
        const val W = 280
    }
}

class ControllersScreen(private val back: Screen) : Screen(Component.translatable("steamify.controllers.title")) {
    private var layout = HeaderAndFooterLayout(this)
    private var list: ScrollableLayout? = null
    private var shown: Any? = null

    override fun tick() {
        if (Profiles.state() != shown) rebuildWidgets()
    }

    override fun init() {
        shown = Profiles.state()
        layout = HeaderAndFooterLayout(this)
        layout.addTitleHeader(title, font)
        val col = LinearLayout.vertical().spacing(4)
        val known = Profiles.controllers()
        if (known.isEmpty()) col.addChild(Button.builder(Component.translatable("steamify.controllers.none")) {}.width(W).build()).active = false
        for (c in known) {
            val row = LinearLayout.horizontal().spacing(4)
            row.addChild(Button.builder(label(c)) {
                val all = Profiles.all
                Profiles.assign(c, all[(all.indexOf(Profiles.profileOf(c)) + 1) % all.size])
                it.message = label(c)
            }.width(W - 54).build())
            row.addChild(Button.builder(Component.translatable("steamify.controllers.forget")) {
                Profiles.forget(c)
                rebuildWidgets()
            }.width(50).tooltip(Tooltip.create(Component.translatable("steamify.controllers.forget.tooltip"))).build())
            col.addChild(row)
        }
        list = layout.addToContents(ScrollableLayout(minecraft, col, layout.contentHeight))
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(200).build())
        layout.visitWidgets(::addRenderableWidget)
        repositionElements()
    }

    private fun label(c: String): Component {
        val name = Component.literal(Profiles.nameOf(c))
        val shown = if (Profiles.inUse(c)) Component.translatable("steamify.controllers.inUse", name) else name
        return Component.translatable("steamify.controllers.row", shown, Profiles.profileOf(c)?.name ?: "-")
    }

    override fun repositionElements() = arrangeScrolling(layout, list)

    override fun onClose() = minecraft.gui.setScreen(back)

    private companion object {
        const val W = 280
    }
}

/** The title and pause screens are kept rather than rebuilt when you come back, so the tooltip updates itself. */
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

        /** Null if a mod moved "Options..." away. */
        @JvmStatic
        fun beside(screen: Screen, widgets: List<*>): ProfileButton? {
            val options = widgets.filterIsInstance<AbstractWidget>().firstOrNull { it.message == OPTIONS } ?: return null
            return ProfileButton(screen).also { it.setPosition(options.x - 24, options.y) }
        }

        private val OPTIONS = Component.translatable("menu.options")
    }
}
