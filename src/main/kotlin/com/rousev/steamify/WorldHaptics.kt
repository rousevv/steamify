package com.rousev.steamify

import com.rousev.steamify.mixin.FishingHookAccessor
import com.rousev.steamify.mixin.GameModeAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.TridentItem
import kotlin.math.sqrt

/** Haptics for what happens in the world: drawing a bow, a fish biting, low health, mining, explosions. */
object WorldHaptics {
    private val mc get() = Minecraft.getInstance()

    // Draw ticks bunch up towards the end, so the bow feels like it's tensing; full draw is a click
    private val BOW_TICKS = intArrayOf(6, 10, 13, 15, 17, 18, 19)
    private var lastUseTicks = 0
    private var wasCharged = false
    private var wasBiting = false
    private var crackStage = -1
    private const val CROSSBOW_LOAD_TICKS = 25.0 // without Quick Charge

    // Heartbeat: "lub" then a softer "dub" shortly after
    private const val HEART_HP = 6f // 3 hearts and below
    private const val DUB_DELAY_NS = 160_000_000L
    private var nextBeat = 0L
    private var dubAt = 0L

    /** Once per frame in game. */
    fun update(now: Long) {
        val p = mc.player
        if (p == null || mc.gui.screen() != null) {
            Steamify.adaptiveTrigger(null)
            return
        }

        // Bow and trident draw
        val anim = if (p.isUsingItem) p.useItem.useAnimation else null
        val t = if (anim != null) p.ticksUsingItem else 0
        when (anim) {
            ItemUseAnimation.BOW -> {
                if (t != lastUseTicks && t in BOW_TICKS) Steamify.feedback(Haptic.BOW, left = true, right = false)
                if (lastUseTicks < BowItem.MAX_DRAW_DURATION && t >= BowItem.MAX_DRAW_DURATION) Steamify.feedback(Haptic.BOW, left = true, right = false, click = true)
            }
            // Trident: a click once it's charged enough to throw
            ItemUseAnimation.TRIDENT -> if (lastUseTicks < TridentItem.THROW_THRESHOLD_TIME && t >= TridentItem.THROW_THRESHOLD_TIME)
                Steamify.feedback(Haptic.BOW, left = true, right = false, click = true)
            else -> {}
        }
        lastUseTicks = t
        // Crossbow: a click the moment it's loaded
        val charged = CrossbowItem.isCharged(p.mainHandItem) || CrossbowItem.isCharged(p.offhandItem)
        if (charged && !wasCharged) Steamify.feedback(Haptic.BOW, left = true, right = true, click = true)
        wasCharged = charged

        // DualSense: LT stiffens as a bow, crossbow or trident draws, and a loaded crossbow's trigger gives way like a gun's
        val strength = Settings.level(Haptic.BOW).coerceAtMost(1.0)
        val draw = when (anim) {
            ItemUseAnimation.BOW -> t / BowItem.MAX_DRAW_DURATION.toDouble()
            ItemUseAnimation.TRIDENT -> t / TridentItem.THROW_THRESHOLD_TIME.toDouble()
            ItemUseAnimation.CROSSBOW -> t / CROSSBOW_LOAD_TICKS
            else -> null
        }
        Steamify.adaptiveTrigger(when {
            strength <= 0 -> null
            draw != null -> listOf(Steamify.TRIGGER_RESIST, 0, (strength * (60 + 195 * draw.coerceAtMost(1.0))).toInt())
            charged -> listOf(Steamify.TRIGGER_BREAK, 80, 160, (strength * 255).toInt())
            else -> null
        })

        // Fish on the line
        val hook = p.fishing
        val biting = hook != null && (hook as FishingHookAccessor).`steamify$biting`()
        if (biting && !wasBiting) Steamify.haptic(Haptic.FISHING, left = true, right = true, ms = 220)
        wasBiting = biting

        // Mining: a faint tick at every crack stage of the block being broken
        val gm = mc.gameMode as? GameModeAccessor
        val stage = if (gm != null && gm.`steamify$isDestroying`()) (gm.`steamify$destroyProgress`() * 10).toInt() else -1
        if (stage > 0 && stage != crackStage) Steamify.feedback(Haptic.MINING, left = false, right = true)
        crackStage = stage

        heartbeat(now, p.health, p.isCreative || p.isSpectator)
    }

    private fun heartbeat(now: Long, hp: Float, immune: Boolean) {
        if (immune || hp <= 0f || hp > HEART_HP) {
            nextBeat = 0L
            dubAt = 0L
            return
        }
        if (dubAt != 0L && now >= dubAt) {
            Steamify.haptic(Haptic.HEARTBEAT, left = true, right = true, ms = 35, scale = 0.6)
            dubAt = 0L
        }
        if (now < nextBeat) return
        // 70 bpm at 3 hearts up to 140 at half a heart
        val danger = ((HEART_HP - hp) / (HEART_HP - 1f)).coerceIn(0f, 1f)
        val bpm = 70 + 70 * danger
        nextBeat = now + (60_000_000_000L / bpm).toLong()
        dubAt = now + DUB_DELAY_NS
        Steamify.haptic(Haptic.HEARTBEAT, left = true, right = true, ms = 45)
    }

    /** Every sound MC plays. Explosions and thunder are felt more the closer they are; a blocked hit is a thud. */
    fun onSound(sound: SoundInstance) {
        val p = mc.player ?: return
        val id = sound.identifier.path
        val dx = sound.x - p.x
        val dy = sound.y - p.y
        val dz = sound.z - p.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        when {
            id.endsWith("explode") -> (1 - dist / 32).takeIf { it > 0 }?.let {
                Steamify.haptic(Haptic.EXPLOSION, left = true, right = true, ms = (150 + 250 * it).toInt(), scale = it)
            }
            id == "entity.lightning_bolt.thunder" ->
                Steamify.haptic(Haptic.THUNDER, left = true, right = true, ms = 400, scale = (1 - dist / 200).coerceIn(0.25, 1.0))
            id == "item.shield.block" && dist < 3 -> Steamify.haptic(Haptic.SHIELD, left = true, right = true, ms = 70)
        }
    }
}
