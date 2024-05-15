package com.heledron.spideranimation.components

import com.heledron.spideranimation.*
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.util.Vector

class DebugRenderer(val spider: Spider): SpiderComponent {
    private val renderer = MultiEntityRenderer()

    override fun close() {
        renderer.close()
    }

    override fun render() {
        renderer.beginRender()
        doRender()
        renderer.finishRender()
    }

    private fun doRender() {
        val scale = spider.gait.bodyHeight.toFloat()

        for ((legIndex, leg) in spider.body.legs.withIndex()) {
            // Render scan bars
                renderer.render(Pair("scanBar", legIndex), lineTemplate(
                    location = leg.scanStartPosition.toLocation(spider.location.world!!),
                    vector = leg.scanVector,
                    thickness = .05f * scale,
                    init = {
                        it.brightness = Display.Brightness(15, 15)
                        it.block = Material.GOLD_BLOCK.createBlockData()
                    }
                ))

            // Render trigger zone
            val vector = Vector(0,1,0).multiply(leg.triggerZone.vertical)
            renderer.render(Pair("triggerZoneVertical", legIndex), lineTemplate(
                location = leg.restPosition.toLocation(spider.location.world!!).subtract(vector.clone().multiply(.5)),
                vector = vector,
                thickness = .07f * scale,
                init = { it.brightness = Display.Brightness(15, 15) },
                update = {
                    val material = if (leg.uncomfortable) Material.RED_STAINED_GLASS else Material.CYAN_STAINED_GLASS
                    it.block = material.createBlockData()
                }
            ))

            // Render trigger zone
            renderer.render(Pair("triggerZoneHorizontal", legIndex), blockTemplate(
                location = run {
                    val location = leg.restPosition.toLocation(leg.spider.location.world!!)
                    location.y = leg.target.position.y.coerceIn(location.y - leg.triggerZone.vertical, location.y + leg.triggerZone.vertical)
                    location
                },
                init = {
                    it.teleportDuration = 1
                    it.interpolationDuration = 1
                    it.brightness = Display.Brightness(15, 15)
                },
                update = {
                    val material = if (leg.uncomfortable) Material.RED_STAINED_GLASS else Material.CYAN_STAINED_GLASS
                    it.block = material.createBlockData()

                    val size = 2 * leg.triggerZone.horizontal.toFloat()
                    val ySize = 0.02f
                    it.transformation = centredTransform(size, ySize, size)
                }
            ))

            // Render end effector
            renderer.render(Pair("endEffector", legIndex), blockTemplate(
                location = leg.endEffector.toLocation(spider.location.world!!),
                init = {
                    it.teleportDuration = 1
                    it.brightness = Display.Brightness(15, 15)
                },
                update = {
                    val size = (if (leg.isSelected) .2f else .15f) * scale
                    it.transformation = centredTransform(size, size, size)
                    it.block = when {
                        leg.isDisabled -> Material.BLACK_CONCRETE.createBlockData()
                        leg.isGrounded() -> Material.DIAMOND_BLOCK.createBlockData()
                        leg.touchingGround -> Material.LAPIS_BLOCK.createBlockData()
                        else -> Material.REDSTONE_BLOCK.createBlockData()
                    }
                }
            ))

            // Render target position
            renderer.render(Pair("targetPosition", legIndex), blockTemplate(
                location = leg.target.position.toLocation(spider.location.world!!),
                init = {
                    it.teleportDuration = 1
                    it.brightness = Display.Brightness(15, 15)

                    val size = 0.2f * scale
                    it.transformation = centredTransform(size, size, size)
                },
                update = {
                    val material = if (leg.target.isGrounded) Material.RED_STAINED_GLASS else Material.LIME_STAINED_GLASS
                    it.block = material.createBlockData()
                }
            ))
        }



        // Render spider direction
        renderer.render("direction", blockTemplate(
            location = spider.location.clone().add(spider.location.direction.clone().multiply(scale)),
            init = {
                it.teleportDuration = 1
                it.brightness = Display.Brightness(15, 15)

                val size = 0.1f * scale
                it.transformation = centredTransform(size, size, size)
            },
            update = {
                it.block = if (spider.isGalloping) Material.REDSTONE_BLOCK.createBlockData() else Material.EMERALD_BLOCK.createBlockData()
            }
        ))



        if (spider.bodyPlan is SymmetricalBodyPlan) {
            // Render leg polygon
            val points = spider.bodyPlan.legsInPolygonalOrder().filter { it.isGrounded() }.map { it.endEffector.toLocation(spider.location.world!!)}
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]

                renderer.render(Pair("legPolygon",i), lineTemplate(
                    location = a,
                    vector = b.toVector().subtract(a.toVector()),
                    thickness = .05f * scale,
                    interpolation = 0,
                    init = { it.brightness = Display.Brightness(15, 15) },
                    update = { it.block = Material.EMERALD_BLOCK.createBlockData() }
                ))
            }


            // Render centre of mass
            renderer.render("centreOfMass", blockTemplate(
                location = spider.body.centreOfMass.toLocation(spider.location.world!!),
                init = {
                    it.teleportDuration = 1
                    it.brightness = Display.Brightness(15, 15)

                    val size = 0.1f * scale
                    it.transformation = centredTransform(size, size, size)
                },
                update = {
                    val material = if (spider.body.isStable) Material.LAPIS_BLOCK else Material.REDSTONE_BLOCK
                    it.block = material.createBlockData()
                }
            ))

            // Render body acceleration
            renderer.render("acceleration", lineTemplate(
                location = spider.body.accelerationOrigin.toLocation(spider.location.world!!),
                vector = spider.body.centreOfMass.clone().subtract(spider.body.accelerationOrigin),
                thickness = .02f * scale,
                interpolation = 0,
                init = { it.brightness = Display.Brightness(15, 15) },
                update = {
                    val material = if (spider.body.acceleration.isZero) Material.BLACK_CONCRETE else Material.WHITE_CONCRETE
                    it.block = material.createBlockData()
                }
            ))
        }
    }


}