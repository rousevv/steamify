package com.rousev.steamify

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.decoration.ArmorStand
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Console-style melee aim assist: turning slows while the crosshair is on a target (friction), and the camera
 * drifts toward the nearest one while steering (pull). Only controller look deltas pass through here.
 */
object AimAssist {
    private val mc get() = Minecraft.getInstance()

    private const val TARGET_CONE_DEG = 25.0
    private const val PULL_PER_SECOND = 4.0
    private const val MAX_FRICTION = 0.6
    // Snapping up and down feels far more intrusive than side to side
    private const val VERTICAL_PULL = 0.5

    fun active(): Boolean {
        if (!Settings[Flag.AIM_ASSIST]) return false
        if (Settings[Flag.AIM_ASSIST_SINGLEPLAYER_ONLY]) {
            // A LAN-opened world has other players in it
            val server = mc.singleplayerServer
            if (server == null || server.isPublished) return false
        }
        return true
    }

    /**
     * Adjusts this frame's controller look deltas (degrees). [steering] is 0..1, how much the player is using the
     * controller right now; pull scales with it so a still player is never dragged.
     */
    fun apply(yaw: Double, pitch: Double, steering: Double, dt: Double): Pair<Double, Double> {
        val player = mc.player ?: return yaw to pitch
        if (steering <= 0.0 || player.isSpectator || !active()) return yaw to pitch
        val target = findTarget(player) ?: return yaw to pitch
        val strength = Settings.effective(Tune.AIM_ASSIST) / 100.0

        val eye = player.eyePosition
        val center = target.boundingBox.center
        val dx = center.x - eye.x
        val dy = center.y - eye.y
        val dz = center.z - eye.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        val yawError = Mth.wrapDegrees(Math.toDegrees(atan2(dz, dx)) - 90 - player.yRot)
        val pitchError = -Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz))) - player.xRot

        val halfWidthDeg = Math.toDegrees(atan2(target.bbWidth / 2.0, distance))
        val halfHeightDeg = Math.toDegrees(atan2(target.bbHeight / 2.0, distance))
        val crosshairOnTarget = abs(yawError) < halfWidthDeg && abs(pitchError) < halfHeightDeg
        val friction = if (crosshairOnTarget) 1 - MAX_FRICTION * strength else 1.0

        val pull = 1 - exp(-PULL_PER_SECOND * strength * steering * dt)
        return (yaw * friction + yawError * pull) to (pitch * friction + pitchError * pull * VERTICAL_PULL)
    }

    /** The living entity closest to the crosshair, within reach and sight. */
    private fun findTarget(player: LocalPlayer): LivingEntity? {
        val level = mc.level ?: return null
        val reach = player.entityInteractionRange() + 0.5
        val eye = player.eyePosition
        val look = player.lookAngle
        var best: LivingEntity? = null
        var bestAngle = TARGET_CONE_DEG
        for (e in level.getEntities(player, player.boundingBox.inflate(reach)) { it is LivingEntity }) {
            e as LivingEntity
            if (!isTargetable(e, player)) continue
            val toTarget = e.boundingBox.center.subtract(eye)
            val distance = toTarget.length()
            if (distance - e.bbWidth / 2 > reach) continue
            val angle = Math.toDegrees(acos((toTarget.dot(look) / distance).coerceIn(-1.0, 1.0)))
            if (angle >= bestAngle || !player.hasLineOfSight(e)) continue
            best = e
            bestAngle = angle
        }
        return best
    }

    private fun isTargetable(e: LivingEntity, player: LocalPlayer): Boolean {
        if (!e.isAlive || e.isSpectator || e is ArmorStand || e.isInvisibleTo(player) || !e.isPickable) return false
        if (e is OwnableEntity && e.owner === player) return false
        return e !== player.vehicle && !e.isPassengerOfSameVehicle(player)
    }
}
