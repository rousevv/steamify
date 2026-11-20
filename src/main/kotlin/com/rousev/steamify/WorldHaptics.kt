package com.rousev.steamify

import com.rousev.steamify.mixin.FishingHookAccessor
import com.rousev.steamify.mixin.GameModeAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.TridentItem
import kotlin.math.sqrt

/** Haptics for what happens in the world: drawing a bow, a fish biting, low health, mining, explosions. */
object WorldHaptics {
    private val mc get() = Minecraft.getInstance()

    // Bunched toward the end so the bow feels like it's tensing
    private val BOW_TICKS = intArrayOf(6, 10, 13, 15, 17, 18, 19)
    private const val CROSSBOW_LOAD_TICKS = 25.0 // without Quick Charge
    private var lastUseTicks = 0
    private var wasCharged = false
    private var wasBiting = false
    private var crackStage = -1

    private const val HEARTBEAT_BELOW_HP = 6f
    private const val CALM_BPM = 70f
    private const val PANIC_BPM = 140f
    private const val DUB_DELAY_NS = 160_000_000L
    private var nextBeat = 0L
    private var dubAt = 0L

    fun update(now: Long) {
        val player = mc.player
        if (player == null || mc.gui.screen() != null) {
            Steamify.adaptiveTrigger(null)
            return
        }
        val animation = if (player.isUsingItem) player.useItem.useAnimation else null
        val useTicks = if (animation != null) player.ticksUsingItem else 0
        drawClicks(animation, useTicks)
        lastUseTicks = useTicks

        val charged = CrossbowItem.isCharged(player.mainHandItem) || CrossbowItem.isCharged(player.offhandItem)
        if (charged && !wasCharged) Steamify.feedback(Haptic.BOW, left = true, right = true, click = true)
        wasCharged = charged
        Steamify.adaptiveTrigger(triggerEffect(animation, useTicks, charged))

        val hook = player.fishing
        val biting = hook != null && (hook as FishingHookAccessor).`steamify$biting`()
        if (biting && !wasBiting) Steamify.haptic(Haptic.FISHING, left = true, right = true, ms = 220)
        wasBiting = biting

        val stage = miningCrackStage()
        if (stage > 0 && stage != crackStage) Steamify.feedback(Haptic.MINING, left = false, right = true)
        crackStage = stage

        heartbeat(now, player.health, player.isCreative || player.isSpectator)
    }

    private fun drawClicks(animation: ItemUseAnimation?, useTicks: Int) {
        fun reached(ticks: Int) = lastUseTicks < ticks && useTicks >= ticks
        when (animation) {
            ItemUseAnimation.BOW -> {
                if (useTicks != lastUseTicks && useTicks in BOW_TICKS) Steamify.feedback(Haptic.BOW, left = true, right = false)
                if (reached(BowItem.MAX_DRAW_DURATION)) Steamify.feedback(Haptic.BOW, left = true, right = false, click = true)
            }
            ItemUseAnimation.TRIDENT -> if (reached(TridentItem.THROW_THRESHOLD_TIME))
                Steamify.feedback(Haptic.BOW, left = true, right = false, click = true)
            else -> {}
        }
    }

    // DualSense: LT stiffens as a bow, crossbow or trident draws, and a loaded crossbow's trigger gives way like a gun's
    private fun triggerEffect(animation: ItemUseAnimation?, useTicks: Int, charged: Boolean): List<Int>? {
        val strength = Settings.level(Haptic.BOW).coerceAtMost(1.0)
        val draw = when (animation) {
            ItemUseAnimation.BOW -> useTicks / BowItem.MAX_DRAW_DURATION.toDouble()
            ItemUseAnimation.TRIDENT -> useTicks / TridentItem.THROW_THRESHOLD_TIME.toDouble()
            ItemUseAnimation.CROSSBOW -> useTicks / CROSSBOW_LOAD_TICKS
            else -> null
        }
        return when {
            strength <= 0 -> null
            draw != null -> listOf(Steamify.TRIGGER_RESIST, 0, (strength * (60 + 195 * draw.coerceAtMost(1.0))).toInt())
            charged -> listOf(Steamify.TRIGGER_BREAK, 80, 160, (strength * 255).toInt())
            else -> null
        }
    }

    private fun miningCrackStage(): Int {
        val gameMode = mc.gameMode as? GameModeAccessor ?: return -1
        return if (gameMode.`steamify$isDestroying`()) (gameMode.`steamify$destroyProgress`() * 10).toInt() else -1
    }

    // "Lub", then a softer "dub" shortly after, faster the lower your health
    private fun heartbeat(now: Long, hp: Float, immune: Boolean) {
        if (immune || hp <= 0f || hp > HEARTBEAT_BELOW_HP) {
            nextBeat = 0L
            dubAt = 0L
            return
        }
        if (dubAt != 0L && now >= dubAt) {
            Steamify.haptic(Haptic.HEARTBEAT, left = true, right = true, ms = 35, scale = 0.6)
            dubAt = 0L
        }
        if (now < nextBeat) return
        val danger = ((HEARTBEAT_BELOW_HP - hp) / (HEARTBEAT_BELOW_HP - 1f)).coerceIn(0f, 1f)
        val bpm = CALM_BPM + (PANIC_BPM - CALM_BPM) * danger
        nextBeat = now + (60_000_000_000L / bpm).toLong()
        dubAt = now + DUB_DELAY_NS
        Steamify.haptic(Haptic.HEARTBEAT, left = true, right = true, ms = 45)
    }

