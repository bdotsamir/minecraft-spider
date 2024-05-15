package com.heledron.spideranimation.components

import com.heledron.spideranimation.*
import org.bukkit.Location
import org.bukkit.util.Vector
import org.joml.Vector2d
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min


class LegTarget(
    val position: Vector,
    val isGrounded: Boolean,
    val id: Int,
)

class Leg(
    val spider: Spider,
    val legPlan: LegPlan,
) {
    val didStep = EventEmitter()

    var triggerZone = triggerZone()
    var comfortZone = comfortZone()

    var isSelected = false
    var isDisabled = false

    val chain = createChain()
    var restPosition = restPosition(); private set
    var lookAheadPosition = lookAheadPosition(); private set
    var scanStartPosition = scanStartPosition(); private set
    var scanVector = scanVector(); private set
    var target = strandedTarget(); private set
    init { target = locateGround() ?: target }

    var endEffector = target.position.clone()

    var uncomfortable = false; private set
    var touchingGround = true; private set
    var isMoving = false; private set
    var moveTime = 0; private set
    var wantsToMove = false; private set


    private fun triggerDistance(): Double {
        val maxSpeed = spider.gait.walkSpeed * spider.gait.gallopBreakpoint.coerceAtMost(1.0)
        val walkFraction = min(spider.velocity.length() / maxSpeed, 1.0)
        val fraction = if (spider.isRotatingYaw || spider.isRotatingPitch) 1.0 else walkFraction


        val diff = spider.gait.legWalkingTriggerDistance - spider.gait.legStationaryTriggerDistance
        return spider.gait.legStationaryTriggerDistance + diff * fraction
    }

    fun isGrounded(): Boolean {
        return touchingGround && !isMoving && !isDisabled
    }

    fun update() {
        updateMovement()
        updateChain()
    }

    private fun updateChain() {
        val attachmentPoint = spider.relativePosition(legPlan.attachmentPosition)

        chain.root.copy(attachmentPoint)

        if (!spider.gait.legNoStraighten) {
            val direction = endEffector.clone().subtract(attachmentPoint)
            direction.y = 0.0

            val crossAxis = Vector(0.0, 1.0, 0.0).crossProduct(direction).normalize()

            val angle = spider.gait.legStraightenRotation
            direction.rotateAroundAxis(crossAxis, Math.toRadians(angle))

            chain.straightenDirection(direction)
        }

        chain.fabrik(endEffector)
    }

    private fun updateMovement() {
        val gait = spider.gait

        var didStep = false

        triggerZone = triggerZone()
        comfortZone = comfortZone()
        restPosition = restPosition()
        lookAheadPosition = lookAheadPosition()
        touchingGround = touchingGround()
        scanStartPosition = scanStartPosition()
        scanVector = scanVector()

        val restPositionDistance = SplitDistance.distance(endEffector, restPosition)

        uncomfortable = !comfortZone.contains(restPositionDistance)

        if (isDisabled) {
            target = disabledTarget()
        } else {
            val ground = locateGround()
            if (ground != null) {
                target = ground
            } else {
                val targetDistance = SplitDistance.distance(endEffector, target.position)
                if (target.isGrounded || uncomfortable || !comfortZone.contains(targetDistance)) {
                    target = strandedTarget()
                }
            }
        }

        if (isMoving) {
            moveTime += 1
        } else {
            moveTime = 0
        }


        // inherit parent velocity
        if (!isGrounded()) {
            endEffector.add(spider.velocity)
            rotateYAbout(endEffector, spider.rotateVelocity, spider.location.toVector())
        }

        // resolve ground collision
        if (!touchingGround) {
            val collision = resolveCollision(endEffector.toLocation(spider.location.world!!), DOWN_VECTOR)
            if (collision != null) {
                didStep = true
                touchingGround = true
                endEffector.y = collision.position.y
            }
        }

        if (isMoving) {
            wantsToMove = true

            val legMoveSpeed = gait.legMoveSpeed

            lerpVectorByConstant(endEffector, target.position, legMoveSpeed)

            val targetY = target.position.y + gait.legLiftHeight
            val hDistance = horizontalDistance(endEffector, target.position)
            if (hDistance > gait.legDropDistance) {
                endEffector.y = lerpNumberByConstant(endEffector.y, targetY, legMoveSpeed)
            }

            if (endEffector.distance(target.position) < 0.0001) {
                isMoving = false

                touchingGround = touchingGround()
                didStep = touchingGround
            }

        } else {
            val canMove = spider.bodyPlan.canMoveLeg(this)

            wantsToMove = target.isGrounded || uncomfortable || !triggerZone.contains(restPositionDistance) || !touchingGround

            val alreadyAtTarget = endEffector.distanceSquared(target.position) < 0.01

            if (canMove && wantsToMove && !alreadyAtTarget) {
                isMoving = true
            }
        }

        if (didStep) this.didStep.emit()
    }

    private fun triggerZone(): SplitDistance {
        return SplitDistance(triggerDistance(), spider.gait.legVerticalTriggerDistance)
    }

    private fun comfortZone(): SplitDistance {
        return SplitDistance(spider.gait.legDiscomfortDistance, spider.gait.legVerticalDiscomfortDistance)
    }

    private fun touchingGround(): Boolean {
        return isOnGround(endEffector.toLocation(spider.location.world!!))
    }

    private fun restPosition(): Vector {
        val pos = legPlan.restPosition.clone()
        pos.y -= spider.gait.bodyHeight
        return spider.relativePosition(pos, pitch = 0f)
    }

    private fun lookAheadPosition(): Vector {
        if (!spider.isWalking || spider.velocity.isZero && spider.rotateVelocity == 0.0) return restPosition

        val fraction = min(spider.velocity.length() / spider.gait.walkSpeed, 1.0)
        val mag = fraction * spider.gait.legWalkingTriggerDistance * spider.gait.legLookAheadFraction

        val direction = if (spider.velocity.isZero) spider.location.direction else spider.velocity.clone().normalize()

        val lookAhead = direction.clone().normalize().multiply(mag).add(restPosition)
        rotateYAbout(lookAhead, spider.rotateVelocity, spider.location.toVector())
        return lookAhead
    }

    private fun createChain(): KinematicChain {
        val segments = arrayListOf<ChainSegment>()

        for (i in 0 until legPlan.segments.size) {
            val length = legPlan.segments[i].length
            val position = spider.location.toVector().add(legPlan.restPosition.clone().normalize().multiply(length * (i + 1)))
            segments.add(ChainSegment(position, length))
        }

        return KinematicChain(spider.location.toVector(), segments)
    }

    private fun scanStartPosition(): Vector {
        val vector = spider.relativeVector(Vector(.0, spider.gait.bodyHeight * 1.6, .0), pitch = 0f)
        return lookAheadPosition.clone().add(vector)
    }

    private fun scanVector(): Vector {
        return spider.relativeVector(Vector(.0, -spider.gait.bodyHeight * 3.5, .0), pitch = 0f)
    }

    private fun locateGround(): LegTarget? {
        val lookAhead = lookAheadPosition.toLocation(spider.location.world!!)
        val scanLength = scanVector.length()

        fun candidateAllowed(id: Int): Boolean {
            if (target.isGrounded) return true
            if (!isMoving) return true
            return id == target.id
        }

        var id = 0
        fun rayCast(x: Double, z: Double): LegTarget? {
            id += 1

            if (!candidateAllowed(id)) return null

            val start = Location(lookAhead.world, x, scanStartPosition.y, z)
            val hit = raycastGround(start, scanVector, scanLength) ?: return null

            return LegTarget(position = hit.hitPosition, isGrounded = false, id = id)
        }

        val x = lookAhead.x
        val z = lookAhead.z

        val mainCandidate = rayCast(x, z)

        if (!spider.gait.legScanAlternativeGround) return mainCandidate

        if (mainCandidate != null) {
            if (mainCandidate.position.y in lookAhead.y - .24 .. lookAhead.y + 1.5) {
                return mainCandidate
            }
        }

        val margin = 2 / 16.0
        val nx = floor(x) - margin
        val nz = floor(z) - margin
        val pz = ceil(z) + margin
        val px = ceil(x) + margin

        val candidates = listOf(
            rayCast(nx, nz), rayCast(nx, z), rayCast(nx, pz),
            rayCast(x, nz),  mainCandidate,  rayCast(x, pz),
            rayCast(px, nz), rayCast(px, z), rayCast(px, pz),
        )

        val preferredPosition = lookAhead.toVector()

        val frontBlock = lookAhead.clone().add(spider.location.direction.clone().multiply(1)).block
        if (!frontBlock.isPassable) preferredPosition.y += spider.gait.legScanHeightBias

        val best = candidates
            .filterNotNull()
//            .filter { comfortZone.contains(SplitDistance.distance(it.position, restPosition)) }
            .minByOrNull { it.position.distanceSquared(preferredPosition) }

        if (best != null && !comfortZone.contains(SplitDistance.distance(best.position, restPosition))) {
            return null
        }

        return best
    }

    fun strandedTarget(): LegTarget {
        return LegTarget(position = lookAheadPosition.clone(), isGrounded = true, id = -1)
    }

    fun disabledTarget(): LegTarget {
        val target = strandedTarget()
        target.position.y += spider.gait.bodyHeight / 2

        val groundPosition = raycastGround(endEffector.toLocation(spider.location.world!!).add(0.0, .5, 0.0), DOWN_VECTOR, 2.0)?.hitPosition
        if (groundPosition != null && groundPosition.y > target.position.y) target.position.y = groundPosition.y + spider.gait.bodyHeight * .3

        return target
    }
}


