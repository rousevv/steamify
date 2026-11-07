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
 * Melee aim assist, console style: turning slows down while the crosshair is on a target (friction), and the
 * camera drifts toward the nearest target while the player steers with the controller (pull). It only ever
 * reshapes the controller's own look deltas, so mouse aiming is never touched.
 */
object AimAssist {
    private val mc get() = Minecraft.getInstance()

    private const val CONE_DEG = 25.0 // how far off the crosshair a target can be and still get picked
    private const val PULL_PER_SEC = 4.0 // share of the angle error closed per second at full strength and steering
    private const val MAX_FRICTION = 0.6 // turn speed lost on target at full strength

    /** Multiplayer counts LAN-opened worlds too, those have other players in them. */
    fun active(): Boolean {
        if (!Settings[Flag.AIM_ASSIST]) return false
        if (Settings[Flag.AIM_ASSIST_SINGLEPLAYER_ONLY]) {
            val server = mc.singleplayerServer
            if (server == null || server.isPublished) return false
        }
        return true
    }

    /**
     * Takes this frame's controller look deltas in degrees and returns them adjusted.
     * activity is 0..1: how much the player is steering or moving with the controller right now.
     */
    fun apply(yaw: Double, pitch: Double, activity: Double, dt: Double): Pair<Double, Double> {
        val p = mc.player ?: return yaw to pitch
        if (activity <= 0.0 || p.isSpectator || !active()) return yaw to pitch
        val target = findTarget(p) ?: return yaw to pitch
        val strength = Settings.effective(Tune.AIM_ASSIST) / 100.0

        val eye = p.eyePosition
        val c = target.boundingBox.center
        val dx = c.x - eye.x
        val dy = c.y - eye.y
        val dz = c.z - eye.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        val errYaw = Mth.wrapDegrees(Math.toDegrees(atan2(dz, dx)) - 90 - p.yRot)
        val errPitch = -Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz))) - p.xRot

        // Friction only while the crosshair is actually over the target's body
        val halfWidth = Math.toDegrees(atan2(target.bbWidth / 2.0, dist))
        val halfHeight = Math.toDegrees(atan2(target.bbHeight / 2.0, dist))
        val onTarget = abs(errYaw) < halfWidth && abs(errPitch) < halfHeight
        val friction = if (onTarget) 1 - MAX_FRICTION * strength else 1.0

        // Pull scales with how hard the player is steering, so standing still never drags the camera.
        // Vertical pull is halved: snapping up and down feels far more intrusive than side to side
        val k = 1 - exp(-PULL_PER_SEC * strength * activity * dt)
        return (yaw * friction + errYaw * k) to (pitch * friction + errPitch * k * 0.5)
    }

    // Nearest-to-crosshair living thing within reach and sight. Pets and armor stands aren't targets
    private fun findTarget(p: LocalPlayer): LivingEntity? {
        val level = mc.level ?: return null
        val reach = p.entityInteractionRange() + 0.5
        val eye = p.eyePosition
        val look = p.lookAngle
        var best: LivingEntity? = null
        var bestAngle = CONE_DEG
        for (e in level.getEntities(p, p.boundingBox.inflate(reach)) { it is LivingEntity }) {
            e as LivingEntity
            if (!e.isAlive || e.isSpectator || e is ArmorStand || e.isInvisibleTo(p) || !e.isPickable) continue
            if (e is OwnableEntity && e.owner === p) continue
            if (e === p.vehicle || e.isPassengerOfSameVehicle(p)) continue
            val to = e.boundingBox.center.subtract(eye)
            val len = to.length()
            if (len - e.bbWidth / 2 > reach) continue
            val angle = Math.toDegrees(acos((to.dot(look) / len).coerceIn(-1.0, 1.0)))
            if (angle >= bestAngle || !p.hasLineOfSight(e)) continue
            best = e
            bestAngle = angle
        }
        return best
    }
}