    private var lastStep = 0
    private var stepLeft = false
    private var lastHp = 0f
    private var lastPlayer: Any? = null
    private const val FULL_DAMAGE_HP = 10f
    private var wasOnGround = true
    private var lastFall = 0.0

    // Blocks per tick: creative flight is ~0.55 (1.1 sprinting), elytra cruises ~1.5, rocket boosts reach ~3
    private const val WIND_FULL_SPEED = 3.0
    private const val WIND_SLOW_MS = 160.0
    private const val WIND_FAST_MS = 40.0
    private const val WIND_POWER = 0.2 // a background hum for long flights
    private var windAt = 0L

    // Blocks per tick downward. A normal jump lands slower than the start; terminal velocity is ~3.9
    private const val FALL_START = 0.6
    private const val FALL_FULL = 3.9
    private const val FALL_SLOW_MS = 120.0
    private const val FALL_FAST_MS = 30.0
    private const val FALL_TICK_MS = 20 // short ticks, so it rattles where flight hums
    private const val FALL_POWER = 0.5 // short ticks need more strength than a hum to be felt
    private var fallAt = 0L

    /** Every frame, menus included. */
    fun playerUpdate() {
        val player = mc.player ?: return
        footsteps(player)
        damage(player)
        landing(player)
        val now = System.nanoTime()
        val inGame = mc.gui.screen() == null // a paused game keeps its velocity and would hum forever
        if (inGame) wind(player, now)
        if (inGame) freeFall(player, now)
    }

    // MC's step sounds use the same counter: one step per whole unit of moveDist
    private fun footsteps(player: LocalPlayer) {
        val step = player.moveDist.toInt()
        if (step != lastStep && player.onGround() && !player.isSpectator) {
            stepLeft = !stepLeft
            Steamify.haptic(Haptic.FOOTSTEPS, left = stepLeft, right = !stepLeft, ms = 30)
        }
        lastStep = step
    }

    // Absorption counts, so hits soaked by golden-apple hearts still register. A new player object (respawn,
    // dimension change) starts fresh instead of reading as healing or a hit
    private fun damage(player: LocalPlayer) {
        val hp = player.health + player.absorptionAmount
        if (player === lastPlayer && hp < lastHp) {
            val hit = ((lastHp - hp) / FULL_DAMAGE_HP).toDouble().coerceIn(0.0, 1.0)
            Steamify.haptic(Haptic.DAMAGE, left = true, right = true, ms = (120 + 280 * hit).toInt(), scale = 0.25 + 0.75 * hit)
        }
        lastHp = hp
        lastPlayer = player
    }

    // fallDistance resets on touchdown, so the landing is judged by last frame's
    private fun landing(player: LocalPlayer) {
        if (player.onGround() && !wasOnGround && lastFall > 2.0) {
            Steamify.haptic(Haptic.LANDING, left = true, right = true, ms = 120, scale = lastFall / 12.0)
        }
        wasOnGround = player.onGround()
        lastFall = player.fallDistance
    }

    // Pulses at a fixed strength, faster the faster you go
    private fun wind(player: LocalPlayer, now: Long) {
        if (!player.isFallFlying && !player.abilities.flying || now < windAt) return
        val speed = player.deltaMovement.length()
        // Curved so creative flight already flutters but only rocket speed buzzes fastest
        val fast = Math.pow(speed / WIND_FULL_SPEED, 0.7).coerceIn(0.0, 1.0)
        val periodMs = (WIND_SLOW_MS + (WIND_FAST_MS - WIND_SLOW_MS) * fast).toInt()
        windAt = now + periodMs * 1_000_000L
        if (speed > 0.1) Steamify.haptic(Haptic.FLIGHT, left = true, right = true, ms = periodMs / 2, scale = WIND_POWER)
    }

    private fun freeFall(player: LocalPlayer, now: Long) {
        val drop = -player.deltaMovement.y
        val falling = !player.isFallFlying && !player.abilities.flying && !player.onGround() && !player.isInWater &&
            !player.onClimbable() && drop > FALL_START
        if (!falling || now < fallAt) return
        val fast = Math.pow(((drop - FALL_START) / (FALL_FULL - FALL_START)).coerceIn(0.0, 1.0), 0.7)
        fallAt = now + ((FALL_SLOW_MS + (FALL_FAST_MS - FALL_SLOW_MS) * fast) * 1_000_000L).toLong()
        Steamify.haptic(Haptic.FALLING, left = true, right = true, ms = FALL_TICK_MS, scale = FALL_POWER)
    }

    /** Explosions and thunder are felt more the closer they are; a blocked hit is a thud. */
    fun onSound(sound: SoundInstance) {
        val player = mc.player ?: return
        val id = sound.identifier.path
        val dx = sound.x - player.x
        val dy = sound.y - player.y
        val dz = sound.z - player.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        when {
            id.endsWith("explode") -> (1 - distance / 32).takeIf { it > 0 }?.let {
                Steamify.haptic(Haptic.EXPLOSION, left = true, right = true, ms = (150 + 250 * it).toInt(), scale = it)
            }
            id == "entity.lightning_bolt.thunder" ->
                Steamify.haptic(Haptic.THUNDER, left = true, right = true, ms = 400, scale = (1 - distance / 200).coerceIn(0.25, 1.0))
            id == "item.shield.block" && distance < 3 -> Steamify.haptic(Haptic.SHIELD, left = true, right = true, ms = 70)
        }
    }
}