class SpiderBody(val spider: Spider): SpiderComponent {
    val onHitGround = EventEmitter()
    var onGround = false; private set

    var centreOfMass: Vector = spider.location.toVector()
    var accelerationOrigin: Vector = spider.location.toVector()
    var acceleration: Vector = Vector(0.0, 0.0, 0.0)
    var isStable = true; private set

    var legs = spider.bodyPlan.legs.map { Leg(spider, it) }

    override fun update() {
        val groundedLegs = spider.bodyPlan.legsInPolygonalOrder().filter { it.isGrounded() }
        val fractionOfLegsGrounded = groundedLegs.size.toDouble() / spider.body.legs.size

        // apply gravity and air resistance
        spider.velocity.y -= spider.gait.gravityAcceleration
        spider.velocity.y *= (1 - spider.gait.airDragCoefficient)

        // apply ground drag
        if (!spider.isWalking) {
            val drag = spider.gait.groundDragCoefficient * fractionOfLegsGrounded
            spider.velocity.x *= drag
            spider.velocity.z *= drag
        }

        if (onGround) {
            spider.velocity.x *= .5
            spider.velocity.z *= .5
        }

        centreOfMass = averageVector(legs.map { it.endEffector })
        lerpVectorByFactor(centreOfMass, spider.location.toVector(), 0.5)
        centreOfMass.y += 0.01

        if (groundedLegs.isEmpty()) {
            isStable = false
            accelerationOrigin = spider.location.toVector()
            acceleration = Vector(0.0, 0.0, 0.0)
        } else {
            val preferredY = getPreferredY()

            val desiredYAcceleration = (preferredY - spider.location.y - spider.velocity.y).coerceAtLeast(0.0)
            val capableAcceleration = spider.gait.bodyHeightCorrectionAcceleration * fractionOfLegsGrounded
            val accelerationMagnitude = min(desiredYAcceleration, capableAcceleration)

            val forceY = groundedLegs.map { it.endEffector.y }.average()


            val legsAsPolygon = groundedLegs.map { Vector2d(it.endEffector.x, it.endEffector.z) }
            accelerationOrigin = Vector(centreOfMass.x, forceY, centreOfMass.z)

            if (legsAsPolygon.size > 1) {
                if (pointInPolygon(Vector2d(centreOfMass.x, centreOfMass.z), legsAsPolygon)) {
                    // stable
                    accelerationOrigin = Vector(centreOfMass.x, forceY, centreOfMass.z)
                    isStable = true
                } else {
                    // unstable
                    val point = nearestPointInPolygon(Vector2d(centreOfMass.x, centreOfMass.z), legsAsPolygon)
                    accelerationOrigin = Vector(point.x, forceY, point.y)
                    isStable = false
                }
            } else {
                // unstable
                accelerationOrigin = groundedLegs.first().endEffector.clone()
                isStable = false
            }

            centreOfMass.x = lerpNumberByFactor(centreOfMass.x, accelerationOrigin.x, spider.gait.stabilizationFactor)
            centreOfMass.z = lerpNumberByFactor(centreOfMass.z, accelerationOrigin.z, spider.gait.stabilizationFactor)

            val direction = centreOfMass.clone().subtract(accelerationOrigin)

            acceleration = if (isStable) {
                Vector(0.0, accelerationMagnitude, 0.0)
            } else {
                val acc = direction.normalize().multiply(accelerationMagnitude)
                val tooSideways = acc.clone().setY(0).length() > acc.y
                val noPointAccelerating = false
                if (tooSideways || noPointAccelerating) acc.multiply(0.0)
                acc
            }

            spider.velocity.add(acceleration)
        }

        // apply velocity
        spider.location.add(spider.velocity)

        // resolve collision
        val collision = resolveCollision(spider.location, Vector(0.0, min(-1.0, -abs(spider.velocity.y)), 0.0))
        if (collision != null) {
            onGround = true
            val didHit = collision.offset.length() > (spider.gait.gravityAcceleration * 2) * (1 - spider.gait.airDragCoefficient)
            if (didHit) onHitGround.emit()

            spider.location.y = collision.position.y
            if (spider.velocity.y < 0) spider.velocity.y *= -spider.gait.bounceFactor
            if (spider.velocity.y < spider.gait.gravityAcceleration) spider.velocity.y = .0
        } else {
            onGround = isOnGround(spider.location)
        }

        for (leg in spider.bodyPlan.legsInUpdateOrder()) {
            leg.update()
        }
    }

    fun getPreferredY(): Double {
    //        val groundY = getGround(spider.location) + .3
        val averageY = spider.body.legs.map { it.target.position.y }.average() + spider.gait.bodyHeight
        val targetY = averageY //max(averageY, groundY)
        val stabilizedY = lerpNumberByFactor(spider.location.y, targetY, spider.gait.bodyHeightCorrectionFactor)
        return stabilizedY
    }
}